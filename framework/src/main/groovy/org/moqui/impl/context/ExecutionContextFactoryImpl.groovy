/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.context

import com.hazelcast.config.Config
import com.hazelcast.config.XmlConfigBuilder
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.ITopic
import groovy.transform.CompileStatic
import org.apache.camel.CamelContext
import org.apache.camel.impl.DefaultCamelContext
import org.apache.shiro.SecurityUtils
import org.apache.shiro.authc.credential.CredentialsMatcher
import org.apache.shiro.authc.credential.HashedCredentialsMatcher
import org.apache.shiro.config.IniSecurityManagerFactory
import org.apache.shiro.crypto.hash.SimpleHash
import org.elasticsearch.client.Client
import org.elasticsearch.node.NodeBuilder
import org.kie.api.KieServices
import org.kie.api.builder.KieBuilder
import org.kie.api.builder.Message
import org.kie.api.builder.ReleaseId
import org.kie.api.builder.Results
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession
import org.kie.api.runtime.StatelessKieSession

import org.moqui.BaseException
import org.moqui.context.*
import org.moqui.entity.EntityDataLoader
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityValue
import org.moqui.impl.StupidClassLoader
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.StupidUtilities
import org.moqui.impl.StupidWebUtilities
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.reference.UrlResourceReference
import org.moqui.impl.entity.EntityCache
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityValueBase
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.camel.MoquiServiceComponent
import org.moqui.impl.service.camel.MoquiServiceConsumer
import org.moqui.screen.ScreenFacade
import org.moqui.service.ServiceFacade
import org.moqui.util.MNode

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache
import java.sql.Timestamp
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile

@CompileStatic
class ExecutionContextFactoryImpl implements ExecutionContextFactory {
    protected final static Logger logger = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class)
    
    protected boolean destroyed = false
    
    protected String runtimePath
    protected final String confPath
    protected final MNode confXmlRoot
    protected MNode serverStatsNode

    protected StupidClassLoader cachedClassLoader
    protected InetAddress localhostAddress = null

    protected LinkedHashMap<String, ComponentInfo> componentInfoMap = new LinkedHashMap<String, ComponentInfo>()
    protected ThreadLocal<ExecutionContextImpl> activeContext = new ThreadLocal<ExecutionContextImpl>()
    protected Map<String, EntityFacadeImpl> entityFacadeByTenantMap = new HashMap<String, EntityFacadeImpl>()
    protected Map<String, WebappInfo> webappInfoMap = new HashMap<>()
    protected List<NotificationMessageListener> registeredNotificationMessageListeners = []

    protected Map<String, ArtifactStatsInfo> artifactStatsInfoByType = new HashMap<>()
    protected Map<String, Boolean> artifactTypeAuthzEnabled = new HashMap<>()
    protected Map<String, Boolean> artifactTypeTarpitEnabled = new HashMap<>()

    /** The SecurityManager for Apache Shiro */
    protected org.apache.shiro.mgt.SecurityManager internalSecurityManager

    /** The central object of the Camel API: CamelContext */
    protected CamelContext camelContext
    protected MoquiServiceComponent moquiServiceComponent
    protected Map<String, MoquiServiceConsumer> camelConsumerByUriMap = new HashMap<String, MoquiServiceConsumer>()

    /** ElasticSearch Node */
    protected org.elasticsearch.node.Node elasticSearchNode
    /** ElasticSearch Client */
    protected Client elasticSearchClient

    /** Jackrabbit Process */
    protected Process jackrabbitProcess

    /** Hazelcast Instance */
    protected HazelcastInstance hazelcastInstance
    /** Entity Cache Invalidate Hazelcase Topic */
    ITopic<EntityCache.EntityCacheInvalidate> entityCacheInvalidateTopic

    /** KIE ReleaseId Cache */
    protected final Cache<String, ReleaseId> kieComponentReleaseIdCache
    /** KIE Component Cache */
    protected final Cache<String, String> kieSessionComponentCache

    // ======== Permanent Delegated Facades ========
    protected final CacheFacadeImpl cacheFacade
    protected final LoggerFacadeImpl loggerFacade
    protected final ResourceFacadeImpl resourceFacade
    protected final ScreenFacadeImpl screenFacade
    protected final ServiceFacadeImpl serviceFacade
    protected final TransactionFacadeImpl transactionFacade

    // Some direct-cached values for better performance
    protected String skipStatsCond
    protected Integer hitBinLengthMillis
    protected Map<String, Boolean> artifactPersistHitByType = new HashMap<String, Boolean>()
    protected Map<String, Boolean> artifactPersistBinByType = new HashMap<String, Boolean>()

    // NOTE: using unbound LinkedBlockingQueue, so max pool size in ThreadPoolExecutor has no effect
    private final BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>()
    private static class WorkerThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("MoquiWorkers")
        private final AtomicInteger threadNumber = new AtomicInteger(1)
        Thread newThread(Runnable r) { return new Thread(workerGroup, r, "MoquiWorker-" + threadNumber.getAndIncrement()) }
    }
    final ThreadPoolExecutor workerPool = new ThreadPoolExecutor(16, 16, 60, TimeUnit.SECONDS, workQueue, new WorkerThreadFactory())

    /**
     * This constructor gets runtime directory and conf file location from a properties file on the classpath so that
     * it can initialize on its own. This is the constructor to be used by the ServiceLoader in the Moqui.java file,
     * or by init methods in a servlet or context filter or OSGi component or Spring component or whatever.
     */
    ExecutionContextFactoryImpl() {
        // get the MoquiInit.properties file
        Properties moquiInitProperties = new Properties()
        URL initProps = this.class.getClassLoader().getResource("MoquiInit.properties")
        if (initProps != null) { InputStream is = initProps.openStream(); moquiInitProperties.load(is); is.close(); }

        // if there is a system property use that, otherwise from the properties file
        this.runtimePath = System.getProperty("moqui.runtime")
        if (!this.runtimePath) this.runtimePath = moquiInitProperties.getProperty("moqui.runtime")
        if (!this.runtimePath) throw new IllegalArgumentException("No moqui.runtime property found in MoquiInit.properties or in a system property (with: -Dmoqui.runtime=... on the command line)")

        if (this.runtimePath.endsWith("/")) this.runtimePath = this.runtimePath.substring(0, this.runtimePath.length()-1)

        // setup the runtimeFile
        File runtimeFile = new File(this.runtimePath)
        if (runtimeFile.exists()) { this.runtimePath = runtimeFile.getCanonicalPath() }
        else { throw new IllegalArgumentException("The moqui.runtime path [${this.runtimePath}] was not found.") }

        // get the moqui configuration file path
        String confPartialPath = System.getProperty("moqui.conf")
        if (!confPartialPath) confPartialPath = moquiInitProperties.getProperty("moqui.conf")
        if (!confPartialPath) throw new IllegalArgumentException("No moqui.conf property found in MoquiInit.properties or in a system property (with: -Dmoqui.conf=... on the command line)")

        String confFullPath
        if (confPartialPath.startsWith("/")) {
            confFullPath = confPartialPath
        } else {
            confFullPath = this.runtimePath + "/" + confPartialPath
        }
        // setup the confFile
        File confFile = new File(confFullPath)
        if (confFile.exists()) {
            this.confPath = confFullPath
        } else {
            this.confPath = null
            throw new IllegalArgumentException("The moqui.conf path [${confFullPath}] was not found.")
        }

        confXmlRoot = this.initConfig()

        preFacadeInit()

        // this init order is important as some facades will use others
        this.cacheFacade = new CacheFacadeImpl(this)
        logger.info("Moqui CacheFacadeImpl Initialized")
        this.loggerFacade = new LoggerFacadeImpl(this)
        logger.info("Moqui LoggerFacadeImpl Initialized")
        this.resourceFacade = new ResourceFacadeImpl(this)
        logger.info("Moqui ResourceFacadeImpl Initialized")

        this.transactionFacade = new TransactionFacadeImpl(this)
        logger.info("Moqui TransactionFacadeImpl Initialized")
        // always init the EntityFacade for tenantId DEFAULT
        initEntityFacade("DEFAULT")
        this.serviceFacade = new ServiceFacadeImpl(this)
        logger.info("Moqui ServiceFacadeImpl Initialized")
        this.screenFacade = new ScreenFacadeImpl(this)
        logger.info("Moqui ScreenFacadeImpl Initialized")

        kieComponentReleaseIdCache = this.cacheFacade.getCache("kie.component.releaseId", String.class, ReleaseId.class)
        kieSessionComponentCache = this.cacheFacade.getCache("kie.session.component", String.class, String.class)

        postFacadeInit()
    }

    /** This constructor takes the runtime directory path and conf file path directly. */
    ExecutionContextFactoryImpl(String runtimePath, String confPath) {
        // setup the runtimeFile
        File runtimeFile = new File(runtimePath)
        if (!runtimeFile.exists()) throw new IllegalArgumentException("The moqui.runtime path [${runtimePath}] was not found.")

        // setup the confFile
        if (runtimePath.endsWith('/')) runtimePath = runtimePath.substring(0, runtimePath.length()-1)
        if (confPath.startsWith('/')) confPath = confPath.substring(1)
        String confFullPath = runtimePath + '/' + confPath
        File confFile = new File(confFullPath)
        if (!confFile.exists()) throw new IllegalArgumentException("The moqui.conf path [${confFullPath}] was not found.")

        this.runtimePath = runtimePath
        this.confPath = confFullPath

        this.confXmlRoot = this.initConfig()

        preFacadeInit()

        // this init order is important as some facades will use others
        this.cacheFacade = new CacheFacadeImpl(this)
        logger.info("Moqui CacheFacadeImpl Initialized")
        this.loggerFacade = new LoggerFacadeImpl(this)
        logger.info("Moqui LoggerFacadeImpl Initialized")
        this.resourceFacade = new ResourceFacadeImpl(this)
        logger.info("Moqui ResourceFacadeImpl Initialized")

        this.transactionFacade = new TransactionFacadeImpl(this)
        logger.info("Moqui TransactionFacadeImpl Initialized")
        // always init the EntityFacade for tenantId DEFAULT
        initEntityFacade("DEFAULT")
        this.serviceFacade = new ServiceFacadeImpl(this)
        logger.info("Moqui ServiceFacadeImpl Initialized")
        this.screenFacade = new ScreenFacadeImpl(this)
        logger.info("Moqui ScreenFacadeImpl Initialized")

        kieComponentReleaseIdCache = this.cacheFacade.getCache("kie.component.releaseId", String.class, ReleaseId.class)
        kieSessionComponentCache = this.cacheFacade.getCache("kie.session.component", String.class, String.class)

        postFacadeInit()
    }

    @Override
    void postInit() {
        this.serviceFacade.postInit()
    }

    protected void preFacadeInit() {
        serverStatsNode = confXmlRoot.first('server-stats')
        skipStatsCond = serverStatsNode.attribute("stats-skip-condition")
        hitBinLengthMillis = (serverStatsNode.attribute("bin-length-seconds") as Integer)*1000 ?: 900000

        try {
            localhostAddress = InetAddress.getLocalHost()
        } catch (UnknownHostException e) {
            logger.warn("Could not get localhost address", new BaseException("Could not get localhost address", e))
        }

        // must load components before ClassLoader since ClassLoader currently adds lib and classes directories at init time
        initComponents()
        // init ClassLoader early so that classpath:// resources and framework interface impls will work
        initClassLoader()

        // initialize Hazelcast (pre-Facade so before CacheFacade, etc); using hazelcast.xml on the classpath for config
        Config hzConfig
        if (System.getProperty("hazelcast.config")) {
            logger.info("Starting Hazelcast with hazelcast.config system property (${System.getProperty("hazelcast.config")})")
            hzConfig = new Config("moqui")
        } else {
            logger.info("Starting Hazelcast with hazelcast.xml from classpath")
            hzConfig = new XmlConfigBuilder(cachedClassLoader.getResourceAsStream("hazelcast.xml")).build()
            hzConfig.setInstanceName("moqui")
        }
        hazelcastInstance = Hazelcast.getOrCreateHazelcastInstance(hzConfig)

        // setup the CamelContext, but don't init yet
        camelContext = new DefaultCamelContext()
    }

    protected void postFacadeInit() {
        // init ElasticSearch after facades, before Camel
        initElasticSearch()

        // everything else ready to go, init Camel
        initCamel()

        // init Jackrabbit standalone instance
        initJackrabbit()

        // init KIE (build modules for all components)
        initKie()

        // init ESAPI
        StupidWebUtilities.canonicalizeValue("test")

        // ========== load a few things in advance so first page hit is faster in production (in dev mode will reload anyway as caches timeout)
        // load entity defs
        long entityStartTime = System.currentTimeMillis()
        EntityFacadeImpl defaultEfi = getEntityFacade("DEFAULT")
        defaultEfi.loadAllEntityLocations()
        List<Map<String, Object>> entityInfoList = this.entityFacade.getAllEntitiesInfo(null, null, false, false, false)
        // load/warm framework entities
        defaultEfi.loadFrameworkEntities()
        logger.info("Loaded entity definitions (${entityInfoList.size()} entities) in ${System.currentTimeMillis() - entityStartTime}ms")

        // now that everything is started up, if configured check all entity tables
        defaultEfi.checkInitDatasourceTables()
        // check the moqui.server.ArtifactHit entity to avoid conflicts during hit logging; if runtime check not enabled this will do nothing
        defaultEfi.getEntityDbMeta().checkTableRuntime(this.entityFacade.getEntityDefinition("moqui.server.ArtifactHit"))

        // register EntityCacheListener
        entityCacheInvalidateTopic = hazelcastInstance.getTopic("entity-cache-invalidate")
        EntityCache.EntityCacheListener eciListener = new EntityCache.EntityCacheListener(this)
        entityCacheInvalidateTopic.addMessageListener(eciListener)

        if (confXmlRoot.first("cache-list").attribute("warm-on-start") != "false") warmCache()

        logger.info("Moqui ExecutionContextFactory Initialization Complete")
    }

    void warmCache() {
        this.entityFacade.warmCache()
        this.serviceFacade.warmCache()
        this.screenFacade.warmCache()
    }

    /** Initialize all permanent framework objects, ie those not sensitive to webapp or user context. */
    protected MNode initConfig() {
        // always set the full moqui.runtime, moqui.conf system properties for use in various places
        System.setProperty("moqui.runtime", this.runtimePath)
        System.setProperty("moqui.conf", this.confPath)

        logger.info("Initializing Moqui ExecutionContextFactoryImpl\n - runtime directory: ${this.runtimePath}\n - config file: ${this.confPath}")

        URL defaultConfUrl = this.class.getClassLoader().getResource("MoquiDefaultConf.xml")
        if (!defaultConfUrl) throw new IllegalArgumentException("Could not find MoquiDefaultConf.xml file on the classpath")
        MNode newConfigXmlRoot = MNode.parse(defaultConfUrl.toString(), defaultConfUrl.newInputStream())

        if (this.confPath) {
            MNode overrideConfXmlRoot = MNode.parse(new File(this.confPath))
            // merge the active/override conf file into the default one to override any settings (they both have the same root node, go from there)
            mergeConfigNodes(newConfigXmlRoot, overrideConfXmlRoot)
        }

        return newConfigXmlRoot
    }

    protected void initComponents() {
        // init components referred to in component-list.component and component-dir elements in the conf file
        for (MNode childNode in confXmlRoot.first("component-list").children) {
            if (childNode.name == "component") {
                addComponent(new ComponentInfo(null, childNode, this))
            } else if (childNode.name == "component-dir") {
                addComponentDir(childNode.attribute("location"))
            }
        }
        checkSortDependentComponents()
    }

    protected void initClassLoader() {
        // now setup the CachedClassLoader, this should init in the main thread so we can set it properly
        ClassLoader pcl = (Thread.currentThread().getContextClassLoader() ?: this.class.classLoader) ?: System.classLoader
        cachedClassLoader = new StupidClassLoader(pcl)
        // add runtime/classes jar files to the class loader
        File runtimeClassesFile = new File(runtimePath + "/classes")
        if (runtimeClassesFile.exists()) {
            cachedClassLoader.addClassesDirectory(runtimeClassesFile)
        }
        // add runtime/lib jar files to the class loader
        File runtimeLibFile = new File(runtimePath + "/lib")
        if (runtimeLibFile.exists()) for (File jarFile: runtimeLibFile.listFiles()) {
            if (jarFile.getName().endsWith(".jar")) {
                cachedClassLoader.addJarFile(new JarFile(jarFile))
                logger.info("Added JAR from runtime/lib: ${jarFile.getName()}")
            }
        }
        // set as context classloader
        Thread.currentThread().setContextClassLoader(cachedClassLoader)
    }

    /** this is called by the ResourceFacadeImpl constructor right after the ResourceReference classes are loaded but before ScriptRunners and TemplateRenderers */
    protected void initComponentLibAndClasses(ResourceFacadeImpl rfi) {
        // add <component>/classes and <component>/lib jar files to the class loader now that component locations loaded
        for (Map.Entry componentEntry in componentBaseLocations) {
            ResourceReference classesRr = rfi.getLocationReference((String) componentEntry.value + "/classes")
            if (classesRr.supportsExists() && classesRr.exists && classesRr.supportsDirectory() && classesRr.isDirectory()) {
                cachedClassLoader.addClassesDirectory(new File(classesRr.getUri()))
            }

            ResourceReference libRr = rfi.getLocationReference((String) componentEntry.value + "/lib")
            if (libRr.supportsExists() && libRr.exists && libRr.supportsDirectory() && libRr.isDirectory()) {
                for (ResourceReference jarRr: libRr.getDirectoryEntries()) {
                    if (jarRr.fileName.endsWith(".jar")) {
                        try {
                            cachedClassLoader.addJarFile(new JarFile(new File(jarRr.getUrl().getPath())))
                            logger.info("Added JAR from [${componentEntry.key}] component: ${jarRr.getLocation()}")
                        } catch (Exception e) {
                            logger.warn("Could not load JAR from [${componentEntry.key}] component: ${jarRr.getLocation()}: ${e.toString()}")
                        }
                    }
                }
            }
        }
    }

    /** Called from MoquiContextListener.contextInitialized after ECFI init */
    boolean checkEmptyDb() {
        String emptyDbLoad = confXmlRoot.first("tools").attribute("empty-db-load")
        if (!emptyDbLoad || emptyDbLoad == 'none') return false

        long enumCount = getEntity().find("moqui.basic.Enumeration").disableAuthz().count()
        if (enumCount == 0) {
            logger.info("Found ${enumCount} Enumeration records, loading empty-db-load data types (${emptyDbLoad})")

            ExecutionContext ec = getExecutionContext()
            try {
                ec.getArtifactExecution().disableAuthz()
                ec.getArtifactExecution().push("loadData", "AT_OTHER", "AUTHZA_ALL", false)
                ec.getArtifactExecution().setAnonymousAuthorizedAll()
                ec.getUser().loginAnonymousIfNoUser()

                EntityDataLoader edl = ec.getEntity().makeDataLoader()
                if (emptyDbLoad != 'all') edl.dataTypes(new HashSet(emptyDbLoad.split(",") as List))

                try {
                    long startTime = System.currentTimeMillis()
                    long records = edl.load()

                    logger.info("Loaded [${records}] records (with types: ${emptyDbLoad}) in ${(System.currentTimeMillis() - startTime)/1000} seconds.")
                } catch (Throwable t) {
                    logger.error("Error loading empty DB data (with types: ${emptyDbLoad})", t)
                }

            } finally {
                ec.destroy()
            }
            return true
        } else {
            logger.info("Found ${enumCount} Enumeration records, NOT loading empty-db-load data types (${emptyDbLoad})")
            return false
        }
    }

    synchronized void destroy() {
        if (destroyed) return

        // shutdown worker pool
        try {
            workerPool.shutdown()
            logger.info("Worker pool shut down")
        } catch (Throwable t) { logger.error("Error in workerPool shutdown", t) }

        // stop Camel to prevent more calls coming in
        if (camelContext != null) try {
            camelContext.stop()
            logger.info("Camel stopped")
        } catch (Throwable t) { logger.error("Error in Camel stop", t) }

        // stop NotificationMessageListeners
        for (NotificationMessageListener nml in registeredNotificationMessageListeners) nml.destroy()

        // stop ElasticSearch, before stopping other things so it doesn't use anything
        if (elasticSearchNode != null) try {
            elasticSearchNode.close()
            while (!elasticSearchNode.isClosed()) {
                logger.info("ElasticSearch still closing")
                this.wait(1000)
            }
            logger.info("ElasticSearch closed")
        } catch (Throwable t) { logger.error("Error in ElasticSearch node close", t) }

        // Stop Jackrabbit process
        if (jackrabbitProcess != null) try {
            jackrabbitProcess.destroy()
            logger.info("Jackrabbit process destroyed")
        } catch (Throwable t) { logger.error("Error in JackRabbit process destroy", t) }

        // persist any remaining bins in artifactHitBinByType
        Timestamp currentTimestamp = new Timestamp(System.currentTimeMillis())
        List<ArtifactStatsInfo> asiList = new ArrayList<>(artifactStatsInfoByType.values())
        artifactStatsInfoByType.clear()
        for (ArtifactStatsInfo asi in asiList) {
            if (asi.curHitBin == null) continue
            EntityValue ahb = asi.curHitBin.makeAhbValue(this, currentTimestamp)
            ahb.setSequencedIdPrimary().create()
        }
        logger.info("ArtifactHitBins stored")

        // this destroy order is important as some use others so must be destroyed first
        if (this.serviceFacade != null) this.serviceFacade.destroy()
        if (this.entityFacade != null) this.entityFacade.destroy()
        if (this.transactionFacade != null) this.transactionFacade.destroy()
        if (this.cacheFacade != null) this.cacheFacade.destroy()
        logger.info("Facades destroyed")

        // shutdown hazelcast
        if (hazelcastInstance != null) hazelcastInstance.shutdown()

        activeContext.remove()

        destroyed = true
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!this.destroyed) {
                this.destroy()
                logger.warn("ExecutionContextFactoryImpl not destroyed, caught in finalize.")
            }
        } catch (Exception e) {
            logger.warn("Error in destroy, called in finalize of ExecutionContextFactoryImpl", e)
        }
        super.finalize()
    }

    @Override
    String getRuntimePath() { return runtimePath }
    MNode getConfXmlRoot() { return confXmlRoot }
    MNode getServerStatsNode() { return serverStatsNode }
    MNode getArtifactExecutionNode(String artifactTypeEnumId) {
        return confXmlRoot.first("artifact-execution-facade")
                .first({ MNode it -> it.name == "artifact-execution" && it.attribute("type") == artifactTypeEnumId })
    }

    InetAddress getLocalhostAddress() { return localhostAddress }

    void registerNotificationMessageListener(NotificationMessageListener nml) {
        nml.init(this)
        registeredNotificationMessageListeners.add(nml)
    }
    List<NotificationMessageListener> getNotificationMessageListeners() { return registeredNotificationMessageListeners }

    org.apache.shiro.mgt.SecurityManager getSecurityManager() {
        if (internalSecurityManager != null) return internalSecurityManager

        // init Apache Shiro; NOTE: init must be done here so that ecfi will be fully initialized and in the static context
        org.apache.shiro.util.Factory<org.apache.shiro.mgt.SecurityManager> factory =
                new IniSecurityManagerFactory("classpath:shiro.ini")
        internalSecurityManager = factory.getInstance()
        // NOTE: setting this statically just in case something uses it, but for Moqui we'll be getting the SecurityManager from the ecfi
        SecurityUtils.setSecurityManager(internalSecurityManager)

        return internalSecurityManager
    }
    CredentialsMatcher getCredentialsMatcher(String hashType) {
        HashedCredentialsMatcher hcm = new HashedCredentialsMatcher()
        if (hashType) {
            hcm.setHashAlgorithmName(hashType)
        } else {
            hcm.setHashAlgorithmName(getPasswordHashType())
        }
        return hcm
    }
    static String getRandomSalt() { return StupidUtilities.getRandomString(8) }
    String getPasswordHashType() {
        MNode passwordNode = confXmlRoot.first("user-facade").first("password")
        return passwordNode.attribute("encrypt-hash-type") ?: "SHA-256"
    }
    String getSimpleHash(String source, String salt) { return getSimpleHash(source, salt, getPasswordHashType()) }
    String getSimpleHash(String source, String salt, String hashType) {
        return new SimpleHash(hashType ?: getPasswordHashType(), source, salt).toString()
    }

    String getLoginKeyHashType() {
        MNode loginKeyNode = confXmlRoot.first("user-facade").first("login-key")
        return loginKeyNode.attribute("encrypt-hash-type") ?: "SHA-256"
    }
    int getLoginKeyExpireHours() {
        MNode loginKeyNode = confXmlRoot.first("user-facade").first("login-key")
        return (loginKeyNode.attribute("expire-hours") ?: "144") as int
    }

    // ========== Getters ==========

    CacheFacadeImpl getCacheFacade() { return this.cacheFacade }

    EntityFacadeImpl getEntityFacade() { return getEntityFacade(getExecutionContext().getTenantId()) }
    EntityFacadeImpl getEntityFacade(String tenantId) {
        // this should never happen, may want to default to tenantId=DEFAULT, but to see if it happens anywhere throw for now
        if (tenantId == null) throw new IllegalArgumentException("For getEntityFacade tenantId cannot be null")
        EntityFacadeImpl efi = (EntityFacadeImpl) entityFacadeByTenantMap.get(tenantId)
        if (efi == null) efi = initEntityFacade(tenantId)

        return efi
    }
    synchronized EntityFacadeImpl initEntityFacade(String tenantId) {
        EntityFacadeImpl efi = this.entityFacadeByTenantMap.get(tenantId)
        if (efi != null) return efi

        efi = new EntityFacadeImpl(this, tenantId)
        this.entityFacadeByTenantMap.put(tenantId, efi)
        logger.info("Moqui EntityFacadeImpl for Tenant ${tenantId} Initialized")
        return efi
    }

    LoggerFacadeImpl getLoggerFacade() { return loggerFacade }
    ResourceFacadeImpl getResourceFacade() { return resourceFacade }
    ScreenFacadeImpl getScreenFacade() { return screenFacade }
    ServiceFacadeImpl getServiceFacade() { return serviceFacade }
    TransactionFacadeImpl getTransactionFacade() { return transactionFacade }
    L10nFacadeImpl getL10nFacade() { return getEci().getL10nFacade() }
    // TODO: find references, change to eci where more direct

    // =============== Apache Camel Methods ===============
    @Override
    CamelContext getCamelContext() { return camelContext }

    MoquiServiceComponent getMoquiServiceComponent() { return moquiServiceComponent }
    void registerCamelConsumer(String uri, MoquiServiceConsumer consumer) { camelConsumerByUriMap.put(uri, consumer) }
    MoquiServiceConsumer getCamelConsumer(String uri) { return camelConsumerByUriMap.get(uri) }

    protected void initCamel() {
        if (confXmlRoot.first("tools").attribute("enable-camel") != "false") {
            logger.info("Starting Camel")
            moquiServiceComponent = new MoquiServiceComponent(this)
            camelContext.addComponent("moquiservice", moquiServiceComponent)
            camelContext.start()
        } else {
            logger.info("Camel disabled, not starting")
        }
    }

    // =============== ElasticSearch Methods ===============
    @Override
    Client getElasticSearchClient() { return elasticSearchClient }

    protected void initElasticSearch() {
        // set the ElasticSearch home directory
        System.setProperty("es.path.home", runtimePath + "/elasticsearch")
        if (confXmlRoot.first("tools").attribute("enable-elasticsearch") != "false") {
            logger.info("Starting ElasticSearch")
            elasticSearchNode = NodeBuilder.nodeBuilder().node()
            elasticSearchClient = elasticSearchNode.client()
        } else {
            logger.info("ElasticSearch disabled, not starting")
        }
    }

    @Override
    HazelcastInstance getHazelcastInstance() { return hazelcastInstance }
    ITopic<EntityCache.EntityCacheInvalidate> getEntityCacheInvalidateTopic() { return entityCacheInvalidateTopic }

    protected void initJackrabbit() {
        if (confXmlRoot.first("tools").attribute("run-jackrabbit") == "true") {
            Properties jackrabbitProperties = new Properties()
            URL jackrabbitProps = this.class.getClassLoader().getResource("jackrabbit_moqui.properties")
            if (jackrabbitProps != null) {
                InputStream is = jackrabbitProps.openStream(); jackrabbitProperties.load(is); is.close();
            }

            String jackrabbitWorkingDir = System.getProperty("moqui.jackrabbit_working_dir")
            if (!jackrabbitWorkingDir) jackrabbitWorkingDir = jackrabbitProperties.getProperty("moqui.jackrabbit_working_dir")
            if (!jackrabbitWorkingDir) jackrabbitWorkingDir = "jackrabbit"

            String jackrabbitJar = System.getProperty("moqui.jackrabbit_jar")
            if (!jackrabbitJar) jackrabbitJar = jackrabbitProperties.getProperty("moqui.jackrabbit_jar")
            if (!jackrabbitJar) throw new IllegalArgumentException(
                    "No moqui.jackrabbit_jar property found in jackrabbit_moqui.ini or in a system property (with: -Dmoqui.jackrabbit_jar=... on the command line)")
            String jackrabbitJarFullPath = this.runtimePath + "/" + jackrabbitWorkingDir + "/" + jackrabbitJar

            String jackrabbitConfFile = System.getProperty("moqui.jackrabbit_configuration_file")
            if (!jackrabbitConfFile)
                jackrabbitConfFile = jackrabbitProperties.getProperty("moqui.jackrabbit_configuration_file")
            if (!jackrabbitConfFile) jackrabbitConfFile = "repository.xml"
            String jackrabbitConfFileFullPath = this.runtimePath + "/" + jackrabbitWorkingDir + "/" + jackrabbitConfFile

            String jackrabbitPort = System.getProperty("moqui.jackrabbit_port")
            if (!jackrabbitPort)
                jackrabbitPort = jackrabbitProperties.getProperty("moqui.jackrabbit_port")
            if (!jackrabbitPort) jackrabbitPort = "8081"

            logger.info("Starting Jackrabbit")

            ProcessBuilder pb = new ProcessBuilder("java", "-jar", jackrabbitJarFullPath, "-p", jackrabbitPort, "-c", jackrabbitConfFileFullPath)
            pb.directory(new File(this.runtimePath + "/" + jackrabbitWorkingDir))
            jackrabbitProcess = pb.start();
        }
    }

    // =============== KIE Methods ===============
    protected void initKie() {
        // if (!System.getProperty("drools.dialect.java.compiler")) System.setProperty("drools.dialect.java.compiler", "JANINO")
        if (!System.getProperty("drools.dialect.java.compiler")) System.setProperty("drools.dialect.java.compiler", "ECLIPSE")

        KieServices services = KieServices.Factory.get()
        for (String componentName in componentBaseLocations.keySet()) {
            try {
                buildKieModule(componentName, services)
            } catch (Throwable t) {
                logger.error("Error initializing KIE in component ${componentName}: ${t.toString()}", t)
            }
        }
    }

    @Override
    KieContainer getKieContainer(String componentName) {
        KieServices services = KieServices.Factory.get()

        ReleaseId releaseId = (ReleaseId) kieComponentReleaseIdCache.get(componentName)
        if (releaseId == null) releaseId = buildKieModule(componentName, services)

        if (releaseId != null) return services.newKieContainer(releaseId)
        return null
    }

    protected synchronized ReleaseId buildKieModule(String componentName, KieServices services) {
        ReleaseId releaseId = (ReleaseId) kieComponentReleaseIdCache.get(componentName)
        if (releaseId != null) return releaseId

        ResourceReference kieRr = getResourceFacade().getLocationReference("component://${componentName}/kie")
        if (!kieRr.getExists() || !kieRr.isDirectory()) {
            if (logger.isTraceEnabled()) logger.trace("No kie directory in component ${componentName}, not building KIE module.")
            return null
        }

        /*
        if (componentName == "mantle-usl") {
            SpreadsheetCompiler sc = new SpreadsheetCompiler()
            String drl = sc.compile(getResourceFacade().getLocationStream("component://mantle-usl/kie/src/main/resources/mantle/shipment/orderrate/OrderShippingDt.xls"), InputType.XLS)
            StringBuilder groovyWithLines = new StringBuilder()
            int lineNo = 1
            for (String line in drl.split("\n")) groovyWithLines.append(lineNo++).append(" : ").append(line).append("\n")
            logger.error("XLS DC as DRL: [\n${groovyWithLines}\n]")
        }
        */

        try {
            File kieDir = new File(kieRr.getUrl().getPath())
            KieBuilder builder = services.newKieBuilder(kieDir)

            // build the KIE module
            builder.buildAll()
            Results results = builder.getResults()
            if (results.hasMessages(Message.Level.ERROR)) {
                throw new BaseException("Error building KIE module in component ${componentName}: ${results.toString()}")
            } else if (results.hasMessages(Message.Level.WARNING)) {
                logger.warn("Warning building KIE module in component ${componentName}: ${results.toString()}")
            }

            findComponentKieSessions(componentName)

            // get the release ID and cache it
            releaseId = builder.getKieModule().getReleaseId()
            kieComponentReleaseIdCache.put(componentName, releaseId)

            return releaseId
        } catch (Throwable t) {
            logger.error("Error initializing KIE at ${kieRr.getLocation()}", t)
            return null
        }
    }

    protected void findAllComponentKieSessions() {
        for (String componentName in componentBaseLocations.keySet()) findComponentKieSessions(componentName)
    }
    protected void findComponentKieSessions(String componentName) {
        ResourceReference kieRr = getResourceFacade().getLocationReference("component://${componentName}/kie")
        if (!kieRr.getExists() || !kieRr.isDirectory()) return

        // get all KieBase and KieSession names and create reverse-reference Map so we know which component's
        //     module they are in, then add convenience methods to get any KieBase or KieSession by name
        ResourceReference kmoduleRr = kieRr.findChildFile("src/main/resources/META-INF/kmodule.xml")
        Node kmoduleNode = new XmlParser().parseText(kmoduleRr.getText())
        for (Object kbObj in kmoduleNode.get("kbase")) {
            Node kbaseNode = (Node) kbObj
            for (Object ksObj in kbaseNode.get("ksession")) {
                Node ksessionNode = (Node) ksObj
                String ksessionName = (String) ksessionNode.attribute("name")
                String existingComponentName = kieSessionComponentCache.get(ksessionName)
                if (existingComponentName) logger.warn("Found KIE session [${ksessionName}] in component [${existingComponentName}], replacing with session in component [${componentName}]")
                kieSessionComponentCache.put(ksessionName, componentName)
            }
        }

    }

    @Override
    KieSession getKieSession(String ksessionName) {
        String componentName = kieSessionComponentCache.get(ksessionName)
        // try finding all component sessions
        if (!componentName) findAllComponentKieSessions()
        componentName = kieSessionComponentCache.get(ksessionName)
        // still nothing? blow up
        if (!componentName) throw new IllegalStateException("No component KIE module found for session [${ksessionName}]")
        return getKieContainer(componentName).newKieSession(ksessionName)
    }
    @Override
    StatelessKieSession getStatelessKieSession(String ksessionName) {
        String componentName = kieSessionComponentCache.get(ksessionName)
        // try finding all component sessions
        if (!componentName) findAllComponentKieSessions()
        componentName = kieSessionComponentCache.get(ksessionName)
        // still nothing? blow up
        if (!componentName) throw new IllegalStateException("No component KIE module found for session [${ksessionName}]")
        return getKieContainer(componentName).newStatelessKieSession(ksessionName)
    }

    // ========== Interface Implementations ==========

    @Override
    ExecutionContext getExecutionContext() { return getEci() }
    ExecutionContextImpl getEci() {
        // the ExecutionContextImpl cast here looks funny, but avoids Groovy using a slow castToType call
        ExecutionContextImpl ec = (ExecutionContextImpl) activeContext.get()
        if (ec != null) return ec

        if (logger.traceEnabled) logger.trace("Creating new ExecutionContext in thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")
        if (!(Thread.currentThread().getContextClassLoader() instanceof StupidClassLoader))
            Thread.currentThread().setContextClassLoader(cachedClassLoader)
        ec = new ExecutionContextImpl(this)
        this.activeContext.set(ec)
        return ec
    }

    void destroyActiveExecutionContext() {
        ExecutionContext ec = this.activeContext.get()
        if (ec) {
            ec.destroy()
            this.activeContext.remove()
        }
    }

    /** Using an EC in multiple threads is dangerous as much of the ECI is not designed to be thread safe. */
    void useExecutionContextInThread(ExecutionContextImpl eci) {
        ExecutionContextImpl curEc = activeContext.get()
        if (curEc != null) curEc.destroy()
        activeContext.set(eci)
    }

    @Override
    void initComponent(String location) {
        ComponentInfo componentInfo = new ComponentInfo(location, this)
        // check dependencies
        if (componentInfo.dependsOnNames) for (String dependsOnName in componentInfo.dependsOnNames) {
            if (!componentInfoMap.containsKey(dependsOnName))
                throw new IllegalArgumentException("Component [${componentInfo.name}] depends on component [${dependsOnName}] which is not initialized")
        }
        addComponent(componentInfo)
    }

    protected void checkSortDependentComponents() {
        // we have an issue here where not all dependencies are declared, most are implied by component load order
        // because of this not doing a full topological sort, just a single pass with dependencies inserted as needed

        ArrayList<String> sortedNames = []
        for (ComponentInfo componentInfo in componentInfoMap.values()) {
            // for each dependsOn make sure component is valid, add to the list if not already there
            // given a close starting sort order this should get us to a pretty good list
            for (String dependsOnName in componentInfo.getRecursiveDependencies())
                if (!sortedNames.contains(dependsOnName)) sortedNames.add(dependsOnName)

            if (!sortedNames.contains(componentInfo.name)) sortedNames.add(componentInfo.name)
        }

        logger.info("Components after depends-on sort: ${sortedNames}")

        // see if all dependencies are met
        List<String> messages = []
        for (int i = 0; i < sortedNames.size(); i++) {
            String name = sortedNames.get(i)
            ComponentInfo componentInfo = componentInfoMap.get(name)
            for (String dependsOnName in componentInfo.dependsOnNames) {
                int dependsOnIndex = sortedNames.indexOf(dependsOnName)
                if (dependsOnIndex > i)
                    messages.add("Broken dependency order after initial pass: [${dependsOnName}] is after [${name}]".toString())
            }
        }

        if (messages) {
            StringBuilder sb = new StringBuilder()
            for (String message in messages) {
                logger.error(message)
                sb.append(message).append(" ")
            }
            throw new IllegalArgumentException(sb.toString())
        }

        // now create a new Map and replace the original
        Map<String, ComponentInfo> newMap = new LinkedHashMap<String, ComponentInfo>()
        for (String sortedName in sortedNames) newMap.put(sortedName, componentInfoMap.get(sortedName))
        componentInfoMap = newMap
    }

    protected void addComponent(ComponentInfo componentInfo) {
        if (componentInfoMap.containsKey(componentInfo.name))
            logger.warn("Overriding component [${componentInfo.name}] at [${componentInfoMap.get(componentInfo.name).location}] with location [${componentInfo.location}] because another component of the same name was initialized")
        // components registered later override those registered earlier by replacing the Map entry
        componentInfoMap.put(componentInfo.name, componentInfo)
        logger.info("Added component [${componentInfo.name}] at [${componentInfo.location}]")
    }

    protected void addComponentDir(String location) {
        ResourceReference componentRr = getResourceReference(location)
        // if directory doesn't exist skip it, runtime doesn't always have an component directory
        if (componentRr.getExists() && componentRr.isDirectory()) {
            // see if there is a components.xml file, if so load according to it instead of all sub-directories
            ResourceReference cxmlRr = getResourceReference(location + "/components.xml")

            if (cxmlRr.getExists()) {
                MNode componentList = MNode.parse(cxmlRr)
                for (MNode childNode in componentList.children) {
                    if (childNode.name == 'component') {
                        ComponentInfo componentInfo = new ComponentInfo(location, childNode, this)
                        addComponent(componentInfo)
                    } else if (childNode.name == 'component-dir') {
                        String locAttr = childNode.attribute("location")
                        addComponentDir(location + "/" + locAttr)
                    }
                }
            } else {
                // get all files in the directory
                TreeMap<String, ResourceReference> componentDirEntries = new TreeMap<String, ResourceReference>()
                for (ResourceReference componentSubRr in componentRr.getDirectoryEntries()) {
                    // if it's a directory and doesn't start with a "." then add it as a component dir
                    if (!componentSubRr.isDirectory() || componentSubRr.getFileName().startsWith(".")) continue
                    componentDirEntries.put(componentSubRr.getFileName(), componentSubRr)
                }
                for (Map.Entry<String, ResourceReference> componentDirEntry in componentDirEntries) {
                    ComponentInfo componentInfo = new ComponentInfo(componentDirEntry.getValue().getLocation(), this)
                    this.addComponent(componentInfo)
                }
            }
        }
    }

    protected ResourceReference getResourceReference(String location) {
        // TODO: somehow support other resource location types
        // the ResourceFacade inits after components are loaded (so it is aware of initial components), so we can't get ResourceReferences from it
        ResourceReference rr = new UrlResourceReference()
        rr.init(location, this)
        return rr
    }

    static class ComponentInfo {
        ExecutionContextFactoryImpl ecfi
        String name
        String location
        Set<String> dependsOnNames = new LinkedHashSet<String>()
        ComponentInfo(String baseLocation, MNode componentNode, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            String curLoc = null
            if (baseLocation) curLoc = baseLocation + "/" + componentNode.attribute("location")
            init(curLoc, componentNode)
        }
        ComponentInfo(String location, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            init(location, null)
        }
        protected void init(String specLoc, MNode origNode) {
            location = specLoc ?: origNode?.attribute("location")
            if (!location) throw new IllegalArgumentException("Cannot init component with no location (not specified or found in component.@location)")

            // clean up the location
            if (location.endsWith('/')) location = location.substring(0, location.length()-1)
            int lastSlashIndex = location.lastIndexOf('/')
            if (lastSlashIndex < 0) {
                // if this happens the component directory is directly under the runtime directory, so prefix loc with that
                location = ecfi.runtimePath + '/' + location
            }
            // set the default component name
            name = location.substring(lastSlashIndex+1)

            // make sure directory exists
            ResourceReference compRr = ecfi.getResourceReference(location)
            if (!compRr.getExists()) throw new IllegalArgumentException("Could not find component directory at: ${location}")
            if (!compRr.isDirectory()) throw new IllegalArgumentException("Component location is not a directory: ${location}")

            // see if there is a component.xml file, if so use that as the componentNode instead of origNode
            ResourceReference compXmlRr = ecfi.getResourceReference(location + "/component.xml")
            MNode componentNode
            if (compXmlRr.getExists()) {
                componentNode = MNode.parse(compXmlRr)
            } else {
                componentNode = origNode
            }

            if (componentNode != null) {
                String nameAttr = componentNode.attribute("name")
                if (nameAttr) name = nameAttr
                if (componentNode.hasChild("depends-on")) for (MNode dependsOnNode in componentNode.children("depends-on")) {
                    dependsOnNames.add(dependsOnNode.attribute("name"))
                }
            }
        }

        List<String> getRecursiveDependencies() {
            List<String> dependsOnList = []
            for (String dependsOnName in dependsOnNames) {
                ComponentInfo depCompInfo = ecfi.componentInfoMap.get(dependsOnName)
                if (depCompInfo == null)
                    throw new IllegalArgumentException("Component [${name}] depends on component [${dependsOnName}] which is not initialized")
                List<String> childDepList = depCompInfo.getRecursiveDependencies()
                for (String childDep in childDepList)
                    if (!dependsOnList.contains(childDep)) dependsOnList.add(childDep)

                if (!dependsOnList.contains(dependsOnName)) dependsOnList.add(dependsOnName)
            }
            return dependsOnList
        }
    }

    @Override
    void destroyComponent(String componentName) throws BaseException { componentInfoMap.remove(componentName) }

    @Override
    LinkedHashMap<String, String> getComponentBaseLocations() {
        LinkedHashMap<String, String> compLocMap = new LinkedHashMap<String, String>()
        for (ComponentInfo componentInfo in componentInfoMap.values()) {
            compLocMap.put(componentInfo.name, componentInfo.location)
        }
        return compLocMap
    }

    @Override
    L10nFacade getL10n() { getEci().getL10nFacade() }

    @Override
    ResourceFacade getResource() { return resourceFacade }

    @Override
    LoggerFacade getLogger() { return loggerFacade }

    @Override
    CacheFacade getCache() { return this.cacheFacade }

    @Override
    TransactionFacade getTransaction() { return transactionFacade }

    @Override
    EntityFacade getEntity() { getEntityFacade(getExecutionContext()?.getTenantId()) }

    @Override
    ServiceFacade getService() { return serviceFacade }

    @Override
    ScreenFacade getScreen() { return screenFacade }

    // ========== Server Stat Tracking ==========
    boolean getSkipStats() {
        // NOTE: the results of this condition eval can't be cached because the expression can use any data in the ec
        ExecutionContextImpl eci = getEci()
        return skipStatsCond ? eci.resource.condition(skipStatsCond, null, [pathInfo:eci.web?.request?.pathInfo]) : false
    }

    protected boolean artifactPersistHit(String artifactType, String artifactSubType) {
        // now checked before calling this: if ("entity".equals(artifactType)) return false
        String cacheKey = artifactType + artifactSubType
        Boolean ph = (Boolean) artifactPersistHitByType.get(cacheKey)
        if (ph == null) {
            MNode artifactStats = getArtifactStatsNode(artifactType, artifactSubType)
            ph = 'true'.equals(artifactStats.attribute('persist-hit'))
            artifactPersistHitByType.put(cacheKey, ph)
        }
        return ph.booleanValue()
    }
    protected boolean artifactPersistBin(String artifactType, String artifactSubType) {
        String cacheKey = artifactType + artifactSubType
        Boolean pb = (Boolean) artifactPersistBinByType.get(cacheKey)
        if (pb == null) {
            MNode artifactStats = getArtifactStatsNode(artifactType, artifactSubType)
            pb = 'true'.equals(artifactStats.attribute('persist-bin'))
            artifactPersistBinByType.put(cacheKey, pb)
        }
        return pb.booleanValue()
    }

    boolean isAuthzEnabled(String artifactTypeEnumId) {
        Boolean en = (Boolean) artifactTypeAuthzEnabled.get(artifactTypeEnumId)
        if (en == null) {
            MNode aeNode = getArtifactExecutionNode(artifactTypeEnumId)
            en = aeNode != null ? !(aeNode.attribute('authz-enabled') == "false") : true
            artifactTypeAuthzEnabled.put(artifactTypeEnumId, en)
        }
        return en.booleanValue()
    }
    boolean isTarpitEnabled(String artifactTypeEnumId) {
        Boolean en = (Boolean) artifactTypeTarpitEnabled.get(artifactTypeEnumId)
        if (en == null) {
            MNode aeNode = getArtifactExecutionNode(artifactTypeEnumId)
            en = aeNode != null ? !(aeNode.attribute('tarpit-enabled') == "false") : true
            artifactTypeTarpitEnabled.put(artifactTypeEnumId, en)
        }
        return en.booleanValue()
    }

    protected MNode getArtifactStatsNode(String artifactType, String artifactSubType) {
        // find artifact-stats node by type AND sub-type, if not found find by just the type
        MNode artifactStats = confXmlRoot.first("server-stats").first({ MNode it -> it.name == "artifact-stats" &&
                it.attribute("type") == artifactType && it.attribute("sub-type") == artifactSubType })
        if (artifactStats == null) artifactStats = confXmlRoot.first("server-stats")
                .first({ MNode it -> it.name == "artifact-stats" && it.attribute('type') == artifactType })
        return artifactStats
    }

    protected final Set<String> entitiesToSkipHitCount = new HashSet([
            'moqui.server.ArtifactHit', 'create#moqui.server.ArtifactHit',
            'moqui.server.ArtifactHitBin', 'create#moqui.server.ArtifactHitBin',
            'moqui.entity.SequenceValueItem', 'moqui.security.UserAccount', 'moqui.tenant.Tenant',
            'moqui.tenant.TenantDataSource', 'moqui.tenant.TenantDataSourceXaProp',
            'moqui.entity.document.DataDocument', 'moqui.entity.document.DataDocumentField',
            'moqui.entity.document.DataDocumentCondition', 'moqui.entity.feed.DataFeedAndDocument',
            'moqui.entity.view.DbViewEntity', 'moqui.entity.view.DbViewEntityMember',
            'moqui.entity.view.DbViewEntityKeyMap', 'moqui.entity.view.DbViewEntityAlias'])
    protected final Set<String> artifactTypesForStatsSkip = new TreeSet(["screen", "transition", "screen-content"])
    protected final long checkSlowThreshold = 20L
    protected final double userImpactMinMillis = 200

    @CompileStatic
    static class ArtifactStatsInfo {
        // put this here so we only have to do one Map lookup per countArtifactHit call
        ArtifactBinInfo curHitBin = null
        long hitCount = 0L
        long slowHitCount = 0L
        double totalTimeMillis = 0
        double totalSquaredTime = 0
        double getAverage() { return hitCount > 0 ? totalTimeMillis / hitCount : 0 }
        double getStdDev() {
            if (hitCount < 2) return 0
            return Math.sqrt(Math.abs(totalSquaredTime - ((totalTimeMillis*totalTimeMillis) / hitCount)) / (hitCount - 1L))
        }
        void incrementHitCount() { hitCount++ }
        void incrementSlowHitCount() { slowHitCount++ }
        void addRunningTime(double runningTime) {
            totalTimeMillis = totalTimeMillis + runningTime
            totalSquaredTime = totalSquaredTime + (runningTime * runningTime)
        }
    }
    @CompileStatic
    static class ArtifactBinInfo {
        String artifactType
        String artifactSubType
        String artifactName
        long startTime

        long hitCount = 0L
        long slowHitCount = 0L
        double totalTimeMillis = 0
        double totalSquaredTime = 0
        double minTimeMillis = Long.MAX_VALUE
        double maxTimeMillis = 0

        ArtifactBinInfo(String artifactType, String artifactSubType, String artifactName, long startTime) {
            this.artifactType = artifactType
            this.artifactSubType = artifactSubType
            this.artifactName = artifactName
            this.startTime = startTime
        }

        void incrementHitCount() { hitCount++ }
        void incrementSlowHitCount() { slowHitCount++ }
        void addRunningTime(double runningTime) {
            totalTimeMillis = totalTimeMillis + runningTime
            totalSquaredTime = totalSquaredTime + (runningTime * runningTime)
        }

        // NOTE: ArtifactHitBin always created in DEFAULT tenant since data is aggregated across all tenants, mostly used to monitor performance
        EntityValue makeAhbValue(ExecutionContextFactoryImpl ecfi, Timestamp binEndDateTime) {
            Map<String, Object> ahb = [artifactType:artifactType, artifactSubType:artifactSubType,
                                       artifactName:artifactName, binStartDateTime:new Timestamp(startTime), binEndDateTime:binEndDateTime,
                                       hitCount:hitCount, totalTimeMillis:new BigDecimal(totalTimeMillis),
                                       totalSquaredTime:new BigDecimal(totalSquaredTime), minTimeMillis:new BigDecimal(minTimeMillis),
                                       maxTimeMillis:new BigDecimal(maxTimeMillis), slowHitCount:slowHitCount] as Map<String, Object>
            ahb.serverIpAddress = ecfi.localhostAddress?.getHostAddress() ?: "127.0.0.1"
            ahb.serverHostName = ecfi.localhostAddress?.getHostName() ?: "localhost"
            EntityValue ahbValue = ecfi.getEntityFacade("DEFAULT").makeValue("moqui.server.ArtifactHitBin")
            ahbValue.setAll(ahb)
            return ahbValue
        }
    }

    void countArtifactHit(String artifactType, String artifactSubType, String artifactName, Map<String, Object> parameters,
                          long startTime, double runningTimeMillis, Long outputSize) {
        boolean isEntity = 'entity'.equals(artifactType) || 'entity-implicit'.equals(artifactSubType)
        // don't count the ones this calls
        if (isEntity && entitiesToSkipHitCount.contains(artifactName)) return
        ExecutionContextImpl eci = getEci()
        if (eci.getSkipStats() && artifactTypesForStatsSkip.contains(artifactType)) return

        boolean isSlowHit = false
        if (artifactPersistBin(artifactType, artifactSubType)) {
            String binKey = new StringBuilder(200).append(artifactType).append('.').append(artifactSubType).append(':').append(artifactName).toString()
            ArtifactStatsInfo statsInfo = (ArtifactStatsInfo) artifactStatsInfoByType.get(binKey)
            if (statsInfo == null) {
                // consider seeding this from the DB using ArtifactHitReport to get all past data, or maybe not to better handle different servers/etc over time, etc
                statsInfo = new ArtifactStatsInfo()
                artifactStatsInfoByType.put(binKey, statsInfo)
            }

            ArtifactBinInfo abi = statsInfo.curHitBin
            if (abi == null) {
                abi = new ArtifactBinInfo(artifactType, artifactSubType, artifactName, startTime)
                statsInfo.curHitBin = abi
            }

            // has the current bin expired since the last hit record?
            long binStartTime = abi.startTime
            if (startTime > (binStartTime + hitBinLengthMillis.longValue())) {
                if (logger.isTraceEnabled()) logger.trace("Advancing ArtifactHitBin [${artifactType}.${artifactSubType}:${artifactName}] current hit start [${new Timestamp(startTime)}], bin start [${new Timestamp(abi.startTime)}] bin length ${hitBinLengthMillis/1000} seconds")
                advanceArtifactHitBin(eci, statsInfo, artifactType, artifactSubType, artifactName, startTime, hitBinLengthMillis)
                abi = statsInfo.curHitBin
            }

            // handle current hit bin
            abi.incrementHitCount()
            // do something funny with these so we get a better avg and std dev, leave out the first result (count 2nd
            //     twice) if first hit is more than 2x the second because the first hit is almost always MUCH slower
            if (abi.hitCount == 2L && abi.totalTimeMillis > (runningTimeMillis * 2)) {
                abi.setTotalTimeMillis(runningTimeMillis * 2)
                abi.setTotalSquaredTime(runningTimeMillis * runningTimeMillis * 2)
            } else {
                abi.addRunningTime(runningTimeMillis)
            }
            if (runningTimeMillis < abi.minTimeMillis) abi.setMinTimeMillis(runningTimeMillis)
            if (runningTimeMillis > abi.maxTimeMillis) abi.setMaxTimeMillis(runningTimeMillis)

            // handle stats since start
            statsInfo.incrementHitCount()
            long statsHitCount = statsInfo.hitCount
            if (statsHitCount == 2L && (statsInfo.totalTimeMillis) > (runningTimeMillis * 2) ) {
                statsInfo.setTotalTimeMillis(runningTimeMillis * 2)
                statsInfo.setTotalSquaredTime(runningTimeMillis * runningTimeMillis * 2)
            } else {
                statsInfo.addRunningTime(runningTimeMillis)
            }
            // check for slow hits
            if (statsHitCount > checkSlowThreshold) {
                // calc new average and standard deviation
                double average = statsInfo.getAverage()
                double stdDev = statsInfo.getStdDev()

                // if runningTime is more than 2.6 std devs from the avg, count it and possibly log it
                // using 2.6 standard deviations because 2 would give us around 5% of hits (normal distro), shooting for more like 1%
                double slowTime = average + (stdDev * 2.6)
                if (slowTime != 0 && runningTimeMillis > slowTime) {
                    if (runningTimeMillis > userImpactMinMillis)
                        logger.warn("Slow hit to ${binKey} running time ${runningTimeMillis} is greater than average [${average}] plus 2 standard deviations [${stdDev}]")
                    abi.incrementSlowHitCount()
                    statsInfo.incrementSlowHitCount()
                    isSlowHit = true
                }
            }
        }
        // NOTE: never save individual hits for entity artifact hits, way too heavy and also avoids self-reference
        //     (could also be done by checking for ArtifactHit/etc of course)
        // Always save slow hits above userImpactMinMillis regardless of settings
        if (!isEntity && ((isSlowHit && runningTimeMillis > userImpactMinMillis) || artifactPersistHit(artifactType, artifactSubType))) {
            // NOTE: ArtifactHit saved in current tenant, ArtifactHitBin saved in DEFAULT tenant
            EntityValueBase ahp = (EntityValueBase) eci.entity.makeValue("moqui.server.ArtifactHit")
            ahp.putNoCheck("visitId", eci.user.visitId)
            ahp.putNoCheck("userId", eci.user.userId)
            ahp.putNoCheck("isSlowHit", isSlowHit ? 'Y' : 'N')
            ahp.putNoCheck("artifactType", artifactType)
            ahp.putNoCheck("artifactSubType", artifactSubType)
            ahp.putNoCheck("artifactName", artifactName)
            ahp.putNoCheck("startDateTime", new Timestamp(startTime))
            ahp.putNoCheck("runningTimeMillis", runningTimeMillis)

            if (parameters != null && parameters.size() > 0) {
                StringBuilder ps = new StringBuilder()
                for (Map.Entry<String, Object> pme in parameters.entrySet()) {
                    if (StupidJavaUtilities.isEmpty(pme.value)) continue
                    if (pme.key?.contains("password")) continue
                    if (ps.length() > 0) ps.append(",")
                    ps.append(pme.key).append("=").append(pme.value)
                }
                if (ps.length() > 255) ps.delete(255, ps.length())
                ahp.putNoCheck("parameterString", ps.toString())
            }
            if (outputSize != null) ahp.putNoCheck("outputSize", outputSize)
            if (eci.getMessage().hasError()) {
                ahp.putNoCheck("wasError", "Y")
                StringBuilder errorMessage = new StringBuilder()
                for (String curErr in eci.message.errors) errorMessage.append(curErr).append(";")
                if (errorMessage.length() > 255) errorMessage.delete(255, errorMessage.length())
                ahp.putNoCheck("errorMessage", errorMessage.toString())
            } else {
                ahp.putNoCheck("wasError", "N")
            }
            if (eci.web != null) {
                String fullUrl = eci.web.getRequestUrl()
                fullUrl = (fullUrl.length() > 255) ? fullUrl.substring(0, 255) : fullUrl.toString()
                ahp.putNoCheck("requestUrl", fullUrl)
                String referrer = eci.web.request.getHeader("Referrer")
                if (referrer != null && referrer.length() > 0) ahp.putNoCheck("referrerUrl", referrer)
            }

            ahp.putNoCheck("serverIpAddress", localhostAddress != null ? localhostAddress.getHostAddress() : "127.0.0.1")
            ahp.putNoCheck("serverHostName", localhostAddress != null ? localhostAddress.getHostName() : "localhost")

            // NOTE: async service scheduling is slow enough that it is faster to just create the record now
            // eci.service.async().name("create", "moqui.server.ArtifactHit").parameters(ahp).call()
            // have an authorize-skip=create on the entity so don't need to disable authz here
            eci.runInWorkerThread({ ahp.setSequencedIdPrimary().create() })
        }
    }

    protected synchronized void advanceArtifactHitBin(ExecutionContextImpl eci, ArtifactStatsInfo statsInfo,
                String artifactType, String artifactSubType, String artifactName, long startTime, int hitBinLengthMillis) {
        ArtifactBinInfo abi = statsInfo.curHitBin
        if (abi == null) {
            statsInfo.curHitBin = new ArtifactBinInfo(artifactType, artifactSubType, artifactName, startTime)
            return
        }

        // check the time again and return just in case something got in while waiting with the same type
        long binStartTime = abi.startTime
        if (startTime < (binStartTime + hitBinLengthMillis)) return

        // otherwise, persist the old and create a new one
        EntityValue ahb = abi.makeAhbValue(this, new Timestamp(binStartTime + hitBinLengthMillis))
        eci.runInWorkerThread({ ahb.setSequencedIdPrimary().create() })

        statsInfo.curHitBin = new ArtifactBinInfo(artifactType, artifactSubType, artifactName, startTime)
    }

    // ========== Configuration File Merging Methods ==========

    protected void mergeConfigNodes(MNode baseNode, MNode overrideNode) {
        baseNode.mergeSingleChild(overrideNode, "tools")

        baseNode.mergeChildWithChildKey(overrideNode, "cache-list", "cache", "name", null)

        if (overrideNode.hasChild("server-stats")) {
            // the artifact-stats nodes have 2 keys: type, sub-type; can't use the normal method
            MNode ssNode = baseNode.first("server-stats")
            MNode overrideSsNode = overrideNode.first("server-stats")
            // override attributes for this node
            ssNode.attributes.putAll(overrideSsNode.attributes)
            for (MNode childOverrideNode in overrideSsNode.children("artifact-stats")) {
                String type = childOverrideNode.attribute("type")
                String subType = childOverrideNode.attribute("sub-type")
                MNode childBaseNode = ssNode.first({ MNode it -> it.name == "artifact-stats" && it.attribute("type") == type &&
                        (it.attribute("sub-type") == subType || (!it.attribute("sub-type") && !subType)) })
                if (childBaseNode) {
                    // merge the node attributes
                    childBaseNode.attributes.putAll(childOverrideNode.attributes)
                } else {
                    // no matching child base node, so add a new one
                    ssNode.append(childOverrideNode)
                }
            }
        }

        baseNode.mergeChildWithChildKey(overrideNode, "webapp-list", "webapp", "name",
                { MNode childBaseNode, MNode childOverrideNode -> mergeWebappChildNodes(childBaseNode, childOverrideNode) })

        baseNode.mergeChildWithChildKey(overrideNode, "artifact-execution-facade", "artifact-execution", "type", null)

        if (overrideNode.hasChild("user-facade")) {
            MNode ufBaseNode = baseNode.first("user-facade")
            MNode ufOverrideNode = overrideNode.first("user-facade")
            ufBaseNode.mergeSingleChild(ufOverrideNode, "password")
            ufBaseNode.mergeSingleChild(ufOverrideNode, "login-key")
            ufBaseNode.mergeSingleChild(ufOverrideNode, "login")
        }

        if (overrideNode.hasChild("transaction-facade")) {
            MNode tfBaseNode = baseNode.first("transaction-facade")
            MNode tfOverrideNode = overrideNode.first("transaction-facade")
            tfBaseNode.attributes.putAll(tfOverrideNode.attributes)
            tfBaseNode.mergeSingleChild(tfOverrideNode, "server-jndi")
            tfBaseNode.mergeSingleChild(tfOverrideNode, "transaction-jndi")
            tfBaseNode.mergeSingleChild(tfOverrideNode, "transaction-internal")
        }

        if (overrideNode.hasChild("resource-facade")) {
            baseNode.mergeChildWithChildKey(overrideNode, "resource-facade", "resource-reference", "scheme", null)
            baseNode.mergeChildWithChildKey(overrideNode, "resource-facade", "template-renderer", "extension", null)
            baseNode.mergeChildWithChildKey(overrideNode, "resource-facade", "script-runner", "extension", null)
        }

        baseNode.mergeChildWithChildKey(overrideNode, "screen-facade", "screen-text-output", "type", null)

        if (overrideNode.hasChild("service-facade")) {
            MNode sfBaseNode = baseNode.first("service-facade")
            MNode sfOverrideNode = overrideNode.first("service-facade")
            sfBaseNode.mergeNodeWithChildKey(sfOverrideNode, "service-location", "name", null)
            sfBaseNode.mergeChildrenByKey(sfOverrideNode, "service-type", "name", null)
            sfBaseNode.mergeChildrenByKey(sfOverrideNode, "service-file", "location", null)
            sfBaseNode.mergeChildrenByKey(sfOverrideNode, "startup-service", "name", null)

            // handle thread-pool
            MNode tpOverrideNode = sfOverrideNode.first("thread-pool")
            if (tpOverrideNode) {
                MNode tpBaseNode = sfBaseNode.first("thread-pool")
                if (tpBaseNode) {
                    tpBaseNode.mergeNodeWithChildKey(tpOverrideNode, "run-from-pool", "name", null)
                } else {
                    sfBaseNode.append(tpOverrideNode)
                }
            }

            // handle jms-service, just copy all over
            for (MNode jsOverrideNode in sfOverrideNode.children("jms-service")) {
                sfBaseNode.append(jsOverrideNode)
            }
        }

        if (overrideNode.hasChild("entity-facade")) {
            MNode efBaseNode = baseNode.first("entity-facade")
            MNode efOverrideNode = overrideNode.first("entity-facade")
            efBaseNode.mergeNodeWithChildKey(efOverrideNode, "datasource", "group-name", { MNode childBaseNode, MNode childOverrideNode ->
                // handle the jndi-jdbc and inline-jdbc nodes: if either exist in override have it totally remove both from base, then copy over
                if (childOverrideNode.hasChild("jndi-jdbc") || childOverrideNode.hasChild("inline-jdbc")) {
                    childBaseNode.remove("jndi-jdbc")
                    childBaseNode.remove("inline-jdbc")

                    if (childOverrideNode.hasChild("inline-jdbc")) {
                        childBaseNode.append(childOverrideNode.first("inline-jdbc"))
                    } else if (childOverrideNode.hasChild("jndi-jdbc")) {
                        childBaseNode.append(childOverrideNode.first("jndi-jdbc"))
                    }
                }
            })
            efBaseNode.mergeSingleChild(efOverrideNode, "server-jndi")
            // for load-entity and load-data just copy over override nodes
            for (MNode copyNode in efOverrideNode.children("load-entity")) efBaseNode.append(copyNode)
            for (MNode copyNode in efOverrideNode.children("load-data")) efBaseNode.append(copyNode)
        }

        if (overrideNode.hasChild("database-list")) {
            baseNode.mergeChildWithChildKey(overrideNode, "database-list", "dictionary-type", "type", null)
            // handle database-list -> database, database -> database-type@type
            baseNode.mergeChildWithChildKey(overrideNode, "database-list", "database", "name",
                    { MNode childBaseNode, MNode childOverrideNode -> childBaseNode.mergeNodeWithChildKey(childOverrideNode, "database-type", "type", null) })
        }

        baseNode.mergeChildWithChildKey(overrideNode, "repository-list", "repository", "name", null)

        if (overrideNode.hasChild("component-list")) {
            if (!baseNode.hasChild("component-list")) baseNode.append("component-list", null)
            MNode baseComponentNode = baseNode.first("component-list")
            for (MNode copyNode in overrideNode.first("component-list").children) baseComponentNode.append(copyNode)
            // mergeNodeWithChildKey((Node) baseNode."component-list"[0], (Node) overrideNode."component-list"[0], "component-dir", "location")
            // mergeNodeWithChildKey((Node) baseNode."component-list"[0], (Node) overrideNode."component-list"[0], "component", "name")
        }
    }

    protected static void mergeWebappChildNodes(MNode baseNode, MNode overrideNode) {
        baseNode.mergeNodeWithChildKey(overrideNode, "root-screen", "host", null)
        // handle webapp -> first-hit-in-visit[1], after-request[1], before-request[1], after-login[1], before-logout[1], root-screen[1]
        mergeWebappActions(baseNode, overrideNode, "first-hit-in-visit")
        mergeWebappActions(baseNode, overrideNode, "after-request")
        mergeWebappActions(baseNode, overrideNode, "before-request")
        mergeWebappActions(baseNode, overrideNode, "after-login")
        mergeWebappActions(baseNode, overrideNode, "before-logout")
        mergeWebappActions(baseNode, overrideNode, "after-startup")
        mergeWebappActions(baseNode, overrideNode, "before-shutdown")
    }

    protected static void mergeWebappActions(MNode baseWebappNode, MNode overrideWebappNode, String childNodeName) {
        List<MNode> overrideActionNodes = overrideWebappNode.first(childNodeName)?.first("actions")?.children
        if (overrideActionNodes) {
            MNode childNode = baseWebappNode.first(childNodeName)
            if (childNode == null) childNode = baseWebappNode.append(childNodeName, null)
            MNode actionsNode = childNode.first("actions")
            if (actionsNode == null) actionsNode = childNode.append("actions", null)

            for (MNode overrideActionNode in overrideActionNodes) actionsNode.append(overrideActionNode)
        }
    }

    MNode getWebappNode(String webappName) { return confXmlRoot.first("webapp-list")
            .first({ MNode it -> it.name == "webapp" && it.attribute("name") == webappName }) }

    WebappInfo getWebappInfo(String webappName) {
        if (webappInfoMap.containsKey(webappName)) return webappInfoMap.get(webappName)
        return makeWebappInfo(webappName)
    }
    protected synchronized WebappInfo makeWebappInfo(String webappName) {
        WebappInfo wi = new WebappInfo(webappName, this)
        webappInfoMap.put(webappName, wi)
        return wi
    }

    static class WebappInfo {
        String webappName
        XmlAction firstHitInVisitActions = null
        XmlAction beforeRequestActions = null
        XmlAction afterRequestActions = null
        XmlAction afterLoginActions = null
        XmlAction beforeLogoutActions = null
        XmlAction afterStartupActions = null
        XmlAction beforeShutdownActions = null

        WebappInfo(String webappName, ExecutionContextFactoryImpl ecfi) {
            this.webappName = webappName
            init(ecfi)
        }

        void init(ExecutionContextFactoryImpl ecfi) {
            // prep actions
            MNode webappNode = ecfi.getWebappNode(webappName)
            if (webappNode.hasChild("first-hit-in-visit"))
                this.firstHitInVisitActions = new XmlAction(ecfi, webappNode.first("first-hit-in-visit").first("actions"),
                        "webapp_${webappName}.first_hit_in_visit.actions")

            if (webappNode.hasChild("before-request"))
                this.beforeRequestActions = new XmlAction(ecfi, webappNode.first("before-request").first("actions"),
                        "webapp_${webappName}.before_request.actions")
            if (webappNode.hasChild("after-request"))
                this.afterRequestActions = new XmlAction(ecfi, webappNode.first("after-request").first("actions"),
                        "webapp_${webappName}.after_request.actions")

            if (webappNode.hasChild("after-login"))
                this.afterLoginActions = new XmlAction(ecfi, webappNode.first("after-login").first("actions"),
                        "webapp_${webappName}.after_login.actions")
            if (webappNode.hasChild("before-logout"))
                this.beforeLogoutActions = new XmlAction(ecfi, webappNode.first("before-logout").first("actions"),
                        "webapp_${webappName}.before_logout.actions")

            if (webappNode.hasChild("after-startup"))
                this.afterStartupActions = new XmlAction(ecfi, webappNode.first("after-startup").first("actions"),
                        "webapp_${webappName}.after_startup.actions")
            if (webappNode.hasChild("before-shutdown"))
                this.beforeShutdownActions = new XmlAction(ecfi, webappNode.first("before-shutdown").first("actions"),
                        "webapp_${webappName}.before_shutdown.actions")
        }
    }

    @Override
    String toString() { return "ExecutionContextFactory" }
}

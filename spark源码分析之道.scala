
1 Spark设计理念和基本架构

	1.1 初始Spark
		Spark vs Hadoop
			Hadoop是高吞吐,批量处理,离线计算;我部门就是跑批的形式,批量计算
			实时计算?No

			Spark通过内存计算,比磁盘快十倍以上,
			内存直接由CPU控制,也就是CPU内部集成的内存控制器,所以说内存是直接与CPU对接,享受与CPU通信的最优带宽,然而硬盘则是通过桥接芯片(在主板上)与CPU相连,所以说速度比较慢,SATA接口目前最高速度是6GB接口也就是实际传输速度550MB/s,这也是硬盘的最快速度,其次就是与CPU直连的m.2口和pcie口,这两种接口都是通过pcie通道与CPU直连,所以说速度都在1.2G/s左右,pcie接口速度随着pcie通道数量的提升而提升,2~4G/s的读写速度都是有的

			内存的读写速度随便可以上20GB/s（50GB/s）
			当然更快的还有CPU的那几级缓存,比如L1可以到400+GB/s的读取、200+GB/s的写入（3100+GB/s读、1600+GB/s写）。
			链接:https://www.zhihu.com/question/33272188

		Spark特点:
			快速处理
			Hadoop是将中间输出和结果存在HDFS中,读写HDFS->磁盘IO,成为速度瓶颈
			Spark可以利用内存,将中间输出和结果存在内存中,避免大量磁盘IO

			Spark本身的DAG,有向无环图,支持内存计算.

			容易使用.支持多种语言编写

			支持查询.SQL

			流式计算,SparkStreaming

			丰富数据库支持.HDFS,Cassandra,HBase,Hive等,都可以接入Spark体系中

	1.2 Spark基础知识
		Spark的概念
			RDD, resillient distributed dataset, 弹性分布式数据集
			Task,任务,分为ShuffleMapTask和ResultMap,分别对应于Map和Reduce
			Job,提交的作业,一个Job可以由多个Task构成
			Stage,Job分成的阶段.一个Job可能由一个多个Stage构成
			Partition,分区,RDD的数据可以划分为几个分区
			NarrowDependency,窄依赖,子RDD依赖于父RDD中的固定RDD,不涉及shuffle过程(不涉及洗牌)
			ShuffleDependency,洗牌依赖,子RDD依赖父RDD的多个未知RDD,涉及洗牌过程

	1.3 Spark设计思想
		Spark模块
			Spark Core = SparkContext初始化, 部署模式, 存储体系, 任务提交和执行, 计算引擎
			SparkSQL
			SparkStreaming,流式计算,支持Kafka,Flume等
			GraphX,图形计算能力
			MLib, 机器学习

		Spark核心功能 Spark Core
			SparkContext, context,顾名思义,环境,上下文.SparkContext = 网络通信+分布式部署+消息通信+存储+计算+缓存+测量系统+文件服务+Web服务
				因此sc的初始化极其重要
				开发人员只需要利用sc提供的api就可以完成功能开发.
				sc内置的DAGScheduler,有向无环图调度器,就可以创建job,将rdd划分到不同stage,提交stage.
				taskscheduler,任务调度器,可以申请资源,任务提交,请求集群对任务的调度

			存储体系
				spark优先利用内存作为存储.减少磁盘IO,提高效率.
				提供以内存为中信的高容错的分布式文件系统,Tachyon

			计算引擎
				DAGScheduler向无环图调度器,RDD,Executor一起,负责Map和Reduce的执行.
				DAGScheduler和RDD位于sc内部,但是任务正式提交与执行之前,会将Job中的RDSD组成DAG,然后划分stage,决定任务执行阶段,任务的数量,迭代计算,shuffle等过程

			部署模式
				Yarn,Mesos,Standalone

		Spark扩展功能
			SQL
			Streaming
			GraphX

		Spark模型设计
			通过sc提供的api编写driver
			使用sc提交的用户程序,先用blockmanager和broadcastmanager将任务的hadoop配置进行广播,然后DAGscheduler将任务转化为RDD,
			并组织成DAG,DAG还被划分为不同的stage;TaskScheduler借助ActorSystem将任务提交给集群管理器ClusterManager
			ClusterManager给任务分配资源,将具体任务分配给worker,worker创建executor来处理任务运行;有不同的集群管理器,standalone,yarn,mesos等

		RDD计算模型
			ClusterManager:yarn,mesos,standalone;负责worker上面的资源分配给应用程序;不负责对executor的资源分配;也就是将资源分给具体的worknode,工作节点,但是工作节点内部如何划分资源,属于work内政
			worker:干活的,工作节点
			executor:执行计算任务的一线进程.负责任务执行,worker和driverapp的信息同步
			driverapp:客户端驱动程序,将任务转成RDD和DAG,与ClusterManager通信和调度

2 SparkContext初始化
参考:https://www.yuque.com/liangjiangjiang/tm6hpg/ogaa4y
	2.1 SparkContext概述
		sparkcontext(以下简称sc)主要由sparkconf(以下简称scf)负责配置参数;如果sc是发动机,scf就是操作面板
		scf的构造很简单,通过concurrenthashmap来维护属性

		class SparkConf(loadDefaults: Boolean) extends Cloneable with Logging with Serializable {

		  import SparkConf._

		  /** Create a SparkConf that loads defaults from system properties and the classpath */
		  def this() = this(true)

		  private val settings = new ConcurrentHashMap[String, String]()

		  @transient private lazy val reader: ConfigReader = {
		    val _reader = new ConfigReader(new SparkConfigProvider(settings))
		    _reader.bindEnv(new ConfigProvider {
		      override def get(key: String): Option[String] = Option(getenv(key))
		    })
		    _reader
		  }
		  ...
		}
  		下面一堆东西,暂且不提.上面,初始化了settings,并初始化了一个configreader,读取配置.通过内置的get方法来获取配置

  		sc的初始化步骤
  			创建spark执行环境 sparkenv
  			创建rdd清理器 metadatacleaner
  			创建并初始化 sparkUI
  			Hadoop相关配置和executor环境变量的设置
  			创建任务调度器 TaskScheduer
  			创建启动 DagScheduer, DAG调度器
  			TaskScheduer 初始化, 任务调度器初始化
  			BlockManager 初始化, 块管理器初始化
  			MetricsSystem, 启动测量系统
  			创建启动 Executor 分配管理器 ExecutorAllocationManager, 负责管理执行器的
  			ContextCleaner, 背景清理器
  			Spark 环境更新
  			创建 DagScheduerSource & BlockManagerSource, DAGScheduler的源, 块管理器的源
  			将sc标记为激活

  		scf的实现
			class SparkContext(config: SparkConf) extends Logging {

			  // The call site where this SparkContext was constructed.
			  private val creationSite: CallSite = Utils.getCallSite() //callsite是线程栈中,最靠近栈顶的用户类和最靠近栈底的scala或spark核心类信息

			  // If true, log warnings instead of throwing exceptions when multiple SparkContexts are active
			  // sc默认只有一个实例;由 spark.driver.allowMultipleContexts 控制;如果需要多个实例,将false改成true;不报错,而是warning
			  // 参数的意思是, 是否允许多个sparkcontext
			  private val allowMultipleContexts: Boolean =
			    config.getBoolean("spark.driver.allowMultipleContexts", false)

			  // In order to prevent multiple SparkContexts from being active at the same time, mark this
			  // context as having started construction.
			  // NOTE: this must be placed at the beginning of the SparkContext constructor.
			  //创建部分构造的sc
			  SparkContext.markPartiallyConstructed(this, allowMultipleContexts)
			  ...
			}

		这里提到的,markPartiallyConstructed 的实现在下面;字面意思是,标记部分初始化的,也就是对没有完全初始化的sc,记下来
		  private[spark] def markPartiallyConstructed(
		      sc: SparkContext,
		      allowMultipleContexts: Boolean): Unit = {
		    SPARK_CONTEXT_CONSTRUCTOR_LOCK.synchronized {
		      assertNoOtherContextIsRunning(sc, allowMultipleContexts)
		      contextBeingConstructed = Some(sc)
		    }
		  }
		这里的Some是Option下属
		Option[A] (sealed trait) 有两个取值:
			1. Some[A] 有类型A的值
			2. None 没有值

		Option一般有两种用法:
			1. 模式匹配
				Option[A] option
				option match {
				    case Some(a) => a
				    case None => "?"
				}
		    2. map
				option map( o => "?" ).getOrElse("默认值")
 

		书里面提到的,复制sparkconf,然后校验配置信息,在setLogLevel方法后面实现;

			def setLogLevel(logLevel: String) {
			// let's allow lowercase or mixed case too
			// 允许小写和混合大小写
			val upperCased = logLevel.toUpperCase(Locale.ROOT)

			require(SparkContext.VALID_LOG_LEVELS.contains(upperCased),
			  s"Supplied level $logLevel did not match one of:" +
			    s" ${SparkContext.VALID_LOG_LEVELS.mkString(",")}")
			Utils.setLogLevel(org.apache.log4j.Level.toLevel(upperCased))
			/*
			require和assert都用于在运行时执行某些检查来验证某些条件:https://blog.csdn.net/u013007900/article/details/79179683
			require是不可取消的,它会在库（包括内部库）中使用,以通知调用者调用给定方法/函数的先决条件,例如,被用于限制某些参数。这样对于开发人员严非常有意义。
			这里是要求sc验证loglevel,需要满足条件
			
			然后利用Utils来设置loglevel
			Utils是Spark中最常用的工具类之一,暂时忽略*/
			}

			try {
			_conf = config.clone()
			_conf.validateSettings()

			/*
			_的用法:https://www.cnblogs.com/linkmust/p/10970631.html;https://www.baeldung.com/scala/underscore
			这里的_conf,是引用定义的私有变量
			*/
			if (!_conf.contains("spark.master")) {
			  throw new SparkException("A master URL must be set in your configuration")
				}
			if (!_conf.contains("spark.app.name")) {
			  throw new SparkException("An application name must be set in your configuration")
				}
			}

	2.2 创建 SparkEnv,执行环境
		SparkEnv 是spark执行环境对象,保活众多与executor执行相关的对象; sparkenv 既然是执行环境, 就和executor相关
		SparkEnv.createDriverEnv 创建DriverEnv,三个参数, conf, isLocal, listenerBus;顾名思义,配置;是否是本地的;监听器
			private[spark] def createSparkEnv(
			    conf: SparkConf,
			    isLocal: Boolean,
			    listenerBus: LiveListenerBus): SparkEnv = {
			  SparkEnv.createDriverEnv(conf, isLocal, listenerBus, SparkContext.numDriverCores(master, conf))
			}

		SparkEnv createDriverEnv,创建驱动环境 会调用create方法创建SparkEnv.SparkEnv构造方法
			创建安全管理器SecurityManager
			创建基于Akka的分布式消息系统ActorSystem
			创建Map任务输出跟踪器 MapOutputtracker
			实例化ShuffleManager
			创建SuffleMemoryManager
			创建块传输服务BlockTransferService
			创建BlockManagerMaster,块管理器老大
			创建块管理器 BlockManager
			创建广播管理器BroadcastManager
			创建缓存管理器 CacheManager
			创建HTTP文件服务器HttpFileServer
			创建测量系统MetricsSystem
			创建SparkEnv

		2.2.1 安全管理器
			负责权限管理,账号设置.代码和书不同了

				private var secretKey: String = _
				logInfo("SecurityManager: authentication " + (if (authOn) "enabled" else "disabled") +
				  "; ui acls " + (if (aclsOn) "enabled" else "disabled") +
				  "; users  with view permissions: " + viewAcls.toString() +
				  "; groups with view permissions: " + viewAclsGroups.toString() +
				  "; users  with modify permissions: " + modifyAcls.toString() +
				  "; groups with modify permissions: " + modifyAclsGroups.toString())

				// Set our own authenticator to properly negotiate user/password for HTTP connections.
				// This is needed by the HTTP client fetching from the HttpServer. Put here so its
				// only set once.
				/*
				用http连接设置口令认证
				如果 autoon 为true
					authenticcator设置默认值(新的认证器,包含了一个 get密码认证的方法,来获取passAuth,也就是获取密码)
				*/
				if (authOn) {
				  Authenticator.setDefault(
				    new Authenticator() {
				      override def getPasswordAuthentication(): PasswordAuthentication = {
				        var passAuth: PasswordAuthentication = null
				        val userInfo = getRequestingURL().getUserInfo()
				        if (userInfo != null) {
				          val  parts = userInfo.split(":", 2)
				          passAuth = new PasswordAuthentication(parts(0), parts(1).toCharArray())
				        }
				        return passAuth
				      }
				    }
				  )
				}

		2.2.2 基于Akka的分布式消息系统ActorSystem
			ActorSystem 是最基础设施.spark用它发送分布式消息,实现并发编程
			SparkEnv 创建基于Akka的分布式消息系统ActorSystem 用到了 AkkaUtils工具类
			但是Akka在最新的spark里面似乎被移除了.ActorSystem在后面版本会被RpcEnv替换掉 //https://blog.csdn.net/luyllyl/article/details/80406842
			SparkEnv类中, //https://blog.csdn.net/dabokele/article/details/85706073
			class SparkEnv (
		    	val executorId: String,
				private[spark] val rpcEnv: RpcEnv

			1、调用栈分析
			(1)Driver端
			Driver端创建SparkEnv对象是在SparkContext中进行的,调用栈如下:SparkContext#createSparkEnv ----> SparkEnv.createDriverEnv --------> SparkEnv.create
			(2)Executor端
			Executor端创建SparkEnv对象的过程是,CoarseGrainedExecutorBackend#run ----> SparkEnv.createExecutorEnv --------> SparkEnv.create

			2,RpcEnv 分析
			Spark中Driver端和Executor端通信主要通过RpcEnv来实现。两端的RpcEnv对象创建过程在SparkEnv#create方法中已经看到过了。
			有关Rpc的代码在org.apache.spark.rpc包中,其中还有一个名为netty的子package
			总共涉及以下三种类
			环境相关,主要包括RpcEnv, NettyRpcEnv,RpcEnvConfig,NettyRpcEnvFactory,
			Server相关,主要是RpcEndpoint,ThreadSafeRpcEndpoint,
			Client相关,代表RpcEndpoint的引用,比如RpcEndpointRef,NettyRpcEndpointRef

				1、RpcEnv生成调用栈
				生成RpcEnv对象的基本调用过程如下所示,最终是通过NettyRpcEnvFactory#create方法得到了一个NettyRpcEnv对象,NettyRpcEnv继承自RpcEnv类。
				SparkEnv#create ----> RpcEnv#create --------> NettyRpcEnvFactory#create

				RpcEnv#create 在RpcEnv中有两个create方法,该方法的实现以及在SparkEnv中的调用方式
				/**
				* systemName: sparkDeiver/sparkExecutor
				* bindAddress: Driver端IP地址,或者Executor端的IP地址
				* advertiseAddress: Driver端IP地址,或者Executor端的IP地址
				* port: Executor端为空,Driver端启动时的端口号
				*/
				val rpcEnv = RpcEnv.create(systemName, bindAddress, advertiseAddress, port, conf, securityManager, clientMode = !isDriver)

				// 定义
				def create(
				  name: String,
				  host: String,
				  port: Int,
				  conf: SparkConf,
				  securityManager: SecurityManager,
				  clientMode: Boolean = false): RpcEnv = {
				create(name, host, host, port, conf, securityManager, 0, clientMode)
				}

				def create(
				  name: String,
				  bindAddress: String,
				  advertiseAddress: String,
				  port: Int,
				  conf: SparkConf,
				  securityManager: SecurityManager,
				  numUsableCores: Int,
				  clientMode: Boolean): RpcEnv = {
				val config = RpcEnvConfig(conf, name, bindAddress, advertiseAddress, port, securityManager,
				  numUsableCores, clientMode)
				new NettyRpcEnvFactory().create(config)
				}

		2.2.3 map任务输出跟踪器 MapOutputtracker
			跟踪map阶段任务输出状态;方便reduce阶段获取地址和中间输出结果
			每个map和reduce任务都有id;reduce会从不同map任务节点拉block数据,叫洗牌(shuffle);shuffle有id
			MapOutputTracker 内部用 mapstatuses:Map[Int, Array[MapStatus]], 调用栈:MapOutputTracker->MapOutputTrackerWorker类-> value mapStatuses
			val mapStatuses: Map[Int, Array[MapStatus]] = new ConcurrentHashMap[Int, Array[MapStatus]]().asScala
			MapOutputTrackerWorker类内部有getstatuses方法获取mapstatuses->Array[MapStatus]

			其中key对应shuffleid,array对应map任务的mapstatus

			Driver端处理 MapOutputTracker 和Executor 处理方式不同
			根据SparkEnv.scala脚本中
				val mapOutputTracker = if (isDriver) {
				  new MapOutputTrackerMaster(conf, broadcastManager, isLocal)
				} else {
				  new MapOutputTrackerWorker(conf)
				}
			//https://www.jianshu.com/p/3c6b4209a5f3?utm_campaign=maleskine&utm_content=note&utm_medium=seo_notes&utm_source=recommendation
			可以看出来,if 是Driver, MapOutputTrackerMaster(conf, broadcastManager, isLocal), 调用 MapOutputTracker的 MapOutputTrackerMaster类
			然后利用Rpc注册到RpcEnv中
			if 是executor, MapOutputTrackerWorker

			无论是driver或者executor,都由 registerOrLookupEndpoint 利用RpcEndpoint 来 传递信息
				def registerOrLookupEndpoint(
				    name: String, endpointCreator: => RpcEndpoint):
				  RpcEndpointRef = {
				  if (isDriver) {
				    logInfo("Registering " + name)
				    rpcEnv.setupEndpoint(name, endpointCreator)
				  } else {
				    RpcUtils.makeDriverRef(name, conf, rpcEnv)
				  }
				}

		2.2.4 实例化 ShuffleManager
			ShuffleManager 负责管理本地和远程的block数据的shuffle操作. 默认为 通过反射方式 生成 sortshufflemanager 实例 <- 通过持有的 indexshuffleblockmanager
			间接操作 blockManager 中的 DiskBlockManager 将map写入本地 根据shuffleid mapid 写入索引id

			DiskBlockManager -> BlockManager -> Indexshuffleblockmanager -> Sortshufflemanager -> ShuffleManager 层层递进
			DiskBlockManager 负责将map结果写入本地,根据shuffleid, mapid 写入索引id 或者从 mapStatuses 从本地或者其他远程节点读取文件
			最终ShuffleManager要化身读文件机器
				// Let the user specify short names for shuffle managers
			    val shortShuffleMgrNames = Map(
				  "sort" -> classOf[org.apache.spark.shuffle.sort.SortShuffleManager].getName,
				  "tungsten-sort" -> classOf[org.apache.spark.shuffle.sort.SortShuffleManager].getName)
		        val shuffleMgrName = conf.get("spark.shuffle.manager", "sort")
				val shuffleMgrClass = shortShuffleMgrNames.getOrElse(shuffleMgrName.toLowerCase(Locale.ROOT), shuffleMgrName)
				val shuffleManager = instantiateClass[ShuffleManager](shuffleMgrClass)
			而
			    private[spark] class SortShuffleManager(conf: SparkConf) extends ShuffleManager with Logging
			    		...
			    		private[this] val numMapsForShuffle = new ConcurrentHashMap[Int, Int]()
  						override val shuffleBlockResolver = new IndexShuffleBlockResolver(conf)
  			至于IndexShuffleBlockResolver
  				private[spark] class IndexShuffleBlockResolver(conf: SparkConf,_blockManager: BlockManager = null)extends ShuffleBlockResolver with Logging

  		2.2.5 ShuffleMemoryManager
  			线程内存管理器
  				管理Shuffle线程占有内存的分配和释放.通过threadMemory缓存每个线程的内存字节数

  			可能被 memoryManager取代了
				val useLegacyMemoryManager = conf.getBoolean("spark.memory.useLegacyMode", false)
				val memoryManager: MemoryManager =
				  if (useLegacyMemoryManager) {
				    new StaticMemoryManager(conf, numUsableCores)
				  } else {
				    UnifiedMemoryManager(conf, numUsableCores)
				  }

			根据useablecores,可用的cpu核数,来确定内存
			如果 useLegacyMemoryManager:
				StaticMemoryManager,静态内存管理器
					StaticMemoryManager(conf: SparkConf,maxOnHeapExecutionMemory: Long,override val maxOnHeapStorageMemory: Long,numCores: Int)
					参数分别为:
					StaticMemoryManager(conf,StaticMemoryManager.getMaxExecutionMemory(conf),StaticMemoryManager.getMaxStorageMemory(conf),numCores)
			如果不是 useLegacyMemoryManager:
				UnifiedMemoryManager,统一内存管理器
					memory.UnifiedMemoryManager
			
			spark.memory.StaticMemoryManager中,getMaxExecutionMemory,Java运行时最大内存*Spark的shuflle最大内存占比*Spark的安全内存占比

		2.2.6 块传输服务 BlockTransferService
		    BlockTransferService默认利用NettyBlockTransferService;以前可以配置属性使用 NioBlockTransferService;现在似乎不可以了
			val blockTransferService =
			  new NettyBlockTransferService(conf, securityManager, bindAddress, advertiseAddress,
			    blockManagerPort, numUsableCores)
			NettyBlockTransferService 具体实现后续再说

	  	2.2.7 BlockManagerMaster 介绍
	  		BlockManagerMaster, 负责管理协调Block;具体操作,老版本依赖于Actor系统,现在是不是RpcEnv?
				val blockManagerMaster = new BlockManagerMaster(registerOrLookupEndpoint(
					BlockManagerMaster.DRIVER_ENDPOINT_NAME,
					new BlockManagerMasterEndpoint(rpcEnv, isLocal, conf, listenerBus)),
					conf, isDriver)
			可见,依赖于BlockManagerMasterEndpoint, 参数为rpcEnv,作为分布式消息系统传输消息
			Driver 和Executor应对方式不同

		2.2.8 创建块管理器 BlockManager
			BlockManager 负责管理Block;只有在 BlockManager 初始化之后,才有效
			// NB: blockManager is not valid until initialize() is called later.
			val blockManager = new BlockManager(executorId, rpcEnv, blockManagerMaster,
			  serializerManager, conf, memoryManager, mapOutputTracker, shuffleManager,
			  blockTransferService, securityManager, numUsableCores)
			参数有,executorId,执行器的id;rpcenv,分布式消息传输系统;blockmanagermaster,块管理器master
			serializerManager,conf等参数.非常长
			需要参考 storage.BlockManager

		2.2.9 创建广播管理器 BroadcastManager
			将配置信息和序列化后的RDD,Job,ShuffleDependency等信息,在本地存储.如果考虑容灾,也会复制到其他节点
			首先initial,然后BroadcastManager生效.
				initialize()

				// Called by SparkContext or Executor before using Broadcast
				private def initialize() {
				synchronized {
				  if (!initialized) {
				    broadcastFactory = new TorrentBroadcastFactory
				    broadcastFactory.initialize(isDriver, conf, securityManager)
				    initialized = true
				  }
				}
				}
			首先initialize
			initialize方法,利用broadcastFactory方法,而broadcastFactor实际上代理了TorrentBroadcastFactory;
				//获取下一个广播id
				private val nextBroadcastId = new AtomicLong(0)
				//初始化缓存值
				private[broadcast] val cachedValues = {
				new ReferenceMap(AbstractReferenceMap.HARD, AbstractReferenceMap.WEAK)
				}
				//初始化新的广播
				def newBroadcast[T: ClassTag](value_ : T, isLocal: Boolean): Broadcast[T] = {
				broadcastFactory.newBroadcast[T](value_, isLocal, nextBroadcastId.getAndIncrement())
				}

		2.2.20 创建缓存管理器 CacheManager
			缓存RDD某个分区计算后的中间结果
			val cacheManager: CacheManager = new CacheManager
			在spark.sql.internal.SharedState中,是分享状态

		2.2.11 HTTP文件服务器 HTTPFileServer
			spark运行时executor可能需要远程下载driver上的jar或文件到本地,对应的内部实现为:
			父类:RpcEnvFileServer
			子类:NettyStreamManager、HttpBasedFileServer,底层分别由netty、jetty实现
			根据参数spark.rpc.useNettyFileServer配置
			看来放弃了 HTTP文件服务器 HTTPFileServer
			根据原来的脚本推测,新版本大概采用了一下方式
				// Add a reference to tmp dir created by driver, we will delete this tmp dir when stop() is
				// called, and we only need to do it for driver. Because driver may run as a service, and if we
				// don't delete this tmp dir when sc is stopped, then will create too many tmp dirs.
				if (isDriver) {
				  val sparkFilesDir = Utils.createTempDir(Utils.getLocalDir(conf), "userFiles").getAbsolutePath
				  envInstance.driverTmpDir = Some(sparkFilesDir)
				}

		2.2.12 创建测量系统 MetricsSystem
			Spark的测量系统.
				val metricsSystem = if (isDriver) {
				  // Don't start metrics system right now for Driver.
				  // We need to wait for the task scheduler to give us an app ID.
				  // Then we can start the metrics system.
				  //如果是driver,不用metrics系统;需要等task 调度器放弃一个appid;然后开启metrics系统
				  MetricsSystem.createMetricsSystem("driver", conf, securityManager)
				} else {
				  // We need to set the executor ID before the MetricsSystem is created because sources and
				  // sinks specified in the metrics configuration file will want to incorporate this executor's
				  // ID into the metrics they report.
				  //在metrics系统被创建之前设置执行器id.需要报道
				  conf.set("spark.executor.id", executorId)
				  val ms = MetricsSystem.createMetricsSystem("executor", conf, securityManager)
				  ms.start()
				  ms
				}

			调用的 createMetricsSystem创建了MetricsSystem
				def createMetricsSystem(
				  instance: String, conf: SparkConf, securityMgr: SecurityManager): MetricsSystem = {
				new MetricsSystem(instance, conf, securityMgr)
				}

			构造MetricsSystem 过程最重要的是调用了 metricsConfig.initialize()方法
				private[spark] class MetricsSystem private (
				    val instance: String,
				    conf: SparkConf,
				    securityMgr: SecurityManager)
				  extends Logging {
				...
				  metricsConfig.initialize()
				...}

			这里 initialize
				def initialize() {
				// Add default properties in case there's no properties file
				// 如果没有属性,增加默认属性
				setDefaultProperties(properties)

				//加载属性文件
				loadPropertiesFromFile(conf.getOption("spark.metrics.conf"))

				// Also look for the properties in provided Spark configuration
				//在spark配置种找属性
				val prefix = "spark.metrics.conf."
				conf.getAll.foreach {
				  case (k, v) if k.startsWith(prefix) =>
				    properties.setProperty(k.substring(prefix.length()), v)
				  case _ =>
				}

				// Now, let's populate a list of sub-properties per instance, instance being the prefix that
				// appears before the first dot in the property name.
				// Add to the sub-properties per instance, the default properties (those with prefix "*"), if
				// they don't have that exact same sub-property already defined.
				//
				// For example, if properties has ("*.class"->"default_class", "*.path"->"default_path,
				// "driver.path"->"driver_path"), for driver specific sub-properties, we'd like the output to be
				// ("driver"->Map("path"->"driver_path", "class"->"default_class")
				// Note how class got added to based on the default property, but path remained the same
				// since "driver.path" already existed and took precedence over "*.path"
				//
				perInstanceSubProperties = subProperties(properties, INSTANCE_REGEX)
				if (perInstanceSubProperties.contains(DEFAULT_PREFIX)) {
				  val defaultSubProperties = perInstanceSubProperties(DEFAULT_PREFIX).asScala
				  for ((instance, prop) <- perInstanceSubProperties if (instance != DEFAULT_PREFIX);
				       (k, v) <- defaultSubProperties if (prop.get(k) == null)) {
				    prop.put(k, v)
				  }
				}
				}
			属性初始化

		2.2.13 SparkEnv初始化
			val envInstance = new SparkEnv(
			  executorId,
			  rpcEnv,
			  serializer,
			  closureSerializer,
			  serializerManager,
			  mapOutputTracker,
			  shuffleManager,
			  broadcastManager,
			  blockManager,
			  securityManager,
			  metricsSystem,
			  memoryManager,
			  outputCommitCoordinator,
			  conf)
			可以看到,cacheManager->serializerManager,blockTransferService删掉了,httpFileServer删掉了,SparkFileDir删掉了,
					shufflememorymanager->memorymanager,多了outputcommitcoordinator


	2.3 创建metadataCleaner
		这部分,原来的 metadataCleaner 似乎被取消了,换成了 spark.ContextCleaner
		//https://www.cnblogs.com/windliu/p/10983334.html

	2.4 SparkUI
		SparkUI 提供监控,浏览器访问

		事件监听体制
			if 用函数调用,那么函数调用越来越多,线程限制,监控数据更新不及时甚至无法监视
			函数监视是同步调用,线程容易阻塞;分布式环境种可能因为网络问题导致线程长时间背调用.
		发送事件体制,事件处理是异步的,当前线程可以继续执行后续逻辑;系统并发度大大增加;
		发送的事件,存入缓存,定时点读取取出后,分配给监听此事件的监听器,更新数据


		首先看 组件 DagScheduer 产生各类 SparkListenerEvent 的源头;将各类 SparkListenerEvent 发送到 ListenBus 事件队列中
		ListenBus 通过定时器将 SparkListenerEvent 事件匹配到具体的 SparkListener 中,改变 SparkListener 统计监控数据 由SparkUI展示

		2.4.1 listenerBus 详解
			参考 https://masterwangzx.com/2020/07/22/listener-bus/#listenerbus
			listenerBus 类型是 LiveListenerBus;它实现了监听器模型;通过监听事件触发对各种监听器监听状态的修改,在UI刷新
			SparkEnv -> createDriverEnv.create 方法 -> create 方法

				val isDriver = executorId == SparkContext.DRIVER_IDENTIFIER

				// Listener bus is only used on the driver
				if (isDriver) {
				  assert(listenerBus != null, "Attempted to create driver SparkEnv with null listener bus!")
				}

			SparkContext -> createSparkEnv -> 
				SparkEnv = {SparkEnv.createDriverEnv(conf, isLocal, listenerBus, SparkContext.numDriverCores(master, conf))

			LiveListenerBus 由以下部分组成:
				事件阻塞队列: LinkedBlockingQueue[SparkListenerEvent]
				监听器数组:ArrayBuffer[SparkListener],存放各类监听器SparkListener
				事件匹配监听器的线程:此Thread不断拉取LinkedBlockingQueue中的事件,遍历监听器,调用监听器方法
					任何事件都在 LinkedBlockingQueue 存在一段时间,Thread处理后,清除之
				ListenerBus,监听,到站就下车


			书里面的,LiveListenerBus的事件处理实现,spark2.4.7中,
				private[spark] class LiveListenerBus(conf: SparkConf) {
				  ...
				  private val queues = new CopyOnWriteArrayList[AsyncEventQueue]()
				  // Visible for testing.
				  val@volatile private[scheduler] var queuedEvents = new mutable.ListBuffer[SparkListenerEvent]()
				  ...

			在spark.scheduler.AsyncEventQueue中
			AsyncEventQueue继承自SparkListenerBus是事件的异步队列,事件的分发都将分配独立的线程,防止在监听器和事件较多的情况下,同步调用造成事件积压的情况

		2.4.2 构造 JobProgressListener
			JobProgressListener, 通过监听ListenerBus中的事件更新任务进度. SparkStatusTracker,和SparkUI,实际上也是通过 JobProgressListener, 来实现任务状态跟踪的
			但是这一套似乎已经取消了;或者换成其他部分了

		2.4.3 SparkUI的创建和初始化
			private[spark] def ui: Option[SparkUI] = _ui
			_ui =
			  if (conf.getBoolean("spark.ui.enabled", true)) {
			    Some(SparkUI.create(Some(this), _statusStore, _conf, _env.securityManager, appName, "",
			      startTime))
			  } else {
			    // For tests, do not enable the UI
			    None
			  }
		可以通过 spark.ui.enabled 对sparkui进行调节
		create方法,SparkUI.create
			def create(
				sc: Option[SparkContext],
				store: AppStatusStore,
				conf: SparkConf,
				securityManager: SecurityManager,
				appName: String,
				basePath: String,
				startTime: Long,
				appSparkVersion: String = org.apache.spark.SPARK_VERSION): SparkUI = {

				new SparkUI(store, sc, conf, securityManager, appName, basePath, startTime, appSparkVersion)
				}

		这里的create方法,比老版本方法,简化了;减少了外来参数

		2.4.4 SparkUI 的页面布局和展示
			JobsTab为案例
			JobsTab 复用SparkUI的killEnabled,SparkContext,JobProgressListener,包括 AllJobsPage,JobPage两个页面

			首先,是initialize中调用jobsTab
				def initialize(): Unit = {
				    val jobsTab = new JobsTab(this, store)
				    attachTab(jobsTab)
				    val stagesTab = new StagesTab(this, store)
				    attachTab(stagesTab)
				    attachTab(new StorageTab(this, store))
				    attachTab(new EnvironmentTab(this, store))
				    attachTab(new ExecutorsTab(this))
				    addStaticHandler(SparkUI.STATIC_RESOURCE_DIR)
				    attachHandler(createRedirectHandler("/", "/jobs/", basePath = basePath))
				    attachHandler(ApiRootResource.getServletHandler(this))

				    // These should be POST only, but, the YARN AM proxy won't proxy POSTs
				    attachHandler(createRedirectHandler(
				      "/jobs/job/kill", "/jobs/", jobsTab.handleKillRequest, httpMethods = Set("GET", "POST")))
				    attachHandler(createRedirectHandler(
				      "/stages/stage/kill", "/stages/", stagesTab.handleKillRequest,
				      httpMethods = Set("GET", "POST")))
				  }
			然后,JobsTab的实现 spark.ui.jobs.jobsTab
				private[ui] class JobsTab(parent: SparkUI, store: AppStatusStore)
				  extends SparkUITab(parent, "jobs") {

				  val sc = parent.sc
				  val killEnabled = parent.killEnabled

				  // Show pool information for only live UI.
				  def isFairScheduler: Boolean = {
				    sc.isDefined &&
				    store
				      .environmentInfo()
				      .sparkProperties
				      .contains(("spark.scheduler.mode", SchedulingMode.FAIR.toString))
				  }

				  def getSparkUser: String = parent.getSparkUser

				  attachPage(new AllJobsPage(this, store))
				  attachPage(new JobPage(this, store))
				  ...}

			AllJobsPage由render方法渲染,利用统计监控数据生成激活完成失败等状态
				spark.ui.jobs.AllJobsPage
			该类,实现了JOBS_LEGEND,EXECUTORS_LEGEND等参数;makeJobEvent方法;makeExecutorEvent方法;makeTimeline方法
				jobsTable方法等,通过render渲染生成.值得注意的是,这里面涉及html脚本;可见,通过脚本里面的参数来渲染.

				def render(request: HttpServletRequest): Seq[Node] = {
				  val appInfo = store.applicationInfo()
				  val startTime = appInfo.attempts.head.startTime.getTime()
				  val endTime = appInfo.attempts.head.endTime.getTime()
				  val activeJobs = new ListBuffer[v1.JobData]()
				  val completedJobs = new ListBuffer[v1.JobData]()
				  val failedJobs = new ListBuffer[v1.JobData]()
				  ...
				  val activeJobsTable =
				    jobsTable(request, "active", "activeJob", activeJobs, killEnabled = parent.killEnabled)
				  val completedJobsTable =
				    jobsTable(request, "completed", "completedJob", completedJobs, killEnabled = false)
				  val failedJobsTable =
				    jobsTable(request, "failed", "failedJob", failedJobs, killEnabled = false)
				  val shouldShowActiveJobs = activeJobs.nonEmpty
				  val shouldShowCompletedJobs = completedJobs.nonEmpty
				  ...
				  val schedulingMode = store.environmentInfo().sparkProperties.toMap
				    .get("spark.scheduler.mode")
				    .map { mode => SchedulingMode.withName(mode).toString }
				    .getOrElse("Unknown")
				  val summary: NodeSeq =
				    <div>
				      <ul class="unstyled">
				        <li>
				          <strong>User:</strong>
				          {parent.getSparkUser}
				        </li>
				        <li>
				          <strong>Total Uptime:</strong>
				          {
				            if (endTime < 0 && parent.sc.isDefined) {
				              UIUtils.formatDuration(System.currentTimeMillis() - startTime)
				            } else if (endTime > 0) {
				              UIUtils.formatDuration(endTime - startTime)
				            }
				          }
				        </li>
				        ...
				        {
				          if (shouldShowActiveJobs) {
				            <li>
				              <a href="#active"><strong>Active Jobs:</strong></a>
				              {activeJobs.size}
				            </li>
				          }
				        }
				        ...
				        {
				          if (shouldShowFailedJobs) {
				            <li>
				              <a href="#failed"><strong>Failed Jobs:</strong></a>
				              {failedJobs.size}
				            </li>
				          }
				        }
				      </ul>
				    </div>
				  var content = summary
				  content ++= makeTimeline(activeJobs ++ completedJobs ++ failedJobs,
				    store.executorList(false), startTime)
				  ...
				  if (shouldShowCompletedJobs) {
				    content ++=
				      <span id="completed" class="collapse-aggregated-completedJobs collapse-table"
				          onClick="collapseTable('collapse-aggregated-completedJobs','aggregated-completedJobs')">
				        <h4>
				          <span class="collapse-table-arrow arrow-open"></span>
				          <a>Completed Jobs ({completedJobNumStr})</a>
				        </h4>
				      </span> ++
				      <div class="aggregated-completedJobs collapsible-table">
				        {completedJobsTable}
				      </div>
				  }
				  ...
				  val helpText = """A job is triggered by an action, like count() or saveAsTextFile().""" +
				    " Click on a job to see information about the stages of tasks inside it."
				  UIUtils.headerSparkPage(request, "Spark Jobs", content, parent, helpText = Some(helpText))
				}
			大致的思路是,形成参数,嵌入html脚本

			JobsTable也一样,用来生成表格数据;在 spark.ui.jobs.AllJobsPage.jobsTable
				private def jobsTable(
				    request: HttpServletRequest,
				    tableHeaderId: String,
				    jobTag: String,
				    jobs: Seq[v1.JobData],
				    killEnabled: Boolean): Seq[Node] = {
				  // stripXSS is called to remove suspicious characters used in XSS attacks
				  val allParameters = request.getParameterMap.asScala.toMap.map { case (k, v) =>
				    UIUtils.stripXSS(k) -> v.map(UIUtils.stripXSS).toSeq
				  }
				  ...
				  val parameterJobPage = UIUtils.stripXSS(request.getParameter(jobTag + ".page"))
				  ...
				  val jobSortColumn = Option(parameterJobSortColumn).map { sortColumn =>
				    UIUtils.decodeURLParameter(sortColumn)
				  }.getOrElse(jobIdTitle)
				  ...
				  val page: Int = {
				    // If the user has changed to a larger page size, then go to page 1 in order to avoid
				    // IndexOutOfBoundsException.
				    if (jobPageSize <= jobPrevPageSize) {
				      jobPage
				    } else {
				      1
				    }
				  }
				  val currentTime = System.currentTimeMillis()
				  try {
				    new JobPagedTable(
				      store,
				      jobs,
				      tableHeaderId,
				      jobTag,
				      UIUtils.prependBaseUri(request, parent.basePath),
				      "jobs", // subPath
				      parameterOtherTable,
				      killEnabled,
				      currentTime,
				      jobIdTitle,
				      pageSize = jobPageSize,
				      sortColumn = jobSortColumn,
				      desc = jobSortDesc
				    ).table(page)
				  } catch {
				    case e @ (_ : IllegalArgumentException | _ : IndexOutOfBoundsException) =>
				      <div class="alert alert-error">
				        <p>Error while rendering job table:</p>
				        <pre>
				          {Utils.exceptionString(e)}
				        </pre>
				      </div>
				  }
				}
			同样,获取参数,然后嵌套进html脚本

			老版本中表格中的数据,通过makeRow方法渲染的;2.4.7中,通过jobRow方法渲染
				// Convert JobUIData to JobTableRowData which contains the final contents to show in the table
				// so that we can avoid creating duplicate contents during sorting the data
				private val data = jobs.map(jobRow).sorted(ordering(sortColumn, desc))
				private var _slicedJobIds: Set[Int] = null
				override def dataSize: Int = data.size
				override def sliceData(from: Int, to: Int): Seq[JobTableRowData] = {
				  val r = data.slice(from, to)
				  _slicedJobIds = r.map(_.jobData.jobId).toSet
				  r
				}
				private def jobRow(jobData: v1.JobData): JobTableRowData = {
				  val duration: Option[Long] = {
				    jobData.submissionTime.map { start =>
				      val end = jobData.completionTime.map(_.getTime()).getOrElse(System.currentTimeMillis())
				      end - start.getTime()
				    }
				  }
				  val formattedDuration = duration.map(d => UIUtils.formatDuration(d)).getOrElse("Unknown")
				  val submissionTime = jobData.submissionTime
				  val formattedSubmissionTime = submissionTime.map(UIUtils.formatDate).getOrElse("Unknown")
				  val (lastStageName, lastStageDescription) = lastStageNameAndDescription(store, jobData)
				  val jobDescription = UIUtils.makeDescription(lastStageDescription, basePath, plainText = false)
				  val detailUrl = "%s/jobs/job/?id=%s".format(basePath, jobData.jobId)
				  new JobTableRowData(
				    jobData,
				    lastStageName,
				    lastStageDescription,
				    duration.getOrElse(-1),
				    formattedDuration,
				    submissionTime.map(_.getTime()).getOrElse(-1L),
				    formattedSubmissionTime,
				    jobDescription,
				    detailUrl
				  )
				}

			上面spark.ui.jobs.jobsTab最后的 attachPage, 是spark.ui.WebUI.WebUITab 的attachPage方法
			WebUI 是SparkUI的父类
			WebUITab类维护   val pages = ArrayBuffer[WebUIPage]()
							val name = prefix.capitalize
			AllJobsPage 和 JobPage 会放入ArrayBuffer中
				private[spark] abstract class WebUITab(parent: WebUI, val prefix: String) {
				  val pages = ArrayBuffer[WebUIPage]()
				  val name = prefix.capitalize

				  /** Attach a page to this tab. This prepends the page's prefix with the tab's own prefix. */
				  def attachPage(page: WebUIPage) {
				    page.prefix = (prefix + "/" + page.prefix).stripSuffix("/")
				    pages += page
				  }

				  /** Get a list of header tabs from the parent UI. */
				  def headerTabs: Seq[WebUITab] = parent.getTabs

				  def basePath: String = parent.getBasePath
				}

				JobsTab创建以后, 通过 SparkUI.initialize 的 jobsTab = new JobsTab(this, store)
														   attachTab(jobsTab)
				被 attachTab 方法加入SparkUI的 ArrayBuffer[WebUIPage] 中,
				attachTab 在 WebUI中, 
					def attachTab(tab: WebUITab): Unit = {
						tab.pages.foreach(attachPage)
						tabs += tab
					}
				可见, attachTab 会调用 WebUI.attachPage 方法; 从而调用attachHandler方法->JettyUtils的createServletHandler一步步到底层

		2.4.5 SparkUI 启动
			SparkUI 创建好后,通过WebUI的bind方法,绑定服务和端口;bind方法实现如下
				def bind(): Unit = {
				  assert(serverInfo.isEmpty, s"Attempted to bind $className more than once!")
				  try {
				    val host = Option(conf.getenv("SPARK_LOCAL_IP")).getOrElse("0.0.0.0")
				    serverInfo = Some(startJettyServer(host, port, sslOptions, handlers, conf, name))
				    logInfo(s"Bound $className to $host, and started at $webUrl")
				  } catch {
				    case e: Exception =>
				      logError(s"Failed to bind $className", e)
				      System.exit(1)
				  }
				}
			通过调用 JettyUtils.startJettyServer,启动服务
			//关于 Jetty: https://blog.csdn.net/zhangxuyan123/article/details/81219404

	2.5 Hadoop 相关配置和Executor环境变量
		2.5.1 Hadoop相关配置信息
			SparkContext 中, Hadoop相关配置代码如下
		    	_hadoopConfiguration = SparkHadoopUtil.get.newConfiguration(_conf)
		    获取的配置信息有
		2.5.2 Executor环境变量
			executorENvs包含的环境变量,将会在注册应用过程中发送给Master;Master给worker发送调度,worker最终使用executorenvs提供的信息启动Executor
			spark.executor.memory 是executor占用的内存大小;可以配置
				_executorMemory = _conf.getOption("spark.executor.memory")
				.orElse(Option(System.getenv("SPARK_EXECUTOR_MEMORY")))
				.orElse(Option(System.getenv("SPARK_MEM"))
				.map(warnSparkMem))
				.map(Utils.memoryStringToMb)
				.getOrElse(1024)

			executorEnvs 定义过程如下
			// Environment variables to pass to our executors.
  			private[spark] val executorEnvs = HashMap[String, String]()


			// Convert java options to env vars as a work around
			// since we can't set env vars directly in sbt.
			for { (envKey, propKey) <- Seq(("SPARK_TESTING", "spark.testing"))
			  value <- Option(System.getenv(envKey)).orElse(Option(System.getProperty(propKey)))} {
			  executorEnvs(envKey) = value
			}
			Option(System.getenv("SPARK_PREPEND_CLASSES")).foreach { v =>
			  executorEnvs("SPARK_PREPEND_CLASSES") = v
			}
			// The Mesos scheduler backend relies on this environment variable to set executor memory.
			// TODO: Set this only in the Mesos scheduler.
			executorEnvs("SPARK_EXECUTOR_MEMORY") = executorMemory + "m"
			executorEnvs ++= _conf.getExecutorEnv
			
			executorEnvs("SPARK_USER") = sparkUser

	2.6 创建任务调度器 TaskScheduler
		负责任务的提交,请求集群管理器对任务调度.也可以视作任务调度的客户端.
		换了代码了,不是 var(schedulerbackend, taskscheduler)了,而且多了deployMode参数
		// Create and start the scheduler
    	val (sched, ts) = SparkContext.createTaskScheduler(this, master, deployMode)
    	createTaskScheduler 会根据master的配置匹配部署模式,创建taskschedulerimpl,并且生成不同的schedulerbackend
    	local,standalone,ya和集群三种模式

    	local模式下为例
			private def createTaskScheduler(
			    sc: SparkContext,
			    master: String,
			    deployMode: String): (SchedulerBackend, TaskScheduler) = {
			  import SparkMasterRegex._
			  // When running locally, don't try to re-execute tasks on failure.
			  val MAX_LOCAL_TASK_FAILURES = 1
			  master match {
			    case "local" =>
			      val scheduler = new TaskSchedulerImpl(sc, MAX_LOCAL_TASK_FAILURES, isLocal = true)
			      val backend = new LocalSchedulerBackend(sc.getConf, scheduler, 1)
			      scheduler.initialize(backend)
			      (backend, scheduler)

		2.6.1 创建taskschedulerimpl
			1,从sparkconf中读取配置信息,例如每个任务的cpu,调度模式等
			2,创建taskresultgetter,通过线程池,对worker上的executor发送的task的执行结果进行处理
				def this(sc: SparkContext, maxTaskFailures: Int, isLocal: Boolean) = {
				    this(
				      sc,
				      maxTaskFailures,
				      TaskSchedulerImpl.maybeCreateBlacklistTracker(sc),
				      isLocal = isLocal)
				  }

				  val conf = sc.conf

				  // How often to check for speculative tasks
				  val SPECULATION_INTERVAL_MS = conf.getTimeAsMs("spark.speculation.interval", "100ms")

				...

				  // CPUs to request per task
				  val CPUS_PER_TASK = conf.getInt("spark.task.cpus", 1)

				...

				  // Listener object to pass upcalls into
				  var dagScheduler: DAGScheduler = null

				  var backend: SchedulerBackend = null

				  val mapOutputTracker = SparkEnv.get.mapOutputTracker

				  private var schedulableBuilder: SchedulableBuilder = null
				  // default scheduler is FIFO
				  private val schedulingModeConf = conf.get(SCHEDULER_MODE_PROPERTY, SchedulingMode.FIFO.toString)
				  val schedulingMode: SchedulingMode =
				    try {
				      SchedulingMode.withName(schedulingModeConf.toUpperCase(Locale.ROOT))
				    } catch {
				      case e: java.util.NoSuchElementException =>
				        throw new SparkException(s"Unrecognized $SCHEDULER_MODE_PROPERTY: $schedulingModeConf")
				    }
			调度模式,有fair和fifo两种;
			最终任务的调度,都是落实到接口schedulerbackend的具体实现上的
			看看 local模式下,schedulerbackend的实现,localbackend;
				localbackend,依赖于localactor与actorsystem进行消息通信
				val backend = new LocalSchedulerBackend(sc.getConf, scheduler, 1)
				上述代码告诉我们,LocalSchedulerBackend替代了localbackend
				后续继续深入挖


	2.7 创建和启动DAGScheduler
		DagScheduer 主要任务, 任务交给 TaskSchedulerImpl 提交之前,做准备工作
		创建Job;将DAG中的RDD划分到不通的Stage;提交stage

		SparkContext中的代码
			volatile 关键字是一种类型修饰符,用它声明的类型变量表示可以被某些编译器未知的因素更改
			@volatile private var _dagScheduler: DAGScheduler = _

			// Create and start the scheduler
		    val (sched, ts) = SparkContext.createTaskScheduler(this, master, deployMode)
		    _schedulerBackend = sched
		    _taskScheduler = ts
		    _dagScheduler = new DAGScheduler(this)
		    _heartbeatReceiver.ask[Boolean](TaskSchedulerIsSet)

		DagScheduer 维护jobID和stageID的关系,stage,activeJob,以及缓存的RDD的partitions 的位置信息
			DAGScheduler.scala脚本中

			class DAGScheduler(
			    private[scheduler] val sc: SparkContext,
			    private[scheduler] val taskScheduler: TaskScheduler,
			    listenerBus: LiveListenerBus,
			    mapOutputTracker: MapOutputTrackerMaster,
			    blockManagerMaster: BlockManagerMaster,
			    env: SparkEnv,
			    clock: Clock = new SystemClock())
			  extends Logging {

			  def this(sc: SparkContext, taskScheduler: TaskScheduler) = {
			    this(
			      sc,
			      taskScheduler,
			      sc.listenerBus,
			      sc.env.mapOutputTracker.asInstanceOf[MapOutputTrackerMaster],
			      sc.env.blockManager.master,
			      sc.env)
			  }

			  def this(sc: SparkContext) = this(sc, sc.taskScheduler)

			  private[spark] val metricsSource: DAGSchedulerSource = new DAGSchedulerSource(this)

			  private[scheduler] val nextJobId = new AtomicInteger(0)
			  private[scheduler] def numTotalJobs: Int = nextJobId.get()
			  private val nextStageId = new AtomicInteger(0)

			  private[scheduler] val jobIdToStageIds = new HashMap[Int, HashSet[Int]]
			  private[scheduler] val stageIdToStage = new HashMap[Int, Stage]

		这里面,是定义 DagScheduer时给定的参数

			// A closure serializer that we reuse.
			// This is only safe because DAGScheduler runs in a single thread.
			private val closureSerializer = SparkEnv.get.closureSerializer.newInstance()

		同时,要注意,private [scheduler] val outputCommitCoordinator = env.outputCommitCoordinator;似乎替代了书里面的
		dagSchedulerActorSupervisor,dagScheduler检测器

		DAGScheduler构造的时候,调用 initializeEventProcessActor,似乎没有了
		https://blog.csdn.net/qq_16669583/article/details/106026722


	2.8 TaskScheduler 启动
		SparkContext.scala脚本中启动
			// start TaskScheduler after taskScheduler sets DAGScheduler reference in DAGScheduler's
			// constructor
			_taskScheduler.start()
		taskScheduler的启动,实际调用了 TaskScheduler.scala的start trait;并不是书中说的backend的start方法;书里面是老代码了

		2.8.1 创建LocalActor	 Actor->EndPoint
			构建local 的 executor;注意,原书是localactor,新代码是localendpoint
			actor->endpoint
			private[spark] class LocalEndpoint(
			    override val rpcEnv: RpcEnv,
			    userClassPath: Seq[URL],
			    scheduler: TaskSchedulerImpl,
			    executorBackend: LocalSchedulerBackend,
			    private val totalCores: Int)
			  extends ThreadSafeRpcEndpoint with Logging {

			  private var freeCores = totalCores

			  val localExecutorId = SparkContext.DRIVER_IDENTIFIER
			  val localExecutorHostname = "localhost"

			  private val executor = new Executor(
			    localExecutorId, localExecutorHostname, SparkEnv.get, userClassPath, isLocal = true)

			  override def receive: PartialFunction[Any, Unit] = {
			    case ReviveOffers =>
			      reviveOffers()
			  ...

			Executor的构建 Executor.scala

				private[spark] class Executor(
				    executorId: String,
				    executorHostname: String,
				    env: SparkEnv,
				    userClassPath: Seq[URL] = Nil,
				    isLocal: Boolean = false,
				    uncaughtExceptionHandler: UncaughtExceptionHandler = SparkUncaughtExceptionHandler)
				  extends Logging {

				  logInfo(s"Starting executor ID $executorId on host $executorHostname")

				  ...
				  // No ip or host:port - just hostname
				  Utils.checkHost(executorHostname, "Expected executed slave to be a hostname")
				  ...
				  if (!isLocal) {
				    // Setup an uncaught exception handler for non-local mode.
				    // Make any thread terminations due to uncaught exceptions kill the entire
				    // executor process to avoid surprising stalls.
				    Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler)
				  }

				  // Start worker thread pool
				  private val threadPool = {
				    val threadFactory = new ThreadFactoryBuilder()
				      .setDaemon(true)
				      .setNameFormat("Executor task launch worker-%d")
				      .setThreadFactory(new ThreadFactory {
				        override def newThread(r: Runnable): Thread =
				          // Use UninterruptibleThread to run tasks so that we can allow running codes without being
				          // interrupted by `Thread.interrupt()`. Some issues, such as KAFKA-1894, HADOOP-10622,
				          // will hang forever if some methods are interrupted.
				          new UninterruptibleThread(r, "unused") // thread name will be set by ThreadFactoryBuilder
				      })
				      .build()
				    Executors.newCachedThreadPool(threadFactory).asInstanceOf[ThreadPoolExecutor]
				  }

					private val executorSource = new ExecutorSource(threadPool, executorId)
					// Pool used for threads that supervise task killing / cancellation
					private val taskReaperPool = ThreadUtils.newDaemonCachedThreadPool("Task reaper")

				前面的准备工作:
					定义当前的文件,jar包;emmpty_byte_buffer;conf
					检查host
					确保没有specified的host
					如果是local模式怎么办

					启动worker线程池

				创建并注册 executorSource; 执行器资源
				获取sparkEnv
					如果不是local的情况下
					  if (!isLocal) {
						    env.metricsSystem.registerSource(executorSource)
						    env.blockManager.initialize(conf.getAppId)
						  }
				然后,创建注册 taskreaper?
				创建 urlclassloader, 用来加载任务上传的jar包中的类,对任务的环境进行隔离

				创建executor 执行 task的线程池
				创建 executor 的心跳线程,用来给driver发送心跳
					// Executor for the heartbeat task.
					private val heartbeater = ThreadUtils.newDaemonSingleThreadScheduledExecutor("driver-heartbeater")


		2.8.2 ExecutorSource 的创建和注册
			ExecutorSource 用于测量系统; metricRegistry 的register 方法注册计量;这些计量信息包括:
				threadpool.activeTasks, threadpool.compeleteTasks, threadpool.currentPoolsize, threadpool.maxPoolsize, filesystem.hdfs.largeReadops,filesystem.hdfs.writeops
			实现方式如下
				首先是Executor.scala脚本中
				private val executorSource = new ExecutorSource(threadPool, executorId)
				定义了executorSource
				然后,是ExecutorSource类
					class ExecutorSource(threadPool: ThreadPoolExecutor, executorId: String) extends Source {

					  private def fileStats(scheme: String) : Option[FileSystem.Statistics] =
					    FileSystem.getAllStatistics.asScala.find(s => s.getScheme.equals(scheme))

					  private def registerFileSystemStat[T](
					        scheme: String, name: String, f: FileSystem.Statistics => T, defaultValue: T) = {
					    metricRegistry.register(MetricRegistry.name("filesystem", scheme, name), new Gauge[T] {
					      override def getValue: T = fileStats(scheme).map(f).getOrElse(defaultValue)
					    })
					  }

					  override val metricRegistry = new MetricRegistry()

					  override val sourceName = "executor"
					  ...

				这里调用了很多metricRegistry;

			创建完 Executorsource之后,调用metricssystem的 registersource,将executorsource注册到metricssystem中
			registersource方法,利用metricregistry的register方法注册

			MetricsSystem.scala方法中 
				def registerSource(source: Source) {
				  sources += source
				  try {
				    val regName = buildRegistryName(source)
				    registry.register(regName, source.metricRegistry)
				  } catch {
				    case e: IllegalArgumentException => logInfo("Metrics already registered", e)
				  }
				}

		2.8.3 ExecutorActor的构建和注册
			原书的 ExecutorActor的构建和注册,利用到的,receivewithlogging,在BlockManagerSlaveEndpoint中
			override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
				...
				case TriggerThreadDump =>
				context.reply(Utils.getThreadDump())
			}

		2.8.4 Spark 自身 ClassLoader 的创建
			获取要创建的 ClassLoader的父加载器currentLoader, 然后根据currentjars,生成url数组
			spark.userclasspathfirst,属性指定加载类时,是否先从用户的classpath下加载?
			最后创建executorurlclassloader或者childexecutorurlclassloader

			仍然是在 Executor.scala文件中
				private def createClassLoader(): MutableURLClassLoader = {
				  // Bootstrap the list of jars with the user class path.
				  val now = System.currentTimeMillis()
				  userClassPath.foreach { url =>
				    currentJars(url.getPath().split("/").last) = now
				  }
				  val currentLoader = Utils.getContextOrSparkClassLoader
				  // For each of the jars in the jarSet, add them to the class loader.
				  // We assume each of the files has already been fetched.
				  val urls = userClassPath.toArray ++ currentJars.keySet.map { uri =>
				    new File(uri.split("/").last).toURI.toURL
				  }
				  if (userClassPathFirst) {
				    new ChildFirstURLClassLoader(urls, currentLoader)
				  } else {
				    new MutableURLClassLoader(urls, currentLoader)
				  }
				}

			原书中,userclasspathfirst需要自己获取参数,conf.getboolean;现在是作为参数 userclasspath 传进executor类中
			然后,在前面就通过
				// Whether to load classes in user jars before those in Spark jars
				private val userClassPathFirst = conf.getBoolean("spark.executor.userClassPathFirst", false)
			获取到了该参数的具体值

			Utils.getContextOrSparkClassLoader, 通过Utils.scala中的getContextOrSparkClassLoader方法获得
			ChildFirstURLClassLoader 继承了 MutableURLClassLoader类;MutableURLClassLoader继承了URLClassLoader

			如果需要 repl 交互,海湖调用addreplclassloaderifneeded
				private def addReplClassLoaderIfNeeded(parent: ClassLoader): ClassLoader = {
				    val classUri = conf.get("spark.repl.class.uri", null)
				    if (classUri != null) {
				      logInfo("Using REPL class URI: " + classUri)
				      try {
				        val _userClassPathFirst: java.lang.Boolean = userClassPathFirst
				        val klass = Utils.classForName("org.apache.spark.repl.ExecutorClassLoader")
				          .asInstanceOf[Class[_ <: ClassLoader]]
				        val constructor = klass.getConstructor(classOf[SparkConf], classOf[SparkEnv],
				          classOf[String], classOf[ClassLoader], classOf[Boolean])
				        constructor.newInstance(conf, env, classUri, parent, _userClassPathFirst)
				      } catch {
				        case _: ClassNotFoundException =>
				          logError("Could not find org.apache.spark.repl.ExecutorClassLoader on classpath!")
				          System.exit(1)
				          null
				      }
				    } else {
				      parent
				    }
				  }

		2.8.5 启动Executor的心跳线程
			smartDriverHeartbeater启动 Executor的心跳
			Executor.scala脚本中 startdriverHeatrbeater;
				private def startDriverHeartbeater(): Unit = {
				    val intervalMs = conf.getTimeAsMs("spark.executor.heartbeatInterval", "10s")

				    // Wait a random interval so the heartbeats don't end up in sync
				    val initialDelay = intervalMs + (math.random * intervalMs).asInstanceOf[Int]

				    val heartbeatTask = new Runnable() {
				      override def run(): Unit = Utils.logUncaughtExceptions(reportHeartBeat())
				    }
				    heartbeater.scheduleAtFixedRate(heartbeatTask, initialDelay, intervalMs, TimeUnit.MILLISECONDS)
				  }
			心跳线程间隔,spark.executor.heartbeatInterval = 10s;超时时间=30s;超时重试3次;重试间隔3s
				原书代码中的大段,在新代码体现为 startdriverHeatrbeater 和 reportHeartBeat 两个函数
				心跳的作用:
					更新正在处理的任务的测量信息
					通知 blockmanagermaster, 此 executor上的blockmanager依然活着
			初始化 taskschedulerimpl后,创建心跳接收器 heartbeatreceiver
			heartbeatreceiver 接收所有分配给当前 driver application 的executor 的心跳 并将 task, task 计量信息, 心跳等
			交给 taskSchedulerimpl 和 dagScheduler 作下一步处理
				_heartbeatReceiver = env.rpcEnv.setupEndpoint(HeartbeatReceiver.ENDPOINT_NAME, new HeartbeatReceiver(this))

			heartbeatreceiver 收到心跳后, 调用 taskScheduler 的 executorheartbeatreceived 方法
			这个逻辑,在 HeartbeatReceiver.scala脚本中, receiveAndReply方法有体现; 
				// Messages received from executors
				    case heartbeat @ Heartbeat(executorId, accumUpdates, blockManagerId) =>
				      if (scheduler != null) {
				        if (executorLastSeen.contains(executorId)) {
				          executorLastSeen(executorId) = clock.getTimeMillis()
				          eventLoopThread.submit(new Runnable {
				            override def run(): Unit = Utils.tryLogNonFatalError {
				              val unknownExecutor = !scheduler.executorHeartbeatReceived(
				                executorId, accumUpdates, blockManagerId)
				              val response = HeartbeatResponse(reregisterBlockManager = unknownExecutor)
				              context.reply(response)
			而 TaskScheduler.scala脚本中有以下方法
				def executorHeartbeatReceived(
				    execId: String,
				    accumUpdates: Array[(Long, Seq[AccumulatorV2[_, _]])],
				    blockManagerId: BlockManagerId): Boolean

			executorHeartbeatReceived 逻辑体现在 TaskSchedulerImpl.scala脚本中
				override def executorHeartbeatReceived(
				      execId: String,
				      accumUpdates: Array[(Long, Seq[AccumulatorV2[_, _]])],
				      blockManagerId: BlockManagerId): Boolean = {
				    // (taskId, stageId, stageAttemptId, accumUpdates)
				    val accumUpdatesWithTaskIds: Array[(Long, Int, Int, Seq[AccumulableInfo])] = synchronized {
				      accumUpdates.flatMap { case (id, updates) =>
				        val accInfos = updates.map(acc => acc.toInfo(Some(acc.value), None))
				        taskIdToTaskSetManager.get(id).map { taskSetMgr =>
				          (id, taskSetMgr.stageId, taskSetMgr.taskSet.stageAttemptId, accInfos)
				        }
				      }
				    }

			dagScheduler 将executorID等信息封装,传到listenerbus中,用于更新stage的各种测量数据

			最后, 给 blockmanagermaster 持有的blockmanagermasteractor发送blockmanagerheartbeat消息
			blockmanagermasteractor会匹配执行 heartbeatReceived方法
				private def heartbeatReceived(blockManagerId: BlockManagerId): Boolean = {
				  if (!blockManagerInfo.contains(blockManagerId)) {
				    blockManagerId.isDriver && !isLocal
				  } else {
				    blockManagerInfo(blockManagerId).updateLastSeenMs()
				    true
				  }
				}
			然后初始化 blockmanager; executor.scala脚本中


	2.9 启动测量系统 MetricsSystem
		使用 codahale 提供的第三方测量仓库 Metrics
		MetricsSystem 有三个概念: Instance 谁在用测量数据, source 从哪收集数据, sink 往哪里输出数据;

		spark 按照instance不同,有 master, worker, application, driver, executor
		sink 有 consolesink csvsink jmxsink metricssevlet graphitesink 等
		MetricsServlet作为默认的Sink
		SparkContext的启动代码
			// The metrics system for Driver need to be set spark.app.id to app ID.
			// So it should start after we get app ID from the task scheduler and set spark.app.id.
			_env.metricsSystem.start()
			// Attach the driver metrics servlet handler to the web ui after the metrics system is started.
			_env.metricsSystem.getServletHandlers.foreach(handler => ui.foreach(_.attachHandler(handler)))

			start
				def start(registerStaticSources: Boolean = true) {
				    require(!running, "Attempting to start a MetricsSystem that is already running")
				    running = true
				    if (registerStaticSources) {
				      StaticSources.allSources.foreach(registerSource)
				      registerSources()
				    }
				    registerSinks()
				    sinks.foreach(_.start)
				  }
		启动代码如下:SparkEnv.scala脚本中
			val metricsSystem = if (isDriver) {
			      // Don't start metrics system right now for Driver.
			      // We need to wait for the task scheduler to give us an app ID.
			      // Then we can start the metrics system.
			      MetricsSystem.createMetricsSystem("driver", conf, securityManager)
			    } else {
			      // We need to set the executor ID before the MetricsSystem is created because sources and
			      // sinks specified in the metrics configuration file will want to incorporate this executor's
			      // ID into the metrics they report.
			      conf.set("spark.executor.id", executorId)
			      val ms = MetricsSystem.createMetricsSystem("executor", conf, securityManager)
			      ms.start()
			      ms
			    }

		MetricsSystem 启动过程如下:
			注册source->注册sinks->给sinks增加Jetty的ServeletContextHandler
			SparkEnv.metricsSystem -> val ms = MetricsSystem.createMetricsSystem -> MetricsSystem的objcet MetricsSystem->
			MetricsSystem.MetricsSystem.createMetricsSystem new MetricsSystem ->MetricsSystem.scala
			构建代码如下
				private[spark] class MetricsSystem private (
				    val instance: String,
				    conf: SparkConf,
				    securityMgr: SecurityManager)
				  extends Logging {

				  private[this] val metricsConfig = new MetricsConfig(conf)

				  private val sinks = new mutable.ArrayBuffer[Sink]
				  private val sources = new mutable.ArrayBuffer[Source]
				  private val registry = new MetricRegistry()

				  private var running: Boolean = false

				  // Treat MetricsServlet as a special sink as it should be exposed to add handlers to web ui
				  private var metricsServlet: Option[MetricsServlet] = None
		可见,是_env.metricsSystem.start()这个逻辑启动metricssystem
		_env.metricsSystem.getServletHandlers.foreach(handler => ui.foreach(_.attachHandler(handler)))启动了handler
		attachHandler->new ServletParams(request => getMetricsSnapshot(request), "text/json"), securityMgr, conf) -> getMetricsSnapshot

		2.9.1 注册sources
			registersources 注册 sources
			告诉测量系统从哪收集数据
				private def registerSources() {
				  val instConfig = metricsConfig.getInstance(instance)
				  val sourceConfigs = metricsConfig.subProperties(instConfig, MetricsSystem.SOURCE_REGEX)
				  // Register all the sources related to instance
				  sourceConfigs.foreach { kv =>
				    val classPath = kv._2.getProperty("class")
				    try {
				      val source = Utils.classForName(classPath).newInstance()
				      registerSource(source.asInstanceOf[Source])
				    } catch {
				      case e: Exception => logError("Source class " + classPath + " cannot be instantiated", e)
				    }
				  }
				}
			从metricsconfig中获取参数
			匹配driver的properties中以source.开头的属性
			将每个source的metricregistry注册到concurrentmap

			注册sinks
			registerSinks;类似registersources
				private def registerSinks() {
				    val instConfig = metricsConfig.getInstance(instance)
				    val sinkConfigs = metricsConfig.subProperties(instConfig, MetricsSystem.SINK_REGEX)

				    sinkConfigs.foreach { kv =>
				      val classPath = kv._2.getProperty("class")
				      if (null != classPath) {
				        try {
				          val sink = Utils.classForName(classPath)
				            .getConstructor(classOf[Properties], classOf[MetricRegistry], classOf[SecurityManager])
				            .newInstance(kv._2, registry, securityMgr)
				          if (kv._1 == "servlet") {
				            metricsServlet = Some(sink.asInstanceOf[MetricsServlet])
				          } else {
				            sinks += sink.asInstanceOf[Sink]
				          }
				        } catch {
				          case e: Exception =>
				            logError("Sink class " + classPath + " cannot be instantiated")
				            throw e
				        }
				      }
				    }
				  }
			首先获取参数,然后利用sink_regex正则表达式,获取的配置信息
			metricsServlet反射得到metricsservlet实例;如果属性的key是servlet,将其设置为 MetricsServlet;如果是sink,加入到[Sink]中

		2.9.3 给sinks增加Jetty的ServeletContextHandler
			为了在sparkui访问到测量数据,需要给sinks增加jetty的ServeletContextHandler
			MetricsSystem的 getServletHandler
				def getServletHandlers: Array[ServletContextHandler] = {
				  require(running, "Can only call getServletHandlers on a running MetricsSystem")
				  metricsServlet.map(_.getHandlers(conf)).getOrElse(Array())
				}
			调用了metricsServlet的getHandlers;实现方式如下
				def getHandlers(conf: SparkConf): Array[ServletContextHandler] = {
				  Array[ServletContextHandler](
				    createServletHandler(servletPath,
				      new ServletParams(request => getMetricsSnapshot(request), "text/json"), securityMgr, conf)
				  )
				}
			生成 ServletContextHandler

	2.10 创建和启动 ExecutorAllocationManager
		ExecutorAllocationManager 用于对已分配的executor进行管理,创建和启动executorallocationmanager
			SparkContext.scala

			private[spark] def executorAllocationManager: Option[ExecutorAllocationManager] =_executorAllocationManager
			
			val dynamicAllocationEnabled = Utils.isDynamicAllocationEnabled(_conf)
			_executorAllocationManager =
			  if (dynamicAllocationEnabled) {
			    schedulerBackend match {
			      case b: ExecutorAllocationClient =>
			        Some(new ExecutorAllocationManager(
			          schedulerBackend.asInstanceOf[ExecutorAllocationClient], listenerBus, _conf,
			          _env.blockManager.master))
			      case _ =>
			        None
			    }
			  } else {
			    None
			  }
			_executorAllocationManager.foreach(_.start())

		默认情况下,不会创建 ExecutorAllocationManager; 可以修改属性 isDynamicAllocationEnabled
		可以设置动态分配最小executor数量,动态分配最大executor数量,每个executor可以运行的task数量等信息
		start方法将 ExecutorAllocationManager 加入listenerbus中, ExecutorAllocationListener 通过监听 listenerbus 的事件, 动态添加删除executor
		通过thread不断添加 executor 遍历 executor 将超时的 executor 杀掉并移除
		ExecutorAllocationManager的实现如下
			// Polling loop interval (ms)
			private val intervalMillis: Long = if (Utils.isTesting) {
			    conf.getLong(TESTING_SCHEDULE_INTERVAL_KEY, 100)
			  } else {
			    100
			  }

		clock
			// Clock used to schedule when executors should be added and removed
			private var clock: Clock = new SystemClock()
		listener
			// Listener for Spark events that impact the allocation policy
			val listener = new ExecutorAllocationListener

		start
			def start(): Unit = {
			  listenerBus.addToManagementQueue(listener)
			  val scheduleTask = new Runnable() {
			    override def run(): Unit = {
			      try {
			        schedule()
			      } catch {
			        case ct: ControlThrowable =>
			          throw ct
			        case t: Throwable =>
			          logWarning(s"Uncaught exception in thread ${Thread.currentThread().getName}", t)
			      }
			    }
			  }
			  executor.scheduleWithFixedDelay(scheduleTask, 0, intervalMillis, TimeUnit.MILLISECONDS)
			  client.requestTotalExecutors(numExecutorsTarget, localityAwareTasks, hostToLocalTaskCount)
			}


	2.11 ContextCleaner 的创建和启动
		ContextCleaner 用于清理那些超出应用范围的 RDD, ShuffleDependency, broadcast等对象
		由于配置属性 spark.cleaner.ReferenceTracking 默认是true; 所以会构造启动 ContextCleaner
			private[spark] def cleaner: Option[ContextCleaner] = _cleaner
			_cleaner =
			  if (_conf.getBoolean("spark.cleaner.referenceTracking", true)) {
			    Some(new ContextCleaner(this))
			  } else {
			    None
			  }
			_cleaner.foreach(_.start())

		追踪到 ContextCleaner 
			private[spark] class ContextCleaner(sc: SparkContext) extends Logging {

			  /**
			   * A buffer to ensure that `CleanupTaskWeakReference`s are not garbage collected as long as they
			   * have not been handled by the reference queue.
			   */
			  private val referenceBuffer =
			    Collections.newSetFromMap[CleanupTaskWeakReference](new ConcurrentHashMap)

			  private val referenceQueue = new ReferenceQueue[AnyRef]

			  private val listeners = new ConcurrentLinkedQueue[CleanerListener]()

			  private val cleaningThread = new Thread() { override def run() { keepCleaning() }}

			  private val periodicGCService: ScheduledExecutorService =
			    ThreadUtils.newDaemonSingleThreadScheduledExecutor("context-cleaner-periodic-gc")
		referenceBuffer:缓存AnyRef的虚引用
		referenceQueue:缓存顶级的AnyRef的引用
		listeners:监听器数组
		cleaningThread:用于具体清理工作的线程

		ContextCleaner 和listenerbus一样,监听器模式
		线程来处理;调用 keepCleaning; ContextCleaner.keepCleaning
			不断调用remove,clean,来清理


	2.12 Spark环境更新
		SparkContext 初始化中 可能对环境造成影响 所以需要更新环境
			postEnvironmentUpdate()
			postApplicationStart()
				private def postApplicationStart() {
				    // Note: this code assumes that the task scheduler has been initialized and has contacted
				    // the cluster manager to get an application ID (in case the cluster manager provides one).
				    listenerBus.post(SparkListenerApplicationStart(appName, Some(applicationId),
				      startTime, sparkUser, applicationAttemptId, schedulerBackend.getDriverLogUrls))
				  }

			其中的jar包和文件的添加如下
				val addedJarPaths = addedJars.keys.toSeq
				val addedFilePaths = addedFiles.keys.toSeq
			->
				// Used to store a URL for each static file/jar together with the file's local timestamp
				private[spark] val addedFiles = new ConcurrentHashMap[String, Long]().asScala
				private[spark] val addedJars = new ConcurrentHashMap[String, Long]().asScala
			->	
				_jars = Utils.getUserJars(_conf)
				_files = _conf.getOption("spark.files").map(_.split(",")).map(_.filter(_.nonEmpty))

			addFile, addJar 都和书中描述不一样了吧
				// Add each JAR given through the constructor
				if (jars != null) {
				  jars.foreach(addJar)
				}

				if (files != null) {
				  files.foreach(addFile)
				}
			然后可以溯源
			postEnvironmentUpdate的实现
				private def postEnvironmentUpdate() {
				    if (taskScheduler != null) {
				      val schedulingMode = getSchedulingMode.toString
				      val addedJarPaths = addedJars.keys.toSeq
				      val addedFilePaths = addedFiles.keys.toSeq
				      val environmentDetails = SparkEnv.environmentDetails(conf, schedulingMode, addedJarPaths,
				        addedFilePaths)
				      val environmentUpdate = SparkListenerEnvironmentUpdate(environmentDetails)
				      listenerBus.post(environmentUpdate)
				    }
				  }
			val environmentDetails = SparkEnv.environmentDetails的实现如下:sparkENv.scala
				def environmentDetails(
				      conf: SparkConf,
				      schedulingMode: String,
				      addedJars: Seq[String],
				      addedFiles: Seq[String]): Map[String, Seq[(String, String)]] = {

				    import Properties._
				    val jvmInformation = Seq(
				      ("Java Version", s"$javaVersion ($javaVendor)"),
				      ("Java Home", javaHome),
				      ("Scala Version", versionString)
				    ).sorted

				    // Spark properties
				    // This includes the scheduling mode whether or not it is configured (used by SparkUI)
				    val schedulerMode =
				      if (!conf.contains("spark.scheduler.mode")) {
				        Seq(("spark.scheduler.mode", schedulingMode))
				      } else {
				        Seq.empty[(String, String)]
				      }
				    val sparkProperties = (conf.getAll ++ schedulerMode).sorted
			SparkContext.postApplicationStart 实现如下
				private def postApplicationStart() {
				  // Note: this code assumes that the task scheduler has been initialized and has contacted
				  // the cluster manager to get an application ID (in case the cluster manager provides one).
				  listenerBus.post(SparkListenerApplicationStart(appName, Some(applicationId),
				    startTime, sparkUser, applicationAttemptId, schedulerBackend.getDriverLogUrls))
				}
		2.13 创建和启动DAGSchedulerSource, BlockManagerSource
			创建 DAGSchedulerSource, BlockManagerSource 之前 首先调用 taskscheduler, poststarthook,为了等待backend就绪
				// Post init
				_taskScheduler.postStartHook()
			    _env.metricsSystem.registerSource(_dagScheduler.metricsSource) ->   private[spark] val metricsSource: DAGSchedulerSource = new DAGSchedulerSource(this)
				_env.metricsSystem.registerSource(new BlockManagerSource(_env.blockManager))
				_executorAllocationManager.foreach { e =>
				  _env.metricsSystem.registerSource(e.executorAllocationManagerSource)

			新版本,似乎是取消了 initialDriverMetrics,直接用registersource体现了

			TaskScheduler.scala脚本中, postStartHook的实现是空的
			TaskSchedulerImpl.scala中,才有 postStartHook 的实现
				override def postStartHook() {
				    waitBackendReady()
				  }
			然后可以层层追溯了

		2.14 将SparkContext标记为激活
			SparkContext 初始化最后,是将SparkContext状态从 contextBeingConstructed->activeContext
				  // In order to prevent multiple SparkContexts from being active at the same time, mark this
				  // context as having finished construction.
				  // NOTE: this must be placed at the end of the SparkContext constructor.
				  SparkContext.setActiveContext(this, allowMultipleContexts)
				}
				private[spark] def setActiveContext(
				      sc: SparkContext,
				      allowMultipleContexts: Boolean): Unit = {
				    SPARK_CONTEXT_CONSTRUCTOR_LOCK.synchronized {
				      assertNoOtherContextIsRunning(sc, allowMultipleContexts)
				      contextBeingConstructed = None
				      activeContext.set(sc)
				    }
				  }


		2.15 总结
			SparkContext的构建
			Driver->Conf->Context
			首先是SparkEnv,创造执行环境
			然后为了保持对所有持久化的RDD的跟踪,使用metadatacleaner;
			然后构建SparkUI界面
			要注意,Hadoop和Executor的配置和环境变量

			接着开始创建任务调度器,TaskScheduler
			创建和启动 DAGScheduler, 有向无环图的调度器
			启动 TaskScheduler
			启动测量系统 MetricsSystem

			这就七七八八了
			然后创建 ExecutorAllocationManager, 分配管理Executor
			创建ContextCleaner,清理器
			更新Spark环境,将给定的参数加进去
			创建 DAGSchedulerSource BlockManagerSource
			最后将SparkContext标记为激活就可以了
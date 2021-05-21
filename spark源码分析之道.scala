
1 Spark设计理念和基本架构

	1.1 初始Spark
		Spark vs Hadoop
			Hadoop是高吞吐,批量处理,离线计算;我部门就是跑批的形式,批量计算
			实时计算?No

			Spark通过内存计算,比磁盘快十倍以上,
			内存直接由CPU控制，也就是CPU内部集成的内存控制器，所以说内存是直接与CPU对接，享受与CPU通信的最优带宽，然而硬盘则是通过桥接芯片(在主板上)与CPU相连，所以说速度比较慢，SATA接口目前最高速度是6GB接口也就是实际传输速度550MB/s，这也是硬盘的最快速度，其次就是与CPU直连的m.2口和pcie口，这两种接口都是通过pcie通道与CPU直连，所以说速度都在1.2G/s左右，pcie接口速度随着pcie通道数量的提升而提升，2~4G/s的读写速度都是有的

			内存的读写速度随便可以上20GB/s（50GB/s）
			当然更快的还有CPU的那几级缓存，比如L1可以到400+GB/s的读取、200+GB/s的写入（3100+GB/s读、1600+GB/s写）。
			链接：https://www.zhihu.com/question/33272188

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

		Option一般有两种用法：
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
			require是不可取消的，它会在库（包括内部库）中使用，以通知调用者调用给定方法/函数的先决条件，例如，被用于限制某些参数。这样对于开发人员严非常有意义。
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
			Driver端创建SparkEnv对象是在SparkContext中进行的，调用栈如下:SparkContext#createSparkEnv ----> SparkEnv.createDriverEnv --------> SparkEnv.create
			(2)Executor端
			Executor端创建SparkEnv对象的过程是，CoarseGrainedExecutorBackend#run ----> SparkEnv.createExecutorEnv --------> SparkEnv.create

			2,RpcEnv 分析
			Spark中Driver端和Executor端通信主要通过RpcEnv来实现。两端的RpcEnv对象创建过程在SparkEnv#create方法中已经看到过了。
			有关Rpc的代码在org.apache.spark.rpc包中，其中还有一个名为netty的子package
			总共涉及以下三种类
			环境相关，主要包括RpcEnv, NettyRpcEnv,RpcEnvConfig,NettyRpcEnvFactory，
			Server相关，主要是RpcEndpoint，ThreadSafeRpcEndpoint，
			Client相关，代表RpcEndpoint的引用，比如RpcEndpointRef,NettyRpcEndpointRef

				1、RpcEnv生成调用栈
				生成RpcEnv对象的基本调用过程如下所示，最终是通过NettyRpcEnvFactory#create方法得到了一个NettyRpcEnv对象，NettyRpcEnv继承自RpcEnv类。
				SparkEnv#create ----> RpcEnv#create --------> NettyRpcEnvFactory#create

				RpcEnv#create 在RpcEnv中有两个create方法，该方法的实现以及在SparkEnv中的调用方式
				/**
				* systemName: sparkDeiver/sparkExecutor
				* bindAddress: Driver端IP地址，或者Executor端的IP地址
				* advertiseAddress: Driver端IP地址，或者Executor端的IP地址
				* port: Executor端为空，Driver端启动时的端口号
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

  			ShuffleMemoryManager 后续详谈
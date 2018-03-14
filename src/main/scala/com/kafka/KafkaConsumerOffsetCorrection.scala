package com.kafka

import java.io.IOException
import java.net.InetSocketAddress
import java.util
import java.util.{Collections, Locale, Properties, UUID}

import scala.collection.JavaConverters._

import kafka.server.{KafkaConfig, KafkaServer}
import kafka.zk.KafkaZkClient
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.{AdminClient, NewTopic, OffsetSpec, RecordsToDelete}
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.clients.producer._
import org.apache.kafka.common.{IsolationLevel, TopicPartition}
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.apache.kafka.common.utils.SystemTime
import org.apache.log4j.LogManager
import org.apache.zookeeper.server.{NIOServerCnxnFactory, ZooKeeperServer}

object KafkaConsumerOffsetCorrection {

  @transient lazy val log = LogManager.getLogger(getClass)

  private class EmbeddedZookeeper(val zkConnect: String) {
    val snapshotDir = Utils.createTempDir()
    val logDir = Utils.createTempDir()

    val zookeeper = new ZooKeeperServer(snapshotDir, logDir, 500)
    val (ip, port) = {
      val splits = zkConnect.split(":")
      (splits(0), splits(1).toInt)
    }
    val factory = new NIOServerCnxnFactory()
    factory.configure(new InetSocketAddress(ip, port), 16)
    factory.startup(zookeeper)

    val actualPort = factory.getLocalPort

    def shutdown() {
      factory.shutdown()
      // The directories are not closed even if the ZooKeeper server is shut down.
      // Please see ZOOKEEPER-1844, which is fixed in 3.4.6+. It leads to test failures
      // on Windows if the directory deletion failure throws an exception.
      try {
        Utils.deleteRecursively(snapshotDir)
      } catch {
        case e: IOException =>
          log.error(e.getMessage)
      }
      try {
        Utils.deleteRecursively(logDir)
      } catch {
        case e: IOException =>
          log.error(e.getMessage)
      }
    }
  }

  private val zkHost = "127.0.0.1"
  private var zkPort = 0
  private val zkConnectionTimeout = 60000
  private val zkSessionTimeout = 10000
  private var zookeeper: EmbeddedZookeeper = _
  private var zkClient: KafkaZkClient = _
  private var zkReady = false

  private val brokerHost = "127.0.0.1"
  private var brokerPort = 0
  private var brokerConf: KafkaConfig = _
  private var server: KafkaServer = _
  private var brokerReady = false

  private var adminClient: AdminClient = null

  def zkAddress: String = {
    assert(zkReady, "Zookeeper not setup yet or already torn down, cannot get zookeeper address")
    s"$zkHost:$zkPort"
  }

  def brokerAddress: String = {
    assert(brokerReady, "Kafka not setup yet or already torn down, cannot get broker address")
    s"$brokerHost:$brokerPort"
  }

  private def setup(): Unit = {
    // Zookeeper server startup
    zookeeper = new EmbeddedZookeeper(s"$zkHost:$zkPort")
    // Get the actual zookeeper binding port
    zkPort = zookeeper.actualPort
    zkClient = KafkaZkClient(s"$zkHost:$zkPort", isSecure = false, zkSessionTimeout,
      zkConnectionTimeout, 1, new SystemTime())
    zkReady = true

    // Kafka broker startup
    brokerConf = new KafkaConfig(brokerConfiguration, doLog = false)
    server = new KafkaServer(brokerConf)
    server.startup()
    brokerPort = server.boundPort(new ListenerName("PLAINTEXT"))
    brokerReady = true

    val props = new Properties()
    props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, s"$brokerAddress")
    adminClient = AdminClient.create(props)
  }

  protected def brokerConfiguration: Properties = {
    val props = new Properties()
    props.put("broker.id", "0")
    props.put("host.name", "127.0.0.1")
    props.put("advertised.host.name", "127.0.0.1")
    props.put("port", brokerPort.toString)
    props.put("log.dir", Utils.createTempDir().getAbsolutePath)
    props.put("zookeeper.connect", zkAddress)
    props.put("zookeeper.connection.timeout.ms", "60000")
    props.put("log.flush.interval.messages", "1")
    props.put("replica.socket.timeout.ms", "1500")
    props.put("delete.topic.enable", "true")
    props.put("group.initial.rebalance.delay.ms", "10")

    // Change the following settings as we have only 1 broker
    props.put("offsets.topic.num.partitions", "1")
    props.put("offsets.topic.replication.factor", "1")
    props.put("transaction.state.log.replication.factor", "1")
    props.put("transaction.state.log.min.isr", "1")

    props
  }

  def deleteTopic(topic: String): Unit = {
    adminClient.deleteTopics(Collections.singleton(topic))
    Thread.sleep(10000)
  }

  def teardown(): Unit = {
    brokerReady = false
    zkReady = false

    if (adminClient != null) {
      adminClient.close()
    }

    if (server != null) {
      server.shutdown()
      server.awaitShutdown()
      server = null
    }

    if (zookeeper != null) {
      zookeeper.shutdown()
      zookeeper = null
    }
  }

  def main(args: Array[String]): Unit = {
    setup()

    val topic = UUID.randomUUID().toString
    log.info(s"New topic: $topic")
    val newTopic1 = new NewTopic(topic, 1, 1.toShort)
    adminClient.createTopics(Collections.singleton(newTopic1)).all().get()

    log.info("Creating config properties...")
    val producerProps = new Properties
    producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerAddress)
    producerProps.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT")
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    log.info("OK")

    log.info("Creating kafka producer...")
    val producer = new KafkaProducer[String, String](producerProps)
    log.info("OK")

    for (i <- 0 until 20) {
      val data = "streamtest-" + i
      val record1 = new ProducerRecord[String, String](topic, null, data)
      producer.send(record1, onSendCallback(i))
      log.info("Producer Record: " + record1)
    }

    log.info("Creating config properties...")
    val consumerProps = new Properties
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerAddress)
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
    consumerProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, IsolationLevel.READ_COMMITTED.toString.toLowerCase(Locale.ROOT))
    log.info("OK")

    log.info("Creating kafka consumer...")
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString)
    val consumer = new KafkaConsumer[String, String](consumerProps)
    consumer.subscribe(util.Arrays.asList(topic))
    log.info("OK")

    val tp = new TopicPartition(topic, 0)

    // This is to make the assignment
    log.info("Making assignment")
    consumer.poll(0)
    log.info("OK")

    // Delete some records to make gaps
    log.info("Deleting records")
    val recordsToDelete = Map(
      tp -> RecordsToDelete.beforeOffset(2)
    ).asJava
    adminClient.deleteRecords(recordsToDelete).all().get()
    log.info("OK")

    consumer.seekToBeginning(util.Arrays.asList(tp))
    log.info(s"Earliest after deletion: ${consumer.position(tp)}")
    consumer.seekToEnd(util.Arrays.asList(tp))
    log.info(s"Latest after deletion: ${consumer.position(tp)}")

    log.info("Position before earliest test")
    val posBeforeEarliest = 0
    consumer.seek(tp, posBeforeEarliest)
    val begin = consumer.position(tp)
    log.info(s"Position to seek: $posBeforeEarliest Position received: $begin")

    log.info("Position after latest test")
    val posAfterLatest = 100
    consumer.seek(tp, posAfterLatest)
    val end = consumer.position(tp)
    log.info(s"Position to seek: $posAfterLatest Position received: $end")

    teardown()
  }

  def onSendCallback(messageNumber: Int): Callback = new Callback() {
    override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
      if (exception == null) {
        log.debug(s"Message $messageNumber sent to topic ${metadata.topic()} in partition ${metadata.partition()} at offset ${metadata.offset()}")
      } else {
        log.error(s"Error sending message $messageNumber")
        exception.printStackTrace()
      }
    }
  }}

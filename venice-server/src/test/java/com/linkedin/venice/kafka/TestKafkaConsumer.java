package com.linkedin.venice.kafka;

import com.linkedin.venice.config.VeniceStoreConfig;
import com.linkedin.venice.message.OperationType;
import com.linkedin.venice.message.VeniceMessage;
import com.linkedin.venice.server.VeniceConfigService;
import com.linkedin.venice.server.VeniceServer;

import com.linkedin.venice.store.AbstractStorageEngine;
import java.util.Map;
import kafka.admin.AdminUtils;
import kafka.producer.KeyedMessage;
import kafka.javaapi.producer.Producer;
import kafka.producer.ProducerConfig;
import kafka.server.KafkaConfig;
import kafka.server.KafkaServerStartable;

import org.I0Itec.zkclient.ZkClient;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.apache.zookeeper.server.NIOServerCnxn;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Properties;


/**
 *  Class which tests the Kafka Consumption class.
 *
 *  Note: This class starts many embedded services:
 *   - Zookeeper
 *   - Kafka Server
 *   - Kafka Producer
 *   - Kafka Consumer
 *   - Venice Storage
 *
 */

public class TestKafkaConsumer {

  static final Logger logger = Logger.getLogger(TestKafkaConsumer.class.getName());

  static final String DEFAULT_KAFKA_LOG_DIR = "/tmp/testng-kafka-logs";
  static final String DEFAULT_ZK_LOG_DIR = "/tmp/testng-zookeeper-logs";

  static final int LOCALHOST_ZK_BROKER_PORT = 2626;
  static final int NUM_CONNECTIONS = 5000;
  static final int TICKTIME = 2000;

  static final String TEST_KEY = "test_key";

  KafkaServerStartable kafkaServer;
  Producer<String, VeniceMessage> kafkaProducer;

  VeniceConfigService veniceConfigService;
  VeniceServer veniceServer;
  String storeName;
  VeniceStoreConfig storeConfig;

  @BeforeClass
  private void init()
      throws Exception {
    clearLogs();
    try {
      File configFile = new File("src/test/resources/config"); //TODO this does not run from IDE because IDE expects
      // relative path starting from venice-server
      veniceConfigService = new VeniceConfigService(configFile.getAbsolutePath());
      Map<String, VeniceStoreConfig> storeConfigs = veniceConfigService.getAllStoreConfigs();

      if (storeConfigs.size() < 1) {
        throw new Exception("No stores defined for executing tests");
      }
      for (String store : storeConfigs.keySet()) {
        storeName = store;
        storeConfig = storeConfigs.get(storeName);
        break;
      }

      startZookeeper();
      Thread.sleep(2000);
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail(e.getMessage());
    }

    // TODO: Understand how topic creation is done in the Kafka Admin API
    // An absolutely awful and terrible "hack" which allows a topic to be created on this embedded ZK instance
    startUpServices();
    //startKafkaConsumers(new InMemoryStorageNode(0));
    sendKafkaMessage("");
    tearDown();
    Thread.sleep(1000);

    // The real startup procedure
    startUpServices();
    try {
      startVeniceStorage();
    } catch (Exception e) {
      e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
    }
  }

  /**
   * Empties out the remaining logs in the Kafka and ZooKeeper directories
   * */
  private void clearLogs() {
    try {
      File kafkaLogs = new File(DEFAULT_KAFKA_LOG_DIR);
      if (kafkaLogs.exists()) {
        FileUtils.deleteDirectory(kafkaLogs);
      }
      File zkLogs = new File(DEFAULT_ZK_LOG_DIR);
      if (zkLogs.exists()) {
        FileUtils.deleteDirectory(zkLogs);
      }
    } catch (IOException e) {
      Assert.fail("Encountered problem while deleting Kafka test logs.");
    }
  }

  /**
   *  Starts a local instance of ZooKeeper
   * */
  private void startZookeeper()
      throws Exception {
    File dir = new File(DEFAULT_ZK_LOG_DIR);
    ZooKeeperServer server = new ZooKeeperServer(dir, dir, TICKTIME);
    server.setMaxSessionTimeout(1000000);
    NIOServerCnxn.Factory standaloneServerFactory =
        new NIOServerCnxn.Factory(new InetSocketAddress(LOCALHOST_ZK_BROKER_PORT), NUM_CONNECTIONS);
    standaloneServerFactory.startup(server);
    Thread.sleep(2000);
  }

  private void startUpServices() {
    Properties kafkaProperties = new Properties();
    try {
      // start Kakfa
      kafkaProperties.load(new FileInputStream("src/test/resources/kafkatest.properties"));
      startKafkaServer(kafkaProperties);
      Thread.sleep(2000);
      // start the Kafka Producer
      startKafkaProducer(storeConfig.getKafkaBrokerUrl());
      // start the Venice Storage nodes
      startVeniceStorage();
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail(e.getMessage());
    }
  }

  /**
   *  Starts a local instance of Kafka
   * */
  private void startKafkaServer(Properties kafkaProps) {
    KafkaConfig config = new KafkaConfig(kafkaProps);
    // start kafka
    kafkaServer = new KafkaServerStartable(config);
    kafkaServer.startup();
  }

  /**
   *  Starts a Kafka producer service.
   *  Kakfa server must be active for the producer to be started properly.
   * */
  private void startKafkaProducer(String brokerUrl) {
    Properties props = new Properties();
    props.put("metadata.broker.list", brokerUrl);
    props.put("key.serializer.class", "kafka.serializer.StringEncoder");
    props.put("serializer.class", "com.linkedin.venice.serialization.VeniceMessageSerializer");
    props.setProperty("partitioner.class", "com.linkedin.venice.kafka.consumer.KafkaPartitioner");
    ProducerConfig config = new ProducerConfig(props);
    kafkaProducer = new Producer<String, VeniceMessage>(config);
  }

  /**
   *  Set up the nodes for Venice, such that they can be written to
   * @throws Exception
   * */
  private void startVeniceStorage()
      throws Exception {
    veniceServer = new VeniceServer(veniceConfigService);
    veniceServer.start();
  }

  /**
   *  Sends a Kafka message through a Kafka Producer.
   *  Kafka Producer must be active
   * */
  public void sendKafkaMessage(String payload) {
    try {
      KeyedMessage<String, VeniceMessage> data =
          new KeyedMessage<String, VeniceMessage>(storeName, TEST_KEY, new VeniceMessage(OperationType.PUT, payload));
      kafkaProducer.send(data);
    } catch (Exception e) {
      logger.error(e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * Safely shutdown the services started in this class
   * */
  @AfterClass
  public void tearDown()
      throws Exception {
    if (veniceServer.isStarted()) {
      veniceServer.shutdown();
    }
    kafkaProducer.close();
    kafkaServer.shutdown();
  }

  /**
   *  A basic test which send messages through Kafka, and consumes them
   * */
  @Test(enabled = true)
  public void testKafkaBasic() {
    AbstractStorageEngine node = veniceServer.getStoreRepository().getLocalStorageEngine(storeName);
    try {
      Thread.sleep(2000);
      ZkClient zkc = new ZkClient(storeConfig.getKafkaZookeeperUrl(), 10000, 10000);
      Assert.assertTrue(AdminUtils.topicExists(zkc, storeName));

      sendKafkaMessage("test_message");
      Thread.sleep(4000);
      Assert.assertEquals(node.get(0, TEST_KEY.getBytes()), "test_message".getBytes());

      sendKafkaMessage("test_message 2");
      Thread.sleep(1000);
      Assert.assertEquals(node.get(0, TEST_KEY.getBytes()), "test_message 2".getBytes());

      sendKafkaMessage("test_message 3");
      Thread.sleep(1000);
      Assert.assertEquals(node.get(0, TEST_KEY.getBytes()), "test_message 3".getBytes());
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail(e.getMessage());
    }
  }
}

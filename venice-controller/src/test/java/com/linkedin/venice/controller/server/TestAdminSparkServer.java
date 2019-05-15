package com.linkedin.venice.controller.server;

import com.linkedin.venice.LastSucceedExecutionIdResponse;
import com.linkedin.venice.controller.Admin;
import com.linkedin.venice.controllerapi.AdminCommandExecution;
import com.linkedin.venice.controllerapi.ControllerApiConstants;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.ControllerResponse;
import com.linkedin.venice.controllerapi.ControllerRoute;
import com.linkedin.venice.controllerapi.MultiNodeResponse;
import com.linkedin.venice.controllerapi.MultiNodesStatusResponse;
import com.linkedin.venice.controllerapi.MultiReplicaResponse;
import com.linkedin.venice.controllerapi.MultiSchemaResponse;
import com.linkedin.venice.controllerapi.MultiStoreStatusResponse;
import com.linkedin.venice.controllerapi.MultiVersionResponse;
import com.linkedin.venice.controllerapi.NewStoreResponse;
import com.linkedin.venice.controllerapi.OwnerResponse;
import com.linkedin.venice.controllerapi.SchemaResponse;
import com.linkedin.venice.controllerapi.StorageEngineOverheadRatioResponse;
import com.linkedin.venice.controllerapi.StoreResponse;
import com.linkedin.venice.controllerapi.TrackableControllerResponse;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.controllerapi.VersionCreationResponse;
import com.linkedin.venice.controllerapi.VersionResponse;
import com.linkedin.venice.controllerapi.routes.AdminCommandExecutionResponse;
import com.linkedin.venice.controllerapi.routes.PushJobStatusUploadResponse;
import com.linkedin.venice.integration.utils.IntegrationTestUtils;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceControllerWrapper;
import com.linkedin.venice.integration.utils.VeniceServerWrapper;
import com.linkedin.venice.integration.utils.ZkServerWrapper;
import com.linkedin.venice.meta.InstanceStatus;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.StoreInfo;
import com.linkedin.venice.meta.StoreStatus;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.participant.protocol.ParticipantMessageStoreUtils;
import com.linkedin.venice.router.httpclient.HttpClientUtils;
import com.linkedin.venice.status.protocol.enums.PushJobStatus;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.message.BasicNameValuePair;
import org.codehaus.jackson.map.ObjectMapper;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Slow test class, given fast priority
 */
@Test(singleThreaded = true, priority = -6)
public class TestAdminSparkServer {
  /**
   * Seems that Helix has limit on the number of resource each node is able to handle.
   * If the test case needs more than one storage node like testing failover etc, please put it into {@link TestAdminSparkServerWithMultiServers}
   *
   * And please collect the store and version you created in the end of your test case.
   */
  private static final int STORAGE_NODE_COUNT = 1;
  private static final int TIME_OUT = 20 * Time.MS_PER_SECOND;

  private VeniceClusterWrapper venice;
  private String routerUrl;
  private ControllerClient controllerClient;
  private VeniceControllerWrapper parentController;

  @BeforeClass
  public void setUp() {
    venice = ServiceFactory.getVeniceCluster(1, STORAGE_NODE_COUNT, 1); //Controllers, Servers, Routers
    ZkServerWrapper parentZk = ServiceFactory.getZkServer();
    parentController =
        ServiceFactory.getVeniceParentController(venice.getClusterName(), parentZk.getAddress(), venice.getKafka(),
            new VeniceControllerWrapper[]{venice.getMasterVeniceController()}, false);
    routerUrl = venice.getRandomRouterURL();
    controllerClient = new ControllerClient(venice.getClusterName(), routerUrl);
  }

  @AfterClass
  public void tearDown(){
    venice.close();
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanQueryNodesInCluster(){
    MultiNodeResponse nodeResponse = controllerClient.listStorageNodes();
    Assert.assertFalse(nodeResponse.isError(), nodeResponse.getError());
    Assert.assertEquals(nodeResponse.getNodes().length, STORAGE_NODE_COUNT, "Node count does not match");
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanQueryInstanceStatusInCluster() {
    MultiNodesStatusResponse nodeResponse = controllerClient.listInstancesStatuses();
    Assert.assertFalse(nodeResponse.isError(), nodeResponse.getError());
    Assert.assertEquals(nodeResponse.getInstancesStatusMap().size(), STORAGE_NODE_COUNT, "Node count does not match");
    Assert.assertEquals(nodeResponse.getInstancesStatusMap().values().iterator().next(),
        InstanceStatus.CONNECTED.toString(), "Node status does not match.");
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanQueryReplicasOnAStorageNode(){
    String storeName = venice.getNewStoreVersion().getName();
    try {
      MultiNodeResponse nodeResponse = controllerClient.listStorageNodes();
      String nodeId = nodeResponse.getNodes()[0];
      MultiReplicaResponse replicas = controllerClient.listStorageNodeReplicas(nodeId);
      Assert.assertFalse(replicas.isError(), replicas.getError());
    }finally {
      deleteStore(storeName);
    }
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanQueryReplicasForTopic(){
    VersionCreationResponse versionCreationResponse = venice.getNewStoreVersion();
    String storeName = versionCreationResponse.getName();
    try {
      Assert.assertFalse(versionCreationResponse.isError(), versionCreationResponse.getError());
      String kafkaTopic = venice.getNewStoreVersion().getKafkaTopic();
      Assert.assertNotNull(kafkaTopic, "venice.getNewStoreVersion() should not return a null topic name\n" + versionCreationResponse.toString());

      String store = Version.parseStoreFromKafkaTopicName(kafkaTopic);
      int version = Version.parseVersionFromKafkaTopicName(kafkaTopic);
      MultiReplicaResponse response = controllerClient.listReplicas(store, version);
      Assert.assertFalse(response.isError(), response.getError());
      int totalReplicasCount = versionCreationResponse.getPartitions() * versionCreationResponse.getReplicas();
      Assert.assertEquals(response.getReplicas().length, totalReplicasCount, "Replica count does not match");
    } finally {
      deleteStore(storeName);
    }
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanCreateNewStore() throws IOException, ExecutionException, InterruptedException {
    String storeToCreate = "newTestStore123";
    String keySchema = "\"string\"";
    String valueSchema = "\"long\"";

    // create Store
    NewStoreResponse newStoreResponse =
        controllerClient.createNewStore(storeToCreate, "owner", keySchema, valueSchema);
    Assert.assertFalse(newStoreResponse.isError(), "create new store should succeed for a store that doesn't exist");
    //if the store is not created by VeniceClusterWrapper, it has to be reported so other test cases will not be broken.
    venice.increaseStoreCount();

    NewStoreResponse duplicateNewStoreResponse =
        controllerClient.createNewStore(storeToCreate, "owner", keySchema, valueSchema);
    Assert.assertTrue(duplicateNewStoreResponse.isError(), "create new store should fail for duplicate store creation");

    // ensure creating a duplicate store throws a http 409, status code isn't exposed in controllerClient
   CloseableHttpAsyncClient httpClient = HttpClientUtils.getMinimalHttpClient(1,1, Optional.empty());
    httpClient.start();
    List<NameValuePair> params = new ArrayList<>();
    params.add(new BasicNameValuePair(ControllerApiConstants.HOSTNAME, Utils.getHostName()));
    params.add(new BasicNameValuePair(ControllerApiConstants.CLUSTER, venice.getClusterName()));
    params.add(new BasicNameValuePair(ControllerApiConstants.NAME, storeToCreate));
    params.add(new BasicNameValuePair(ControllerApiConstants.OWNER, "owner"));
    params.add(new BasicNameValuePair(ControllerApiConstants.KEY_SCHEMA, keySchema));
    params.add(new BasicNameValuePair(ControllerApiConstants.VALUE_SCHEMA, valueSchema));
    final HttpPost post = new HttpPost(venice.getAllControllersURLs() + ControllerRoute.NEW_STORE.getPath());
    post.setEntity(new UrlEncodedFormEntity(params));
    HttpResponse duplicateStoreCreationHttpResponse = httpClient.execute(post, null).get();
    Assert.assertEquals(duplicateStoreCreationHttpResponse.getStatusLine().getStatusCode(), 409, IOUtils.toString(duplicateStoreCreationHttpResponse.getEntity().getContent()));
    httpClient.close();
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientGetKeySchema() {
    String storeToCreate = TestUtils.getUniqueString("newTestStore125");
    String keySchemaStr = "\"string\"";
    String valueSchemaStr = "\"long\"";
    // Get key schema from non-existed store
    SchemaResponse sr0 = controllerClient.getKeySchema(storeToCreate);
    Assert.assertTrue(sr0.isError());
    // Create Store
    NewStoreResponse newStoreResponse =
        controllerClient.createNewStore(storeToCreate, "owner", keySchemaStr, valueSchemaStr);

    Assert.assertFalse(newStoreResponse.isError(), "create new store should succeed for a store that doesn't exist");
    SchemaResponse sr1 = controllerClient.getKeySchema(storeToCreate);
    Assert.assertEquals(sr1.getId(), 1);
    Assert.assertEquals(sr1.getSchemaStr(), keySchemaStr);
  }

  private String formatSchema(String schema) {
    return new Schema.Parser().parse(schema).toString();
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientManageValueSchema() {
    String storeToCreate = TestUtils.getUniqueString("newTestStore");
    String keySchemaStr = "\"string\"";
    String schemaPrefix = "        {\n" + "           \"type\": \"record\",\n" + "           \"name\": \"KeyRecord\",\n"
        + "           \"fields\" : [\n"
        + "               {\"name\": \"name\", \"type\": \"string\", \"doc\": \"name field\"},\n"
        + "               {\"name\": \"company\", \"type\": \"string\"},\n" + "               {\n"
        + "                 \"name\": \"Suit\", \n" + "                 \"type\": {\n"
        + "                        \"name\": \"SuitType\", \"type\": \"enum\", \"symbols\": [\"SPADES\", \"DIAMONDS\", \"HEART\", \"CLUBS\"]\n"
        + "                }\n" + "              },\n";

    String schemaSuffix = "           ]\n" + "        }";
    String salaryFieldWithoutDefault = "               {\"name\": \"salary\", \"type\": \"long\"}\n";

    String salaryFieldWithDefault = "               {\"name\": \"salary\", \"type\": \"long\", \"default\": 123 }\n";

    String schema1 = formatSchema(schemaPrefix + salaryFieldWithoutDefault + schemaSuffix);
    String schema2 = formatSchema(schemaPrefix + salaryFieldWithDefault + schemaSuffix);
    String invalidSchema = "abc";
    String incompatibleSchema = "\"string\"";

    // Add value schema to non-existed store
    SchemaResponse sr0 = controllerClient.addValueSchema(storeToCreate, schema1);
    Assert.assertTrue(sr0.isError());
    // Add value schema to an existing store
    NewStoreResponse newStoreResponse = controllerClient.createNewStore(storeToCreate, "owner", keySchemaStr, schema1);
    Assert.assertFalse(newStoreResponse.isError(), "create new store should succeed for a store that doesn't exist");
    SchemaResponse sr1 = controllerClient.addValueSchema(storeToCreate, schema1);
    Assert.assertFalse(sr1.isError());
    Assert.assertEquals(sr1.getId(), 1);
    // Add same value schema
    SchemaResponse sr2 = controllerClient.addValueSchema(storeToCreate, schema1);
    Assert.assertFalse(sr2.isError());
    Assert.assertEquals(sr2.getId(), sr1.getId());
    // Add a new value schema
    SchemaResponse sr3 = controllerClient.addValueSchema(storeToCreate, schema2);
    Assert.assertFalse(sr3.isError());
    Assert.assertEquals(sr3.getId(), 2);
    // Add invalid schema
    SchemaResponse sr4 = controllerClient.addValueSchema(storeToCreate, invalidSchema);
    Assert.assertTrue(sr4.isError());
    // Add incompatible schema
    SchemaResponse sr5 = controllerClient.addValueSchema(storeToCreate, incompatibleSchema);
    Assert.assertTrue(sr5.isError());

    // Formatted schema string
    String formattedSchemaStr1 = formatSchema(schema1);
    String formattedSchemaStr2 = formatSchema(schema2);
    // Get schema by id
    SchemaResponse sr6 = controllerClient.getValueSchema(storeToCreate, 1);
    Assert.assertFalse(sr6.isError());
    Assert.assertEquals(sr6.getSchemaStr(), formattedSchemaStr1);
    SchemaResponse sr7 = controllerClient.getValueSchema(storeToCreate, 2);
    Assert.assertFalse(sr7.isError());
    Assert.assertEquals(sr7.getSchemaStr(), formattedSchemaStr2);
    // Get schema by non-existed schema id
    SchemaResponse sr8 = controllerClient.getValueSchema(storeToCreate, 3);
    Assert.assertTrue(sr8.isError());

    // Get value schema by schema
    SchemaResponse sr9 = controllerClient.getValueSchemaID(storeToCreate, schema1);
    Assert.assertFalse(sr9.isError());
    Assert.assertEquals(sr9.getId(), 1);
    SchemaResponse sr10 = controllerClient.getValueSchemaID(storeToCreate, schema2);
    Assert.assertFalse(sr10.isError());
    Assert.assertEquals(sr10.getId(), 2);
    SchemaResponse sr11 = controllerClient.getValueSchemaID(storeToCreate, invalidSchema);
    Assert.assertTrue(sr11.isError());
    SchemaResponse sr12 = controllerClient.getValueSchemaID(storeToCreate, incompatibleSchema);
    Assert.assertTrue(sr12.isError());

    // Get all value schema
    MultiSchemaResponse msr = controllerClient.getAllValueSchema(storeToCreate);
    Assert.assertFalse(msr.isError());
    MultiSchemaResponse.Schema[] schemas = msr.getSchemas();
    Assert.assertEquals(schemas.length, 2);
    Assert.assertEquals(schemas[0].getId(), 1);
    Assert.assertEquals(schemas[0].getSchemaStr(), formattedSchemaStr1);
    Assert.assertEquals(schemas[1].getId(), 2);
    Assert.assertEquals(schemas[1].getSchemaStr(), formattedSchemaStr2);

    // Add way more schemas, to test for the bug where we ordered schemas lexicographically: 1, 10, 11, 2, 3, ...
    String[] allSchemas = new String[100];
    allSchemas[0] = schema1;
    allSchemas[1] = schema2;
    String prefixForLotsOfSchemas = schemaPrefix + salaryFieldWithDefault;

    for (int i = 3; i < allSchemas.length; i++) {
      prefixForLotsOfSchemas += "," +
          "               {\"name\": \"newField" + i + "\", \"type\": \"long\", \"default\": 123 }\n";
      String schema = formatSchema(prefixForLotsOfSchemas + schemaSuffix);
      allSchemas[i - 1] = schema;
      SchemaResponse sr = controllerClient.addValueSchema(storeToCreate, schema);
      Assert.assertFalse(sr.isError());
      Assert.assertEquals(sr.getId(), i);

      // At each new schema we create, we test that the ordering is correct
      MultiSchemaResponse msr2 = controllerClient.getAllValueSchema(storeToCreate);
      Assert.assertFalse(msr2.isError());
      MultiSchemaResponse.Schema[] schemasFromController = msr2.getSchemas();
      Assert.assertEquals(schemasFromController.length, i,
          "getAllValueSchema request should return " + i + " schemas.");

      for (int j = 1; j <= i; j++) {
        Assert.assertEquals(schemasFromController[j - 1].getId(), j,
            "getAllValueSchema request should return the right schema ID for item " + j
                + " after " + i + " schemas have been created.");
        Assert.assertEquals(schemasFromController[j - 1].getSchemaStr(), allSchemas[j - 1],
            "getAllValueSchema request should return the right schema string for item " + j
                + " after " + i + " schemas have been created.");
      }
    }
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientSchemaOperationsAgainstInvalidStore() {
    String schema1 = "\"string\"";
    // Verify getting operations against non-existed store
    String nonExistedStore = TestUtils.getUniqueString("test2434095i02");
    SchemaResponse sr1 = controllerClient.getValueSchema(nonExistedStore, 1);
    Assert.assertTrue(sr1.isError());
    SchemaResponse sr2 = controllerClient.getValueSchemaID(nonExistedStore, schema1);
    Assert.assertTrue(sr2.isError());
    MultiSchemaResponse msr1 = controllerClient.getAllValueSchema(nonExistedStore);
    Assert.assertTrue(msr1.isError());
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanGetStoreInfo(){
    String topic = venice.getNewStoreVersion().getKafkaTopic();
    String storeName = Version.parseStoreFromKafkaTopicName(topic);
    try {
      StoreResponse storeResponse = controllerClient.getStore(storeName);
      Assert.assertFalse(storeResponse.isError(), storeResponse.getError());

      StoreInfo store = storeResponse.getStore();
      Assert.assertEquals(store.getName(), storeName, "Store Info should have same store name as request");
      Assert.assertTrue(store.isEnableStoreWrites(), "New store should not be disabled");
      Assert.assertTrue(store.isEnableStoreReads(), "New store should not be disabled");
      List<Version> versions = store.getVersions();
      Assert.assertEquals(versions.size(), 1, " Store from new store-version should only have one version");
    }finally {
      deleteStore(storeName);
    }
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanDisableStoresWrite()
      throws InterruptedException {
    String topic = venice.getNewStoreVersion().getKafkaTopic();
    String storeName = Version.parseStoreFromKafkaTopicName(topic);
    try {
      StoreInfo store = controllerClient.getStore(storeName).getStore();
      Assert.assertTrue(store.isEnableStoreWrites(), "Store should NOT be disabled after creating new store-version");

      ControllerResponse response = controllerClient.enableStoreWrites(storeName, false);
      Assert.assertFalse(response.isError(), response.getError());

      store = controllerClient.getStore(storeName).getStore();
      Assert.assertFalse(store.isEnableStoreWrites(), "Store should be disabled after setting disabled status to true");
    } finally {
      deleteStore(storeName);
    }
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanDisableStoresRead()
      throws InterruptedException {
    String topic = venice.getNewStoreVersion().getKafkaTopic();

    String storeName = Version.parseStoreFromKafkaTopicName(topic);

    StoreInfo store = controllerClient.getStore(storeName).getStore();
    Assert.assertTrue(store.isEnableStoreReads(), "Store should NOT be disabled after creating new store-version");

    ControllerResponse response = controllerClient.enableStoreReads(storeName, false);
    Assert.assertFalse(response.isError(), response.getError());

    store = controllerClient.getStore(storeName).getStore();
    Assert.assertFalse(store.isEnableStoreReads(), "Store should be disabled after setting disabled status to true");
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanDisableStoresReadWrite()
      throws InterruptedException {
    String topic = venice.getNewStoreVersion().getKafkaTopic();

    String storeName = Version.parseStoreFromKafkaTopicName(topic);
    try {
      StoreInfo store = controllerClient.getStore(storeName).getStore();
      Assert.assertTrue(store.isEnableStoreReads(), "Store should NOT be disabled after creating new store-version");
      Assert.assertTrue(store.isEnableStoreWrites(), "Store should NOT be disabled after creating new store-version");

      ControllerResponse response = controllerClient.enableStoreReadWrites(storeName, false);
      Assert.assertFalse(response.isError(), response.getError());

      store = controllerClient.getStore(storeName).getStore();
      Assert.assertFalse(store.isEnableStoreReads(), "Store should be disabled after setting disabled status to true");
      Assert.assertFalse(store.isEnableStoreWrites(), "Store should be disabled after setting disabled status to true");
    }finally {
      deleteStore(storeName);
    }
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanSetStoreMetadata() {
    String storeName = TestUtils.getUniqueString("store");
    String owner = TestUtils.getUniqueString("owner");
    int partitionCount = 2;

    venice.getNewStore(storeName);
    ControllerClient controllerClient = new ControllerClient(venice.getClusterName(), routerUrl);

    OwnerResponse ownerRes = controllerClient.setStoreOwner(storeName, owner);
    Assert.assertFalse(ownerRes.isError(), ownerRes.getError());
    Assert.assertEquals(ownerRes.getOwner(), owner);

    UpdateStoreQueryParams updateStoreQueryParams =
        new UpdateStoreQueryParams()
            .setPartitionCount(partitionCount)
            .setIncrementalPushEnabled(true);
    ControllerResponse partitionRes = controllerClient.updateStore(storeName, updateStoreQueryParams);
    Assert.assertFalse(partitionRes.isError(), partitionRes.getError());

    StoreResponse storeResponse = controllerClient.getStore(storeName);
    Assert.assertEquals(storeResponse.getStore().getPartitionCount(), partitionCount);
    Assert.assertEquals(storeResponse.getStore().isIncrementalPushEnabled(), true);
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanQueryRemovability(){
    VeniceServerWrapper server = venice.getVeniceServers().get(0);
    String nodeId = Utils.getHelixNodeIdentifier(server.getPort());

    ControllerResponse response = controllerClient.isNodeRemovable(nodeId);
    Assert.assertFalse(response.isError(), response.getError());
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanDeleteAllVersion() {
    String storeName = "controllerClientCanDeleteAllVersion";
    venice.getNewStore(storeName);
    venice.getNewVersion(storeName, 100);

    controllerClient.enableStoreReads(storeName, false);
    controllerClient.enableStoreWrites(storeName, false);
    MultiVersionResponse response = controllerClient.deleteAllVersions(storeName);
    Assert.assertEquals(response.getExecutionId(), 0,
        "The command executed in non-parent controller should have an execution id 0");

    StoreInfo store = controllerClient.getStore(storeName).getStore();
    Assert.assertEquals(store.getVersions().size(), 0);
  }


  @Test(timeOut = TIME_OUT)
  public void controllerClientCanDeleteOldVersion() {
    String storeName = "controllerClientCanDeleteOldVersion";
    venice.getNewStore(storeName);
    venice.getNewVersion(storeName, 100);

    VersionResponse response = controllerClient.deleteOldVersion(storeName, 1);
    Assert.assertEquals(response.getVersion(), 1);
    StoreInfo store = controllerClient.getStore(storeName).getStore();
    Assert.assertEquals(store.getVersions().size(), 0);
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanGetLastSucceedExecutionId() {
    LastSucceedExecutionIdResponse response = controllerClient.getLastSucceedExecutionId();
    Assert.assertFalse(response.isError());
    Assert.assertTrue(response.getLastSucceedExecutionId() > -1);
  }

  @Test(timeOut = 3 * TIME_OUT)
  public void controllerClientCanGetExecutionOfDeleteAllVersions()
      throws InterruptedException {
    String cluster = venice.getClusterName();
    String storeName = "controllerClientCanDeleteAllVersion";
    parentController.getVeniceAdmin().addStore(cluster, storeName, "test", "\"string\"", "\"string\"");
    parentController.getVeniceAdmin().incrementVersionIdempotent(cluster, storeName, Version.guidBasedDummyPushId(),
        1, 1, true);

    ControllerClient controllerClient = new ControllerClient(cluster, parentController.getControllerUrl());
    controllerClient.enableStoreReads(storeName, false);
    controllerClient.enableStoreWrites(storeName, false);
    MultiVersionResponse multiVersionResponse = controllerClient.deleteAllVersions(storeName);
    long executionId = multiVersionResponse.getExecutionId();
    AdminCommandExecutionResponse response =
        controllerClient.getAdminCommandExecution(executionId);
    Assert.assertFalse(response.isError());
    Assert.assertNotNull(response.getExecution());
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanListStoresStatuses() {
    List<String> storeNames = new ArrayList<>();
    int storeCount = 2;
    for (int i = 0; i < storeCount; i++) {
      storeNames.add(venice.getNewStore("testStore" + i, "test").getName());
    }

    MultiStoreStatusResponse storeResponse =
        controllerClient.listStoresStatuses();
    Assert.assertFalse(storeResponse.isError());
    //since all test cases share VeniceClusterWrapper, we get the total number of stores from the Wrapper.
    List<String> storesInCluster = storeResponse.getStoreStatusMap().entrySet().stream()
        .map(e -> e.getKey()).collect(Collectors.toList());
    for (String storeName : storeNames) {
      Assert.assertTrue(storesInCluster.contains(storeName), "Result of listing store status should contain all stores we created.");
    }
    List<String> storeStatuses = storeResponse.getStoreStatusMap().entrySet().stream()
        .filter(e -> !e.getKey().equals(ParticipantMessageStoreUtils.getStoreNameForCluster(venice.getClusterName())))
        .map(Map.Entry::getValue).collect(Collectors.toList());
    Assert.assertFalse(storeStatuses.isEmpty());
    for (String status : storeStatuses) {
      Assert.assertEquals(status, StoreStatus.UNAVAILABLE.toString(),
          "Store should be unavailable because we have not created a version for this store.");
    }
    for (String expectedStore : storeNames) {
      Assert.assertTrue(storeResponse.getStoreStatusMap().containsKey(expectedStore),
          "Result of list store status should contain the store we created: " + expectedStore);
    }
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanUpdateWhiteList() {
    Admin admin = venice.getMasterVeniceController().getVeniceAdmin();

    String nodeId = Utils.getHelixNodeIdentifier(34567);
    Assert.assertFalse(admin.getWhitelist(venice.getClusterName()).contains(nodeId),
        nodeId + " has not been added into white list.");
    controllerClient.addNodeIntoWhiteList(nodeId);
    Assert.assertTrue(admin.getWhitelist(venice.getClusterName()).contains(nodeId),
        nodeId + " has been added into white list.");
    controllerClient.removeNodeFromWhiteList(nodeId);
    Assert.assertFalse(admin.getWhitelist(venice.getClusterName()).contains(nodeId),
        nodeId + " has been removed from white list.");
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanSetStore() {
    //mutable store metadata
    String owner = TestUtils.getUniqueString("owner");
    int partitionCount = 2;
    int current = 1;
    boolean enableReads = false;
    boolean enableWrite = true;
    boolean accessControlled = true;
    long storageQuotaInByte = 100l;
    long readQuotaInCU = 200l;
    int numVersionToPreserve = 100;

    String storeName = venice.getNewStoreVersion().getName();
    ControllerClient controllerClient = new ControllerClient(venice.getClusterName(), routerUrl);
    // Disable writes at first and test could we enable writes again through the update store method.
    Assert.assertFalse(controllerClient.enableStoreReadWrites(storeName, false).isError(),
        "Disable writes should not fail.");

    UpdateStoreQueryParams queryParams = new UpdateStoreQueryParams()
        .setOwner(owner)
        .setPartitionCount(partitionCount)
        .setCurrentVersion(current)
        .setEnableReads(enableReads)
        .setEnableWrites(enableWrite)
        .setStorageQuotaInByte(storageQuotaInByte)
        .setReadQuotaInCU(readQuotaInCU)
        .setAccessControlled(accessControlled)
        .setNumVersionsToPreserve(numVersionToPreserve);

    ControllerResponse response = controllerClient.updateStore(storeName, queryParams);

    Assert.assertFalse(response.isError(), response.getError());
    Store store = venice.getMasterVeniceController().getVeniceAdmin().getStore(venice.getClusterName(), storeName);
    Assert.assertEquals(store.getOwner(), owner);
    Assert.assertEquals(store.getPartitionCount(), partitionCount);
    Assert.assertEquals(store.getCurrentVersion(), current);
    Assert.assertEquals(store.isEnableReads(), enableReads);
    Assert.assertEquals(store.isEnableWrites(), enableWrite);
    Assert.assertEquals(store.isAccessControlled(), accessControlled);
    Assert.assertEquals(store.getNumVersionsToPreserve(), numVersionToPreserve);

    enableWrite = false;
    accessControlled = !accessControlled;
    queryParams = new UpdateStoreQueryParams()
        .setEnableWrites(enableWrite)
        .setAccessControlled(accessControlled);
    Assert.assertFalse(controllerClient.updateStore(storeName, queryParams).isError(),
        "We should be able to disable store writes again.");

    store = venice.getMasterVeniceController().getVeniceAdmin().getStore(venice.getClusterName(), storeName);
    Assert.assertEquals(store.isAccessControlled(), accessControlled);
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanSetStoreMissingSomeFields() {
    String storeName = null;
    try {
      //partial metadata
      int partitionCount = 2;
      int current = 1;
      boolean enableReads = false;

      storeName = venice.getNewStoreVersion().getName();
      ControllerClient controllerClient = new ControllerClient(venice.getClusterName(), routerUrl);
      ControllerResponse response = controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
          .setPartitionCount(partitionCount)
          .setCurrentVersion(current)
          .setEnableReads(enableReads));

      Assert.assertFalse(response.isError(), response.getError());
      Store store = venice.getMasterVeniceController().getVeniceAdmin().getStore(venice.getClusterName(), storeName);
      Assert.assertEquals(store.getPartitionCount(), partitionCount);
      Assert.assertEquals(store.getCurrentVersion(), current);
      Assert.assertEquals(store.isEnableReads(), enableReads);
    } finally {
      if (null != storeName) {
        deleteStore(storeName);
      }
    }
  }

  @Test(timeOut = TIME_OUT)
  public void canCreateAHybridStore() {
    String storeName = TestUtils.getUniqueString("store");
    String owner = TestUtils.getUniqueString("owner");
    NewStoreResponse newStoreResponse = controllerClient.createNewStore(storeName, owner, "\"string\"", "\"string\"");
    controllerClient.updateStore(storeName, new UpdateStoreQueryParams()
        .setHybridRewindSeconds(123L)
        .setHybridOffsetLagThreshold(1515L));
    StoreResponse storeResponse = controllerClient.getStore(storeName);
    Assert.assertEquals(storeResponse.getStore().getHybridStoreConfig().getRewindTimeInSeconds(), 123L);
    Assert.assertEquals(storeResponse.getStore().getHybridStoreConfig().getOffsetLagThresholdToGoOnline(), 1515L);
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanGetStorageEngineOverheadRatio() {
    String storeName = venice.getNewStoreVersion().getName();
    try {
      StorageEngineOverheadRatioResponse response = controllerClient.getStorageEngineOverheadRatio(storeName);

      Assert.assertFalse(response.isError(), response.getError());
      Assert.assertEquals(response.getStorageEngineOverheadRatio(), VeniceControllerWrapper.DEFAULT_STORAGE_ENGINE_OVERHEAD_RATIO);
    }finally {
      deleteStore(storeName);
    }
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanDeleteStore() {
    String storeName = "controllerClientCanDeleteStore";
    venice.getNewStore(storeName);
    venice.getNewVersion(storeName, 100);

    controllerClient.enableStoreReads(storeName, false);
    controllerClient.enableStoreWrites(storeName, false);
    TrackableControllerResponse response = controllerClient.deleteStore(storeName);
    Assert.assertEquals(response.getExecutionId(), 0,
        "The command executed in non-parent controller should have an execution id 0");
    StoreResponse storeResponse = controllerClient.getStore(storeName);
    Assert.assertTrue(storeResponse.isError(), "Store should already be deleted.");
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanGetExecutionOfDeleteStore()
      throws InterruptedException {
    String cluster = venice.getClusterName();
    ZkServerWrapper parentZk = ServiceFactory.getZkServer();
    VeniceControllerWrapper parentController =
        ServiceFactory.getVeniceParentController(cluster, parentZk.getAddress(), ServiceFactory.getKafkaBroker(),
            new VeniceControllerWrapper[]{venice.getMasterVeniceController()}, false);
    TestUtils.waitForNonDeterministicAssertion(TIME_OUT, TimeUnit.MILLISECONDS, () -> Assert.assertTrue(
        parentController.isMasterController(cluster),
        "Parent controller needs to be master in order for this test to proceed."));
    String storeName = "controllerClientCanGetExecutionOfDeleteStore";
    parentController.getVeniceAdmin().addStore(cluster, storeName, "test", "\"string\"", "\"string\"");
    parentController.getVeniceAdmin().incrementVersionIdempotent(cluster, storeName, "test", 1, 1, true);

    ControllerClient controllerClient = new ControllerClient(cluster, parentController.getControllerUrl());
    controllerClient.enableStoreReads(storeName, false);
    controllerClient.enableStoreWrites(storeName, false);
    TrackableControllerResponse trackableControllerResponse = controllerClient.deleteStore(storeName);
    long executionId = trackableControllerResponse.getExecutionId();
    AdminCommandExecutionResponse response = controllerClient.getAdminCommandExecution(executionId);
    Assert.assertFalse(response.isError());
    AdminCommandExecution execution = response.getExecution();
    Assert.assertNotNull(execution);
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientProvidesErrorWhenRequestingTopicForStoreThatDoesNotExist() throws IOException {
    String storeNameDoesNotExist = TestUtils.getUniqueString("no-store");
    String pushId = TestUtils.getUniqueString("no-store-push");

    VersionCreationResponse vcr =
        controllerClient.requestTopicForWrites(storeNameDoesNotExist, 1L, ControllerApiConstants.PushType.BATCH, pushId,
            false);
    Assert.assertTrue(vcr.isError(),
        "Request topic for store that has not been created must return error, instead it returns: " + new ObjectMapper()
            .writeValueAsString(vcr));

    vcr = controllerClient.requestTopicForWrites(storeNameDoesNotExist, 1L, ControllerApiConstants.PushType.STREAM, pushId,
        false);
    Assert.assertTrue(vcr.isError(),
        "Request topic for store that has not been created must return error, instead it returns: " + new ObjectMapper()
            .writeValueAsString(vcr));
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanEnableThrottling(){
    controllerClient.enableThrotting(false);
    Assert.assertFalse(controllerClient.getRoutersClusterConfig().getConfig().isThrottlingEnabled());
    controllerClient.enableThrotting(true);
    Assert.assertTrue(controllerClient.getRoutersClusterConfig().getConfig().isThrottlingEnabled());

  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanEnableMaxCapacityProtection(){
    controllerClient.enableMaxCapacityProtection(false);
    Assert.assertFalse(controllerClient.getRoutersClusterConfig().getConfig().isMaxCapacityProtectionEnabled());
    controllerClient.enableMaxCapacityProtection(true);
    Assert.assertTrue(controllerClient.getRoutersClusterConfig().getConfig().isMaxCapacityProtectionEnabled());
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanEnableQuotaRebalance() {
    int expectedRouterCount = 100;
    controllerClient.enableQuotaRebalanced(false, expectedRouterCount);
    Assert.assertFalse(controllerClient.getRoutersClusterConfig().getConfig().isQuotaRebalanceEnabled());
    Assert.assertEquals(controllerClient.getRoutersClusterConfig().getConfig().getExpectedRouterCount(),
        expectedRouterCount);
    // Afte enable this feature, Venice don't need expected router count, because it will use the live router count, so could give any expected router count here.
    controllerClient.enableQuotaRebalanced(true, 0);
    Assert.assertTrue(controllerClient.getRoutersClusterConfig().getConfig().isQuotaRebalanceEnabled());
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanDiscoverCluster() {
    String storeName = "controllerClientCanDiscoverCluster";
    controllerClient.createNewStore(storeName, "test", "\"string\"", "\"string\"");
    Assert.assertEquals(
        ControllerClient.discoverCluster(venice.getMasterVeniceController().getControllerUrl(), storeName).getCluster(),
        venice.getClusterName(), "Should be able to find the cluster which the given store belongs to.");
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanUploadPushProperties() {
    String storeName = "controllerClientCanUploadPushProperties";
    int version = 1;
    Properties p = new Properties();
    for(int i=0;i<100;i++){
      p.setProperty(i+"venice.push.properteis.xyz"+i, "http://testinfo.url"+i);
    }
    controllerClient.uploadPushProperties(storeName, version, p);
  }

  @Test(timeOut = TIME_OUT)
  public void controllerClientCanUploadPushJobStatus() {
    String storeName = "controllerClientCanUploadJobStatus";
    int version = 1;
    PushJobStatusUploadResponse jobStatusUploadResponse= controllerClient.uploadPushJobStatus(storeName, version,
        PushJobStatus.SUCCESS, 1000, "test-push-id", "");
    // expected to fail because the push job status topic/store is not created (no parent controller in the cluster).
    Assert.assertEquals(jobStatusUploadResponse.isError(), true);
  }

  private void deleteStore(String storeName){
    controllerClient.enableStoreReadWrites(storeName, false);
    controllerClient.deleteStore(storeName);
  }
}

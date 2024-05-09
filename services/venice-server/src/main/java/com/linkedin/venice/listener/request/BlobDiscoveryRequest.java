package com.linkedin.venice.listener.request;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.read.RequestType;
import com.linkedin.venice.request.RequestHelper;
import io.netty.handler.codec.http.HttpRequest;


/**
 * {@code BlobDiscoveryRequest} encapsulates a GET request to blob_discovery/storename/version/partition
 * to find a node(s) with blobs
 */
public class BlobDiscoveryRequest extends RouterRequest {
  private final String storeName;
  private final int version;
  private final int partition;

  private BlobDiscoveryRequest(String storeName, int version, int partition, HttpRequest request) {
    super(storeName, request);
    this.storeName = storeName;
    this.version = version;
    this.partition = partition;
  }

  public static BlobDiscoveryRequest parseGetHttpRequest(HttpRequest request) {
    String uri = request.uri();
    String[] requestParts = RequestHelper.getRequestParts(uri);

    if (requestParts.length == 5) {
      // [0]""/[1]"action"/[2]"store name"/[3]"version"/[4]"partition number"
      String storeName = requestParts[2];
      int version = Integer.valueOf(requestParts[3]);
      int partition = Integer.valueOf(requestParts[4]);
      return new BlobDiscoveryRequest(storeName, version, partition, request);
    } else {
      throw new VeniceException("not a valid request for a BlobDiscoveryRequest action: " + uri);
    }
  }

  public String getStoreName() {
    return storeName;
  }

  public int getVersion() {
    return version;
  }

  public int getPartition() {
    return partition;
  }

  @Override
  public RequestType getRequestType() {
    return RequestType.BLOB_DISCOVERY_REQUEST;
  }

  @Override
  public int getKeyCount() {
    return 1;
  }
}

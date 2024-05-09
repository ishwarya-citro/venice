package com.linkedin.davinci.listener.response;

import java.util.List;


public class BlobDiscoveryResponse {
  private boolean isError;
  private List<String> availableNode;

  private String message;

  public BlobDiscoveryResponse() {
  }

  public void setAvailableNodes(List<String> nodeNames) {
    this.availableNode = nodeNames;
  }

  public List<String> getAvailableNodes() {
    return availableNode;
  }

  public void setError(boolean error) {
    this.isError = error;
  }

  public boolean isError() {
    return this.isError;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getMessage() {
    return this.message;
  }
}

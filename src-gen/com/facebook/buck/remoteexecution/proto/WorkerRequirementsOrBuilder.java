// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: src/com/facebook/buck/remoteexecution/proto/metadata.proto

package com.facebook.buck.remoteexecution.proto;

@javax.annotation.Generated(value="protoc", comments="annotations:WorkerRequirementsOrBuilder.java.pb.meta")
public interface WorkerRequirementsOrBuilder extends
    // @@protoc_insertion_point(interface_extends:facebook.remote_execution.WorkerRequirements)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.facebook.remote_execution.WorkerRequirements.WorkerSize worker_size = 1;</code>
   */
  int getWorkerSizeValue();
  /**
   * <code>.facebook.remote_execution.WorkerRequirements.WorkerSize worker_size = 1;</code>
   */
  com.facebook.buck.remoteexecution.proto.WorkerRequirements.WorkerSize getWorkerSize();

  /**
   * <code>.facebook.remote_execution.WorkerRequirements.WorkerPlatformType platform_type = 2;</code>
   */
  int getPlatformTypeValue();
  /**
   * <code>.facebook.remote_execution.WorkerRequirements.WorkerPlatformType platform_type = 2;</code>
   */
  com.facebook.buck.remoteexecution.proto.WorkerRequirements.WorkerPlatformType getPlatformType();
}

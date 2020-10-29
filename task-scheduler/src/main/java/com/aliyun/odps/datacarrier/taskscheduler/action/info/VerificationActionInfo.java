/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.odps.datacarrier.taskscheduler.action.info;

import java.util.LinkedList;
import java.util.List;

import com.aliyun.odps.datacarrier.taskscheduler.action.info.AbstractActionInfo;

public class VerificationActionInfo extends AbstractActionInfo {
  private List<List<String>> succeededPartitions = new LinkedList<>();
  private List<List<String>> failedPartitions = new LinkedList<>();
  Boolean passed;
  Boolean isPartitioned;

  public Boolean isPartitioned() {
    return isPartitioned;
  }

  public Boolean passed() {
    return passed;
  }

  public List<List<String>> getSucceededPartitions() {
    return succeededPartitions;
  }

  public List<List<String>> getFailedPartitions() {
    return failedPartitions;
  }

  public void setPassed(Boolean passed) {
    this.passed = passed;
  }

  public void setIsPartitioned(Boolean isPartitioned) {
    this.isPartitioned = isPartitioned;
  }

  public void setSucceededPartitions(List<List<String>> succeededPartitions) {
    this.succeededPartitions = succeededPartitions;
  }

  public void setFailedPartitions(List<List<String>> failedPartitions) {
    this.failedPartitions = failedPartitions;
  }

  @Override
  public String toString() {
    //TODO: to string
    return null;
  }
}

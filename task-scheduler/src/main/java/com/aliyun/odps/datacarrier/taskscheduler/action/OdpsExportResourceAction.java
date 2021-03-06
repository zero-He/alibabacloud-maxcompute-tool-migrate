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

package com.aliyun.odps.datacarrier.taskscheduler.action;

import com.aliyun.odps.FileResource;
import com.aliyun.odps.PartitionSpec;
import com.aliyun.odps.Resource;
import com.aliyun.odps.TableResource;
import com.aliyun.odps.datacarrier.taskscheduler.GsonUtils;
import com.aliyun.odps.datacarrier.taskscheduler.MmaException;
import com.aliyun.odps.datacarrier.taskscheduler.OdpsUtils;
import com.aliyun.odps.datacarrier.taskscheduler.OssUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;

import static com.aliyun.odps.datacarrier.taskscheduler.Constants.EXPORT_META_FILE_NAME;
import static com.aliyun.odps.datacarrier.taskscheduler.Constants.EXPORT_OBJECT_FILE_NAME;
import static com.aliyun.odps.datacarrier.taskscheduler.Constants.EXPORT_RESOURCE_FOLDER;

public class OdpsExportResourceAction extends OdpsNoSqlAction {
  private static final Logger LOG = LogManager.getLogger(OdpsExportResourceAction.class);

  private String taskName;
  private Resource resource;

  public OdpsExportResourceAction(String id, String taskName, Resource resource) {
    super(id);
    this.taskName = taskName;
    this.resource = resource;
  }

   @Override
  public void doAction() throws MmaException {
    try {
      if (StringUtils.isEmpty(resource.getName())) {
        LOG.error("Invalid resource name {} for task {}", resource.getName(), id);
        setProgress(ActionProgress.FAILED);
        return;
      }
      String tableName = null;
      String partitionSpec = null;
      if (Resource.Type.TABLE.equals(resource.getType())) {
        TableResource tableResource = (TableResource) resource;
        tableName = tableResource.getSourceTable().getName();
        PartitionSpec spec = tableResource.getSourceTablePartition();
        if (spec != null) {
          partitionSpec = spec.toString();
        }
      }
      OdpsResourceInfo resourceInfo =
          new OdpsResourceInfo(resource.getName(), resource.getType(), resource.getComment(), tableName, partitionSpec);
      String ossFileName = OssUtils.getOssPathToExportObject(taskName,
          EXPORT_RESOURCE_FOLDER,
          resource.getProject(),
          resource.getName(),
          EXPORT_META_FILE_NAME);
      OssUtils.createFile(ossFileName, GsonUtils.toJson(resourceInfo));
      if (!Resource.Type.TABLE.equals(resource.getType())) {
        FileResource fileResource = (FileResource) resource;
        InputStream inputStream = OdpsUtils.getInstance().resources()
            .getResourceAsStream(fileResource.getProject(), fileResource.getName());
        ossFileName = OssUtils.getOssPathToExportObject(taskName,
            EXPORT_RESOURCE_FOLDER,
            resource.getProject(),
            resource.getName(),
            EXPORT_OBJECT_FILE_NAME);
        OssUtils.createFile(ossFileName, inputStream);
      }
      setProgress(ActionProgress.SUCCEEDED);
    } catch (Exception e) {
      LOG.error("Action failed, actionId: {}, stack trace: {}",
                id, ExceptionUtils.getFullStackTrace(e));
      setProgress(ActionProgress.FAILED);
    }
  }

  @Override
  public String getName() {
    return "Resource exporting";
  }
}
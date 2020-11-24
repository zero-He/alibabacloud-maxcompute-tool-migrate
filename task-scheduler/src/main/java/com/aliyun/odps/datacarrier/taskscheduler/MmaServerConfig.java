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

package com.aliyun.odps.datacarrier.taskscheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MmaServerConfig {
  private static final Logger LOG = LogManager.getLogger(MmaServerConfig.class);

  private static final Map<String, String> DEFAULT_UI_CONFIG;
  public static final String MMA_SERVER_HOST = "MMA_SERVER_HOST";
  private static final String DEFAULT_MMA_SERVER_HOST_VALUE = "0.0.0.0";
  public static final String MMA_SERVER_PORT = "MMA_SERVER_PORT";
  private static final String DEFAULT_MMA_SERVER_PORT_VALUE = "18888";
  static {
    DEFAULT_UI_CONFIG = new HashMap<>();
    DEFAULT_UI_CONFIG.put(MMA_SERVER_HOST, DEFAULT_MMA_SERVER_HOST_VALUE);
    DEFAULT_UI_CONFIG.put(MMA_SERVER_PORT, DEFAULT_MMA_SERVER_PORT_VALUE);
  }

  private static MmaServerConfig instance;

  private DataSource dataSource;
  private MmaConfig.OssConfig ossConfig;
  private MmaConfig.HiveConfig hiveConfig;
  private MmaConfig.OdpsConfig odpsConfig;
  private MmaEventConfig eventConfig;
  private Map<String, String> resourceConfig;
  private Map<String, String> uiConfig;

  MmaServerConfig(DataSource dataSource,
                  MmaConfig.OssConfig ossConfig,
                  MmaConfig.HiveConfig hiveConfig,
                  MmaConfig.OdpsConfig odpsConfig,
                  MmaEventConfig eventConfig,
                  Map<String, String> resourceConfig,
                  Map<String, String> uiConfig) {
    this.dataSource = dataSource;
    this.ossConfig = ossConfig;
    this.hiveConfig = hiveConfig;
    this.odpsConfig = odpsConfig;
    this.eventConfig = eventConfig;
    this.resourceConfig = resourceConfig;
    this.uiConfig = uiConfig;
  }

  public DataSource getDataSource() {
    return dataSource;
  }

  public MmaConfig.OdpsConfig getOdpsConfig() {
    return odpsConfig;
  }

  public MmaConfig.HiveConfig getHiveConfig() {
    return hiveConfig;
  }

  public MmaConfig.OssConfig getOssConfig() {
    return ossConfig;
  }

  public MmaEventConfig getEventConfig() {
    return eventConfig;
  }

  public Map<String, String> getResourceConfig() {
    return resourceConfig;
  }

  public Map<String, String> getUIConfig() {
    if (uiConfig == null) {
      return DEFAULT_UI_CONFIG;
    }

    // Merge with default ui config, make sure necessary configurations exist
    Map<String, String> temp = new HashMap<>(DEFAULT_UI_CONFIG);
    temp.putAll(uiConfig);

    return temp;
  }

  public String toJson() {
    return GsonUtils.getFullConfigGson().toJson(this);
  }

  public boolean validate() {
    boolean valid = true;

    switch (this.dataSource) {
      case Hive:
        if (!this.hiveConfig.validate()) {
          valid = false;
          LOG.error("Validate MetaConfiguration failed due to {}", this.hiveConfig);
        }
        break;
      case OSS:
        if (!this.ossConfig.validate()) {
          valid = false;
          LOG.error("Validate MetaConfiguration failed due to {}", this.ossConfig);
        }
        break;
      case ODPS:
        break;
      default:
          throw new IllegalArgumentException("Unsupported datasource");
    }

    if (!odpsConfig.validate()) {
      valid = false;
      LOG.error("Validate MetaConfiguration failed due to {}", this.odpsConfig);
    }

    return valid;
  }

  public synchronized static void init(Path path) throws IOException {
    if (!path.toFile().exists()) {
      throw new IllegalArgumentException("File not found: " + path);
    }

    String content = DirUtils.readFile(path);
    MmaServerConfig mmaServerConfig =
        GsonUtils.getFullConfigGson().fromJson(content, MmaServerConfig.class);
    if (mmaServerConfig.validate()) {
      instance = mmaServerConfig;
    } else {
      throw new IllegalArgumentException(
          "Invalid MmaServerConfig, see mma/log/mma_server.LOG for detailed reason");
    }
  }

  public static MmaServerConfig getInstance() {
    if (instance == null) {
      throw new IllegalStateException("MmaServerConfig not initialized");
    }

    return instance;
  }
}

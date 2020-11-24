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

package com.aliyun.odps.datacarrier.taskscheduler.ui;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.servlet.ServletContextHandler;

import com.aliyun.odps.datacarrier.taskscheduler.ui.utils.JettyUtils;
import com.aliyun.odps.datacarrier.taskscheduler.ui.utils.JettyUtils.ServerInfo;

public abstract class WebUI {
  private int port;
  private String basePath;
  private ServerInfo serverInfo;
  private List<WebUITab> tabs = new LinkedList<>();
  private List<ServletContextHandler> handlers = new LinkedList<>();

  public WebUI(int port, String basePath) {
    this.port = port;
    this.basePath = Objects.requireNonNull(basePath);
  }

  public String getBasePath() {
    return basePath;
  }

  public List<WebUITab> getTabs() {
    return tabs;
  }

  public List<ServletContextHandler> getHandlers() {
    return handlers;
  }

  public void attachHandler(ServletContextHandler handler) {
    handlers.add(handler);
  }

  public void attachTab(WebUITab tab) {
    tab.getPages().forEach(this::attachPage);
    tabs.add(tab);
  }

  public void attachPage(WebUIPage page) {
    String pagePath = "/" + page.getPrefix();
    ServletContextHandler handler = JettyUtils.createServletHandler(pagePath, page, basePath);
    handlers.add(handler);
  }

  public void addStaticHandler(String resourceBase, String path) {
    attachHandler(JettyUtils.createStaticHandler(resourceBase, path));
  }

  public void bind(String host) {
    serverInfo = JettyUtils.startJettyServer(host, port, handlers);
  }

  public void stop() throws Exception {
    if (serverInfo != null) {
      serverInfo.stop();
    }
  }
}

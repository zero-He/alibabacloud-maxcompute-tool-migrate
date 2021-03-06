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

import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.aliyun.odps.datacarrier.taskscheduler.MmaException;
import com.aliyun.odps.datacarrier.taskscheduler.action.executor.ActionExecutorFactory;
import com.aliyun.odps.datacarrier.taskscheduler.action.info.HiveSqlActionInfo;

abstract class HiveSqlAction extends AbstractAction {

  private static final Logger LOG = LogManager.getLogger(HiveSqlAction.class);

  Future<List<List<String>>> future;

  public HiveSqlAction(String id) {
    super(id);
    actionInfo = new HiveSqlActionInfo();
  }

  @Override
  public void execute() throws MmaException {
    setProgress(ActionProgress.RUNNING);

    this.future = ActionExecutorFactory
        .getHiveSqlExecutor()
        .execute(getSql(), getSettings(), id, (HiveSqlActionInfo) actionInfo);
  }

  @Override
  public void afterExecution() throws MmaException {
    try {
      List<List<String>> result = future.get();
      setProgress(ActionProgress.SUCCEEDED);
      ((HiveSqlActionInfo) actionInfo).setResult(result);
    } catch (Exception e) {
      LOG.error("Action failed, actionId: {}, stack trace: {}",
                id,
                ExceptionUtils.getFullStackTrace(e));
      setProgress(ActionProgress.FAILED);
    }
  }

  @Override
  public boolean executionFinished() {
    if (ActionProgress.FAILED.equals(getProgress())
        || ActionProgress.SUCCEEDED.equals(getProgress())) {
      return true;
    }

    if (future == null) {
      throw new IllegalStateException("Action not executed, actionId: " + id);
    }

    return future.isDone();
  }

  @Override
  public void stop() {
    // TODO: try to stop hive sql
  }

  abstract String getSql();

  abstract Map<String, String> getSettings();
}

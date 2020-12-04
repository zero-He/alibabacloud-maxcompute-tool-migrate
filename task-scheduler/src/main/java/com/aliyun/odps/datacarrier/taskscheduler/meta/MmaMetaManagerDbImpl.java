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

package com.aliyun.odps.datacarrier.taskscheduler.meta;

import static com.aliyun.odps.datacarrier.taskscheduler.meta.MmaMetaManagerDbImplUtils.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.aliyun.odps.datacarrier.taskscheduler.Constants;
import com.aliyun.odps.datacarrier.taskscheduler.ExternalTableStorage;
import com.aliyun.odps.datacarrier.taskscheduler.GsonUtils;
import com.aliyun.odps.datacarrier.taskscheduler.MmaConfig;
import com.aliyun.odps.datacarrier.taskscheduler.MmaConfig.TableMigrationConfig;
import com.aliyun.odps.datacarrier.taskscheduler.MmaException;
import com.aliyun.odps.datacarrier.taskscheduler.MmaExceptionFactory;
import com.aliyun.odps.datacarrier.taskscheduler.meta.MetaSource.PartitionMetaModel;
import com.aliyun.odps.datacarrier.taskscheduler.meta.MmaMetaManagerDbImplUtils.RestoreTaskInfo;
import com.google.common.base.Strings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;


/**
 * This class implements {@link MmaMetaManager} using a H2 embedded database.
 */

public class MmaMetaManagerDbImpl implements MmaMetaManager {

  private static final Logger LOG = LogManager.getLogger(MmaMetaManagerDbImpl.class);

  private HikariDataSource ds;
  private MetaSource metaSource;

  public MmaMetaManagerDbImpl(Path parentDir, MetaSource metaSource, boolean needRecover)
      throws MmaException {
    if (parentDir == null) {
      // Ensure MMA_HOME is set
      String mmaHome = System.getenv("MMA_HOME");
      if (mmaHome == null) {
        throw new IllegalStateException("Environment variable 'MMA_HOME' not set");
      }
      parentDir = Paths.get(mmaHome);
    }

    this.metaSource = metaSource;

    LOG.info("Initialize MmaMetaManagerDbImpl");
    try {
      Class.forName("org.h2.Driver");
    } catch (ClassNotFoundException e) {
      LOG.error("H2 JDBC driver not found");
      throw new IllegalStateException("Class not found: org.h2.Driver");
    }

    LOG.info("Create connection pool");
    String connectionUrl =
        "jdbc:h2:file:" + Paths.get(parentDir.toString(), Constants.DB_FILE_NAME).toAbsolutePath() +
        ";AUTO_SERVER=TRUE";
    setupDatasource(connectionUrl);
    LOG.info("JDBC connection URL: {}", connectionUrl);

    LOG.info("Create connection pool done");

    LOG.info("Setup database");
    try (Connection conn = ds.getConnection()) {
      createMmaTableMeta(conn);
      createMmaRestoreTable(conn);
      removeActiveTasksFromRestoreTable(conn);
      createMmaTemporaryTable(conn);
      conn.commit();
    } catch (Throwable e) {
      throw new MmaException("Setting up database failed", e);
    }
    LOG.info("Setup database done");

    if (needRecover) {
      try {
        recover();
      } catch (Throwable e) {
        throw new IllegalStateException("Recover failed", e);
      }
    }
    LOG.info("Initialize MmaMetaManagerDbImpl done");
  }

  private void setupDatasource(String connectionUrl) {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(connectionUrl);
    hikariConfig.setUsername("mma");
    hikariConfig.setPassword("mma");
    hikariConfig.setAutoCommit(false);
    hikariConfig.setMaximumPoolSize(50);
    hikariConfig.setMinimumIdle(1);
    hikariConfig.setTransactionIsolation("TRANSACTION_SERIALIZABLE");
    ds = new HikariDataSource(hikariConfig);
  }

  @Override
  public void shutdown() {
    LOG.info("Enter shutdown");
    metaSource.shutdown();
    ds.close();
    LOG.info("Leave shutdown");
  }

  private void recover() throws SQLException, MmaException {
    LOG.info("Enter recover");
    // Change the statuses of running jobs to PENDING
    try (Connection conn = ds.getConnection()) {
      List<JobInfo> jobInfos = selectFromMmaTableMeta(conn, null, -1);

      for (JobInfo jobInfo : jobInfos) {
        if (MigrationStatus.RUNNING.equals(jobInfo.getStatus())) {
          updateStatusInternal(jobInfo.getDb(), jobInfo.getTbl(), MigrationStatus.PENDING);
        }

        if (jobInfo.isPartitioned()) {
          List<MigrationJobPtInfo> jobPtInfos = selectFromMmaPartitionMeta(conn,
                  jobInfo.getDb(),
                  jobInfo.getTbl(),
                  MigrationStatus.RUNNING,
                  -1);
          updateStatusInternal(jobInfo.getDb(),
                               jobInfo.getTbl(),
                               jobPtInfos
                                   .stream()
                                   .map(MigrationJobPtInfo::getPartitionValues)
                                   .collect(Collectors.toList()),
                               MigrationStatus.PENDING);
        }
      }

      conn.commit();
    }
    LOG.info("Leave recover");
  }

  @Override
  public synchronized void addMigrationJob(TableMigrationConfig config)
      throws MmaException {

    LOG.info("Enter addMigrationJob");

    if (config == null) {
      throw new IllegalArgumentException("'config' cannot be null");
    }

    String db = config.getSourceDataBaseName().toLowerCase();
    String tbl = config.getSourceTableName().toLowerCase();
    LOG.info("Add migration job, db: {}, tbl: {}", db, tbl);

    mergeJobInfoIntoMetaDB(
        db,
        tbl,
        true,
        MmaConfig.JobType.MIGRATION,
        TableMigrationConfig.toJson(config),
        config.getAdditionalTableConfig(),
        config.getPartitionValuesList(),
        config.getBeginPartition(),
        config.getEndPartition());
  }

  @Override
  public void addBackupJob(MmaConfig.ObjectExportConfig config) throws MmaException {
    String db = config.getDatabaseName().toLowerCase();
    String object = config.getObjectName().toLowerCase();

    LOG.info("Add backup job, db: {}, object: {}, type: {}",
             db, object, config.getObjectType().name());

    mergeJobInfoIntoMetaDB(
        db,
        object,
        MmaConfig.ObjectType.TABLE.equals(config.getObjectType()),
        MmaConfig.JobType.BACKUP,
        MmaConfig.ObjectExportConfig.toJson(config),
        config.getAdditionalTableConfig(),
        config.getPartitionValuesList(),
        null,
        null);
  }

  @Override
  public void addObjectRestoreJob(MmaConfig.ObjectRestoreConfig config) throws MmaException {
    String db = config.getOriginDatabaseName().toLowerCase();
    String object = config.getObjectName().toLowerCase();
    LOG.info("Add restore job, from {} to {}, object: {}, type: {}",
             config.getOriginDatabaseName(),
             config.getDestinationDatabaseName(),
             object,
             config.getObjectType().name());

    mergeJobInfoIntoMetaDB(
        db,
        object,
        false,
        MmaConfig.JobType.RESTORE,
        MmaConfig.ObjectRestoreConfig.toJson(config),
        config.getAdditionalTableConfig(),
        config.getPartitionValuesList(),
        null,
        null);
  }

  @Override
  public void addDatabaseRestoreJob(MmaConfig.DatabaseRestoreConfig config) throws MmaException {
    String db = config.getOriginDatabaseName().toLowerCase();
    LOG.info("Add restore database job, from {} to {}, types: {}",
        config.getOriginDatabaseName(), config.getDestinationDatabaseName(), config.getRestoreTypes());
    mergeJobInfoIntoMetaDB(
        db,
        "",
        false,
        MmaConfig.JobType.RESTORE,
        MmaConfig.DatabaseRestoreConfig.toJson(config),
        config.getAdditionalTableConfig(),
        null,
        null,
        null);
  }

  @Override
  public void mergeJobInfoIntoRestoreDB(RestoreTaskInfo taskInfo) throws MmaException {
    try (Connection conn = ds.getConnection()) {
      try {
        mergeIntoRestoreTableMeta(conn, taskInfo);
        conn.commit();
      } catch (Throwable e) {
        // Rollback
        if (conn != null) {
          try {
            conn.rollback();
          } catch (Throwable e2) {
            LOG.error("Add restore job rollback failed, task info {}",GsonUtils.getFullConfigGson().toJson(taskInfo));
          }
        }
        LOG.error(e);
        throw new MmaException("Merge job info to restore db fail: " + GsonUtils.getFullConfigGson().toJson(taskInfo), e);
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public void updateStatusInRestoreDB(RestoreTaskInfo taskInfo, MigrationStatus newStatus) throws MmaException {
    StringBuilder builder = new StringBuilder("WHERE ");
    builder.append(String.format("%s='%s'\n", Constants.MMA_OBJ_RESTORE_COL_UNIQUE_ID, taskInfo.getUniqueId()));
    builder.append(String.format("AND %s='%s'\n", Constants.MMA_OBJ_RESTORE_COL_TYPE, taskInfo.getType()));
    builder.append(String.format("AND %s='%s'\n", Constants.MMA_OBJ_RESTORE_COL_DB_NAME, taskInfo.getDb()));
    builder.append(String.format("AND %s='%s'\n", Constants.MMA_OBJ_RESTORE_COL_OBJECT_NAME, taskInfo.getObject()));
    List<RestoreTaskInfo> currentInfos = listRestoreJobs(builder.toString(), -1);
    if (currentInfos.isEmpty()) {
      throw new MmaException("Restore object task not found: " + GsonUtils.toJson(taskInfo));
    }
    RestoreTaskInfo currentInfo = currentInfos.get(0);
    switch (newStatus) {
      case SUCCEEDED:
        currentInfo.setStatus(newStatus);
        currentInfo.setAttemptTimes(currentInfo.getAttemptTimes() + 1);
        break;
      case FAILED:
        int attemptTimes = currentInfo.getAttemptTimes() + 1;
        int retryTimesLimit = currentInfo
            .getJobConfig()
            .getAdditionalTableConfig()
            .getRetryTimesLimit();
        if (attemptTimes <= retryTimesLimit) {
          newStatus = MigrationStatus.PENDING;
        }
        currentInfo.setStatus(newStatus);
        currentInfo.setAttemptTimes(attemptTimes);
        break;
      case RUNNING:
      case PENDING:
      default:
    }
    mergeJobInfoIntoRestoreDB(currentInfo);
  }

  private void mergeJobInfoIntoMetaDB(
      String db,
      String object,
      boolean isTable,
      MmaConfig.JobType type,
      String config,
      MmaConfig.AdditionalTableConfig additionalTableConfig,
      List<List<String>> partitionValuesList,
      List<String> beginPartition,
      List<String> endPartition) throws MmaException {

    try (Connection conn = ds.getConnection()) {
      try {
        JobInfo jobInfo = selectFromMmaTableMeta(conn, db, object);
        if (jobInfo != null
            && MigrationStatus.RUNNING.equals(jobInfo.getStatus())) {
          throw MmaExceptionFactory.getRunningJobExistsException(db, object);
        }

        if (isTable) {
          MetaSource.TableMetaModel tableMetaModel =
              metaSource.getTableMetaWithoutPartitionMeta(db, object);
          boolean isPartitioned = tableMetaModel.partitionColumns.size() > 0;

          mergeObjectInfoIntoMetaDB(
              db, object, type, config, additionalTableConfig, isPartitioned, conn);
          // Create or update mma partition meta
          // If partitions are specified, MMA will only create or update these partition. Else, MMA
          // will fetch all the partitions, then create meta for new partitions, reset meta for
          // failed partitions and modified succeeded partitions.
          // TODO: this behavior should be configurable
          if (isPartitioned) {
            createMmaPartitionMetaSchema(conn, db);
            createMmaPartitionMeta(conn, db, object);

            List<MigrationJobPtInfo> jobPtInfosToMerge = new LinkedList<>();

            if (partitionValuesList != null) {
              for (List<String> partitionValues : partitionValuesList) {
                if (!metaSource.hasPartition(db, object, partitionValues)) {
                  throw new MmaException("Partition not found: " + partitionValues);
                } else {
                  PartitionMetaModel partitionMetaModel =
                      metaSource.getPartitionMeta(db, object, partitionValues);
                  jobPtInfosToMerge.add(new MigrationJobPtInfo(
                      partitionValues,
                      MigrationStatus.PENDING,
                      Constants.MMA_PT_META_INIT_ATTEMPT_TIMES,
                      partitionMetaModel.lastModifiedTime));
                }
              }
            } else {
              List<MigrationJobPtInfo> jobPtInfos = MmaMetaManagerDbImplUtils
                  .selectFromMmaPartitionMeta(conn, db, object, null, -1);

              Comparator<List<String>> partitionComparator = (o1, o2) -> {
                int ret = 0;
                for (int i = 0; i < o1.size(); i++) {
                  if (o1.get(i).length() < o2.get(i).length()) {
                    ret = -1;
                  } else if (o1.get(i).length() > o2.get(i).length()) {
                    ret = 1;
                  } else {
                    ret = o1.get(i).compareTo(o2.get(i));
                  }
                  if (ret != 0) {
                    break;
                  }
                }
                return ret;
              };

              List<List<String>> totalPartitionValuesList = metaSource.listPartitions(db, object);

              if (beginPartition != null && endPartition != null) {
                if (partitionComparator.compare(beginPartition, endPartition) > 0) {
                  throw new IllegalArgumentException("Invalid start and end partition, start partition > end partition");
                }
              }

              if (beginPartition != null) {
                if (beginPartition.size() != tableMetaModel.partitionColumns.size()) {
                  throw new IllegalArgumentException("Invalid start partition, number of columns not matched");
                }
                totalPartitionValuesList =
                    totalPartitionValuesList.stream()
                                            .filter(list -> partitionComparator.compare(beginPartition, list) <= 0)
                                            .collect(Collectors.toList());
              }

              if (endPartition != null) {
                if (endPartition.size() != tableMetaModel.partitionColumns.size()) {
                  throw new IllegalArgumentException("Invalid end partition, number of columns not matched");
                }
                totalPartitionValuesList =
                    totalPartitionValuesList.stream()
                                            .filter(list -> partitionComparator.compare(endPartition, list) >= 0)
                                            .collect(Collectors.toList());
              }

              // Iterate over latest partition list and try to find partitions that should be
              // migrated
              for (List<String> partitionValues : totalPartitionValuesList) {
                MigrationJobPtInfo jobPtInfo = jobPtInfos
                    .stream()
                    .filter(info -> info.getPartitionValues().equals(partitionValues))
                    .findAny()
                    .orElse(null);

                PartitionMetaModel partitionMetaModel =
                    metaSource.getPartitionMeta(db, object, partitionValues);

                if (jobPtInfo == null
                    || MigrationStatus.FAILED.equals(jobPtInfo.getStatus())) {
                  if (jobPtInfo == null) {
                    LOG.info("Found new partition: {}", partitionValues);
                  } else {
                    LOG.info("Found failed partition: {}", partitionValues);
                  }
                  // New partition or failed partition
                  jobPtInfosToMerge.add(new MigrationJobPtInfo(
                      partitionValues,
                      MigrationStatus.PENDING,
                      Constants.MMA_PT_META_INIT_ATTEMPT_TIMES,
                      partitionMetaModel.lastModifiedTime));
                } else if (MigrationStatus.SUCCEEDED.equals(jobPtInfo.getStatus())){
                  // Modified partitions
                  if (partitionMetaModel.lastModifiedTime == null) {
                    LOG.warn("Failed to get last modified time of partition {}",
                             partitionValues);
                  } else if (partitionMetaModel.lastModifiedTime > jobPtInfo.getLastModifiedTime()) {
                    LOG.info("Found modified partition, {}, old mtime: {}, new mtime: {}",
                             partitionValues,
                             jobPtInfo.getLastModifiedTime(),
                             partitionMetaModel.lastModifiedTime);

                    jobPtInfosToMerge.add(new MigrationJobPtInfo(
                        partitionValues,
                        MigrationStatus.PENDING,
                        Constants.MMA_PT_META_INIT_ATTEMPT_TIMES,
                        partitionMetaModel.lastModifiedTime));
                  }
                }
              }
            }

            mergeIntoMmaPartitionMeta(conn, db, object, jobPtInfosToMerge);
          }
        } else {
          mergeObjectInfoIntoMetaDB(
              db, object, type, config, additionalTableConfig, false, conn);
        }
        conn.commit();
        LOG.info("Leave addMigrationJob");
      } catch (Throwable e) {
        // Rollback
        if (conn != null) {
          try {
            conn.rollback();
          } catch (Throwable e2) {
            LOG.error("Add migration job rollback failed, db: {}, object: {}", db, object);
          }
        }

        MmaException mmaException =
            MmaExceptionFactory.getFailedToAddMigrationJobException(db, object, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  private void mergeObjectInfoIntoMetaDB(String db,
                                         String object,
                                         MmaConfig.JobType type,
                                         String jobDescription,
                                         MmaConfig.AdditionalTableConfig additionalTableConfig,
                                         boolean isPartitioned,
                                         Connection conn) throws SQLException {
    MmaConfig.JobConfig jobConfig = new MmaConfig.JobConfig(
        db,
        object,
        type,
        jobDescription,
        additionalTableConfig);
    JobInfo jobInfo = new JobInfo(
        db,
        object,
        isPartitioned,
        jobConfig,
        MigrationStatus.PENDING,
        Constants.MMA_TBL_META_INIT_VALUE_ATTEMPT_TIMES,
        Constants.MMA_TBL_META_NA_VALUE_LAST_MODIFIED_TIME);

    mergeIntoMmaTableMeta(conn, jobInfo);
  }

  @Override
  public synchronized void removeMigrationJob(String db, String tbl) throws MmaException {

    LOG.info("Enter removeMigrationJob");

    if (db == null || tbl == null) {
      throw new IllegalArgumentException("'db' or 'tbl' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        JobInfo jobInfo = selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          return;
        } else {
          if (MigrationStatus.RUNNING.equals(getStatusInternal(db, tbl))) {
            // Restart running job is not allowed
            MmaException e = MmaExceptionFactory.getRunningJobExistsException(db, tbl);
            LOG.error(e);
            throw e;
          }
        }

        if (jobInfo.isPartitioned()) {
          dropMmaPartitionMeta(conn, db, tbl);
        }
        deleteFromMmaMeta(conn, db, tbl);

        conn.commit();
        LOG.info("Leave removeMigrationJob");
      } catch (Throwable e) {
        // Rollback
        if (conn != null) {
          try {
            conn.rollback();
          } catch (Throwable e2) {
            LOG.error("Remove migration job rollback failed, db: {}, tbl: {}", db, tbl);
          }
        }

        MmaException mmaException =
            MmaExceptionFactory.getFailedToRemoveMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized boolean hasMigrationJob(String db, String tbl) throws MmaException {
    return getMigrationJob(db, tbl) != null;
  }

  @Override
  public synchronized JobInfo getMigrationJob(String db, String tbl) throws MmaException {
    if (db == null || tbl == null) {
      throw new IllegalArgumentException("'db' or 'tbl' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        return selectFromMmaTableMeta(conn, db, tbl);
      } catch (Throwable e) {
        MmaException mmaException =
            MmaExceptionFactory.getFailedToGetMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }


  @Override
  public synchronized List<MmaConfig.JobConfig> listMigrationJobs(int limit)
      throws MmaException {

    return listMigrationJobsInternal(null, limit);
  }

  @Override
  public synchronized List<MmaConfig.JobConfig> listMigrationJobs(
      MigrationStatus status,
      int limit)
      throws MmaException {

    return listMigrationJobsInternal(status, limit);
  }

  @Override
  public synchronized List<RestoreTaskInfo> listRestoreJobs(String condition, int limit)
      throws MmaException {
    try (Connection conn = ds.getConnection()) {
      try {
        List<RestoreTaskInfo> taskInfos = selectFromRestoreMeta(conn, condition, limit);
        return taskInfos;
      } catch (Throwable e) {
        LOG.error(e);
        throw new MmaException("Failed to list restore jobs", e);
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized void removeRestoreJob(String uniqueId)
      throws MmaException {
    try (Connection conn = ds.getConnection()) {
      String query = null;
      try {
        query = String.format("DELETE FROM %s WHERE %s='%s'",
            Constants.MMA_OBJ_RESTORE_TBL_NAME,
            Constants.MMA_OBJ_RESTORE_COL_UNIQUE_ID,
            uniqueId);
        try (Statement stmt = conn.createStatement()) {
          stmt.execute(query);
          conn.commit();
        }
      } catch (Throwable e) {
        LOG.error(e);
        throw new MmaException("Failed to remove restore job: " + query, e);
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized Map<String, List<String>> listTemporaryTables(String condition, int limit) throws MmaException {
     try (Connection conn = ds.getConnection()) {
      try {
        Map<String, List<String>> result = selectFromTemporaryTableMeta(conn, condition, limit);
        LOG.info("Temporary tables to be dropped: {}", GsonUtils.toJson(result));
        return result;
      } catch (Throwable e) {
        LOG.error(e);
        throw new MmaException("Failed to list restore jobs", e);
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public void mergeTableInfoIntoTemporaryTableDB(String uniqueId, String db, String tbl) {
    try (Connection conn = ds.getConnection()) {
      try {
        mergeIntoTemporaryTableMeta(conn, uniqueId, db, tbl);
        conn.commit();
      } catch (Throwable e) {
        // Rollback
        if (conn != null) {
          try {
            conn.rollback();
          } catch (Throwable e2) {
            LOG.error("Add temporary table job rollback failed, db {}, tbl {}", db, tbl);
          }
        }
        LOG.error("Merge into temporary table failed, uniqueId: {}, db: {}, tbl: {}",
            uniqueId, db, tbl, e);
      }
    } catch (SQLException e) {
      LOG.error("Merge into temporary table failed, uniqueId: {}, db: {}, tbl: {}",
            uniqueId, db, tbl, e);
    }
  }

  @Override
  public synchronized void removeTemporaryTableMeta(String uniqueId, String db, String tbl) throws MmaException {
    try (Connection conn = ds.getConnection()) {
      String query = null;
      try {
        query = String.format("DELETE FROM %s WHERE %s='%s' AND %s='%s' AND %s='%s';\n",
            Constants.MMA_OBJ_TEMPORARY_TBL_NAME,
            Constants.MMA_OBJ_TEMPORARY_COL_UNIQUE_ID,
            uniqueId,
            Constants.MMA_OBJ_TEMPORARY_COL_PROJECT,
            db,
            Constants.MMA_OBJ_TEMPORARY_COL_TABLE,
            tbl);
        LOG.info("Execute query: {}", query);
        try (Statement stmt = conn.createStatement()) {
          stmt.execute(query);
          conn.commit();
        }
      } catch (Throwable e) {
        LOG.error(e);
        throw new MmaException("Failed to remove temporary table: " + query, e);
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  private List<MmaConfig.JobConfig> listMigrationJobsInternal(
      MigrationStatus status,
      int limit)
      throws MmaException {

    try (Connection conn = ds.getConnection()) {
      try {
        List<JobInfo> jobInfos = selectFromMmaTableMeta(conn, status, limit);
        List<MmaConfig.JobConfig> migrationConfigs = new LinkedList<>();

        for (JobInfo jobInfo : jobInfos) {
          if (status == null) {
            migrationConfigs.add(jobInfo.getJobConfig());
          } else {
            if (jobInfo.isPartitioned()) {
              String db = jobInfo.getDb();
              String tbl = jobInfo.getTbl();
              MigrationStatus realStatus =
                  inferPartitionedTableStatus(conn, db, tbl);
              if (status.equals(realStatus)) {
                migrationConfigs.add(jobInfo.getJobConfig());
              }
            } else {
              if (status.equals(jobInfo.getStatus())) {
                migrationConfigs.add(jobInfo.getJobConfig());
              }
            }
          }
        }

        return migrationConfigs;
      } catch (Throwable e) {
        MmaException mmaException = MmaExceptionFactory.getFailedToListMigrationJobsException(e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized void updateStatus(String db, String tbl, MigrationStatus status)
      throws MmaException {
    LOG.info("Enter updateStatus");

    if (db == null || tbl == null || status == null) {
      throw new IllegalArgumentException("'db' or 'tbl' or 'status' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    updateStatusInternal(db, tbl, status);
    LOG.info("Leave updateStatus");
  }

  private void updateStatusInternal(String db, String tbl, MigrationStatus status)
      throws MmaException {
    try (Connection conn = ds.getConnection()) {
      try {
        JobInfo jobInfo = selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          throw MmaExceptionFactory.getMigrationJobNotExistedException(db, tbl);
        }

        jobInfo.setStatus(status);
        // For a partitioned table, its migration status is inferred from its partitions' migration
        // statuses. And it does not have table level attr 'attemptTimes'.
        if (!jobInfo.isPartitioned()) {
          switch (status) {
            case SUCCEEDED: {
              jobInfo.setAttemptTimes(jobInfo.getAttemptTimes() + 1);
              break;
            }
            case FAILED: {
              int attemptTimes = jobInfo.getAttemptTimes() + 1;
              int retryTimesLimit = jobInfo
                  .getJobConfig()
                  .getAdditionalTableConfig()
                  .getRetryTimesLimit();
              if (attemptTimes <= retryTimesLimit) {
                status = MigrationStatus.PENDING;
              }
              jobInfo.setStatus(status);
              jobInfo.setAttemptTimes(attemptTimes);
              break;
            }
            case RUNNING:
            case PENDING:
            default:
          }
        }
        mergeIntoMmaTableMeta(conn, jobInfo);

        conn.commit();
      } catch (Throwable e) {
        // Rollback
        if (conn != null) {
          try {
            conn.rollback();
          } catch (Throwable e2) {
            LOG.error("Update migration job rollback failed, db: {}, tbl: {}", db, tbl);
          }
        }

        MmaException mmaException =
            MmaExceptionFactory.getFailedToUpdateMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized void updateStatus(
      String db,
      String tbl,
      List<List<String>> partitionValuesList,
      MigrationStatus status)
      throws MmaException {
    LOG.info("Enter updateStatus");

    if (db == null || tbl == null || partitionValuesList == null || status == null) {
      throw new IllegalArgumentException(
          "'db' or 'tbl' or 'partitionValuesList' or 'status' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    updateStatusInternal(db, tbl, partitionValuesList, status);
    LOG.info("Leave updateStatus");
  }

  private void updateStatusInternal(
      String db,
      String tbl,
      List<List<String>> partitionValuesList,
      MigrationStatus status)
      throws MmaException {
    try (Connection conn = ds.getConnection()) {
      try {
        JobInfo jobInfo = selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          throw MmaExceptionFactory.getMigrationJobNotExistedException(db, tbl);
        }

        List<MigrationJobPtInfo> newJobPtInfos = new LinkedList<>();
        for (List<String> partitionValues : partitionValuesList) {
          MigrationJobPtInfo jobPtInfo =
              selectFromMmaPartitionMeta(conn, db, tbl, partitionValues);
          if (jobPtInfo == null) {
            throw MmaExceptionFactory
                .getMigrationJobPtNotExistedException(db, tbl, partitionValues);
          }

          jobPtInfo.setStatus(status);
          switch (status) {
            case SUCCEEDED: {
              jobPtInfo.setAttemptTimes(jobPtInfo.getAttemptTimes() + 1);
              break;
            }
            case FAILED: {
              int attemptTimes = jobPtInfo.getAttemptTimes() + 1;
              int retryTimesLimit = jobInfo
                  .getJobConfig()
                  .getAdditionalTableConfig()
                  .getRetryTimesLimit();
              jobPtInfo.setStatus(status);
              if (attemptTimes <= retryTimesLimit) {
                jobPtInfo.setStatus(MigrationStatus.PENDING);
              }
              jobPtInfo.setAttemptTimes(attemptTimes);
              break;
            }
            case RUNNING:
            case PENDING:
            default:
          }

          newJobPtInfos.add(jobPtInfo);
        }
        mergeIntoMmaPartitionMeta(conn, db, tbl, newJobPtInfos);

        // Update the table level status
        MigrationStatus newStatus = inferPartitionedTableStatus(conn, db, tbl);
        if (!jobInfo.getStatus().equals(newStatus)) {
          updateStatusInternal(db, tbl, newStatus);
        }

        conn.commit();
      } catch (Throwable e) {
        // Rollback
        if (conn != null) {
          try {
            conn.rollback();
          } catch (Throwable e2) {
            LOG.error("Update migration job pt rollback failed, db: {}, tbl: {}", db, tbl);
          }
        }

        MmaException mmaException =
            MmaExceptionFactory.getFailedToUpdateMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized MigrationStatus getStatus(String db, String tbl) throws MmaException {
    LOG.info("Enter getStatus");

    if (db == null || tbl == null) {
      throw new IllegalArgumentException("'db' or 'tbl' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    return getStatusInternal(db, tbl);
  }

  private MigrationStatus getStatusInternal(String db, String tbl) throws MmaException {
    try (Connection conn = ds.getConnection()) {
      try {
        JobInfo jobInfo = selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          throw MmaExceptionFactory.getMigrationJobNotExistedException(db, tbl);
        }

        if (jobInfo.isPartitioned()) {
          return inferPartitionedTableStatus(conn, jobInfo.getDb(), jobInfo.getTbl());
        } else {
          return jobInfo.getStatus();
        }
      } catch (Throwable e) {
        MmaException mmaException =
            MmaExceptionFactory.getFailedToGetMigrationJobException(db, tbl, e);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized MigrationStatus getStatus(String db, String tbl, List<String> partitionValues)
      throws MmaException {
    LOG.info("Enter getStatus");

    if (db == null || tbl == null || partitionValues == null) {
      throw new IllegalArgumentException("'db' or 'tbl' or 'partitionValues' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        MigrationJobPtInfo jobPtInfo =
            selectFromMmaPartitionMeta(conn, db, tbl, partitionValues);
        if (jobPtInfo == null) {
          throw MmaExceptionFactory.getMigrationJobPtNotExistedException(db, tbl, partitionValues);
        }
        return jobPtInfo.getStatus();
      } catch (Throwable e) {
        MmaException mmaException =
            MmaExceptionFactory.getFailedToGetMigrationJobPtException(db, tbl, partitionValues);
        LOG.error(e);
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized MigrationProgress getProgress(String db, String tbl) throws MmaException {
    LOG.info("Enter getProgress");

    if (db == null || tbl == null) {
      throw new IllegalArgumentException("'db' or 'tbl' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        JobInfo jobInfo = selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          throw MmaExceptionFactory.getMigrationJobNotExistedException(db, tbl);
        }

        if (!jobInfo.isPartitioned()) {
          return null;
        }

        Map<MigrationStatus, Integer> statusDistribution =
            getPartitionStatusDistribution(conn, db, tbl);
        int pending = statusDistribution.getOrDefault(MigrationStatus.PENDING, 0);
        int running = statusDistribution.getOrDefault(MigrationStatus.RUNNING, 0);
        int succeeded = statusDistribution.getOrDefault(MigrationStatus.SUCCEEDED, 0);
        int failed = statusDistribution.getOrDefault(MigrationStatus.FAILED, 0);

        return new MigrationProgress(pending, running, succeeded, failed);
      } catch (Throwable e) {
        return null;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized MmaConfig.JobConfig getConfig(String db, String tbl)
      throws MmaException {
    LOG.info("Enter getConfig");

    if (db == null || tbl == null) {
      throw new IllegalArgumentException("'db' or 'tbl' cannot be null");
    }

    db = db.toLowerCase();
    tbl = tbl.toLowerCase();

    try (Connection conn = ds.getConnection()) {
      try {
        JobInfo jobInfo = selectFromMmaTableMeta(conn, db, tbl);
        if (jobInfo == null) {
          throw MmaExceptionFactory.getMigrationJobNotExistedException(db, tbl);
        }
        return jobInfo.getJobConfig();
      } catch (Throwable e) {
        MmaException mmaException =
            MmaExceptionFactory.getFailedToGetMigrationJobException(db, tbl, e);
        LOG.error(ExceptionUtils.getStackTrace(e));
        throw mmaException;
      }
    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    }
  }

  @Override
  public synchronized List<MetaSource.TableMetaModel> getPendingTables() throws MmaException {
    LOG.info("Enter getPendingTables");

    try (Connection conn = ds.getConnection()) {
      List<JobInfo> jobInfos =
          selectFromMmaTableMeta(conn, MigrationStatus.PENDING, -1);
      List<MetaSource.TableMetaModel> ret = new LinkedList<>();
      for (JobInfo jobInfo : jobInfos) {
        String db = jobInfo.getDb();
        String tbl = jobInfo.getTbl();

        MetaSource.TableMetaModel tableMetaModel;
        try {
          MmaConfig.JobType jobType = jobInfo.getJobConfig().getJobType();
          if (MmaConfig.JobType.BACKUP.equals(jobType)) {
            MmaConfig.ObjectExportConfig config =
                MmaConfig.ObjectExportConfig.fromJson(jobInfo.getJobConfig().getDescription());
            if (!MmaConfig.ObjectType.TABLE.equals(config.getObjectType())) {
              tableMetaModel = new MetaSource.TableMetaModel();
              tableMetaModel.databaseName = config.getDatabaseName();
              tableMetaModel.tableName = config.getObjectName();
              ret.add(tableMetaModel);
              continue;
            }
          } else if (MmaConfig.JobType.RESTORE.equals(jobType)) {
            tableMetaModel = new MetaSource.TableMetaModel();
            if (Strings.isNullOrEmpty(tbl)) {
              MmaConfig.DatabaseRestoreConfig config =
                  MmaConfig.DatabaseRestoreConfig.fromJson(jobInfo.getJobConfig().getDescription());
              tableMetaModel.databaseName = config.getOriginDatabaseName();
              tableMetaModel.odpsProjectName = config.getDestinationDatabaseName();
              tableMetaModel.tableName = tbl;
            } else {
              MmaConfig.ObjectRestoreConfig config =
                  MmaConfig.ObjectRestoreConfig.fromJson(jobInfo.getJobConfig().getDescription());
              tableMetaModel.databaseName = config.getOriginDatabaseName();
              tableMetaModel.odpsProjectName = config.getDestinationDatabaseName();
              tableMetaModel.tableName = config.getObjectName();
              tableMetaModel.odpsTableName = config.getObjectName();
            }
            ret.add(tableMetaModel);
            continue;
          }
          tableMetaModel = metaSource.getTableMetaWithoutPartitionMeta(db, tbl);
        } catch (Exception e) {
          // Table could be deleted after the task is submitted. In this case,
          // metaSource.getTableMetaWithoutPartitionMeta# will fail.
          LOG.warn("Failed to get metadata, db: {}, tbl: {}", db, tbl, e);
          updateStatusInternal(db, tbl, MigrationStatus.FAILED);
          // TODO: Should throw MMA meta exception here and stop the task scheduler
          continue;
        }

        if (jobInfo.isPartitioned()) {
          List<MigrationJobPtInfo> jobPtInfos = selectFromMmaPartitionMeta(conn,
                  db,
                  tbl,
                  MigrationStatus.PENDING,
                  -1);

          List<MetaSource.PartitionMetaModel> partitionMetaModels = new LinkedList<>();
          for (MigrationJobPtInfo jobPtInfo : jobPtInfos) {
            try {
              partitionMetaModels.add(
                  metaSource.getPartitionMeta(db, tbl, jobPtInfo.getPartitionValues()));
            } catch (Exception e) {
              // Partitions could be deleted after the task is submitted. In this case,
              // metaSource.getPartitionMeta# will fail.
              LOG.warn("Failed to get metadata, db: {}, tbl: {}, pt: {}",
                       db, tbl, jobPtInfo.getPartitionValues());
              updateStatusInternal(
                  db,
                  tbl,
                  Collections.singletonList(jobPtInfo.getPartitionValues()),
                  MigrationStatus.FAILED);
              // TODO: Should throw MMA meta exception here and stop the task scheduler
            }
          }
          tableMetaModel.partitions = partitionMetaModels;
        }

        if (MmaConfig.JobType.MIGRATION.equals(jobInfo.getJobConfig().getJobType())) {
          TableMigrationConfig tableMigrationConfig =
              TableMigrationConfig.fromJson(jobInfo.getJobConfig().getDescription());
          tableMigrationConfig.apply(tableMetaModel);
        } else if (MmaConfig.JobType.BACKUP.equals(jobInfo.getJobConfig().getJobType())) {
          MmaConfig.ObjectExportConfig objectExportConfig =
              MmaConfig.ObjectExportConfig.fromJson(jobInfo.getJobConfig().getDescription());
          objectExportConfig.setDestTableStorage(ExternalTableStorage.OSS.name());
          objectExportConfig.apply(tableMetaModel);
          tableMetaModel.odpsProjectName = objectExportConfig.getDatabaseName();
          tableMetaModel.odpsTableName = Constants.MMA_TEMPORARY_TABLE_PREFIX
              + objectExportConfig.getObjectName() + "_"
              + objectExportConfig.getTaskName();
        }
        ret.add(tableMetaModel);
      }

      // Sort by name, make it easy to test
      ret.sort(Comparator.comparing(a -> (a.databaseName + a.tableName)));
      return ret;

    } catch (SQLException e) {
      throw MmaExceptionFactory.getFailedToCreateConnectionException(e);
    } catch (Throwable e) {
      MmaException mmaException = MmaExceptionFactory.getFailedToGetPendingJobsException(e);
      LOG.error(e);
      throw mmaException;
    }
  }

  @Override
  public synchronized MetaSource.TableMetaModel getNextPendingTable() {
    throw new UnsupportedOperationException();
  }
}

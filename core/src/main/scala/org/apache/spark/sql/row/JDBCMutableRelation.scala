/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package org.apache.spark.sql.row

import java.sql.Connection

import io.snappydata.SnappyTableStatsProviderService

import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.SortDirection
import org.apache.spark.sql.collection.Utils
import org.apache.spark.sql.execution.columnar.ExternalStoreUtils
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.execution.datasources.jdbc._
import org.apache.spark.sql.execution.row.RowDMLExec
import org.apache.spark.sql.execution.{ConnectionPool, SparkPlan}
import org.apache.spark.sql.hive.{QualifiedTableName, SnappyStoreHiveCatalog}
import org.apache.spark.sql.jdbc.JdbcDialect
import org.apache.spark.sql.sources._
import org.apache.spark.sql.store.CodeGeneration
import org.apache.spark.sql.types._
import org.apache.spark.{Logging, Partition}

/**
 * A LogicalPlan implementation for an external row table whose contents
 * are retrieved using a JDBC URL or DataSource.
 */
case class JDBCMutableRelation(
    connProperties: ConnectionProperties,
    table: String,
    provider: String,
    mode: SaveMode,
    userSpecifiedString: String,
    parts: Array[Partition],
    origOptions: Map[String, String],
    @transient override val sqlContext: SQLContext)
    extends BaseRelation
    with PrunedUnsafeFilteredScan
    with InsertableRelation
    with PlanInsertableRelation
    with RowInsertableRelation
    with UpdatableRelation
    with DeletableRelation
    with DestroyRelation
    with IndexableRelation
    with Logging {

  override val needConversion: Boolean = false

  override def sizeInBytes: Long = {
    SnappyTableStatsProviderService.getService.getTableStatsFromService(table) match {
      case Some(s) => s.getTotalSize
      case None => super.sizeInBytes
    }
  }

  val driver: String = Utils.registerDriverUrl(connProperties.url)

  protected final def dialect: JdbcDialect = connProperties.dialect

  // create table in external store once upfront
  var tableSchema: String = _

  override final lazy val schema: StructType = JDBCRDD.resolveTable(
    connProperties.url + connProperties.urlSecureSuffix, table, connProperties.connProps)

  var tableExists: Boolean = _

  final lazy val schemaFields: Map[String, StructField] =
    Utils.schemaFields(schema)

  override def unhandledFilters(filters: Array[Filter]): Array[Filter] =
    filters.filter(ExternalStoreUtils.unhandledFilter)

  protected final val connFactory: () => Connection = JdbcUtils
      .createConnectionFactory(connProperties.url + connProperties.urlSecureSuffix,
        connProperties.connProps)

  protected final val sysConnFactory: () => Connection = {
    val url = connProperties.url +
        ";user=" + SnappyStoreHiveCatalog.HIVE_METASTORE +
        ";password=" + SnappyStoreHiveCatalog.HIVE_METASTORE
    JdbcUtils.createConnectionFactory(url, connProperties.connProps)
  }

  def createTable(mode: SaveMode): String = {
    var conn: Connection = null
    var tableSchema: String = ""
      try {
      conn = connFactory()
      tableExists = JdbcExtendedUtils.tableExists(table, conn,
        dialect, sqlContext)
      tableSchema = conn.getSchema
      if (mode == SaveMode.Ignore && tableExists) {
//        dialect match {
//          case d: JdbcExtendedDialect => d.initializeTable(table,
//            sqlContext.conf.caseSensitiveAnalysis, conn)
//          case _ => // Do Nothing
//        }
        return tableSchema
      }

      // We should not throw table already exists from here. This is expected
      // as new relation objects are created on each invocation of DDL.
      // We should silently ignore it. Or else we have to take care of all
      // SaveMode in top level APIs, which will be cumbersome as we take
      // actions based on dialects e.g. for SaveMode.Overwrite we truncate
      // rather than dropping the table. ErrorIfExist should be checked from
      // top level APIs like session.createTable, which we already do.
      // if (mode == SaveMode.ErrorIfExists && tableExists) {
      //  sys.error(s"Table $table already exists.")
      // }

      if (mode == SaveMode.Overwrite && tableExists) {
        // truncate the table if possible
        val truncate = dialect match {
          case d: JdbcExtendedDialect => d.truncateTable(table)
          case _ => s"TRUNCATE TABLE $table"
        }
        JdbcExtendedUtils.executeUpdate(truncate, conn)
      }

      // Create the table if the table didn't exist.
      if (!tableExists) {
        val sql = s"CREATE TABLE $table $userSpecifiedString"
        logInfo(s"Applying DDL (url=$connProperties.url; " +
            s"props=${connProperties.connProps}): $sql")
        JdbcExtendedUtils.executeUpdate(sql, conn)
        dialect match {
          case d: JdbcExtendedDialect => d.initializeTable(table,
            sqlContext.conf.caseSensitiveAnalysis, conn, sysConnFactory())
          case _ => // Do Nothing
        }
      }
    } catch {
      case sqle: java.sql.SQLException =>
        if (sqle.getMessage.contains("No suitable driver found")) {
          throw new AnalysisException(s"${sqle.getMessage}\n" +
              "Ensure that the 'driver' option is set appropriately and " +
              "the driver jars available (--jars option in spark-submit).")
        } else {
          throw sqle
        }
    } finally {
      if (conn != null) {
        conn.commit()
        conn.close()
      }
    }
    tableSchema
  }

  override def buildUnsafeScan(requiredColumns: Array[String],
      filters: Array[Filter]): (RDD[Any], Seq[RDD[InternalRow]]) = {
    val rdd = JDBCRDD.scanTable(
      sqlContext.sparkContext,
      schema,
      connProperties.url + connProperties.urlSecureSuffix,
      connProperties.executorConnProps,
      table,
      requiredColumns,
      filters.filterNot(ExternalStoreUtils.unhandledFilter),
      parts).asInstanceOf[RDD[Any]]
    (rdd, Nil)
  }

  final lazy val rowInsertStr: String = JdbcExtendedUtils.getInsertOrPutString(
    table, schema, putInto = false)

  override def getInsertPlan(relation: LogicalRelation,
      child: SparkPlan): SparkPlan = {
    RowDMLExec(child, putInto = false, delete = false, Seq.empty, Seq.empty, -1,
      schema, Some(this), onExecutor = false, table, connProperties)
  }

  override def getDeletePlan(relation: LogicalRelation,
      child: SparkPlan): SparkPlan = {
    RowDMLExec(child, putInto = false, delete = true, Seq.empty, Seq.empty, -1,
      schema, Some(this), onExecutor = false, table, connProperties)
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    // use the normal DataFrameWriter which will create an InsertIntoTable plan
    // that will use the getInsertPlan above (in StoreStrategy)
    data.write.mode(if (overwrite) SaveMode.Overwrite else SaveMode.Append)
        .insertInto(table)
  }

  override def insert(rows: Seq[Row]): Int = {
    val numRows = rows.length
    if (numRows == 0) {
      throw new IllegalArgumentException(
        "JDBCUpdatableRelation.insert: no rows provided")
    }
    val connProps = connProperties.connProps
    val batchSize = connProps.getProperty("batchsize", "1000").toInt
    // use bulk insert using insert plan for large number of rows
    if (numRows > (batchSize * 4)) {
      JdbcExtendedUtils.bulkInsertOrPut(rows, sqlContext.sparkSession, schema,
        table, upsert = false)
    } else {
      val connection = ConnectionPool.getPoolConnection(table, dialect,
        connProperties.poolProps, connProps, connProperties.hikariCP, connProperties.urlSecureSuffix)
      try {
        val stmt = connection.prepareStatement(rowInsertStr)
        val result = CodeGeneration.executeUpdate(table, stmt,
          rows, numRows > 1, batchSize, schema.fields, dialect)
        stmt.close()
        result
      } finally {
        connection.commit()
        connection.close()
      }
    }
  }

  override def executeUpdate(sql: String): Int = {
    val connection = ConnectionPool.getPoolConnection(table, dialect,
      connProperties.poolProps, connProperties.connProps,
      connProperties.hikariCP, connProperties.urlSecureSuffix)
    try {
      val stmt = connection.prepareStatement(sql)
      val result = stmt.executeUpdate()
      stmt.close()
      result
    } finally {
      connection.commit()
      connection.close()
    }
  }

  override def update(filterExpr: String, newColumnValues: Row,
      updateColumns: Seq[String]): Int = {
    val ncols = updateColumns.length
    if (ncols == 0) {
      throw new IllegalArgumentException(
        "JDBCUpdatableRelation.update: no columns provided")
    }
    val setFields = new Array[StructField](ncols)
    var index = 0
    // not using loop over index below because incoming Seq[...]
    // may not have efficient index lookup
    updateColumns.foreach { c =>
      setFields(index) = schemaFields.getOrElse(c, throw new AnalysisException(
          "JDBCUpdatableRelation: Cannot resolve column name " +
              s""""$c" among (${schema.fieldNames.mkString(", ")})"""))
      index += 1
    }
    val connection = ConnectionPool.getPoolConnection(table, dialect,
      connProperties.poolProps, connProperties.connProps,
      connProperties.hikariCP, connProperties.urlSecureSuffix)
    try {
      val setStr = updateColumns.mkString("SET ", "=?, ", "=?")
      val whereStr = if (filterExpr == null || filterExpr.isEmpty) ""
      else " WHERE " + filterExpr
      val updateSql = s"UPDATE $table $setStr$whereStr"
      val stmt = connection.prepareStatement(updateSql)
      val result = CodeGeneration.executeUpdate(updateSql, stmt,
        newColumnValues, setFields, dialect)
      stmt.close()
      result
    } finally {
      connection.commit()
      connection.close()
    }
  }

  override def delete(filterExpr: String): Int = {
    val connection = ConnectionPool.getPoolConnection(table, dialect,
      connProperties.poolProps, connProperties.connProps,
      connProperties.hikariCP, connProperties.urlSecureSuffix)
    try {
      val whereStr =
        if (filterExpr == null || filterExpr.isEmpty) ""
        else "WHERE " + filterExpr
      val stmt = connection.prepareStatement(s"DELETE FROM $table $whereStr")
      val result = stmt.executeUpdate()
      stmt.close()
      result
    } finally {
      connection.commit()
      connection.close()
    }
  }

  override def destroy(ifExists: Boolean): Unit = {
    // drop the external table using a non-pool connection
    val conn = connFactory()
    try {
      // clean up the connection pool and caches
      ExternalStoreUtils.removeCachedObjects(sqlContext, table)
    } finally {
      try {
        JdbcExtendedUtils.dropTable(conn, table, dialect, sqlContext, ifExists)
      } finally {
        conn.commit()
        conn.close()
      }
    }
  }

  def truncate(): Unit = {
    val conn = connFactory()
    try {
      JdbcExtendedUtils.truncateTable(conn, table, dialect)
    }
    finally {
      conn.commit()
      conn.close()
    }
  }

  protected def constructSQL(indexName: String,
      baseTable: String,
      indexColumns: Map[String, Option[SortDirection]],
      options: Map[String, String]): String = {

    ""
  }

  override def createIndex(indexIdent: QualifiedTableName,
      tableIdent: QualifiedTableName,
      indexColumns: Map[String, Option[SortDirection]],
      options: Map[String, String]): Unit = {
    val conn = connFactory()
    try {
      val tableExists = JdbcExtendedUtils.tableExists(tableIdent.toString(),
        conn, dialect, sqlContext)

      val sql = constructSQL(indexIdent.toString(), tableIdent.toString(),
        indexColumns, options)

      // Create the Index if the table exists.
      if (tableExists) {
        JdbcExtendedUtils.executeUpdate(sql, conn)
      } else {
        throw new AnalysisException(s"Base table $table does not exist.")
      }
    } catch {
      case se: java.sql.SQLException =>
        if (se.getMessage.contains("No suitable driver found")) {
          throw new AnalysisException(s"${se.getMessage}\n" +
              "Ensure that the 'driver' option is set appropriately and " +
              "the driver jars available (--jars option in spark-submit).")
        } else {
          throw se
        }
    } finally {
      conn.commit()
      conn.close()
    }
  }

  override def dropIndex(indexIdent: QualifiedTableName,
      tableIdent: QualifiedTableName,
      ifExists: Boolean): Unit = {
    throw new UnsupportedOperationException()
  }
}

final class DefaultSource extends MutableRelationProvider

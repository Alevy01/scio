/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package com.spotify.scio.bigquery

import java.util.regex.Pattern

import com.google.api.services.bigquery.model.TableReference
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO

private[bigquery] object BigQueryPartitionUtil {

  // Ported from com.google.cloud.dataflow.sdk.io.BigQueryIO

  private val PROJECT_ID_REGEXP = "[a-z][-a-z0-9:.]{4,61}[a-z0-9]"
  private val DATASET_REGEXP = "[-\\w.]{1,1024}"
  private val TABLE_REGEXP = "[-\\w$@]{1,1024}($LATEST)?"
  private val DATASET_TABLE_REGEXP_LEGACY =
    s"((?<PROJECT>$PROJECT_ID_REGEXP):)?(?<DATASET>$DATASET_REGEXP)\\.(?<TABLE>$TABLE_REGEXP)"
  private val DATASET_TABLE_REGEXP_STANDARD =
    s"((?<PROJECT>$PROJECT_ID_REGEXP).)?(?<DATASET>$DATASET_REGEXP)\\.(?<TABLE>$TABLE_REGEXP)"
  private val QUERY_TABLE_SPEC_LEGACY =
    Pattern.compile(s"(?<=\\[)$DATASET_TABLE_REGEXP_LEGACY(?=\\])")
  private val QUERY_TABLE_SPEC_STANDARD =
    Pattern.compile(s"(?<=\\`)$DATASET_TABLE_REGEXP_STANDARD(?=\\`)")

  private def extractTables(sqlQuery: String): Map[String, TableReference] = {
    val b = Map.newBuilder[String, TableReference]
    val m1 = QUERY_TABLE_SPEC_LEGACY.matcher(sqlQuery)
    while (m1.find()) {
      val t = m1.group(0)
      b += (s"[$t]" -> BigQueryIO.parseTableSpec(t))
    }
    val m2 = QUERY_TABLE_SPEC_STANDARD.matcher(sqlQuery)
    while (m2.find()) {
      val t = m2.group(0)
      b += (s"`$t`" -> BigQueryIO.parseTableSpec(t.replaceFirst("\\.", ":")))
    }
    b.result()
  }

  private def getPartitions(bq: BigQueryClient, tableRef: TableReference): Set[String] = {
    val prefix = tableRef.getTableId.split('$')(0)
    bq.getTables(tableRef.getProjectId, tableRef.getDatasetId)
      .filter(_.getTableId.startsWith(prefix))
      .map(_.getTableId.substring(prefix.length))
      .toSet
  }

  def latestQuery(bq: BigQueryClient, sqlQuery: String): String = {
    val tables = extractTables(sqlQuery).filter(_._2.getTableId.endsWith("$LATEST"))
    if (tables.isEmpty) {
      sqlQuery
    } else {
      val overlaps = tables
        .map(t => getPartitions(bq, t._2))
        .reduce(_ intersect _)
      require(
        overlaps.nonEmpty,
        "Cannot find latest common partition for " + tables.keys.mkString(", "))
      val latest = overlaps.max
      tables.foldLeft(sqlQuery) { case (q, (spec, _)) =>
        q.replace(spec, spec.replace("$LATEST", latest))
      }
    }
  }

  def latestTable(bq: BigQueryClient, tableSpec: String): String = {
    val ref = BigQueryIO.parseTableSpec(tableSpec)
    if (ref.getTableId.endsWith("$LATEST")) {
      val partitions = getPartitions(bq, ref)
      require(partitions.nonEmpty, s"Cannot find latest partition for $tableSpec")
      tableSpec.replace("$LATEST", partitions.max)
    } else {
      tableSpec
    }
  }

}

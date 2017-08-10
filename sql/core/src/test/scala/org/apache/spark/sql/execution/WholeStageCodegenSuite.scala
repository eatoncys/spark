/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import org.apache.spark.sql.{Column, Dataset, Row}
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.{Add, Literal, Stack}
import org.apache.spark.sql.execution.aggregate.HashAggregateExec
import org.apache.spark.sql.execution.joins.BroadcastHashJoinExec
import org.apache.spark.sql.execution.joins.SortMergeJoinExec
import org.apache.spark.sql.expressions.scalalang.typed
import org.apache.spark.sql.functions.{avg, broadcast, col, max}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSQLContext
import org.apache.spark.sql.types.{IntegerType, StringType, StructType}

class WholeStageCodegenSuite extends SparkPlanTest with SharedSQLContext {

  test("range/filter should be combined") {
    val df = spark.range(10).filter("id = 1").selectExpr("id + 1")
    val plan = df.queryExecution.executedPlan
    assert(plan.find(_.isInstanceOf[WholeStageCodegenExec]).isDefined)
    assert(df.collect() === Array(Row(2)))
  }

  test("Aggregate should be included in WholeStageCodegen") {
    val df = spark.range(10).groupBy().agg(max(col("id")), avg(col("id")))
    val plan = df.queryExecution.executedPlan
    assert(plan.find(p =>
      p.isInstanceOf[WholeStageCodegenExec] &&
        p.asInstanceOf[WholeStageCodegenExec].child.isInstanceOf[HashAggregateExec]).isDefined)
    assert(df.collect() === Array(Row(9, 4.5)))
  }

  test("Aggregate with grouping keys should be included in WholeStageCodegen") {
    val df = spark.range(3).groupBy("id").count().orderBy("id")
    val plan = df.queryExecution.executedPlan
    assert(plan.find(p =>
      p.isInstanceOf[WholeStageCodegenExec] &&
        p.asInstanceOf[WholeStageCodegenExec].child.isInstanceOf[HashAggregateExec]).isDefined)
    assert(df.collect() === Array(Row(0, 1), Row(1, 1), Row(2, 1)))
  }

  test("BroadcastHashJoin should be included in WholeStageCodegen") {
    val rdd = spark.sparkContext.makeRDD(Seq(Row(1, "1"), Row(1, "1"), Row(2, "2")))
    val schema = new StructType().add("k", IntegerType).add("v", StringType)
    val smallDF = spark.createDataFrame(rdd, schema)
    val df = spark.range(10).join(broadcast(smallDF), col("k") === col("id"))
    assert(df.queryExecution.executedPlan.find(p =>
      p.isInstanceOf[WholeStageCodegenExec] &&
        p.asInstanceOf[WholeStageCodegenExec].child.isInstanceOf[BroadcastHashJoinExec]).isDefined)
    assert(df.collect() === Array(Row(1, 1, "1"), Row(1, 1, "1"), Row(2, 2, "2")))
  }

  test("Sort should be included in WholeStageCodegen") {
    val df = spark.range(3, 0, -1).toDF().sort(col("id"))
    val plan = df.queryExecution.executedPlan
    assert(plan.find(p =>
      p.isInstanceOf[WholeStageCodegenExec] &&
        p.asInstanceOf[WholeStageCodegenExec].child.isInstanceOf[SortExec]).isDefined)
    assert(df.collect() === Array(Row(1), Row(2), Row(3)))
  }

  test("MapElements should be included in WholeStageCodegen") {
    import testImplicits._

    val ds = spark.range(10).map(_.toString)
    val plan = ds.queryExecution.executedPlan
    assert(plan.find(p =>
      p.isInstanceOf[WholeStageCodegenExec] &&
      p.asInstanceOf[WholeStageCodegenExec].child.isInstanceOf[SerializeFromObjectExec]).isDefined)
    assert(ds.collect() === 0.until(10).map(_.toString).toArray)
  }

  test("typed filter should be included in WholeStageCodegen") {
    val ds = spark.range(10).filter(_ % 2 == 0)
    val plan = ds.queryExecution.executedPlan
    assert(plan.find(p =>
      p.isInstanceOf[WholeStageCodegenExec] &&
        p.asInstanceOf[WholeStageCodegenExec].child.isInstanceOf[FilterExec]).isDefined)
    assert(ds.collect() === Array(0, 2, 4, 6, 8))
  }

  test("back-to-back typed filter should be included in WholeStageCodegen") {
    val ds = spark.range(10).filter(_ % 2 == 0).filter(_ % 3 == 0)
    val plan = ds.queryExecution.executedPlan
    assert(plan.find(p =>
      p.isInstanceOf[WholeStageCodegenExec] &&
      p.asInstanceOf[WholeStageCodegenExec].child.isInstanceOf[FilterExec]).isDefined)
    assert(ds.collect() === Array(0, 6))
  }

  test("simple typed UDAF should be included in WholeStageCodegen") {
    import testImplicits._

    val ds = Seq(("a", 10), ("b", 1), ("b", 2), ("c", 1)).toDS()
      .groupByKey(_._1).agg(typed.sum(_._2))

    val plan = ds.queryExecution.executedPlan
    assert(plan.find(p =>
      p.isInstanceOf[WholeStageCodegenExec] &&
        p.asInstanceOf[WholeStageCodegenExec].child.isInstanceOf[HashAggregateExec]).isDefined)
    assert(ds.collect() === Array(("a", 10.0), ("b", 3.0), ("c", 1.0)))
  }

  test("SPARK-19512 codegen for comparing structs is incorrect") {
    // this would raise CompileException before the fix
    spark.range(10)
      .selectExpr("named_struct('a', id) as col1", "named_struct('a', id+2) as col2")
      .filter("col1 = col2").count()
    // this would raise java.lang.IndexOutOfBoundsException before the fix
    spark.range(10)
      .selectExpr("named_struct('a', id, 'b', id) as col1",
        "named_struct('a',id+2, 'b',id+2) as col2")
      .filter("col1 = col2").count()
  }

  test("SPARK-21441 SortMergeJoin codegen with CodegenFallback expressions should be disabled") {
    withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "1") {
      import testImplicits._

      val df1 = Seq((1, 1), (2, 2), (3, 3)).toDF("key", "int")
      val df2 = Seq((1, "1"), (2, "2"), (3, "3")).toDF("key", "str")

      val df = df1.join(df2, df1("key") === df2("key"))
        .filter("int = 2 or reflect('java.lang.Integer', 'valueOf', str) = 1")
        .select("int")

      val plan = df.queryExecution.executedPlan
      assert(!plan.find(p =>
        p.isInstanceOf[WholeStageCodegenExec] &&
          p.asInstanceOf[WholeStageCodegenExec].child.children(0)
            .isInstanceOf[SortMergeJoinExec]).isDefined)
      assert(df.collect() === Array(Row(1), Row(2)))
    }
  }

  test("SPARK-21603 check there is a too long generated function") {
    val ds = spark.range(10)
      .selectExpr(
        "id",
        "(id & 1023) as k1",
        "cast(id & 1023 as double) as k2",
        "cast(id & 1023 as int) as k3",
        "case when id > 100 and id <= 200 then 1 else 0 end as v1",
        "case when id > 200 and id <= 300 then 1 else 0 end as v2",
        "case when id > 300 and id <= 400 then 1 else 0 end as v3",
        "case when id > 400 and id <= 500 then 1 else 0 end as v4",
        "case when id > 500 and id <= 600 then 1 else 0 end as v5",
        "case when id > 600 and id <= 700 then 1 else 0 end as v6",
        "case when id > 700 and id <= 800 then 1 else 0 end as v7",
        "case when id > 800 and id <= 900 then 1 else 0 end as v8",
        "case when id > 900 and id <= 1000 then 1 else 0 end as v9",
        "case when id > 1000 and id <= 1100 then 1 else 0 end as v10",
        "case when id > 1100 and id <= 1200 then 1 else 0 end as v11",
        "case when id > 1200 and id <= 1300 then 1 else 0 end as v12",
        "case when id > 1300 and id <= 1400 then 1 else 0 end as v13",
        "case when id > 1400 and id <= 1500 then 1 else 0 end as v14",
        "case when id > 1500 and id <= 1600 then 1 else 0 end as v15",
        "case when id > 1600 and id <= 1700 then 1 else 0 end as v16",
        "case when id > 1700 and id <= 1800 then 1 else 0 end as v17",
        "case when id > 1800 and id <= 1900 then 1 else 0 end as v18",
        "case when id > 1900 and id <= 2000 then 1 else 0 end as v19",
        "case when id > 2000 and id <= 2100 then 1 else 0 end as v20",
        "case when id > 2100 and id <= 2200 then 1 else 0 end as v21",
        "case when id > 2200 and id <= 2300 then 1 else 0 end as v22",
        "case when id > 2300 and id <= 2400 then 1 else 0 end as v23",
        "case when id > 2400 and id <= 2500 then 1 else 0 end as v24",
        "case when id > 2500 and id <= 2600 then 1 else 0 end as v25",
        "case when id > 2600 and id <= 2700 then 1 else 0 end as v26")
      .groupBy("k1", "k2", "k3")
      .sum()
    val plan = ds.queryExecution.executedPlan
    val wholeStageCodegenExec = plan.find(p =>
      p.isInstanceOf[WholeStageCodegenExec] &&
        p.asInstanceOf[WholeStageCodegenExec].child.isInstanceOf[HashAggregateExec] &&
        p.asInstanceOf[WholeStageCodegenExec].child.asInstanceOf[HashAggregateExec]
          .child.isInstanceOf[ProjectExec]
    )
    assert(wholeStageCodegenExec.isDefined)
    val (ctx, _) =
      wholeStageCodegenExec.get.asInstanceOf[WholeStageCodegenExec].doCodeGen()
    assert(ctx.isTooLongGeneratedFunction === true)
  }

  test("SPARK-21603 check there is not a too long generated function") {
    val ds = spark.range(10)
      .selectExpr(
        "id",
        "(id & 1023) as k1",
        "cast(id & 1023 as double) as k2",
        "cast(id & 1023 as int) as k3",
        "case when id > 100 and id <= 200 then 1 else 0 end as v1")
      .groupBy("k1", "k2", "k3")
      .sum()
    val plan = ds.queryExecution.executedPlan
    val wholeStageCodegenExec = plan.find(p =>
      p.isInstanceOf[WholeStageCodegenExec] &&
        p.asInstanceOf[WholeStageCodegenExec].child.isInstanceOf[HashAggregateExec] &&
        p.asInstanceOf[WholeStageCodegenExec].child.asInstanceOf[HashAggregateExec]
          .child.isInstanceOf[ProjectExec]
    )
    assert(wholeStageCodegenExec.isDefined)
    val (ctx, _) =
      wholeStageCodegenExec.get.asInstanceOf[WholeStageCodegenExec].doCodeGen()
    assert(ctx.isTooLongGeneratedFunction === false)
  }
}

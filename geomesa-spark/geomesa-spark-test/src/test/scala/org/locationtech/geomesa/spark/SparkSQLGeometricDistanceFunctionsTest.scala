/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.spark

import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.sql.{SQLContext, SparkSession}
import org.geotools.api.data.{DataStore, DataStoreFinder}
import org.junit.runner.RunWith
import org.locationtech.geomesa.utils.text.WKTUtils
import org.locationtech.jts.geom.Point
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.util.{Map => JMap}

@RunWith(classOf[JUnitRunner])
class SparkSQLGeometricDistanceFunctionsTest extends Specification with LazyLogging {

  import scala.collection.JavaConverters._

  sequential

  val dsParams: JMap[String, String] =
    Map("namespace" -> getClass.getSimpleName, "cqengine" -> "true", "geotools" -> "true").asJava

  var ds: DataStore = _
  var spark: SparkSession = _
  var sc: SQLContext = _

  step {
    ds = DataStoreFinder.getDataStore(dsParams)
    spark = SparkSQLTestUtils.createSparkSession()
    sc = spark.sqlContext

    SparkSQLTestUtils.ingestChicago(ds)

    val df = spark.read
        .format("geomesa")
        .options(dsParams)
        .option("geomesa.feature", "chicago")
        .load()
    logger.debug(df.schema.treeString)
    df.createOrReplaceTempView("chicago")
  }

  "sql geometric distance functions" should {

    "st_aggregateDistanceSpheroid" >> {
      "should work with window functions" >> {
        val res = sc.sql(
          """
          |select
          |   case_number,dtg,st_aggregateDistanceSpheroid(l)
          |from (
          |  select
          |      case_number,
          |      dtg,
          |      collect_list(geom) OVER (PARTITION BY true ORDER BY dtg asc ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) as l
          |  from chicago
          |)
          |where
          |   size(l) > 1
        """.stripMargin).
          collect().map(_.getDouble(2))
        Array(70681.00230533126, 141178.0595870745) must beEqualTo(res)
      }
    }

    "st_lengthSpheroid" >> {
      "should handle null" >> {
        sc.sql("select st_lengthSpheroid(null)").collect.head(0) must beNull
      }

      "should get great circle length of a linestring" >> {
        val res = sc.sql(
          """
          |select
          |  case_number,st_lengthSpheroid(st_makeLine(l))
          |from (
          |   select
          |      case_number,
          |      dtg,
          |      collect_list(geom) OVER (PARTITION BY true ORDER BY dtg asc ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) as l
          |   from chicago
          |)
          |where
          |   size(l) > 1
        """.stripMargin).
          collect().map(_.getDouble(1))
        Array(70681.00230533126, 141178.0595870745) must beEqualTo(res)
      }
    }

    "st_transform" >> {
      "should handle null" >> {
        sc.sql("select st_transform(null, null, null)").collect.head(0) must beNull
      }

      "should transform the coordinates of a point" >> {
        val pointWGS84 = "POINT(-0.871722 52.023636)"
        val expectedOSGB36 = "POINT(477514.0081191745 236736.03179981868)"
        val r = sc.sql(
          s"select st_transform(st_geomFromWKT('$pointWGS84'), 'EPSG:4326', 'EPSG:27700')"
        ).collect()
        r.head.getAs[Point](0) mustEqual WKTUtils.read(expectedOSGB36)
      }
    }
  }

  step {
    ds.dispose()
    spark.close()
  }
}

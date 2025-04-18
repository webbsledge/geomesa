/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.index

import org.apache.accumulo.core.security.Authorizations
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.TestWithFeatureType
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.index.index.z3.Z3Index
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes.Configs.{IndexZ2Shards, IndexZ3Shards}
import org.locationtech.geomesa.utils.index.GeoMesaSchemaValidator
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ConfigureShardsTest extends Specification with TestWithFeatureType {

  import scala.collection.JavaConverters._

  val spec = "name:String,dtg:Date,*geom:Point:srid=4326;geomesa.z3.splits='8'"

  val features: Seq[ScalaSimpleFeature] = {
    (0 until 100).map { i =>
      val sf = new ScalaSimpleFeature(sft, s"$i")
      i match {
        case a if a < 24 => sf.setAttributes(Array[AnyRef](s"name$i", s"2010-05-07T$i:00:00.000Z",
          s"POINT(40 $i)"))
        case b if b < 48 => sf.setAttributes(Array[AnyRef](s"name$i", s"2010-05-08T$i:00:00.000Z",
          s"POINT(40 ${i - 24})"))
        case c if c < 72 => sf.setAttributes(Array[AnyRef](s"name$i", s"2010-05-09T$i:00:00.000Z",
          s"POINT(40 ${i - 48})"))
        case d if d < 96 => sf.setAttributes(Array[AnyRef](s"name$i", s"2010-05-10T$i:00:00.000Z",
          s"POINT(40 ${i - 72})"))
        case e => sf.setAttributes(Array[AnyRef](s"name$i", s"2010-05-11T$i:00:00.000Z",
          s"POINT(40 ${i - 96})"))
      }
      sf
    }
  }

  step {
    addFeatures(features)
  }

  "Indexes" should {
    "configure from spec" >> {
      var shardSet: Set[Long] = Set[Long]()
      val index = ds.manager.indices(sft).find(_.name == Z3Index.name)
      index must beSome
      index.get.getTableNames().foreach { table =>
        ds.connector.createScanner(table, new Authorizations()).asScala.foreach { r =>
          val bytes = r.getKey.getRow.getBytes
          val shard = bytes(0).toInt
          shardSet = shardSet + shard
        }
      }
      shardSet must haveSize(8)
    }

    "throw exception on invalid z2 shards" >> {
      val sftPrivate = SimpleFeatureTypes.createType("private", spec)
      sftPrivate.getUserData.put(IndexZ2Shards, "128")
      GeoMesaSchemaValidator.validate(sftPrivate) must throwAn[IllegalArgumentException]
    }

    "throw exception on invalid z3 shards" >> {
      val sftPrivate = SimpleFeatureTypes.createType("private", spec)
      sftPrivate.getUserData.put(IndexZ3Shards, "128")
      GeoMesaSchemaValidator.validate(sftPrivate) must throwAn[IllegalArgumentException]
    }
  }
}

/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.data

import org.apache.accumulo.core.security.Authorizations
import org.geotools.api.data._
import org.geotools.api.feature.simple.SimpleFeature
import org.geotools.data.collection.ListFeatureCollection
import org.geotools.filter.text.ecql.ECQL
import org.junit.runner.RunWith
import org.locationtech.geomesa.accumulo.TestWithFeatureType
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.security.{AuthorizationsProvider, DefaultAuthorizationsProvider, FilteringAuthorizationsProvider, GEOMESA_AUTH_PROVIDER_IMPL, SecurityUtils}
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.io.WithClose
import org.specs2.runner.JUnitRunner

import java.util
import java.util.Collections

@RunWith(classOf[JUnitRunner])
class AccumuloDataStoreAuthTest extends TestWithFeatureType {

  import AccumuloDataStoreParams.{AuthsParam, ForceEmptyAuthsParam}
  import org.locationtech.geomesa.security.AuthProviderParam

  import scala.collection.JavaConverters._

  override val spec = "name:String:index=join,dtg:Date,*geom:Point:srid=4326"

  step {
    addFeatures((0 until 2).map { i =>
      val sf = new ScalaSimpleFeature(sft, i.toString)
      sf.setAttribute(0, i.toString)
      sf.setAttribute(1, s"2016-01-01T01:0$i:00.000Z")
      sf.setAttribute(2, s"POINT (45 5$i)")
      if (i == 0) {
        SecurityUtils.setFeatureVisibility(sf, "user")
      } else {
        SecurityUtils.setFeatureVisibility(sf, "admin")
      }
      sf
    })
  }

  val threadedAuths = new ThreadLocal[Authorizations]

  val authProvider = new AuthorizationsProvider {
    override def getAuthorizations: java.util.List[String] = threadedAuths.get.getAuthorizations.asScala.map(new String(_)).asJava
    override def configure(params: util.Map[String, _]): Unit = {}
  }

  "AccumuloDataStore" should {
    "provide ability to configure authorizations" >> {
      "by static auths" >> {
        val params = dsParams ++ Map(AuthsParam.key -> "user")
        WithClose(DataStoreFinder.getDataStore(params.asJava).asInstanceOf[AccumuloDataStore]) { ds =>
          ds must not(beNull)
          ds.config.authProvider must beAnInstanceOf[DefaultAuthorizationsProvider]
          ds.auths mustEqual new Authorizations("user")
        }
      }

      "by comma-delimited static auths" >> {
        val params = dsParams ++ Map(AuthsParam.key -> "user,admin,system")
        WithClose(DataStoreFinder.getDataStore(params.asJava).asInstanceOf[AccumuloDataStore]) { ds =>
          ds must not(beNull)
          ds.config.authProvider must beAnInstanceOf[DefaultAuthorizationsProvider]
          ds.auths mustEqual new Authorizations("user", "admin", "system")
        }
      }

      "fail when auth provider system property does not match an actual class" >> {
        GEOMESA_AUTH_PROVIDER_IMPL.threadLocalValue.set("my.fake.Clas")
        try {
          val params = dsParams ++ Map(AuthsParam.key -> "user,admin,system")
          DataStoreFinder.getDataStore(params.asJava).asInstanceOf[AccumuloDataStore] must throwAn[IllegalArgumentException]
        } finally {
          GEOMESA_AUTH_PROVIDER_IMPL.threadLocalValue.remove()
        }
      }

      "fail when authorizations are explicitly provided, but the flag to force empty authorizations is set" >> {
        val params = dsParams ++ Map(AuthsParam.key -> "user,admin,system", ForceEmptyAuthsParam.key -> "true")
        DataStoreFinder.getDataStore(params.asJava).asInstanceOf[AccumuloDataStore] must throwAn[IllegalArgumentException]
      }

      "replace empty authorizations with the Accumulo user's full authorizations (without the override)" >> {
        val params = dsParams ++ Map(AuthsParam.key -> "")
        WithClose(DataStoreFinder.getDataStore(params.asJava).asInstanceOf[AccumuloDataStore]) { ds =>
          ds must not(beNull)
          ds.config.authProvider must beAnInstanceOf[DefaultAuthorizationsProvider]
          ds.auths mustEqual root.auths
        }
      }

      "use empty authorizations (with the override)" >> {
        val params = dsParams ++ Map(AuthsParam.key -> "", ForceEmptyAuthsParam.key -> "true")
        WithClose(DataStoreFinder.getDataStore(params.asJava).asInstanceOf[AccumuloDataStore]) { ds =>
          ds must not(beNull)
          ds.config.authProvider must beAnInstanceOf[DefaultAuthorizationsProvider]
          ds.auths mustEqual new Authorizations()
        }
      }

      "query with a threaded auth provider against various indices" >> {
        val params = dsParams ++ Map(AuthsParam.key -> "user,admin", AuthProviderParam.key -> authProvider)
        WithClose(DataStoreFinder.getDataStore(params.asJava).asInstanceOf[AccumuloDataStore]) { ds =>
          ds must not(beNull)
          ds.config.authProvider must beAnInstanceOf[FilteringAuthorizationsProvider]

          val user  = Seq("IN('0')", "name = '0'", "bbox(geom, 44, 49.1, 46, 50.1)", "bbox(geom, 44, 49.1, 46, 50.1) AND dtg DURING 2016-01-01T00:59:30.000Z/2016-01-01T01:00:30.000Z")
          val admin = Seq("IN('1')", "name = '1'", "bbox(geom, 44, 50.1, 46, 51.1)", "bbox(geom, 44, 50.1, 46, 51.1) AND dtg DURING 2016-01-01T01:00:30.000Z/2016-01-01T01:01:30.000Z")
          val both  = Seq("INCLUDE", "IN('0', '1')", "name < '2'", "bbox(geom, 44, 49.1, 46, 51.1)", "bbox(geom, 44, 49.1, 46, 51.1) AND dtg DURING 2016-01-01T00:59:30.000Z/2016-01-01T01:01:30.000Z")

          forall(user) { filter =>
            val q = new Query(sftName, ECQL.toFilter(filter))
            threadedAuths.set(new Authorizations("user"))
            try {
              val results = SelfClosingIterator(ds.getFeatureReader(q, Transaction.AUTO_COMMIT)).toList
              results must haveLength(1)
              results.head.getID mustEqual "0"
            } finally {
              threadedAuths.remove()
            }
            threadedAuths.set(new Authorizations("admin"))
            try {
              val results = SelfClosingIterator(ds.getFeatureReader(q, Transaction.AUTO_COMMIT)).toList
              results must beEmpty
            } finally {
              threadedAuths.remove()
            }
          }

          forall(admin) { filter =>
            val q = new Query(sftName, ECQL.toFilter(filter))
            threadedAuths.set(new Authorizations("admin"))
            try {
              val results = SelfClosingIterator(ds.getFeatureReader(q, Transaction.AUTO_COMMIT)).toList
              results must haveLength(1)
              results.head.getID mustEqual "1"
            } finally {
              threadedAuths.remove()
            }
            threadedAuths.set(new Authorizations("user"))
            try {
              val results = SelfClosingIterator(ds.getFeatureReader(q, Transaction.AUTO_COMMIT)).toList
              results must beEmpty
            } finally {
              threadedAuths.remove()
            }
          }

          forall(both) { filter =>
            val q = new Query(sftName, ECQL.toFilter(filter))
            threadedAuths.set(new Authorizations("user"))
            try {
              val results = SelfClosingIterator(ds.getFeatureReader(q, Transaction.AUTO_COMMIT)).toList
              results must haveLength(1)
              results.head.getID mustEqual "0"
            } finally {
              threadedAuths.remove()
            }
            threadedAuths.set(new Authorizations("admin"))
            try {
              val results = SelfClosingIterator(ds.getFeatureReader(q, Transaction.AUTO_COMMIT)).toList
              results must haveLength(1)
              results.head.getID mustEqual "1"
            } finally {
              threadedAuths.remove()
            }
            threadedAuths.set(new Authorizations("user", "admin"))
            try {
              val results = SelfClosingIterator(ds.getFeatureReader(q, Transaction.AUTO_COMMIT)).toList
              results must haveLength(2)
              results.map(_.getID).sorted mustEqual Seq("0", "1")
            } finally {
              threadedAuths.remove()
            }
          }
        }
      }
    }

    "allow users with sufficient auths to write data" >> {
      val params = dsParams ++ Map(AuthsParam.key -> "user,admin")
      WithClose(DataStoreFinder.getDataStore(params.asJava).asInstanceOf[AccumuloDataStore]) { ds =>
        ds must not(beNull)

        val sft = SimpleFeatureTypes.createType("canwrite", spec)
        ds.createSchema(sft)

        // write some data
        val fs = ds.getFeatureSource("canwrite").asInstanceOf[SimpleFeatureStore]
        val feat: SimpleFeature = new ScalaSimpleFeature(sft, "1")
        feat.setAttribute("geom", "POINT(45 55)")
        SecurityUtils.setFeatureVisibility(feat, "user&admin")
        val written = fs.addFeatures(new ListFeatureCollection(sft, Collections.singletonList(feat)))

        written must not(beNull)
        written.size mustEqual 1
      }
    }
  }
}

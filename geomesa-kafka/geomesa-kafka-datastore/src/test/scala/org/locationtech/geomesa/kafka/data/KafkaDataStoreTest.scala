/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.kafka.data

import com.codahale.metrics.{MetricRegistry, ScheduledReporter}
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.core.instrument.{Counter, Gauge, Metrics}
import org.apache.commons.lang3.StringUtils
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, NewTopic}
import org.apache.kafka.common.config.ConfigResource
import org.geotools.api.data._
import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.geotools.api.filter.Filter
import org.geotools.data._
import org.geotools.filter.identity.FeatureIdImpl
import org.geotools.filter.text.ecql.ECQL
import org.geotools.geometry.jts.JTSFactoryFinder
import org.geotools.util.factory.Hints
import org.junit.runner.RunWith
import org.locationtech.geomesa.features.{ScalaSimpleFeature, SerializationType}
import org.locationtech.geomesa.index.InMemoryMetadata
import org.locationtech.geomesa.index.conf.QueryHints
import org.locationtech.geomesa.index.metadata.TableBasedMetadata
import org.locationtech.geomesa.kafka.ExpirationMocking.{MockTicker, ScheduledExpiry, WrappedRunnable}
import org.locationtech.geomesa.kafka.KafkaContainerTest
import org.locationtech.geomesa.kafka.consumer.BatchConsumer.BatchResult
import org.locationtech.geomesa.kafka.consumer.BatchConsumer.BatchResult.BatchResult
import org.locationtech.geomesa.kafka.utils.GeoMessageSerializer.GeoMessageSerializerFactory
import org.locationtech.geomesa.kafka.utils.KafkaFeatureEvent.{KafkaFeatureChanged, KafkaFeatureCleared, KafkaFeatureRemoved}
import org.locationtech.geomesa.kafka.utils.{GeoMessage, GeoMessageProcessor}
import org.locationtech.geomesa.metrics.core.GeoMesaMetrics
import org.locationtech.geomesa.security.{AuthorizationsProvider, SecurityUtils}
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes.Configs
import org.locationtech.geomesa.utils.geotools.{FeatureUtils, SimpleFeatureTypes}
import org.locationtech.geomesa.utils.index.SizeSeparatedBucketIndex
import org.locationtech.geomesa.utils.io.{CloseQuietly, WithClose}
import org.locationtech.jts.geom.Point
import org.mockito.ArgumentMatchers
import org.specs2.mock.Mockito
import org.specs2.runner.JUnitRunner

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{CopyOnWriteArrayList, ScheduledExecutorService, SynchronousQueue, TimeUnit}
import java.util.{Collections, Date, Properties, UUID}

@RunWith(classOf[JUnitRunner])
class KafkaDataStoreTest extends KafkaContainerTest with Mockito {

  import scala.collection.JavaConverters._
  import scala.concurrent.duration._

  lazy val baseParams = Map(
//    "kafka.serialization.type" -> "avro",
    "kafka.brokers"            -> brokers,
    "kafka.topic.partitions"   -> 1,
    "kafka.topic.replication"  -> 1,
    "kafka.consumer.read-back" -> "Inf"
  )

  val gf = JTSFactoryFinder.getGeometryFactory
  val paths = new AtomicInteger(0)

  def getUniquePath: String = s"geomesa/${paths.getAndIncrement()}/test/"

  def getStore(zkPath: String, consumers: Int, extras: Map[String, AnyRef] = Map.empty): KafkaDataStore = {
    val catalog = if (extras.contains("kafka.zookeepers")) { KafkaDataStoreParams.ZkPath } else { KafkaDataStoreParams.Catalog }
    val params = baseParams ++ Map(catalog.key -> zkPath, "kafka.consumer.count" -> consumers) ++ extras
    DataStoreFinder.getDataStore(params.asJava).asInstanceOf[KafkaDataStore]
  }

  def createStorePair(params: Map[String, AnyRef] = Map.empty): (KafkaDataStore, KafkaDataStore, SimpleFeatureType) = {
    // note: the topic gets set in the user data, so don't re-use the same sft instance
    val sft = SimpleFeatureTypes.createType("kafka", "name:String,age:Int,dtg:Date,*geom:Point:srid=4326")
    val path = getUniquePath
    (getStore(path, 0, params), getStore(path, 1, params), sft)
  }

  "KafkaDataStore" should {

    "return correctly from canProcess" >> {
      import org.locationtech.geomesa.kafka.data.KafkaDataStoreParams._
      val factory = new KafkaDataStoreFactory
      factory.canProcess(Collections.emptyMap[String, java.io.Serializable]) must beFalse
      factory.canProcess(Map[String, java.io.Serializable](Brokers.key -> "test", Zookeepers.key -> "test").asJava) must beTrue
    }

    "handle old read-back params" >> {
      val deprecated = Seq(
        "autoOffsetReset" -> "earliest",
        "autoOffsetReset" -> "latest",
        "kafka.consumer.from-beginning" -> "true",
        "kafka.consumer.from-beginning" -> "false"
      )
      foreach(deprecated) { case (k, v) =>
        KafkaDataStoreParams.ConsumerReadBack.lookupOpt(Collections.singletonMap(k, v)) must not(throwAn[Exception])
      }
    }

    "create unique topics based on zkPath" >> {
      val path = s"geomesa/topics/test/${paths.getAndIncrement()}"
      val ds = getStore(path, 0)
      try {
        ds.createSchema(SimpleFeatureTypes.createType("kafka", "name:String,age:Int,dtg:Date,*geom:Point:srid=4326"))
        ds.getSchema("kafka").getUserData.get(KafkaDataStore.TopicKey) mustEqual s"$path-kafka".replaceAll("/", "-")
        ds.getSchema("kafka").getUserData.get(KafkaDataStore.PartitioningKey) mustEqual KafkaDataStore.PartitioningDefault
      } finally {
        ds.dispose()
      }
    }

    "use default kafka partitioning" >> {
      val path = s"geomesa/topics/test/${paths.getAndIncrement()}"
      val ds = getStore(path, 0)
      try {
        ds.createSchema(SimpleFeatureTypes.createType("kafka", "name:String,age:Int,dtg:Date,*geom:Point:srid=4326"))
        KafkaDataStore.usesDefaultPartitioning(ds.getSchema("kafka")) must beTrue
      } finally {
        ds.dispose()
      }
    }

    "clean up metrics" >> {
      val reporter = mock[ScheduledReporter]
      val metrics = new GeoMesaMetrics(new MetricRegistry(), "", Seq(reporter))
      val config = {
        val orig = KafkaDataStoreFactory.buildConfig(baseParams.asJava)
        CloseQuietly(orig.metrics)
        orig.copy(metrics = Some(metrics))
      }
      val serializer = new GeoMessageSerializerFactory(SerializationType.KRYO)
      new KafkaDataStore(config, new InMemoryMetadata[String](), serializer).dispose()

      there was one(reporter).close()
    }

    "use namespaces" >> {
      import org.locationtech.geomesa.kafka.data.KafkaDataStoreParams._
      val path = s"geomesa/namespace/test/${paths.getAndIncrement()}"
      val ds = getStore(path, 0, Map(NamespaceParam.key -> "ns0"))
      try {
        ds.createSchema(SimpleFeatureTypes.createType("kafka", "name:String,age:Int,dtg:Date,*geom:Point:srid=4326"))
        ds.getSchema("kafka").getName.getNamespaceURI mustEqual "ns0"
        ds.getSchema("kafka").getName.getLocalPart mustEqual "kafka"
      } finally {
        ds.dispose()
      }
      val ds2 = getStore(path, 0, Map(NamespaceParam.key -> "ns1"))
      try {
        ds2.getSchema("kafka").getName.getNamespaceURI mustEqual "ns1"
        ds2.getSchema("kafka").getName.getLocalPart mustEqual "kafka"
      } finally {
        ds2.dispose()
      }
    }

    "allow schemas to be created and deleted" >> {
      foreach(Seq(true, false)) { zk =>
        TableBasedMetadata.Expiry.threadLocalValue.set("10ms")
        val (producer, consumer, _) = try {
          createStorePair(if (zk) { Map("kafka.zookeepers" -> zookeepers) } else { Map.empty[String, String] })
        } finally {
          TableBasedMetadata.Expiry.threadLocalValue.remove()
        }
        consumer must not(beNull)
        producer must not(beNull)
        try {
          val sft = SimpleFeatureTypes.createImmutableType("kafka", "name:String,age:Int,dtg:Date,*geom:Point:srid=4326;geomesa.foo='bar'")
          val topic = s"${producer.config.catalog}-${sft.getTypeName}".replaceAll("/", "-")
          producer.createSchema(sft)
          consumer.metadata.resetCache()
          foreach(Seq(producer, consumer)) { ds =>
            ds.getTypeNames.toSeq mustEqual Seq(sft.getTypeName)
            val schema = ds.getSchema(sft.getTypeName)
            schema must not(beNull)
            schema mustEqual sft
            schema.getUserData.get("geomesa.foo") mustEqual "bar"
            schema.getUserData.get(KafkaDataStore.TopicKey) mustEqual topic
          }

          val props = Collections.singletonMap[String, AnyRef](AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
          WithClose(AdminClient.create(props)) { admin =>
            admin.listTopics().names().get.asScala must contain(topic)
          }
          consumer.removeSchema(sft.getTypeName)
          foreach(Seq(consumer, producer)) { ds =>
            eventually(40, 100.millis)(ds.getTypeNames.toSeq must beEmpty)
            ds.getSchema(sft.getTypeName) must beNull
          }
          WithClose(AdminClient.create(props)) { admin =>
            eventually(40, 100.millis)(admin.listTopics().names().get.asScala must not(contain(topic)))
          }
        } finally {
          consumer.dispose()
          producer.dispose()
        }
      }
    }

    "write/update/read/delete features" >> {
      foreach(Seq(true, false)) { cqEngine =>
        val params = if (cqEngine) {
          Map("kafka.index.cqengine" -> "geom:default,name:unique", "kafka.zookeepers" -> zookeepers)
        } else {
          Map.empty[String, String]
        }
        val (producer, consumer, sft) = createStorePair(params)
        try {
          producer.createSchema(sft)
          consumer.metadata.resetCache()
          val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling

          val f0 = ScalaSimpleFeature.create(sft, "sm", "smith", 30, "2017-01-01T00:00:00.000Z", "POINT (0 0)")
          val f1 = ScalaSimpleFeature.create(sft, "jo", "jones", 20, "2017-01-02T00:00:00.000Z", "POINT (-10 -10)")

          // initial write
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must containTheSameElementsAs(Seq(f0, f1)))

          // update
          val f2 = ScalaSimpleFeature.create(sft, "sm", "smith2", 32, "2017-01-01T00:00:02.000Z", "POINT (2 2)")
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            FeatureUtils.write(writer, f2, useProvidedFid = true)
          }
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must containTheSameElementsAs(Seq(f1, f2)))

          // query
          val queries = Seq(
            "strToUpperCase(name) = 'JONES'",
            "name = 'jones' OR name = 'smith'",
            "name = 'foo' OR name = 'bar' OR name = 'baz' OR name = 'blarg' OR name = 'jones' OR name = 'smith'",
            "name = 'jones'",
            "age < 25",
            "bbox(geom, -15, -15, -5, -5) AND age < 25",
            "bbox(geom, -15, -15, 5, 5) AND dtg DURING 2017-01-01T12:00:00.000Z/2017-01-02T12:00:00.000Z",
            "INTERSECTS(geom, POLYGON((-11 -11, -9 -11, -9 -9, -11 -9, -11 -11))) AND bbox(geom, -15, -15, 5, 5)"
          )

          forall(queries) { ecql =>
            val query = new Query(sft.getTypeName, ECQL.toFilter(ecql))
            val features = SelfClosingIterator(consumer.getFeatureReader(query, Transaction.AUTO_COMMIT)).toSeq
            features mustEqual Seq(f1)
          }

          // delete
          producer.getFeatureSource(sft.getTypeName).removeFeatures(ECQL.toFilter("IN('sm')"))
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must beEqualTo(Seq(f1)))

          // clear
          producer.getFeatureSource(sft.getTypeName).removeFeatures(Filter.INCLUDE)
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must beEmpty)
        } finally {
          consumer.dispose()
          producer.dispose()
        }
      }
    }

    "support metrics" >> {
      val registry = new SimpleMeterRegistry()
      Metrics.addRegistry(registry)
      try {
        val (producer, consumer, originalSft) = createStorePair()
        try {
          val sft = SimpleFeatureTypes.renameSft(originalSft, s"metrics-${UUID.randomUUID()}")
          producer.createSchema(sft)
          consumer.metadata.resetCache()
          val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling

          val f0 = ScalaSimpleFeature.create(sft, "sm", "smith", 30, "2017-01-01T00:00:00.000Z", "POINT (0 0)")
          val f1 = ScalaSimpleFeature.create(sft, "jo", "jones", 20, "2017-01-02T00:00:00.000Z", "POINT (-10 -10)")

          // initial write
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must containTheSameElementsAs(Seq(f0, f1)))
          // write a second time so that our "live" metrics get updated, vs we may have hit the initial loader in our first write
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }

          // delete
          producer.getFeatureSource(sft.getTypeName).removeFeatures(ECQL.toFilter("IN('sm')"))
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must beEqualTo(Seq(f1)))

          val meters = registry.getMeters.asScala.filter(_.getId.getTags.asScala.exists(_.getValue == sft.getTypeName))
          meters must haveLength(10)
          eventually {
            meters.collectFirst { case g: Gauge if g.getId.getName.contains("index.size") => g.value() } must beSome(1d)
            meters.collectFirst { case c: Counter if c.getId.getName.contains("index.expirations") => c.count() } must beSome(0d)
            meters.collectFirst { case g: Gauge if g.getId.getName.contains("dtg.latest") => g.value() } must
              beSome(f1.getAttribute("dtg").asInstanceOf[Date].getTime)
            meters.collectFirst { case c: Counter if c.getId.getName.contains("read.updates") => c.count() } must
              beSome(beGreaterThanOrEqualTo(2d)) // may have been between 2-4 reads, due to timing around initial loading
            meters.collectFirst { case c: Counter if c.getId.getName.contains("read.deletes") => c.count() } must beSome(1d)
          }
        } finally {
          consumer.dispose()
          producer.dispose()
        }
      } finally {
        Metrics.removeRegistry(registry)
        registry.close()
      }
    }

    "support topic read-back" >> {
      foreach(Seq(true, false)) { cqEngine =>
        val params = if (cqEngine) {
          Map("kafka.index.cqengine" -> "geom:default,name:unique", "kafka.zookeepers" -> zookeepers)
        } else {
          Map.empty[String, String]
        }
        val (producer, consumer, sft) = createStorePair(params ++ Map("kafka.consumer.read-back" -> "Inf"))
        try {
          producer.createSchema(sft)

          val f0 = ScalaSimpleFeature.create(sft, "sm", "smith", 30, "2017-01-01T00:00:00.000Z", "POINT (0 0)")
          val f1 = ScalaSimpleFeature.create(sft, "jo", "jones", 20, "2017-01-02T00:00:00.000Z", "POINT (-10 -10)")

          // initial write
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }

          consumer.metadata.resetCache()
          val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must containTheSameElementsAs(Seq(f0, f1)))
        } finally {
          consumer.dispose()
          producer.dispose()
        }
      }
    }

    "write/read with visibilities" >> {
      import org.locationtech.geomesa.security.AuthProviderParam

      foreach(Seq(true, false)) { cqEngine =>
        var auths: Set[String] = null
        val provider = new AuthorizationsProvider() {
          import scala.collection.JavaConverters._
          override def getAuthorizations: java.util.List[String] = auths.toList.asJava
          override def configure(params: java.util.Map[String, _]): Unit = {}
        }
        val params = if (cqEngine) {
          Map("kafka.index.cqengine" -> "geom:default,name:unique", "kafka.zookeepers" -> zookeepers)
        } else {
          Map.empty[String, String]
        }
        val (producer, consumer, sft) = createStorePair(params + (AuthProviderParam.key -> provider))
        try {
          producer.createSchema(sft)
          consumer.metadata.resetCache()
          val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling

          val f0 = ScalaSimpleFeature.create(sft, "sm", "smith", 30, "2017-01-01T00:00:00.000Z", "POINT (0 0)")
          f0.getUserData.put(SecurityUtils.FEATURE_VISIBILITY, "USER")
          val f1 = ScalaSimpleFeature.create(sft, "jo", "jones", 20, "2017-01-02T00:00:00.000Z", "POINT (-10 -10)")
          f1.getUserData.put(SecurityUtils.FEATURE_VISIBILITY, "USER&ADMIN")

          // initial write
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }

          val q = new Query(sft.getTypeName)
          q.getHints.put(QueryHints.EXACT_COUNT, java.lang.Boolean.TRUE)

          // admin user
          auths = Set("USER", "ADMIN")
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must containTheSameElementsAs(Seq(f0, f1)))
          store.getCount(q) mustEqual 2

          // regular user
          auths = Set("USER")
          SelfClosingIterator(store.getFeatures.features).toSeq mustEqual Seq(f0)
          store.getCount(q) mustEqual 1

          // unauthorized
          auths = Set.empty
          SelfClosingIterator(store.getFeatures.features).toSeq must beEmpty
        } finally {
          consumer.dispose()
          producer.dispose()
        }
      }
    }

    "require visibilities on write" >> {
      val (producer, consumer, sft) = createStorePair()
      try {
        sft.getUserData.put(Configs.RequireVisibility, "true")
        producer.createSchema(sft)
        consumer.metadata.resetCache()

        val f0 = ScalaSimpleFeature.create(sft, "sm", "smith", 30, "2017-01-01T00:00:00.000Z", "POINT (0 0)")
        val f1 = ScalaSimpleFeature.create(sft, "jo", "jones", 20, "2017-01-02T00:00:00.000Z", "POINT (-10 -10)")

        WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
          Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true)) must throwAn[IllegalArgumentException]
          f0.getUserData.put(SecurityUtils.FEATURE_VISIBILITY, "USER")
          f1.getUserData.put(SecurityUtils.FEATURE_VISIBILITY, "USER&ADMIN")
          Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true)) must not(throwAn[Exception]) // ok
        }
      } finally {
        consumer.dispose()
        producer.dispose()
      }
    }

    "write/read json array attributes" >> {
      val sft = SimpleFeatureTypes.createType("kafka", "name:String:json=true,age:Int,dtg:Date,*geom:Point:srid=4326")
      val path = getUniquePath
      val (producer, consumer) = (getStore(path, 0), getStore(path, 1))
      try {
        producer.createSchema(sft)
        consumer.metadata.resetCache()
        val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling

        val f0 = ScalaSimpleFeature.create(sft, "sm", "[\"smith1\",\"smith2\"]", 30, "2017-01-01T00:00:00.000Z", "POINT (0 0)")
        val f1 = ScalaSimpleFeature.create(sft, "jo", "[\"jones\"]", 20, "2017-01-02T00:00:00.000Z", "POINT (-10 -10)")

        // initial write
        WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
          Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
        }

        eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must
            containTheSameElementsAs(Seq(f0, f1)))
      } finally {
        consumer.dispose()
        producer.dispose()
      }
    }

    "write/read avro collection attributes" >> {
      foreach(KafkaDataStoreParams.SerializationTypes.Types) { serde =>
        val params = Map(KafkaDataStoreParams.SerializationType.key -> serde)
        val sft =
          SimpleFeatureTypes.createType(
            "kafka",
            "names:List[String],props:Map[String,String],uuid:UUID,dtg:Date,*geom:Point:srid=4326")
        val path = getUniquePath
        val (producer, consumer) = (getStore(path, 0, params), getStore(path, 1, params))
        try {
          producer.createSchema(sft)
          consumer.metadata.resetCache()
          val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling

          val f0 =
            ScalaSimpleFeature.create(
              sft,
              "sm",
              List("smith1", "smith2"),
              Map("s" -> "smith"),
              "8e619e92-e894-4553-b65d-ce65681a75f4",
              "2017-01-01T00:00:00.000Z",
              "POINT (0 0)")
          val f1 =
            ScalaSimpleFeature.create(
              sft,
              "jo",
              List("jones"),
              Map("j1" -> "jones1", "j2" -> "jones2"),
              "d6505c88-c5ea-4bb3-99d7-26af5b531eda",
              "2017-01-02T00:00:00.000Z",
              "POINT (-10 -10)")

          // initial write
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }

          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must
              containTheSameElementsAs(Seq(f0, f1)))
        } finally {
          consumer.dispose()
          producer.dispose()
        }
      }
    }

    "expire entries" >> {
      foreach(Seq(true, false)) { cqEngine =>
        val executor = mock[ScheduledExecutorService]
        val ticker = new MockTicker()
        val params = if (cqEngine) {
          Map("kafka.cache.expiry" -> "100ms",
            "kafka.cache.executor" -> (executor, ticker),
            "kafka.index.cqengine" -> "geom:default,name:unique",
            "kafka.zookeepers" -> zookeepers)
        } else {
          Map("kafka.cache.expiry" -> "100ms", "kafka.cache.executor" -> (executor, ticker))
        }
        val (producer, consumer, sft) = createStorePair(params)
        try {
          producer.createSchema(sft)
          consumer.metadata.resetCache()
          val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling

          val f0 = ScalaSimpleFeature.create(sft, "sm", "smith", 30, "2017-01-01T00:00:00.000Z", "POINT (0 0)")
          val f1 = ScalaSimpleFeature.create(sft, "jo", "jones", 20, "2017-01-02T00:00:00.000Z", "POINT (-10 -10)")

          val bbox = ECQL.toFilter("bbox(geom,-10,-10,10,10)")

          val expirations = Collections.synchronizedList(new java.util.ArrayList[WrappedRunnable](2))
          executor.schedule(ArgumentMatchers.any[Runnable](), ArgumentMatchers.anyLong(), ArgumentMatchers.eq(TimeUnit.MILLISECONDS)) responds { args =>
            val expire = new WrappedRunnable(0L)
            expire.runnable = args.asInstanceOf[Array[AnyRef]](0).asInstanceOf[Runnable]
            expirations.add(expire)
            new ScheduledExpiry(expire)
          }

          // initial write
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }
          // check the cache directly
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must
              containTheSameElementsAs(Seq(f0, f1)))
          // check the spatial index
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures(bbox).features).toSeq must
              containTheSameElementsAs(Seq(f0, f1)))

          // expire the cache
          expirations.asScala.foreach(_.runnable.run())

          // verify feature has expired - hit the cache directly
          SelfClosingIterator(store.getFeatures.features) must beEmpty
          // verify feature has expired - hit the spatial index
          SelfClosingIterator(store.getFeatures(bbox).features) must beEmpty
        } finally {
          consumer.dispose()
          producer.dispose()
        }
      }
    }

    "expire entries based on cql filters" >> {
      foreach(Seq(true, false)) { cqEngine =>
        val executor = mock[ScheduledExecutorService]
        val ticker = new MockTicker()
        val params = {
          val expiry =
            """{
               |"name = 'smith'": "100ms",
               |"name = 'jones'": "200ms"
               |}""".stripMargin
          val base = Map(
            "kafka.cache.expiry.dynamic" -> expiry,
            "kafka.cache.expiry"         -> "300ms",
            "kafka.cache.executor"       -> (executor, ticker)
          )
          if (cqEngine) {
            base + ("kafka.index.cqengine" -> "geom:default,name:unique", "kafka.zookeepers" -> zookeepers)
          } else {
            base
          }
        }
        val (producer, consumer, sft) = createStorePair(params)
        try {
          producer.createSchema(sft)
          consumer.metadata.resetCache()
          val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling

          val f0 = ScalaSimpleFeature.create(sft, "sm", "smith", 30, "2017-01-01T00:00:00.000Z", "POINT (0 0)")
          val f1 = ScalaSimpleFeature.create(sft, "jo", "jones", 20, "2017-01-02T00:00:00.000Z", "POINT (-10 -10)")
          val f2 = ScalaSimpleFeature.create(sft, "wi", "wilson", 10, "2017-01-03T00:00:00.000Z", "POINT (10 10)")

          val bbox = ECQL.toFilter("bbox(geom,-10,-10,10,10)")

          val expirations = Collections.synchronizedList(new java.util.ArrayList[WrappedRunnable](2))

          // test the first filter expiry
          executor.schedule(ArgumentMatchers.any[Runnable](), ArgumentMatchers.eq(100L), ArgumentMatchers.eq(TimeUnit.MILLISECONDS)) responds { args =>
            val expire = new WrappedRunnable(0L)
            expire.runnable = args.asInstanceOf[Array[AnyRef]](0).asInstanceOf[Runnable]
            expirations.add(expire)
            new ScheduledExpiry(expire)
          }

          // initial write
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            Seq(f0).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }
          // check the cache directly
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must
              containTheSameElementsAs(Seq(f0)))
          // check the spatial index
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures(bbox).features).toSeq must
              containTheSameElementsAs(Seq(f0)))

          there was one(executor).schedule(ArgumentMatchers.eq(expirations.get(0).runnable), ArgumentMatchers.eq(100L), ArgumentMatchers.eq(TimeUnit.MILLISECONDS))

          // test the second filter expiry
          executor.schedule(ArgumentMatchers.any[Runnable](), ArgumentMatchers.eq(200L), ArgumentMatchers.eq(TimeUnit.MILLISECONDS)) responds { args =>
            val expire = new WrappedRunnable(0L)
            expire.runnable = args.asInstanceOf[Array[AnyRef]](0).asInstanceOf[Runnable]
            expirations.add(expire)
            new ScheduledExpiry(expire)
          }

          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            Seq(f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }

          // check the cache directly
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must
              containTheSameElementsAs(Seq(f0, f1)))
          // check the spatial index
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures(bbox).features).toSeq must
              containTheSameElementsAs(Seq(f0, f1)))

          there was one(executor).schedule(ArgumentMatchers.eq(expirations.get(1).runnable), ArgumentMatchers.eq(200L), ArgumentMatchers.eq(TimeUnit.MILLISECONDS))

          // test the fallback expiry
          executor.schedule(ArgumentMatchers.any[Runnable](), ArgumentMatchers.eq(300L), ArgumentMatchers.eq(TimeUnit.MILLISECONDS)) responds { args =>
            val expire = new WrappedRunnable(0L)
            expire.runnable = args.asInstanceOf[Array[AnyRef]](0).asInstanceOf[Runnable]
            expirations.add(expire)
            new ScheduledExpiry(expire)
          }

          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            Seq(f2).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }

          // check the cache directly
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must
              containTheSameElementsAs(Seq(f0, f1, f2)))
          // check the spatial index
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures(bbox).features).toSeq must
              containTheSameElementsAs(Seq(f0, f1, f2)))

          there was one(executor).schedule(ArgumentMatchers.eq(expirations.get(2).runnable), ArgumentMatchers.eq(300L), ArgumentMatchers.eq(TimeUnit.MILLISECONDS))

          // expire the cache
          expirations.asScala.foreach(_.runnable.run())

          // verify feature has expired - hit the cache directly
          SelfClosingIterator(store.getFeatures.features) must beEmpty
          // verify feature has expired - hit the spatial index
          SelfClosingIterator(store.getFeatures(bbox).features) must beEmpty
        } finally {
          consumer.dispose()
          producer.dispose()
        }
      }
    }

    "clear on startup" >> {
      val params = Map("kafka.producer.clear" -> "true")
      val (producer, consumer, sft) = createStorePair(params)
      try {
        producer.createSchema(sft)
        consumer.metadata.resetCache()
        val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling

        val f0 = ScalaSimpleFeature.create(sft, "sm", "smith", 30, "2017-01-01T00:00:00.000Z", "POINT (0 0)")
        val f1 = ScalaSimpleFeature.create(sft, "jo", "jones", 20, "2017-01-02T00:00:00.000Z", "POINT (-10 -10)")
        val f2 = ScalaSimpleFeature.create(sft, "do", "doe", 40, "2017-01-03T00:00:00.000Z", "POINT (10 10)")

        // initial write
        WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
          Seq(f0, f1).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
        }
        eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq must containTheSameElementsAs(Seq(f0, f1)))

        // new producer - clears on startup
        val producer2 = getStore(producer.config.catalog, 0, params)
        try {
          // write the third feature
          WithClose(producer2.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            FeatureUtils.write(writer, f2, useProvidedFid = true)
          }
          eventually(40, 100.millis)(SelfClosingIterator(store.getFeatures.features).toSeq mustEqual Seq(f2))
        } finally {
          producer2.dispose()
        }
      } finally {
        consumer.dispose()
        producer.dispose()
      }
    }

    "support listeners" >> {
      val (producer, consumer, sft) = createStorePair()
      try {
        val id = "fid-0"
        val numUpdates = 1
        val maxLon = 80.0

        var latestLon = -1.0
        var count = 0

        val listener = new FeatureListener {
          override def changed(event: FeatureEvent): Unit = {
            val feature = event.asInstanceOf[KafkaFeatureChanged].feature
            feature.getID mustEqual id
            latestLon = feature.getDefaultGeometry.asInstanceOf[Point].getX
            count += 1
          }
        }

        producer.createSchema(sft)
        consumer.metadata.resetCache()
        val consumerStore = consumer.getFeatureSource(sft.getTypeName)
        consumerStore.addFeatureListener(listener)

        WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
          (numUpdates to 1 by -1).foreach { i =>
            val ll = maxLon - maxLon / i
            val sf = writer.next()
            sf.setAttributes(Array[AnyRef]("smith", Int.box(30), new Date(), s"POINT ($ll $ll)"))
            sf.getIdentifier.asInstanceOf[FeatureIdImpl].setID(id)
            sf.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
            writer.write()
          }
        }

        eventually(40, 100.millis)(count must beEqualTo(numUpdates))
        latestLon must be equalTo 0.0
      } finally {
        consumer.dispose()
        producer.dispose()
      }
    }

    "support listeners without indexing" >> {
      val params = Map(KafkaDataStoreParams.CacheExpiry.getName -> "0s")
      val (producer, consumer, sft) = createStorePair(params)
      try {
        val id = "fid-0"
        val numUpdates = 1
        val maxLon = 80.0

        var latestLon = -1.0
        var count = 0

        val listener = new FeatureListener {
          override def changed(event: FeatureEvent): Unit = {
            val feature = event.asInstanceOf[KafkaFeatureChanged].feature
            feature.getID mustEqual id
            latestLon = feature.getDefaultGeometry.asInstanceOf[Point].getX
            count += 1
          }
        }

        producer.createSchema(sft)
        consumer.metadata.resetCache()
        val consumerStore = consumer.getFeatureSource(sft.getTypeName)
        consumerStore.addFeatureListener(listener)

        WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
          (numUpdates to 1 by -1).foreach { i =>
            val ll = maxLon - maxLon / i
            val sf = writer.next()
            sf.setAttributes(Array[AnyRef]("smith", Int.box(30), new Date(), s"POINT ($ll $ll)"))
            sf.getIdentifier.asInstanceOf[FeatureIdImpl].setID(id)
            sf.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
            writer.write()
          }
        }

        eventually(40, 100.millis)(count must beEqualTo(numUpdates))
        latestLon must be equalTo 0.0
      } finally {
        consumer.dispose()
        producer.dispose()
      }
    }

    "support transactions" >> {
      val (producer, consumer, _) = createStorePair()
      try {
        val sft = SimpleFeatureTypes.createType("test", "name:String,age:Int,dtg:Date,*geom:Point:srid=4326")
        producer.createSchema(sft)
        consumer.metadata.resetCache()

        val features = Seq.tabulate(10) { i =>
          ScalaSimpleFeature.create(sft, s"$i", s"name$i", i, f"2018-01-01T$i%02d:00:00.000Z", s"POINT (4$i 55)")
        }

        val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling

        val ids = new CopyOnWriteArrayList[String]()

        val listener = new FeatureListener() {
          override def changed(event: FeatureEvent): Unit = {
            ids.add(event.asInstanceOf[KafkaFeatureChanged].feature.getID)
          }
        }

        store.addFeatureListener(listener)

        try {
          WithClose(new DefaultTransaction()) { transaction =>
            WithClose(producer.getFeatureWriterAppend(sft.getTypeName, transaction)) { writer =>
              features.take(2).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
              transaction.rollback()
              features.take(3).foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
              transaction.commit()
            }
            eventually(40, 100.millis)(ids.asScala mustEqual Seq.tabulate(3)(_.toString))

            WithClose(producer.getFeatureWriterAppend(sft.getTypeName, transaction)) { writer =>
              features.foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
              transaction.commit()
            }

            eventually(40, 100.millis)(ids.asScala mustEqual Seq.tabulate(3)(_.toString) ++ Seq.tabulate(10)(_.toString))
          }
        } finally {
          store.removeFeatureListener(listener)
        }
      } finally {
        consumer.dispose()
        producer.dispose()
      }
    }

    "support layer views" >> {
      val views =
        """{
          |  test = [
          |    { type-name = test2, filter = "dtg > '2018-01-01T05:00:00.000Z'", transform = [ "name", "dtg", "geom" ] }
          |    { type-name = test3, transform = [ "derived=strConcat(name,'-d')", "dtg", "geom" ] }
          |    { type-name = test4, filter = "dtg > '2018-01-01T05:00:00.000Z'" }
          |  ]
          |}
          |
          |""".stripMargin
      val (producer, consumer, _) = createStorePair(Map(KafkaDataStoreParams.LayerViews.key -> views))
      try {
        val sft = SimpleFeatureTypes.createType("test", "name:String,age:Int,dtg:Date,*geom:Point:srid=4326")
        producer.createSchema(sft)
        consumer.metadata.resetCache()

        val sft2 = SimpleFeatureTypes.createType("test2", "name:String,dtg:Date,*geom:Point:srid=4326")
        val sft3 = SimpleFeatureTypes.createType("test3", "derived:String,dtg:Date,*geom:Point:srid=4326")
        val sft4 = SimpleFeatureTypes.createType("test4", "name:String,age:Int,dtg:Date,*geom:Point:srid=4326")

        val features = Seq.tabulate(10) { i =>
          ScalaSimpleFeature.create(sft, s"$i", s"name$i", i, f"2018-01-01T$i%02d:00:00.000Z", s"POINT (4$i 55)")
        }
        val derived = Seq.tabulate(10) { i =>
          ScalaSimpleFeature.create(sft3, s"$i", s"name$i-d", f"2018-01-01T$i%02d:00:00.000Z", s"POINT (4$i 55)")
        }

        consumer.getTypeNames.toSeq must containTheSameElementsAs(Seq("test", "test2", "test3", "test4"))
        SimpleFeatureTypes.encodeType(consumer.getSchema("test2")) mustEqual SimpleFeatureTypes.encodeType(sft2)
        SimpleFeatureTypes.encodeType(consumer.getSchema("test3")) mustEqual SimpleFeatureTypes.encodeType(sft3)
        SimpleFeatureTypes.encodeType(consumer.getSchema("test4")) mustEqual SimpleFeatureTypes.encodeType(sft4)

        val store = consumer.getFeatureSource(sft.getTypeName) // start the consumer polling

        val ids = new CopyOnWriteArrayList[String]()

        val listener = new FeatureListener() {
          override def changed(event: FeatureEvent): Unit = {
            event match {
              case e: KafkaFeatureChanged => ids.add(e.feature.getID)
              case e: KafkaFeatureRemoved => ids.remove(e.id)
              case _: KafkaFeatureCleared => ids.clear()
              case _ => failure(s"Unexpected event: $event")
            }
          }
        }

        store.addFeatureListener(listener)

        try {
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            features.foreach(FeatureUtils.write(writer, _, useProvidedFid = true))
          }

          eventually(40, 100.millis)(ids.asScala mustEqual Seq.tabulate(10)(_.toString))
          eventually(40, 100.millis)(SelfClosingIterator(consumer.getFeatureReader(new Query("test"), Transaction.AUTO_COMMIT)).toSeq must
            containTheSameElementsAs(features))
          SelfClosingIterator(consumer.getFeatureReader(new Query("test2"), Transaction.AUTO_COMMIT)).toSeq must
            containTheSameElementsAs(features.drop(6).map(ScalaSimpleFeature.retype(sft2, _)))
          SelfClosingIterator(consumer.getFeatureReader(new Query("test3"), Transaction.AUTO_COMMIT)).toSeq must
            containTheSameElementsAs(derived)
          SelfClosingIterator(consumer.getFeatureReader(new Query("test4"), Transaction.AUTO_COMMIT))
            containTheSameElementsAs(features.drop(6).map(ScalaSimpleFeature.retype(sft4, _)))

          val toRemove = ECQL.toFilter("IN('0','9')")
          WithClose(producer.getFeatureWriter(sft.getTypeName, toRemove, Transaction.AUTO_COMMIT)) { writer =>
            while(writer.hasNext) {
              writer.next()
              writer.remove()
            }
          }

          eventually(40, 100.millis)(ids.asScala mustEqual Seq.tabulate(10)(_.toString).slice(1, 9))
          eventually(40, 100.millis)(SelfClosingIterator(consumer.getFeatureReader(new Query("test"), Transaction.AUTO_COMMIT)).toSeq must
            containTheSameElementsAs(features.slice(1, 9)))
          SelfClosingIterator(consumer.getFeatureReader(new Query("test2"), Transaction.AUTO_COMMIT)).toSeq must
            containTheSameElementsAs(features.drop(6).dropRight(1).map(ScalaSimpleFeature.retype(sft2, _)))
          SelfClosingIterator(consumer.getFeatureReader(new Query("test3"), Transaction.AUTO_COMMIT)).toSeq must
            containTheSameElementsAs(derived.slice(1, 9))
          SelfClosingIterator(consumer.getFeatureReader(new Query("test4"), Transaction.AUTO_COMMIT)).toSeq must
            containTheSameElementsAs(features.drop(6).dropRight(1).map(ScalaSimpleFeature.retype(sft4, _)))

          producer.getFeatureSource(sft.getTypeName).removeFeatures(Filter.INCLUDE)
          eventually(40, 100.millis)(ids.asScala must beEmpty)
          eventually(40, 100.millis)(SelfClosingIterator(consumer.getFeatureReader(new Query("test"), Transaction.AUTO_COMMIT)).toSeq must beEmpty)
          SelfClosingIterator(consumer.getFeatureReader(new Query("test2"), Transaction.AUTO_COMMIT)).toSeq must beEmpty
          SelfClosingIterator(consumer.getFeatureReader(new Query("test3"), Transaction.AUTO_COMMIT)).toSeq must beEmpty
          SelfClosingIterator(consumer.getFeatureReader(new Query("test4"), Transaction.AUTO_COMMIT)).toSeq must beEmpty
        } finally {
          store.removeFeatureListener(listener)
        }
      } finally {
        consumer.dispose()
        producer.dispose()
      }
    }

    "support at-least-once consumers" >> {
      val params = Map(
        KafkaDataStoreParams.ConsumerConfig.key -> "auto.offset.reset=earliest",
        KafkaDataStoreParams.ConsumerCount.key -> "2",
        KafkaDataStoreParams.TopicPartitions.key -> "2"
      )
      val (producer, consumer, sft) = createStorePair(params)
      try {
        val id = "fid-0"
        val numUpdates = 3
        val maxLon = 80.0

        val seen = new AtomicBoolean(false)
        val results = new CopyOnWriteArrayList[SimpleFeature]().asScala

        val processor = new GeoMessageProcessor() {
          override def consume(records: Seq[GeoMessage]): BatchResult = {
            if (!seen.get) {
              seen.set(true)
              BatchResult.Continue // this should cause the messages to be replayed
            } else {
              results ++= records.collect { case GeoMessage.Change(f) => f }
              BatchResult.Commit
            }
          }
        }

        producer.createSchema(sft)
        consumer.metadata.resetCache()

        def writeUpdates(): Unit = {
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            (numUpdates to 1 by -1).foreach { i =>
              val ll = maxLon - maxLon / i
              val sf = writer.next()
              sf.setAttributes(Array[AnyRef]("smith", Int.box(30), new Date(), s"POINT ($ll $ll)"))
              sf.getIdentifier.asInstanceOf[FeatureIdImpl].setID(s"$id-$ll")
              sf.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
              writer.write()
            }
          }
        }

        WithClose(consumer.createConsumer(sft.getTypeName, "mygroup", processor)) { _ =>
          writeUpdates()
          eventually(seen.get must beTrue)
          eventually(results must haveLength(numUpdates))
        }

        // verify that we can read a second batch
        writeUpdates()
        WithClose(consumer.createConsumer(sft.getTypeName, "mygroup", processor)) { _ =>
          eventually(results must haveLength(numUpdates * 2))
        }
      } finally {
        consumer.dispose()
        producer.dispose()
      }
    }

    "support pausing at-least-once consumers" >> {
      skipped("inconsistent")
      val params = Map(
        KafkaDataStoreParams.ConsumerConfig.key -> "auto.offset.reset=earliest",
        KafkaDataStoreParams.ConsumerCount.key -> "2",
        KafkaDataStoreParams.TopicPartitions.key -> "2"
      )
      val (producer, consumer, sft) = createStorePair(params)
      try {
        val id = "fid-0"
        val numUpdates = 3
        val maxLon = 80.0

        val in = new SynchronousQueue[Seq[SimpleFeature]]()
        val out = new SynchronousQueue[BatchResult]()

        val processor = new GeoMessageProcessor() {
          override def consume(records: Seq[GeoMessage]): BatchResult = {
            in.offer(records.collect { case GeoMessage.Change(f) => f }, 10, TimeUnit.SECONDS)
            Option(out.poll(10, TimeUnit.SECONDS)).getOrElse(BatchResult.Continue)
          }
        }

        producer.createSchema(sft)
        consumer.metadata.resetCache()

        def writeUpdates(): Unit = {
          WithClose(producer.getFeatureWriterAppend(sft.getTypeName, Transaction.AUTO_COMMIT)) { writer =>
            (numUpdates to 1 by -1).foreach { i =>
              val ll = maxLon - maxLon / i
              val sf = writer.next()
              sf.setAttributes(Array[AnyRef]("smith", Int.box(30), new Date(), s"POINT ($ll $ll)"))
              sf.getIdentifier.asInstanceOf[FeatureIdImpl].setID(s"$id-$ll")
              sf.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE)
              writer.write()
            }
          }
        }

        WithClose(consumer.createConsumer(sft.getTypeName, "mygroup", processor)) { _ =>
          writeUpdates()
          in.poll(10, TimeUnit.SECONDS) must haveLength(numUpdates)
          out.put(BatchResult.Pause)
          writeUpdates()
          in.poll(10, TimeUnit.SECONDS) must haveLength(numUpdates)
          foreach(0 until 10) { _ =>
            out.put(BatchResult.Pause)
            in.poll(10, TimeUnit.SECONDS) must haveLength(numUpdates)
          }
          out.put(BatchResult.Continue)
          eventually {
            val res = in.poll(10, TimeUnit.SECONDS)
            out.put(BatchResult.Continue)
            res must haveLength(numUpdates * 2)
          }
          in.poll(10, TimeUnit.SECONDS) must haveLength(numUpdates * 2)
          out.put(BatchResult.Commit)
          writeUpdates()
          in.poll(10, TimeUnit.SECONDS) must haveLength(numUpdates)
          out.put(BatchResult.Commit)
        }
        ok
      } finally {
        consumer.dispose()
        producer.dispose()
      }
    }

    "migrate old kafka data store schemas" >> {
      val spec = "test:String,dtg:Date,*location:Point:srid=4326"

      val path = s"geomesa/migrate/test/${paths.getAndIncrement()}"
      val client = CuratorFrameworkFactory.builder()
          .namespace(path)
          .connectString(zookeepers)
          .retryPolicy(new ExponentialBackoffRetry(1000, 3))
          .build()
      client.start()

      try {
        client.create.forPath("/test", s"$spec;geomesa.index.dtg=dtg".getBytes(StandardCharsets.UTF_8))
        client.create.forPath("/test/Topic", "test-topic".getBytes(StandardCharsets.UTF_8))

        val ds = getStore(path, 0, Map("kafka.zookeepers" -> zookeepers))
        try {
          ds.getTypeNames.toSeq mustEqual Seq("test")
          val sft = ds.getSchema("test")
          sft must not(beNull)
          KafkaDataStore.topic(sft) mustEqual "test-topic"
          SimpleFeatureTypes.encodeType(sft) mustEqual spec

          client.checkExists().forPath("/test") must beNull
        } finally {
          ds.dispose()
        }
      } finally {
        client.close()
      }
    }

    "configure topics by feature type" in {
      val ds = getStore(getUniquePath, 0)
      try {
        val sft = SimpleFeatureTypes.createType("test", "name:String,age:Int,dtg:Date,*geom:Point:srid=4326;")
        sft.getUserData.put("kafka.topic.config", "cleanup.policy=compact\nretention.ms=86400000")
        ds.createSchema(sft)
        val topic = KafkaDataStore.topic(ds.getSchema(sft.getTypeName))
        val props = new Properties()
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)

        WithClose(AdminClient.create(props)) { admin =>
          val configs =
            admin.describeConfigs(Collections.singletonList(new ConfigResource(ConfigResource.Type.TOPIC, topic)))
          val config = configs.values().get(new ConfigResource(ConfigResource.Type.TOPIC, topic)).get()
          config must not(beNull)
          config.entries().asScala.map(e => e.name() -> e.value()).toMap must
              containAllOf(Seq("cleanup.policy" -> "compact", "retention.ms" -> "86400000"))
        }
      } finally {
        ds.dispose()
      }
    }

    "update compaction policy for catalog topics if not set" in {
      val props = new Properties()
      props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
      val path = getUniquePath
      val topic = StringUtils.strip(path, " /").replace("/", "-")
      //Create the topic
      WithClose(AdminClient.create(props)) { admin =>
        val newTopic = new NewTopic(topic, 1, 1.toShort)
        admin.createTopics(Collections.singletonList(newTopic)).all().get
      }
      val ds = getStore(path, 0)
      try {
        ds.getTypeNames()
        //Verify the compaction policy
        WithClose(AdminClient.create(props)) { admin =>
          val configs =
            admin.describeConfigs(Collections.singletonList(new ConfigResource(ConfigResource.Type.TOPIC, topic)))
          val config = configs.values().get(new ConfigResource(ConfigResource.Type.TOPIC, topic)).get()
          config must not(beNull)
          config.entries().asScala.map(e => e.name() -> e.value()).toMap must
            containAllOf(Seq("cleanup.policy" -> "compact"))
        }
      } finally {
        ds.dispose()
      }
    }

  }

  "KafkaDataStoreFactory" should {
    "clean zkPath" >> {
      def getNamespace(path: java.io.Serializable): String =
        KafkaDataStoreFactory.createZkNamespace(Map(KafkaDataStoreParams.ZkPath.getName -> path).asJava)

      // a well formed path starts does not start or end with a /
      getNamespace("foo/bar/baz") mustEqual "foo/bar/baz"
      getNamespace("foo/bar/baz/") mustEqual "foo/bar/baz" // trailing slash
      getNamespace("/foo/bar/baz") mustEqual "foo/bar/baz" // leading slash
      getNamespace("/foo/bar/baz/") mustEqual "foo/bar/baz" // both leading and trailing slash
      forall(Seq("/", "//", "", null))(n => getNamespace(n) mustEqual KafkaDataStoreFactory.DefaultZkPath) // empty
    }
    "Parse SSI tiers" >> {
      val key = KafkaDataStoreParams.IndexTiers.getName
      KafkaDataStoreFactory.parseSsiTiers(Collections.emptyMap()) mustEqual SizeSeparatedBucketIndex.DefaultTiers
      KafkaDataStoreFactory.parseSsiTiers(Collections.singletonMap(key, "foo")) mustEqual SizeSeparatedBucketIndex.DefaultTiers
      KafkaDataStoreFactory.parseSsiTiers(Collections.singletonMap(key, "1:2")) mustEqual Seq((1d, 2d))
      KafkaDataStoreFactory.parseSsiTiers(Collections.singletonMap(key, "1:2,3:4")) mustEqual Seq((1d, 2d), (3d, 4d))
      KafkaDataStoreFactory.parseSsiTiers(Collections.singletonMap(key, "3:4,1:2")) mustEqual Seq((1d, 2d), (3d, 4d))
    }
  }

  "KafkaFeatureSource" should {
    "handle Query instances with null TypeName (GeoServer querylayer extension implementation nuance)" >> {
      val (producer, consumer, sft) = createStorePair()
      try {
        producer.createSchema(sft)
        consumer.metadata.resetCache()
        val fs = consumer.getFeatureSource(sft.getTypeName)
        val q = new Query(null, Filter.INCLUDE)
        fs.getFeatures(q).features().close() must not(throwA[NullPointerException])
      } finally {
        producer.dispose()
        consumer.dispose()
      }
    }
  }
}

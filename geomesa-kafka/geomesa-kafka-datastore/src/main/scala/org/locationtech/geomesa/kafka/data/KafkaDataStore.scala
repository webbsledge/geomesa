/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.kafka.data

import com.github.benmanes.caffeine.cache.{CacheLoader, Caffeine, Ticker}
import com.typesafe.scalalogging.LazyLogging
import io.micrometer.core.instrument.Tags
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, NewTopic}
import org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG
import org.apache.kafka.clients.consumer.{Consumer, KafkaConsumer}
import org.apache.kafka.clients.producer.ProducerConfig.{ACKS_CONFIG, PARTITIONER_CLASS_CONFIG}
import org.apache.kafka.clients.producer.{KafkaProducer, Producer}
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, ByteArraySerializer}
import org.geotools.api.data.{Query, SimpleFeatureStore, Transaction}
import org.geotools.api.feature.simple.SimpleFeatureType
import org.geotools.api.filter.Filter
import org.locationtech.geomesa.features.SerializationType.SerializationType
import org.locationtech.geomesa.filter.factory.FastFilterFactory
import org.locationtech.geomesa.index.FlushableFeatureWriter
import org.locationtech.geomesa.index.audit.AuditWriter
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory.NamespaceConfig
import org.locationtech.geomesa.index.geotools.{GeoMesaFeatureReader, MetadataBackedDataStore}
import org.locationtech.geomesa.index.metadata.GeoMesaMetadata
import org.locationtech.geomesa.index.stats.{GeoMesaStats, HasGeoMesaStats, RunnableStats}
import org.locationtech.geomesa.index.utils.DistributedLocking.LocalLocking
import org.locationtech.geomesa.kafka.consumer.ThreadedConsumer.ConsumerErrorHandler
import org.locationtech.geomesa.kafka.data.KafkaCacheLoader.KafkaCacheLoaderImpl
import org.locationtech.geomesa.kafka.data.KafkaDataStore.KafkaDataStoreConfig
import org.locationtech.geomesa.kafka.data.KafkaFeatureWriter._
import org.locationtech.geomesa.kafka.index._
import org.locationtech.geomesa.kafka.utils.GeoMessageProcessor
import org.locationtech.geomesa.kafka.utils.GeoMessageProcessor.GeoMessageConsumer
import org.locationtech.geomesa.kafka.utils.GeoMessageSerializer.{GeoMessagePartitioner, GeoMessageSerializerFactory}
import org.locationtech.geomesa.kafka.versions.KafkaConsumerVersions
import org.locationtech.geomesa.memory.cqengine.utils.CQIndexType.CQIndexType
import org.locationtech.geomesa.metrics.core.GeoMesaMetrics
import org.locationtech.geomesa.metrics.micrometer.{MetricsTags, RegistrySetup}
import org.locationtech.geomesa.security.AuthorizationsProvider
import org.locationtech.geomesa.utils.conf.GeoMesaSystemProperties.SystemProperty
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes.Configs.TableSharing
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes.InternalConfigs.TableSharingPrefix
import org.locationtech.geomesa.utils.geotools.Transform.Transforms
import org.locationtech.geomesa.utils.geotools.{SimpleFeatureTypes, Transform}
import org.locationtech.geomesa.utils.io.{CloseWithLogging, WithClose}
import org.locationtech.geomesa.utils.zk.ZookeeperLocking

import java.io.{Closeable, IOException, StringReader}
import java.util.concurrent.{ConcurrentHashMap, ScheduledExecutorService}
import java.util.{Collections, Properties, UUID}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal

class KafkaDataStore(
    val config: KafkaDataStoreConfig,
    val metadata: GeoMesaMetadata[String],
    private[kafka] val serialization: GeoMessageSerializerFactory
  ) extends MetadataBackedDataStore(config) with HasGeoMesaStats with LocalLocking {

  import KafkaDataStore.TopicKey
  import org.apache.kafka.clients.producer.ProducerConfig.TRANSACTIONAL_ID_CONFIG
  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

  import scala.collection.JavaConverters._

  override val stats: GeoMesaStats = new RunnableStats(this)

  private val registry = config.registrySetup.map(_.register())

  // note: sharing a single producer is generally faster
  // http://kafka.apache.org/0110/javadoc/index.html?org/apache/kafka/clients/producer/KafkaProducer.html

  // only instantiate the producer if needed
  private val defaultProducer = new LazyProducer(KafkaDataStore.producer(config.brokers, config.producers.properties))
  // noinspection ScalaDeprecation
  private val partitionedProducer = new LazyProducer(KafkaDataStore.producer(config))

  // view type name -> actual type name
  private val layerViewLookup =
    config.layerViewsConfig.flatMap { case (typeName, views) => views.map(_.typeName -> typeName).toMap }

  private val cleared = Collections.newSetFromMap(new ConcurrentHashMap[String, java.lang.Boolean]())

  private val caches = Caffeine.newBuilder().build[String, KafkaCacheLoader](new CacheLoader[String, KafkaCacheLoader] {
    override def load(key: String): KafkaCacheLoader = {
      if (config.consumers.count < 1) {
        logger.info("Kafka consumers disabled for this data store instance")
        KafkaCacheLoader.NoOpLoader
      } else {
        val sft = KafkaDataStore.super.getSchema(key)
        val views = config.layerViewsConfig.getOrElse(key, Seq.empty).map(KafkaDataStore.createLayerView(sft, _))
        // if the expiry is zero, this will return a NoOpFeatureCache
        val cache = KafkaFeatureCache(sft, config.indices, views)
        val topic = KafkaDataStore.topic(sft)
        val consumers = KafkaDataStore.consumers(config.brokers, topic, config.consumers)
        val frequency = java.time.Duration.ofMillis(KafkaDataStore.LoadIntervalProperty.toDuration.get.toMillis)
        val serializer = serialization.apply(sft)
        val readBack = config.consumers.readBack
        val expiry = config.indices.expiry
        val offsetCommitInterval = config.consumers.offsetCommitInterval
        val loader = new KafkaCacheLoaderImpl(sft, cache, consumers, topic, frequency, offsetCommitInterval, serializer, readBack, expiry)
        try { loader.start() } catch {
          case NonFatal(e) =>
            CloseWithLogging(loader)
            throw e
        }
        loader
      }
    }
  })

  private val runner = new KafkaQueryRunner(this, cache)

  /**
    * Start consuming from all topics. Consumers are normally only started for a simple feature type
    * when it is first queried - this will start them immediately.
    */
  def startAllConsumers(): Unit = super.getTypeNames.foreach(caches.get)

  /**
   * Create a message consumer for the given feature type. This can be used for guaranteed at-least-once
   * message processing
   *
   * @param typeName type name
   * @param groupId consumer group id
   * @param processor message processor
   * @return
   */
  def createConsumer(typeName: String, groupId: String, processor: GeoMessageProcessor): Closeable =
    createConsumer(typeName, groupId, processor, None)

  /**
   * Create a message consumer for the given feature type. This can be used for guaranteed at-least-once
   * message processing
   *
   * @param typeName type name
   * @param groupId consumer group id
   * @param processor message processor
   * @param errorHandler error handler
   * @return
   */
  def createConsumer(
      typeName: String,
      groupId: String,
      processor: GeoMessageProcessor,
      errorHandler: Option[ConsumerErrorHandler]): Closeable = {
    val sft = getSchema(typeName)
    if (sft == null) {
      throw new IllegalArgumentException(s"Schema '$typeName' does not exist; call `createSchema` first")
    }
    val topic = KafkaDataStore.topic(sft)
    val consumers =
      KafkaDataStore.consumers(config.brokers, topic,
        config.consumers.copy(properties = config.consumers.properties + (GROUP_ID_CONFIG -> groupId))) // add group id
    consumers.foreach(KafkaConsumerVersions.subscribe(_, topic))
    val frequency = java.time.Duration.ofMillis(KafkaDataStore.LoadIntervalProperty.toDuration.get.toMillis)
    val serializer = serialization.apply(sft)
    val consumer = new GeoMessageConsumer(consumers, frequency, serializer, processor)
    consumer.startConsumers(errorHandler)
    consumer
  }

  override def getSchema(typeName: String): SimpleFeatureType = {
    layerViewLookup.get(typeName) match {
      case None => super.getSchema(typeName)
      case Some(orig) =>
        val parent = super.getSchema(orig)
        if (parent == null) {
          logger.warn(s"Backing schema '$orig' for configured layer view '$typeName' does not exist")
          null
        } else {
          val view = config.layerViewsConfig.get(orig).flatMap(_.find(_.typeName == typeName)).getOrElse {
            // this should be impossible since we created the lookup from the view config
            throw new IllegalStateException("Inconsistent layer view config")
          }
          KafkaDataStore.createLayerView(parent, view).viewSft
        }
    }
  }

  override def getTypeNames: Array[String] = {
    val nonViews = super.getTypeNames
    nonViews ++ layerViewLookup.toArray.flatMap { case (k, v) =>
      if (nonViews.contains(v)) {
        Some(k)
      } else {
        logger.warn(s"Backing schema '$v' for configured layer view '$k' does not exist")
        None
      }
    }
  }

  @throws(classOf[IllegalArgumentException])
  override protected def preSchemaCreate(sft: SimpleFeatureType): Unit = {
    // note: kafka doesn't allow slashes in topic names
    KafkaDataStore.topic(sft) match {
      case null  => KafkaDataStore.setTopic(sft, s"${config.catalog}-${sft.getTypeName}".replaceAll("/", "-"))
      case topic if topic.contains("/") => throw new IllegalArgumentException(s"Topic cannot contain '/': $topic")
      case topic => logger.debug(s"Using user-defined topic [$topic]")
    }
    // disable our custom partitioner by default, as it messes with Kafka streams co-partition joining
    // and it's not required since we switched our keys to be feature ids
    if (!sft.getUserData.containsKey(KafkaDataStore.PartitioningKey)) {
      sft.getUserData.put(KafkaDataStore.PartitioningKey, KafkaDataStore.PartitioningDefault)
    }
    // remove table sharing as it's not relevant
    sft.getUserData.remove(TableSharing)
    sft.getUserData.remove(TableSharingPrefix)
  }

  @throws(classOf[IllegalArgumentException])
  override protected def preSchemaUpdate(sft: SimpleFeatureType, previous: SimpleFeatureType): Unit = {
    if (layerViewLookup.contains(sft.getTypeName)) {
      throw new IllegalArgumentException(
        s"Schema '${sft.getTypeName}' is a read-only view of '${layerViewLookup(sft.getTypeName)}'")
    }
    val topic = KafkaDataStore.topic(sft)
    if (topic == null) {
      throw new IllegalArgumentException(s"Topic must be defined in user data under '$TopicKey'")
    } else if (topic != KafkaDataStore.topic(previous)) {
      if (topic.contains("/")) {
        throw new IllegalArgumentException(s"Topic cannot contain '/': $topic")
      }
      onSchemaDeleted(previous)
      onSchemaCreated(sft)
    }
  }

  // create kafka topic
  override protected def onSchemaCreated(sft: SimpleFeatureType): Unit = {
    val topic = KafkaDataStore.topic(sft)
    val props = new Properties()
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.brokers)
    config.consumers.properties.foreach { case (k, v) => props.put(k, v) }
    config.producers.properties.foreach { case (k, v) => props.put(k, v) }

    WithClose(AdminClient.create(props)) { admin =>
      if (admin.listTopics().names().get.contains(topic)) {
        logger.warn(
          s"Topic [$topic] already exists - it may contain invalid data and/or not " +
              "match the expected topic configuration")
      } else {
        val newTopic =
          new NewTopic(topic, config.topics.partitions, config.topics.replication.toShort)
              .configs(KafkaDataStore.topicConfig(sft))
        admin.createTopics(Collections.singletonList(newTopic)).all().get
      }
    }
  }

  // invalidate any cached consumers in order to reload the new schema
  override protected def onSchemaUpdated(sft: SimpleFeatureType, previous: SimpleFeatureType): Unit = {
    Option(caches.getIfPresent(sft.getTypeName)).foreach { cache =>
      cache.close()
      caches.invalidate(sft.getTypeName)
    }
  }

  // stop consumers and delete kafka topic
  override protected def onSchemaDeleted(sft: SimpleFeatureType): Unit = {
    if (layerViewLookup.contains(sft.getTypeName)) {
      throw new IllegalArgumentException(
        s"Schema '${sft.getTypeName}' is a read-only view of '${layerViewLookup(sft.getTypeName)}'")
    }
    Option(caches.getIfPresent(sft.getTypeName)).foreach { cache =>
      cache.close()
      caches.invalidate(sft.getTypeName)
    }
    val topic = KafkaDataStore.topic(sft)
    val props = new Properties()
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.brokers)
    config.consumers.properties.foreach { case (k, v) => props.put(k, v) }
    config.producers.properties.foreach { case (k, v) => props.put(k, v) }

    WithClose(AdminClient.create(props)) { admin =>
      if (admin.listTopics().names().get.contains(topic)) {
        admin.deleteTopics(Collections.singletonList(topic)).all().get
      } else {
        logger.warn(s"Topic [$topic] does not exist, can't delete it")
      }
    }
  }

  /**
    * @see org.geotools.api.data.DataStore#getFeatureSource(org.geotools.api.feature.type.Name)
    * @param typeName simple feature type name
    * @return featureStore, suitable for reading and writing
    */
  override def getFeatureSource(typeName: String): SimpleFeatureStore = {
    val sft = getSchema(typeName)
    if (sft == null) {
      throw new IOException(s"Schema '$typeName' has not been initialized. Please call 'createSchema' first.")
    }
    new KafkaFeatureStore(this, sft, cache(typeName))
  }

  override private[geomesa] def getFeatureReader(
      sft: SimpleFeatureType,
      transaction: Transaction,
      query: Query): GeoMesaFeatureReader = {
    // kick off the kafka consumers for this sft, if not already started
    caches.get(layerViewLookup.getOrElse(query.getTypeName, query.getTypeName))
    GeoMesaFeatureReader(sft, query, runner, config.audit)
  }

  override private[geomesa] def getFeatureWriter(
      sft: SimpleFeatureType,
      transaction: Transaction,
      filter: Option[Filter]): FlushableFeatureWriter = {
    if (layerViewLookup.contains(sft.getTypeName)) {
      throw new IllegalArgumentException(
        s"Schema '${sft.getTypeName}' is a read-only view of '${layerViewLookup(sft.getTypeName)}'")
    }
    val producer = getTransactionalProducer(sft, transaction)
    val vis = sft.isVisibilityRequired
    val serializer = serialization.apply(sft)
    val writer = filter match {
      case None if vis    => new AppendKafkaFeatureWriter(sft, producer, serializer) with RequiredVisibilityWriter
      case None           => new AppendKafkaFeatureWriter(sft, producer, serializer)
      case Some(f) if vis => new ModifyKafkaFeatureWriter(sft, producer, serializer, f) with RequiredVisibilityWriter
      case Some(f)        => new ModifyKafkaFeatureWriter(sft, producer, serializer, f)
    }
    if (config.clearOnStart && cleared.add(sft.getTypeName)) {
      writer.clear()
    }
    writer
  }

  override def dispose(): Unit = {
    CloseWithLogging(defaultProducer)
    CloseWithLogging(partitionedProducer)
    CloseWithLogging(caches.asMap.asScala.values)
    CloseWithLogging(registry)
    CloseWithLogging(config.metrics)
    caches.invalidateAll()
    super.dispose()
  }

  private def getTransactionalProducer(sft: SimpleFeatureType, transaction: Transaction): KafkaFeatureProducer = {
    val useDefaultPartitioning = KafkaDataStore.usesDefaultPartitioning(sft)

    if (transaction == null || transaction == Transaction.AUTO_COMMIT) {
      val producer = if (useDefaultPartitioning) { defaultProducer.instance } else { partitionedProducer.instance }
      return AutoCommitProducer(producer)
    }

    val state = transaction.getState(KafkaDataStore.TransactionStateKey)
    if (state == null) {
      val partitioner = if (useDefaultPartitioning) { Map.empty } else {
        Map(PARTITIONER_CLASS_CONFIG -> classOf[GeoMessagePartitioner].getName)
      }
      // add kafka transactional id if it's not set, but force acks to "all" as required by kafka
      val props =
        Map(TRANSACTIONAL_ID_CONFIG -> UUID.randomUUID().toString) ++
            partitioner ++
            config.producers.properties ++
            Map(ACKS_CONFIG -> "all")
      val producer = KafkaTransactionState(KafkaDataStore.producer(config.brokers, props))
      transaction.putState(KafkaDataStore.TransactionStateKey, producer)
      producer
    } else {
      state match {
        case p: KafkaTransactionState => p
        case _ => throw new IllegalArgumentException(s"Found non-kafka state in transaction: $state")
      }
    }
  }

  /**
   * Get the feature cache for the type name, which may be a real feature type or a view
   *
   * @param typeName type name
   * @return
   */
  private def cache(typeName: String): KafkaFeatureCache = {
    layerViewLookup.get(typeName) match {
      case None => caches.get(typeName).cache
      case Some(orig) =>
        caches.get(orig).cache.views.find(_.sft.getTypeName == typeName).getOrElse {
          throw new IllegalStateException(
            s"Could not find layer view for typeName '$typeName' in cache ${caches.get(orig)}")
        }
    }
  }
}

object KafkaDataStore extends LazyLogging {

  val TopicKey = "geomesa.kafka.topic"
  val TopicConfigKey = "kafka.topic.config"
  val PartitioningKey = "geomesa.kafka.partitioning"

  val MetadataPath = "metadata"

  val TransactionStateKey = "geomesa.kafka.state"

  val PartitioningDefault = "default"

  val MetricsPrefix = "geomesa.kafka"

  val LoadIntervalProperty: SystemProperty = SystemProperty("geomesa.kafka.load.interval", "1s")

  // marker to trigger the cq engine index when using the deprecated enable flag
  private[kafka] val CqIndexFlag: (String, CQIndexType) = null

  def topic(sft: SimpleFeatureType): String = sft.getUserData.get(TopicKey).asInstanceOf[String]

  def setTopic(sft: SimpleFeatureType, topic: String): Unit = sft.getUserData.put(TopicKey, topic)

  def topicConfig(sft: SimpleFeatureType): java.util.Map[String, String] = {
    val props = new Properties()
    val config = sft.getUserData.get(TopicConfigKey).asInstanceOf[String]
    if (config != null) {
      props.load(new StringReader(config))
    }
    props.asInstanceOf[java.util.Map[String, String]]
  }

  def usesDefaultPartitioning(sft: SimpleFeatureType): Boolean =
    sft.getUserData.get(PartitioningKey) == PartitioningDefault

  /**
   * Get tags for metrics
   *
   * @param sft simple feature type
   * @return
   */
  private[kafka] def tags(sft: SimpleFeatureType): Tags = MetricsTags.typeNameTag(sft.getTypeName)

  @deprecated("Uses a custom partitioner which creates issues with Kafka streams. Use `producer(String, Map[String, String]) instead")
  def producer(config: KafkaDataStoreConfig): Producer[Array[Byte], Array[Byte]] = {
    val props =
      if (config.producers.properties.contains(PARTITIONER_CLASS_CONFIG)) {
        config.producers.properties
      } else {
        config.producers.properties + (PARTITIONER_CLASS_CONFIG -> classOf[GeoMessagePartitioner].getName)
      }
    producer(config.brokers, props)
  }

  /**
   * Create a Kafka producer
   *
   * @param bootstrapServers Kafka bootstrap servers config
   * @param properties Kafka producer properties
   * @return
   */
  def producer(bootstrapServers: String, properties: Map[String, String]): Producer[Array[Byte], Array[Byte]] = {
    import org.apache.kafka.clients.producer.ProducerConfig._

    val props = new Properties()
    // set some defaults but allow them to be overridden
    props.put(ACKS_CONFIG, "1") // mix of reliability and performance
    props.put(RETRIES_CONFIG, Int.box(3))
    props.put(LINGER_MS_CONFIG, Int.box(3)) // helps improve batching at the expense of slight delays in write
    props.put(KEY_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
    props.put(VALUE_SERIALIZER_CLASS_CONFIG, classOf[ByteArraySerializer].getName)
    props.put(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    properties.foreach { case (k, v) => props.put(k, v) }
    new KafkaProducer[Array[Byte], Array[Byte]](props)
  }

  def consumer(config: KafkaDataStoreConfig, group: String): Consumer[Array[Byte], Array[Byte]] =
    consumer(config.brokers, Map(GROUP_ID_CONFIG -> group) ++ config.consumers.properties)

  def consumer(brokers: String, properties: Map[String, String]): Consumer[Array[Byte], Array[Byte]] = {
    import org.apache.kafka.clients.consumer.ConsumerConfig._

    val props = new Properties()
    props.put(BOOTSTRAP_SERVERS_CONFIG, brokers)
    props.put(ENABLE_AUTO_COMMIT_CONFIG, "false")
    props.put(KEY_DESERIALIZER_CLASS_CONFIG, classOf[ByteArrayDeserializer].getName)
    props.put(VALUE_DESERIALIZER_CLASS_CONFIG, classOf[ByteArrayDeserializer].getName)
    properties.foreach { case (k, v) => props.put(k, v) }

    new KafkaConsumer[Array[Byte], Array[Byte]](props)
  }

  // creates a consumer and sets to the latest offsets
  private[kafka] def consumers(
      brokers: String,
      topic: String,
      config: ConsumerConfig): Seq[Consumer[Array[Byte], Array[Byte]]] = {
    require(config.count > 0, "Number of consumers must be greater than 0")
    val props = Map(GROUP_ID_CONFIG -> s"${config.groupPrefix}${UUID.randomUUID()}") ++ config.properties
    logger.debug(s"Creating ${config.count} consumers for topic [$topic] with group-id [${props(GROUP_ID_CONFIG)}]")
    Seq.fill(config.count)(KafkaDataStore.consumer(brokers, props))
  }

  /**
   * Create a layer view based on a config and the actual feature type
   *
   * @param sft simple feature type the view is based on
   * @param config layer view config
   * @return
   */
  private[kafka] def createLayerView(sft: SimpleFeatureType, config: LayerViewConfig): LayerView = {
    val viewSft = SimpleFeatureTypes.renameSft(sft, config.typeName)
    val filter = config.filter.map(FastFilterFactory.optimize(viewSft, _))
    val transform = config.transform.map(Transforms(viewSft, _))
    val finalSft = transform.map(Transforms.schema(viewSft, _)).getOrElse(viewSft)
    LayerView(finalSft, filter, transform)
  }

  class KafkaDataStoreWithZk(
      config: KafkaDataStoreConfig,
      metadata: GeoMesaMetadata[String],
      serialization: GeoMessageSerializerFactory,
      override protected val zookeepers: String
    ) extends KafkaDataStore(config, metadata, serialization) with ZookeeperLocking

  case class KafkaDataStoreConfig(
      catalog: String,
      brokers: String,
      zookeepers: Option[String],
      consumers: ConsumerConfig,
      producers: ProducerConfig,
      clearOnStart: Boolean,
      topics: TopicConfig,
      @deprecated("unused")
      serialization: SerializationType,
      indices: IndexConfig,
      looseBBox: Boolean,
      layerViewsConfig: Map[String, Seq[LayerViewConfig]],
      authProvider: AuthorizationsProvider,
      audit: Option[AuditWriter],
      @deprecated("replaced with micrometer metrics")
      metrics: Option[GeoMesaMetrics],
      registrySetup: Option[RegistrySetup],
      namespace: Option[String]) extends NamespaceConfig

  case class ConsumerConfig(
      count: Int,
      groupPrefix: String,
      properties: Map[String, String],
      readBack: Option[Duration],
      offsetCommitInterval: FiniteDuration,
    )

  case class ProducerConfig(properties: Map[String, String])

  case class TopicConfig(partitions: Int, replication: Int)

  case class IndexConfig(
      expiry: ExpiryTimeConfig,
      resolution: IndexResolution,
      ssiTiers: Seq[(Double, Double)],
      cqAttributes: Seq[(String, CQIndexType)],
      @deprecated("unused")
      lazyDeserialization: Boolean,
      executor: Option[(ScheduledExecutorService, Ticker)]
    )

  case class IndexResolution(x: Int, y: Int)

  sealed trait ExpiryTimeConfig
  case object NeverExpireConfig extends ExpiryTimeConfig
  case object ImmediatelyExpireConfig extends ExpiryTimeConfig
  case class IngestTimeConfig(expiry: Duration) extends ExpiryTimeConfig
  case class EventTimeConfig(expiry: Duration, expression: String, ordered: Boolean) extends ExpiryTimeConfig
  case class FilteredExpiryConfig(expiry: Seq[(String, ExpiryTimeConfig)]) extends ExpiryTimeConfig

  case class LayerViewConfig(typeName: String, filter: Option[Filter], transform: Option[Seq[String]])
  case class LayerView(viewSft: SimpleFeatureType, filter: Option[Filter], transform: Option[Seq[Transform]])
}

/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.fs.data

import com.github.benmanes.caffeine.cache.{CacheLoader, Caffeine, RemovalCause, RemovalListener}
import com.typesafe.scalalogging.LazyLogging
import org.geotools.api.data.{FeatureReader, FeatureWriter, Query, QueryCapabilities}
import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.geotools.api.filter.Filter
import org.geotools.data.simple.DelegateSimpleFeatureReader
import org.geotools.data.store.{ContentEntry, ContentFeatureStore}
import org.geotools.feature.collection.DelegateSimpleFeatureIterator
import org.geotools.geometry.jts.ReferencedEnvelope
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.fs.data.FileSystemFeatureStore._
import org.locationtech.geomesa.fs.storage.api.FileSystemStorage.FileSystemWriter
import org.locationtech.geomesa.fs.storage.api.{CloseableFeatureIterator, FileSystemStorage}
import org.locationtech.geomesa.index.geotools.{GeoMesaFeatureWriter, FastSettableFeatureWriter}
import org.locationtech.geomesa.index.utils.ThreadManagement.{LowLevelScanner, ManagedScan, Timeout}
import org.locationtech.geomesa.utils.io.{CloseQuietly, CloseWithLogging, FlushQuietly, FlushWithLogging}

import java.io.{Closeable, Flushable}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.Duration

class FileSystemFeatureStore(
    val storage: FileSystemStorage,
    entry: ContentEntry,
    query: Query,
    readThreads: Int,
    writeTimeout: Duration,
    queryTimeout: Option[Duration],
  ) extends ContentFeatureStore(entry, query) with LazyLogging {

  private val sft = storage.metadata.sft

  override def getWriterInternal(query: Query, flags: Int): FeatureWriter[SimpleFeatureType, SimpleFeature] = {
    // note: check update first as sometimes we get ADD | UPDATE
    if ((flags & WRITER_UPDATE) == WRITER_UPDATE) {
      new FileSystemFeatureWriterModify(storage, sft, query.getFilter, readThreads)
    } else if ((flags & WRITER_ADD) == WRITER_ADD) {
      new FileSystemFeatureWriterAppend(storage, sft, writeTimeout)
    } else {
      throw new IllegalArgumentException(s"Expected one of $WRITER_ADD or $WRITER_UPDATE, but got: $flags")
    }
  }

  override def buildFeatureType(): SimpleFeatureType = sft

  override def getBoundsInternal(query: Query): ReferencedEnvelope = {
    val envelope = new ReferencedEnvelope(org.locationtech.geomesa.utils.geotools.CRS_EPSG_4326)
    storage.getPartitions(query.getFilter).foreach { partition =>
      partition.bounds.foreach(b => envelope.expandToInclude(b.envelope))
    }
    envelope
  }

  override def getCountInternal(query: Query): Int =
    storage.getPartitions(query.getFilter).map(_.count).sum.toInt

  override def getReaderInternal(original: Query): FeatureReader[SimpleFeatureType, SimpleFeature] = {
    import org.locationtech.geomesa.index.conf.QueryHints._

    val query = new Query(original)
    // The type name can sometimes be empty such as Query.ALL
    query.setTypeName(sft.getTypeName)

    val reader = queryTimeout match {
      case None => storage.getReader(query, threads = readThreads)
      case Some(timeout) =>
        val filter = Option(query.getFilter).filter(_ != Filter.INCLUDE)
        new ManagedScan(new FileSystemScanner(storage, query, readThreads), Timeout(timeout), query.getTypeName, filter)
    }
    // get a closeable java iterator that DelegateSimpleFeatureIterator will process correctly
    val iter = new FileSystemFeatureIterator(reader)

    // transforms will be set after getting the iterator
    val transformSft = query.getHints.getTransformSchema.getOrElse(sft)

    // note: DelegateSimpleFeatureIterator will close the iterator by checking that it implements Closeable
    new DelegateSimpleFeatureReader(transformSft, new DelegateSimpleFeatureIterator(iter))
  }

  override def canLimit: Boolean = false
  override def canLimit(query: Query): Boolean = false
  override def canTransact: Boolean = false
  override def canEvent: Boolean = false
  override def canReproject: Boolean = false
  override def canSort: Boolean = false
  override def canSort(query: Query): Boolean = false
  override def canFilter: Boolean = true
  override def canFilter(query: Query): Boolean = true
  override def canRetype: Boolean = true
  override def canRetype(query: Query): Boolean = true

  override protected def buildQueryCapabilities(): QueryCapabilities = FileSystemFeatureStore.capabilities
}

object FileSystemFeatureStore {

  import scala.collection.JavaConverters._

  private val capabilities = new QueryCapabilities() {
    override def isReliableFIDSupported: Boolean = true
    override def isUseProvidedFIDSupported: Boolean = true
  }

  private class FileSystemScanner(storage: FileSystemStorage, val query: Query, threads: Int)
      extends LowLevelScanner[SimpleFeature] {

    private var reader: CloseableFeatureIterator = _

    override def iterator: Iterator[SimpleFeature] = synchronized {
      reader = storage.getReader(query, threads = threads)
      reader
    }
    override def close(): Unit = synchronized {
      if (reader != null) {
        reader.close()
      }
    }
  }

  /**
    * Iterator for querying file system storage
    *
    * Note: implements Closeable and not AutoCloseable so that DelegateFeatureIterator will close it properly
    *
    * @param iter delegate iterator
    */
  class FileSystemFeatureIterator(iter: CloseableFeatureIterator)
      extends java.util.Iterator[SimpleFeature] with Closeable {
    override def hasNext: Boolean = iter.hasNext
    override def next(): SimpleFeature = iter.next()
    override def close(): Unit = iter.close()
  }

  /**
    * Appending feature writer
    *
    * @param storage storage instance
    * @param sft simple feature type
    * @param timeout write timeout, for flushing partitions
    */
  class FileSystemFeatureWriterAppend(storage: FileSystemStorage, sft: SimpleFeatureType, timeout: Duration)
      extends FastSettableFeatureWriter with LazyLogging {

    private val removalListener = new RemovalListener[String, Closeable with Flushable]() {
      override def onRemoval(key: String, value: Closeable with Flushable, cause: RemovalCause): Unit = {
        if (cause == RemovalCause.EXPIRED) {
          logger.info(s"Flushing writer for partition: $key")
          FlushWithLogging(value)
          CloseWithLogging(value)
        }
      }
    }

    private val writers =
      Caffeine.newBuilder()
        .expireAfterAccess(timeout.toMillis, TimeUnit.MILLISECONDS)
        .removalListener[String, FileSystemWriter](removalListener)
        .build(new CacheLoader[String, FileSystemWriter]() {
          override def load(partition: String): FileSystemWriter = storage.getWriter(partition)
        })

    private val featureIds = new AtomicLong(0)
    private var feature: ScalaSimpleFeature = _

    override def getFeatureType: SimpleFeatureType = sft

    override def hasNext: Boolean = false

    override def next(): ScalaSimpleFeature = {
      feature = new ScalaSimpleFeature(sft, featureIds.getAndIncrement().toString)
      feature
    }

    override def write(): Unit = {
      val sf = GeoMesaFeatureWriter.featureWithFid(feature)
      writers.get(storage.metadata.scheme.getPartitionName(sf)).write(sf)
      feature = null
    }

    override def remove(): Unit = throw new IllegalArgumentException("This writer is append only")

    override def close(): Unit = {
      val values = writers.asMap().values().asScala.toSeq
      val flush = FlushQuietly(values)
      CloseQuietly(values) match {
        case None => flush.foreach(e => throw e)
        case Some(e) => flush.foreach(e.addSuppressed); throw e
      }
    }
  }

  /**
    * Modifying feature writer
    *
    * @param storage instance
    * @param sft simple feature type
    * @param filter query filter
    * @param readThreads read threads
    */
  class FileSystemFeatureWriterModify(
      storage: FileSystemStorage,
      sft: SimpleFeatureType,
      filter: Filter,
      readThreads: Int
    ) extends FeatureWriter[SimpleFeatureType, SimpleFeature] with LazyLogging {

    private val writer = storage.getWriter(filter, threads = readThreads)

    override def getFeatureType: SimpleFeatureType = sft

    override def hasNext: Boolean = writer.hasNext
    // TODO implement ScalaSimpleFeatureWriter and wire ScalaSimpleFeature all the way through
    override def next(): SimpleFeature = writer.next()
    override def write(): Unit = writer.write()
    override def remove(): Unit = writer.remove()
    override def close(): Unit = writer.close()
  }
}

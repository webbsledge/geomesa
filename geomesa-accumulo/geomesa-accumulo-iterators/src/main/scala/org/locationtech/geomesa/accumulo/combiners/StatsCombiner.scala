/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.accumulo.combiners

import com.typesafe.scalalogging.LazyLogging
import org.apache.accumulo.core.client.{AccumuloClient, IteratorSetting}
import org.apache.accumulo.core.data.{Key, Value}
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope
import org.apache.accumulo.core.iterators.{Combiner, IteratorEnvironment, SortedKeyValueIterator}
import org.apache.hadoop.io.Text
import org.geotools.api.feature.simple.SimpleFeatureType
import org.locationtech.geomesa.index.metadata.KeyValueStoreMetadata
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.stats.{Stat, StatSerializer}

import scala.util.control.NonFatal

/**
 * Combiner for serialized stats. Should be one instance configured per catalog table. Simple feature
 * types and columns with stats should be set in the configuration.
 */
class StatsCombiner extends Combiner with LazyLogging {

  import StatsCombiner.{SeparatorOption, SftOption}

  import scala.collection.JavaConverters._

  private var serializers: Map[String, StatSerializer] = _
  private var separator: Char = '~'

  override def init(source: SortedKeyValueIterator[Key, Value],
      options: java.util.Map[String, String],
      env: IteratorEnvironment): Unit = {
    super.init(source, options, env)
    serializers = options.asScala.toMap.collect {
      case (k, v) if k.startsWith(SftOption) =>
        val typeName = k.substring(SftOption.length)
        (typeName, StatSerializer(SimpleFeatureTypes.createType(typeName, v)))
    }
    separator = Option(options.get(SeparatorOption)).map(_.charAt(0)).getOrElse('~')
  }

  override def reduce(key: Key, iter: java.util.Iterator[Value]): Value = {
    val head = iter.next()
    if (!iter.hasNext) { head } else {
      val sftName = try {
        KeyValueStoreMetadata.decodeRow(key.getRow.getBytes, separator)._1
      } catch {
        // back compatible check
        case NonFatal(_) => StatsCombiner.SingleRowMetadata.getTypeName(key.getRow)
      }
      val serializer = serializers(sftName)

      var stat = deserialize(head, serializer)

      while (stat == null) {
        if (iter.hasNext) {
          stat = deserialize(iter.next, serializer)
        } else {
          return head // couldn't parse anything... return head value and let client deal with it
        }
      }

      iter.asScala.foreach { s =>
        try { stat += serializer.deserialize(s.get) } catch {
          case NonFatal(e) => logger.error("Error combining stats:", e)
        }
      }

      new Value(serializer.serialize(stat))
    }
  }

  private def deserialize(value: Value, serializer: StatSerializer): Stat = {
    try { serializer.deserialize(value.get) } catch {
      case NonFatal(e) => logger.error("Error deserializing stat:", e); null
    }
  }
}

object StatsCombiner {

  import scala.collection.JavaConverters._

  val CombinerName = "stats-combiner"

  val SftOption = "sft-"
  val SeparatorOption = "sep"

  def configure(sft: SimpleFeatureType, connector: AccumuloClient, table: String, separator: String): Unit = {
    val sftKey = getSftKey(sft)
    val sftOpt = SimpleFeatureTypes.encodeType(sft)

    getExisting(connector, table) match {
      case None => attach(connector, table, options(separator) + (sftKey -> sftOpt))
      case Some(existing) =>
        val existingSfts = existing.getOptions.asScala.filter(_._1.startsWith(SftOption))
        if (!existingSfts.get(sftKey).contains(sftOpt)) {
          connector.tableOperations().removeIterator(table, CombinerName, java.util.EnumSet.allOf(classOf[IteratorScope]))
          attach(connector, table, existingSfts.toMap ++ options(separator) + (sftKey -> sftOpt))
        }
    }
  }

  def remove(sft: SimpleFeatureType, connector: AccumuloClient, table: String, separator: String): Unit = {
    getExisting(connector, table).foreach { existing =>
      val sftKey = getSftKey(sft)
      val existingSfts = existing.getOptions.asScala.filter(_._1.startsWith(SftOption))
      if (existingSfts.asJava.containsKey(sftKey)) {
        connector.tableOperations().removeIterator(table, CombinerName, java.util.EnumSet.allOf(classOf[IteratorScope]))
        if (existingSfts.size > 1) {
          attach(connector, table, (existingSfts.toMap - sftKey) ++ options(separator))
        }
      }
    }
  }

  def list(connector: AccumuloClient, table: String): scala.collection.Map[String, String] = {
    getExisting(connector, table) match {
      case None => Map.empty
      case Some(existing) =>
        existing.getOptions.asScala.collect {
          case (k, v) if k.startsWith(SftOption) => (k.substring(SftOption.length), v)
        }
    }
  }

  private def getExisting(connector: AccumuloClient, table: String): Option[IteratorSetting] = {
    if (!connector.tableOperations().exists(table)) { None } else {
      Option(connector.tableOperations().getIteratorSetting(table, CombinerName, IteratorScope.scan))
    }
  }

  private def options(separator: String): Map[String, String] =
    Map(SeparatorOption -> separator, "all" -> "true")

  private def getSftKey(sft: SimpleFeatureType): String = s"$SftOption${sft.getTypeName}"

  private def attach(connector: AccumuloClient, table: String, options: Map[String, String]): Unit = {
    // priority needs to be less than the versioning iterator at 20
    // note: we use the old class name to allow full interop between gm versions
    val is = new IteratorSetting(10, CombinerName, "org.locationtech.geomesa.accumulo.data.stats.StatsCombiner")
    options.foreach { case (k, v) => is.addOption(k, v) }
    connector.tableOperations().attachIterator(table, is)
  }

  /**
   * Code copied from org.locationtech.geomesa.accumulo.data.AccumuloBackedMetadata.SingleRowAccumuloMetadata,
   * just kept around for back compatibility
   */
  private object SingleRowMetadata {

    private val MetadataTag = "~METADATA"
    private val MetadataRowKeyRegex = (MetadataTag + """_(.*)""").r

    def getTypeName(row: Text): String = {
      val MetadataRowKeyRegex(typeName) = row.toString
      typeName
    }
  }
}

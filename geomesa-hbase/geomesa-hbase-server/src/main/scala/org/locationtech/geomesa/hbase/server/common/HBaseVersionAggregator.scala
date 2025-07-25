/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.server.common

import org.apache.hadoop.hbase.regionserver.RegionScanner
import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.locationtech.geomesa.hbase.server.common.HBaseVersionAggregator.VersionAggregator
import org.locationtech.geomesa.index.iterators.AggregatingScan
import org.locationtech.geomesa.index.iterators.AggregatingScan.AggregateCallback
import org.locationtech.geomesa.utils.conf.GeoMesaProperties

import java.nio.charset.StandardCharsets

class HBaseVersionAggregator extends HBaseAggregator[VersionAggregator] {

  private var scanned = false

  override def setScanner(scanner: RegionScanner): Unit = {}

  override def init(options: Map[String, String]): Unit = {}

  override def aggregate[A <: AggregateCallback](callback: A): A = {
    if (!scanned) {
      scanned = true
      callback.batch(GeoMesaProperties.ProjectVersion.getBytes(StandardCharsets.UTF_8))
    }
    callback
  }

  override protected def createResult(
      sft: SimpleFeatureType,
      transform: Option[SimpleFeatureType],
      batchSize: Int,
      options: Map[String, String]): VersionAggregator = throw new UnsupportedOperationException()

  override protected def defaultBatchSize: Int = throw new UnsupportedOperationException()
}

object HBaseVersionAggregator {
  class VersionAggregator extends AggregatingScan.Result {
    override def init(): Unit = {}
    override def aggregate(sf: SimpleFeature): Int = 1
    override def encode(): Array[Byte] = GeoMesaProperties.ProjectVersion.getBytes(StandardCharsets.UTF_8)
    override def cleanup(): Unit = {}
  }
}

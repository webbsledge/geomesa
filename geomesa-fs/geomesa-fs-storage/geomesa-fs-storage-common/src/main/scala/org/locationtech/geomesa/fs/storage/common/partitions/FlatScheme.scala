/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.fs.storage.common.partitions

import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.geotools.api.filter.Filter
import org.locationtech.geomesa.fs.storage.api.PartitionScheme.SimplifiedFilter
import org.locationtech.geomesa.fs.storage.api.{NamedOptions, PartitionScheme, PartitionSchemeFactory}

object FlatScheme extends PartitionScheme {

  override val depth: Int = 0

  override def pattern: String = ""

  override def getPartitionName(feature: SimpleFeature): String = ""

  override def getSimplifiedFilters(filter: Filter, partition: Option[String]): Option[Seq[SimplifiedFilter]] =
    Some(Seq(SimplifiedFilter(filter, Seq(""), partial = false)))

  override def getIntersectingPartitions(filter: Filter): Option[Seq[String]] = Some(Seq(""))

  override def getCoveringFilter(partition: String): Filter = Filter.INCLUDE

  class FlatPartitionSchemeFactory extends PartitionSchemeFactory {
    override def load(sft: SimpleFeatureType, config: NamedOptions): Option[PartitionScheme] =
      if (config.name.equalsIgnoreCase("flat")) { Some(FlatScheme) } else { None }
  }
}

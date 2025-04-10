/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.hbase.aggregators

import org.geotools.api.feature.simple.SimpleFeatureType
import org.locationtech.geomesa.hbase.rpc.coprocessor.GeoMesaCoprocessor
import org.locationtech.geomesa.index.api.GeoMesaFeatureIndex
import org.locationtech.geomesa.index.iterators.AggregatingScan

object HBaseVersionAggregator {

  import org.locationtech.geomesa.hbase.data.HBaseIndexAdapter.AggregatorPackage

  def configure(sft: SimpleFeatureType, index: GeoMesaFeatureIndex[_, _]): Map[String, String] = {
    AggregatingScan.configure(sft, index, None, None, None, 1) +
        (GeoMesaCoprocessor.AggregatorClass -> s"$AggregatorPackage.HBaseVersionAggregator")
  }
}

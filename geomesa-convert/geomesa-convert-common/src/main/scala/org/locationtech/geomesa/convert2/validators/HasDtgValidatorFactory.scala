/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.convert2.validators

import io.micrometer.core.instrument.Tags
import org.geotools.api.feature.simple.SimpleFeatureType
import org.locationtech.geomesa.convert2.metrics.ConverterMetrics

class HasDtgValidatorFactory extends SimpleFeatureValidatorFactory {

  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

  override val name: String = HasDtgValidatorFactory.Name

  override def apply(sft: SimpleFeatureType, metrics: ConverterMetrics, config: Option[String]): SimpleFeatureValidator =
    apply(sft, config, Tags.empty())

  override def apply(sft: SimpleFeatureType, config: Option[String], tags: Tags): SimpleFeatureValidator = {
    val i = sft.getDtgIndex.getOrElse(-1)
    if (i == -1) { NoValidator } else { new NullValidator(i, Errors.DateNull, counter("dtg.null", tags)) }
  }
}

object HasDtgValidatorFactory {
  val Name = "has-dtg"
}

/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.convert.json

import com.google.gson.JsonElement
import org.geotools.api.feature.simple.SimpleFeatureType
import org.locationtech.geomesa.convert.EvaluationContext
import org.locationtech.geomesa.convert.Modes.ErrorMode
import org.locationtech.geomesa.convert2.transforms.Predicate
import org.locationtech.geomesa.convert2.{AbstractCompositeConverter, ParsingConverter}
import org.locationtech.geomesa.utils.collection.CloseableIterator

import java.io.InputStream
import java.nio.charset.Charset

class JsonCompositeConverter(
    sft: SimpleFeatureType,
    encoding: Charset,
    errorMode: ErrorMode,
    delegates: Seq[(Predicate, ParsingConverter[JsonElement])]
  ) extends AbstractCompositeConverter(sft, errorMode, delegates) {

  override protected def parse(is: InputStream, ec: EvaluationContext): CloseableIterator[JsonElement] =
    new JsonConverter.JsonIterator(is, encoding, ec)
}

/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.strategies

import org.geotools.api.feature.simple.SimpleFeatureType
import org.geotools.api.filter.{And, Filter, Id, Or}
import org.locationtech.geomesa.filter._
import org.locationtech.geomesa.filter.visitor.IdExtractingVisitor
import org.locationtech.geomesa.index.api.{FilterStrategy, GeoMesaFeatureIndex}

trait IdFilterStrategy[T, U] extends GeoMesaFeatureIndex[T, U] {

  override def getFilterStrategy(filter: Filter, transform: Option[SimpleFeatureType]): Option[FilterStrategy] = {
    if (filter == Filter.INCLUDE) {
      Some(FilterStrategy(this, None, None, temporal = false, Float.PositiveInfinity))
    } else {
      val (ids, notIds) = IdExtractingVisitor(filter)
      if (ids.isDefined) {
        // top-priority index if there are actually ID filters
        // note: although there's no temporal predicate, there's an implied exact date for the given feature
        Some(FilterStrategy(this, ids, notIds, temporal = true, .001f))
      } else {
        Some(FilterStrategy(this, None, Some(filter), temporal = false, Float.PositiveInfinity))
      }
    }
  }
}

object IdFilterStrategy {

  def intersectIdFilters(filter: Filter): Set[String] = {
    import scala.collection.JavaConverters._
    filter match {
      case f: And => f.getChildren.asScala.map(intersectIdFilters).reduceLeftOption(_ intersect _).getOrElse(Set.empty)
      case f: Or  => f.getChildren.asScala.flatMap(intersectIdFilters).toSet
      case f: Id  => f.getIDs.asScala.map(_.toString).toSet
      case _ => throw new IllegalArgumentException(s"Expected ID filter, got ${filterToString(filter)}")
    }
  }
}

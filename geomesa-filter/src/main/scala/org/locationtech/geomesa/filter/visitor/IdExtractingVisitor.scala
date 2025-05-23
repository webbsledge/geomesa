/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.filter.visitor

import org.geotools.api.filter.spatial._
import org.geotools.api.filter.temporal._
import org.geotools.api.filter._

import scala.collection.JavaConverters._

/**
  * Extracts ID filters
  */
object IdExtractingVisitor {
  def apply(filter: Filter): (Option[Filter], Option[Filter]) = {
    val (yes, no) = filter.accept(new IdExtractingVisitor, null).asInstanceOf[(Filter, Filter)]
    (Option(yes), Option(no))
  }
}

class IdExtractingVisitor extends FilterVisitor {

  import org.locationtech.geomesa.filter.{andOption, ff, orOption}

  override def visit(f: Or, data: AnyRef): AnyRef = {
    val children = f.getChildren.asScala.map(_.accept(this, data).asInstanceOf[(Filter, Filter)])
    val yes = children.map(_._1).filter(_ != null).toSeq
    val no  = children.map(_._2).filter(_ != null).toSeq
    (orOption(yes).orNull, orOption(no).orNull)
  }

  override def visit(f: And, data: AnyRef): AnyRef = {
    val children = f.getChildren.asScala.map(_.accept(this, data).asInstanceOf[(Filter, Filter)])
    val yes = children.map(_._1).filter(_ != null).toSeq
    val no  = children.map(_._2).filter(_ != null).toSeq
    (andOption(yes).orNull, andOption(no).orNull)
  }

  override def visit(f: Id, data: AnyRef): AnyRef = (f, null)

  override def visit(f: Not, data: AnyRef): AnyRef = (null, f)

  override def visit(f: IncludeFilter, data: AnyRef): AnyRef = (null, f)

  override def visit(f: ExcludeFilter, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Meets, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Ends, data: AnyRef): AnyRef = (null, f)

  override def visit(f: EndedBy, data: AnyRef): AnyRef = (null, f)

  override def visit(f: During, data: AnyRef): AnyRef = (null, f)

  override def visit(f: BegunBy, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Begins, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Before, data: AnyRef): AnyRef = (null, f)

  override def visit(f: AnyInteracts, data: AnyRef): AnyRef = (null, f)

  override def visit(f: After, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Within, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Touches, data: AnyRef): AnyRef = (null, f)

  override def visit(f: MetBy, data: AnyRef): AnyRef = (null, f)

  override def visit(f: OverlappedBy, data: AnyRef): AnyRef = (null, f)

  override def visit(f: TContains, data: AnyRef): AnyRef = (null, f)

  override def visit(f: TEquals, data: AnyRef): AnyRef = (null, f)

  override def visit(f: TOverlaps, data: AnyRef): AnyRef = (null, f)

  override def visit(f: PropertyIsLessThanOrEqualTo, data: AnyRef): AnyRef = (null, f)

  override def visit(f: PropertyIsLessThan, data: AnyRef): AnyRef = (null, f)

  override def visit(f: PropertyIsGreaterThanOrEqualTo, data: AnyRef): AnyRef = (null, f)

  override def visit(f: PropertyIsGreaterThan, data: AnyRef): AnyRef = (null, f)

  override def visit(f: PropertyIsNotEqualTo, data: AnyRef): AnyRef = (null, f)

  override def visit(f: PropertyIsEqualTo, data: AnyRef): AnyRef = (null, f)

  override def visit(f: PropertyIsBetween, data: AnyRef): AnyRef = (null, f)

  override def visit(f: PropertyIsLike, data: AnyRef): AnyRef = (null, f)

  override def visit(f: PropertyIsNull, data: AnyRef): AnyRef = (null, f)

  override def visit(f: PropertyIsNil, data: AnyRef): AnyRef = (null, f)

  override def visit(f: BBOX, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Beyond, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Contains, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Crosses, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Disjoint, data: AnyRef): AnyRef = (null, f)

  override def visit(f: DWithin, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Equals, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Intersects, data: AnyRef): AnyRef = (null, f)

  override def visit(f: Overlaps, data: AnyRef): AnyRef = (null, f)

  override def visitNullFilter(data: AnyRef): AnyRef = (null, null)
}

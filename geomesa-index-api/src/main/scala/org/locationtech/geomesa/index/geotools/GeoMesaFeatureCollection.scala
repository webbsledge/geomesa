/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.geotools

import com.typesafe.scalalogging.LazyLogging
import org.geotools.api.data._
import org.geotools.api.feature.FeatureVisitor
import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.geotools.api.filter.Filter
import org.geotools.api.filter.expression.{Expression, PropertyName}
import org.geotools.api.filter.sort.SortBy
import org.geotools.api.util.ProgressListener
import org.geotools.data.simple.SimpleFeatureCollection
import org.geotools.data.store.DataFeatureCollection
import org.geotools.data.util.NullProgressListener
import org.geotools.feature.FeatureCollection
import org.geotools.feature.collection.{DecoratingFeatureCollection, DecoratingSimpleFeatureCollection}
import org.geotools.feature.visitor.GroupByVisitor.GroupByRawResult
import org.geotools.feature.visitor._
import org.geotools.geometry.jts.ReferencedEnvelope
import org.locationtech.geomesa.filter.FilterHelper
import org.locationtech.geomesa.index.geotools.GeoMesaFeatureCollection.GeoMesaFeatureVisitingCollection
import org.locationtech.geomesa.index.process.GeoMesaProcessVisitor
import org.locationtech.geomesa.index.stats.GeoMesaStats
import org.locationtech.geomesa.utils.collection.CloseableIterator
import org.locationtech.geomesa.utils.io.WithClose
import org.locationtech.geomesa.utils.stats._

import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec

/**
  * Feature collection implementation
  */
class GeoMesaFeatureCollection(source: GeoMesaFeatureSource, query: Query)
    extends GeoMesaFeatureVisitingCollection(source, source.ds.stats, query) {

  private val transaction = source match {
    case s: SimpleFeatureStore => s.getTransaction
    case _ => Transaction.AUTO_COMMIT
  }

  private lazy val featureReader = source.ds.getFeatureReader(source.sft, transaction, query)

  override def getSchema: SimpleFeatureType = featureReader.schema

  override def reader(): FeatureReader[SimpleFeatureType, SimpleFeature] = featureReader.reader()

  override def subCollection(filter: Filter): SimpleFeatureCollection = {
    val merged = new Query(query)
    val filters = Seq(merged.getFilter, filter).filter(_ != Filter.INCLUDE)
    FilterHelper.filterListAsAnd(filters).foreach(merged.setFilter)
    new GeoMesaFeatureCollection(source, merged)
  }

  override def sort(order: SortBy): SimpleFeatureCollection = {
    val merged = new Query(query)
    if (merged.getSortBy == null) {
      merged.setSortBy(order)
    } else {
      merged.setSortBy(merged.getSortBy :+ order: _*)
    }
    new GeoMesaFeatureCollection(source, merged)
  }

  override def getBounds: ReferencedEnvelope = source.getBounds(query)

  override def getCount: Int = source.getCount(query)

  // note: this shouldn't return -1 (as opposed to FeatureSource.getCount), but we still don't return a valid
  // size unless exact counts are enabled
  override def size: Int = {
    val count = getCount
    if (count < 0) { 0 } else { count }
  }

  override def contains(o: Any): Boolean = {
    o match {
      case f: SimpleFeature =>
        val sub = subCollection(FilterHelper.ff.id(f.getIdentifier))
        WithClose(CloseableIterator(sub.features()))(_.nonEmpty)

      case _ => false
    }
  }

  override def containsAll(collection: java.util.Collection[_]): Boolean = {
    val size = collection.size()
    if (size == 0) { true } else {
      val filters = Seq.newBuilder[Filter]
      filters.sizeHint(size)

      val features = collection.iterator()
      while (features.hasNext) {
        features.next match {
          case f: SimpleFeature => filters += FilterHelper.ff.id(f.getIdentifier)
          case _ => return false
        }
      }

      val sub = subCollection(org.locationtech.geomesa.filter.orFilters(filters.result))
      WithClose(CloseableIterator(sub.features()))(_.length) == size
    }
  }
}

object GeoMesaFeatureCollection extends LazyLogging {

  import scala.collection.JavaConverters._

  private val oneUp = new AtomicLong(0)

  def nextId: String = s"GeoMesaFeatureCollection-${oneUp.getAndIncrement()}"

  /**
    * Attempts to visit the feature collection in an optimized manner. This will unwrap any decorating
    * feature collections that may interfere with the `accepts` method.
    *
    * Note that generally this may not be a good idea - collections are presumably wrapped for a reason.
    * However, our visitation functionality keeps being broken by changes in GeoServer, so we're being
    * defensive here.
    *
    * @param collection feature collection
    * @param visitor visitor
    * @param progress progress monitor
    */
  def visit(
      collection: FeatureCollection[SimpleFeatureType, SimpleFeature],
      visitor: FeatureVisitor,
      progress: ProgressListener = new NullProgressListener): Unit = {
    val unwrapped = if (collection.isInstanceOf[GeoMesaFeatureVisitingCollection]) { collection } else {
      try { unwrap(collection) } catch {
        case e: Throwable => logger.debug("Error trying to unwrap feature collection:", e); collection
      }
    }
    unwrapped.accepts(visitor, progress)
  }

  /**
    * Attempts to remove any decorating feature collections
    *
    * @param collection collection
    * @param level level of collections that have been removed, used to detect potentially infinite looping
    * @return
    */
  @tailrec
  private def unwrap(
      collection: FeatureCollection[SimpleFeatureType, SimpleFeature],
      level: Int = 1): FeatureCollection[SimpleFeatureType, SimpleFeature] = {

    // noinspection TypeCheckCanBeMatch
    val unwrapped = if (collection.isInstanceOf[DecoratingSimpleFeatureCollection]) {
      getDelegate(collection, classOf[DecoratingSimpleFeatureCollection])
    } else if (collection.isInstanceOf[DecoratingFeatureCollection[SimpleFeatureType, SimpleFeature]]) {
      getDelegate(collection, classOf[DecoratingFeatureCollection[SimpleFeatureType, SimpleFeature]])
    } else {
      logger.debug(s"Unable to unwrap feature collection $collection of class ${collection.getClass.getName}")
      return collection
    }

    logger.debug(s"Unwrapped feature collection $collection of class ${collection.getClass.getName} to " +
        s"$unwrapped of class ${unwrapped.getClass.getName}")

    if (unwrapped.isInstanceOf[GeoMesaFeatureVisitingCollection]) {
      unwrapped
    } else if (level > 9) {
      logger.debug("Aborting feature collection unwrapping after 10 iterations")
      unwrapped
    } else {
      unwrap(unwrapped, level + 1)
    }
  }

  /**
    * Uses reflection to access the protected field 'delegate' in decorating feature collection classes
    *
    * @param collection decorating feature collection
    * @param clas decorating feature base class
    * @return
    */
  private def getDelegate(
      collection: FeatureCollection[SimpleFeatureType, SimpleFeature],
      clas: Class[_]): FeatureCollection[SimpleFeatureType, SimpleFeature] = {
    val m = clas.getDeclaredField("delegate")
    m.setAccessible(true)
    m.get(collection).asInstanceOf[FeatureCollection[SimpleFeatureType, SimpleFeature]]
  }

  /**
    * Base class for handling feature visitors
    *
    * @param source feature source
    * @param stats geomesa stat hook
    * @param query query
    */
  abstract class GeoMesaFeatureVisitingCollection(source: SimpleFeatureSource, stats: GeoMesaStats, query: Query)
      extends DataFeatureCollection(nextId) with LazyLogging {

    private def unoptimized(visitor: FeatureVisitor, progress: ProgressListener): Unit = {
      lazy val warning = s"Using unoptimized method for visiting '${visitor.getClass.getName}'"
      logger.warn(warning)
      if (progress != null) {
        progress.warningOccurred(getClass.getName, "accepts()", warning)
      }
      super.accepts(visitor, progress)
    }

    override def accepts(visitor: FeatureVisitor, progress: ProgressListener): Unit = {
      visitor match {
        case v: GeoMesaProcessVisitor =>
          v.execute(source, query)

        case v: AverageVisitor if v.getExpression.isInstanceOf[PropertyName] =>
          val attribute = v.getExpression.asInstanceOf[PropertyName].getPropertyName
          val stat = Stat.DescriptiveStats(Seq(attribute))
          stats.getStat[DescriptiveStats](source.getSchema, stat, query.getFilter, exact = true) match {
            case Some(s) if s.count <= Int.MaxValue.toLong => v.setValue(s.count.toInt, s.sum(0))
            case Some(s) => v.setValue(s.mean(0))
            case None    => unoptimized(visitor, progress)
          }

        case v: BoundsVisitor =>
          v.getBounds.expandToInclude(stats.getBounds(source.getSchema, query.getFilter))

        case v: CountVisitor =>
          v.setValue(source.getCount(query))

        case v: MaxVisitor if v.getExpression.isInstanceOf[PropertyName] =>
          val attribute = v.getExpression.asInstanceOf[PropertyName].getPropertyName
          minMax(attribute, exact = false).orElse(minMax(attribute, exact = true)) match {
            case Some((_, max)) => v.setValue(max)
            case None           => unoptimized(visitor, progress)
          }

        case v: GroupByVisitor if v.getExpression.isInstanceOf[PropertyName] =>
          val attribute = v.getExpression.asInstanceOf[PropertyName].getPropertyName
          groupBy(attribute, v.getGroupByAttributes.asScala.toSeq, v.getAggregateVisitor) match {
            case Some(result) => v.setValue(result)
            case None         => unoptimized(visitor, progress)
          }

        case v: MinVisitor if v.getExpression.isInstanceOf[PropertyName] =>
          val attribute = v.getExpression.asInstanceOf[PropertyName].getPropertyName
          minMax(attribute, exact = false).orElse(minMax(attribute, exact = true)) match {
            case Some((min, _)) => v.setValue(min)
            case None           => unoptimized(visitor, progress)
          }

        case v: SumVisitor if v.getExpression.isInstanceOf[PropertyName] =>
          val attribute = v.getExpression.asInstanceOf[PropertyName].getPropertyName
          val stat = Stat.DescriptiveStats(Seq(attribute))
          stats.getStat[DescriptiveStats](source.getSchema, stat, query.getFilter, exact = true) match {
            case Some(s) => v.setValue(s.sum(0))
            case None    => unoptimized(visitor, progress)
          }

        case v: UniqueVisitor if v.getExpression.isInstanceOf[PropertyName] =>
          val attribute = v.getExpression.asInstanceOf[PropertyName].getPropertyName
          val stat = Stat.Enumeration(attribute)
          stats.getStat[EnumerationStat[Any]](source.getSchema, stat, query.getFilter, exact = true) match {
            case Some(s) => v.setValue(s.values.toList.asJava)
            case None    => unoptimized(visitor, progress)
          }

        case _ =>
          unoptimized(visitor, progress)
      }
    }

    private def minMax(attribute: String, exact: Boolean): Option[(Any, Any)] =
      stats.getMinMax[Any](source.getSchema, attribute, query.getFilter, exact).map(_.bounds)

    private def groupBy(
        attribute: String,
        groupByExpression: Seq[Expression],
        aggregate: FeatureVisitor): Option[java.util.List[GroupByRawResult]] = {
      if (groupByExpression.lengthCompare(1) != 0
          || groupByExpression.exists(e => !e.isInstanceOf[PropertyName])) { None } else {
        val groupBy = groupByExpression.map(_.asInstanceOf[PropertyName].getPropertyName).head
        val op: Option[(String, Stat => Any)] = aggregate match {
          case _: CountVisitor =>
            Some(Stat.Count() -> { (s: Stat) => math.min( s.asInstanceOf[CountStat].count, Int.MaxValue.toLong).toInt })

          case _: MaxVisitor =>
            Some(Stat.MinMax(attribute) -> { (s: Stat) => s.asInstanceOf[MinMax[Any]].max })

          case _: MinVisitor =>
            Some(Stat.MinMax(attribute) -> { (s: Stat) => s.asInstanceOf[MinMax[Any]].min })

          case _ =>
            None
        }
        op.flatMap { case (nested, unwrap) =>
          val stat = Stat.GroupBy(groupBy, nested)
          stats.getStat[GroupBy[AnyRef]](source.getSchema, stat, query.getFilter, exact = true).map { grouped =>
            val result = new java.util.ArrayList[GroupByRawResult]
            grouped.iterator.foreach { case (group, stat) =>
              result.add(new GroupByRawResult(Collections.singletonList(group), unwrap(stat)))
            }
            result
          }
        }
      }
    }
  }
}

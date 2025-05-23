/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.fs.storage.common.partitions

import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.geotools.api.filter.{And, Filter, Or, PropertyIsEqualTo}
import org.locationtech.geomesa.filter.FilterHelper
import org.locationtech.geomesa.filter.visitor.FilterExtractingVisitor
import org.locationtech.geomesa.fs.storage.api.PartitionScheme.SimplifiedFilter
import org.locationtech.geomesa.fs.storage.api.{NamedOptions, PartitionScheme, PartitionSchemeFactory}
import org.locationtech.geomesa.index.index.attribute.AttributeIndexKey

/**
  * Lexicoded attribute partitioning
  *
  * @param attribute attribute name
  * @param index attribute index in the sft
  * @param binding type binding
  * @param allowedValues list of allowedValues to partition
  */
case class AttributeScheme(attribute: String, index: Int, binding: Class[_], defaultPartition: String, allowedValues: Seq[String]) extends PartitionScheme {

  import FilterHelper.ff

  private val alias = AttributeIndexKey.alias(binding)

  override val depth: Int = 1

  override def pattern: String = s"<$attribute>"

  override def getPartitionName(feature: SimpleFeature): String = {
    val value = feature.getAttribute(index)
    if (value == null) {
      return defaultPartition
    }
    val encodedValue = AttributeIndexKey.typeEncode(value)
    if (allowedValues.isEmpty || allowedValues.contains(encodedValue)) {
      encodedValue
    } else {
      defaultPartition
    }
  }

  override def getSimplifiedFilters(filter: Filter, partition: Option[String]): Option[Seq[SimplifiedFilter]] = {
    getIntersectingPartitions(filter).map { covered =>
      // remove the attribute filter that we've already accounted for in our covered partitions
      val coveredFilter = FilterExtractingVisitor(filter, attribute, AttributeScheme.propertyIsEquals _)._2
      val simplified = SimplifiedFilter(coveredFilter.getOrElse(Filter.INCLUDE), covered, partial = false)

      partition match {
        case None => Seq(simplified)
        case Some(p) if simplified.partitions.contains(p) => Seq(simplified.copy(partitions = Seq(p)))
        case _ => Seq.empty
      }
    }
  }

  override def getIntersectingPartitions(filter: Filter): Option[Seq[String]] = {
    val bounds = FilterHelper.extractAttributeBounds(filter, attribute, binding)
    if (bounds.disjoint) {
      Some(Seq.empty)
    } else if (bounds.isEmpty || !bounds.precise || !bounds.forall(_.isEquals)) {
      if (allowedValues.nonEmpty) Some(allowedValues) else None
    } else {
      // note: we verified they are all single values above
      val partitions = bounds.values.map(bound => AttributeIndexKey.encodeForQuery(bound.lower.value.get, binding))
      if (allowedValues.nonEmpty) Some(partitions.filter(partition => allowedValues.contains(partition))) else Some(partitions)
    }
  }

  override def getCoveringFilter(partition: String): Filter =
    ff.equals(ff.property(attribute), ff.literal(AttributeIndexKey.decode(alias, partition)))

}

object AttributeScheme {

  val Name = "attribute"

  /**
    * Check to extract only the equality filters that we can process with this partition scheme
    *
    * @param filter filter
    * @return
    */
  def propertyIsEquals(filter: Filter): Boolean = {
    filter match {
      case _: And | _: Or => true // note: implies further processing of children
      case _: PropertyIsEqualTo => true
      case _ => false
    }
  }

  object Config {
    val AttributeOpt: String = "partitioned-attribute"
    val AllowedListOpt: String = "allow-list"
    val DefaultPartitionOpt: String = "default-partition"
  }

  class AttributePartitionSchemeFactory extends PartitionSchemeFactory {
    override def load(sft: SimpleFeatureType, config: NamedOptions): Option[PartitionScheme] = {
      if (config.name != Name) { None } else {
        val attribute = config.options.getOrElse(Config.AttributeOpt, null)
        require(attribute != null, s"Attribute scheme requires valid attribute name '${Config.AttributeOpt}'")
        val index = sft.indexOf(attribute)
        require(index != -1, s"Attribute '$attribute' does not exist in schema '${sft.getTypeName}'")
        val binding = sft.getDescriptor(index).getType.getBinding
        require(AttributeIndexKey.encodable(binding),
          s"Invalid type binding '${binding.getName}' of attribute '$attribute'")
        val allowedValues: Seq[String] = config.options.get(Config.AllowedListOpt).map(_.split(',').toSeq).getOrElse(Seq.empty).map(allowed => AttributeIndexKey.encodeForQuery(allowed.trim(),binding))
        val defaultPartition = AttributeIndexKey.encodeForQuery(config.options.getOrElse(Config.DefaultPartitionOpt, allowedValues.headOption.getOrElse("")), binding)
        require(allowedValues.isEmpty || allowedValues.contains(defaultPartition), "Default partition must be one of the allowed values")
        Some(AttributeScheme(attribute, index, binding, defaultPartition, allowedValues))
      }
    }
  }
}

/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.arrow.features

import org.geotools.api.feature.`type`.{AttributeDescriptor, Name}
import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.geotools.api.feature.{GeometryAttribute, Property}
import org.geotools.api.filter.identity.FeatureId
import org.geotools.api.geometry.BoundingBox
import org.geotools.geometry.jts.ReferencedEnvelope
import org.locationtech.geomesa.arrow.vector.ArrowAttributeReader
import org.locationtech.geomesa.utils.geotools.ImmutableFeatureId
import org.locationtech.jts.geom.Geometry

import java.util.{Objects, Collection => jCollection, List => jList, Map => jMap}

/**
  * Simple feature backed by an arrow vector. Attributes are lazily evaluated - this allows filters to only
  * examine the relevant arrow vectors for optimized reads, but also means that they are tied to the underlying
  * vectors
  *
  * @param sft simple feature type
  * @param idReader id reader
  * @param attributeReaders attribute readers
  * @param index index of the feature in the arrow vector
  */
class ArrowSimpleFeature(sft: SimpleFeatureType,
                         idReader: ArrowAttributeReader,
                         attributeReaders: Array[ArrowAttributeReader],
                         private [arrow] var index: Int) extends SimpleFeature {
  import scala.collection.JavaConverters._

  private lazy val geomIndex = sft.indexOf(sft.getGeometryDescriptor.getLocalName)

  override def getAttribute(i: Int): AnyRef = attributeReaders(i).apply(index)

  /**
    * Gets the underlying arrow reader for this feature
    *
    * @param i attribute to be read
    * @return reader
    */
  def getReader(i: Int): ArrowAttributeReader = attributeReaders(i)

  /**
    * Gets the index of this feature, for use with the attribute reader
    *
    * @return
    */
  def getIndex: Int = index

  override def getID: String = idReader.apply(index).asInstanceOf[String]
  override def getIdentifier: FeatureId = new ImmutableFeatureId(getID)

  override def getUserData: jMap[AnyRef, AnyRef] = Map.empty[AnyRef, AnyRef].asJava

  override def getType: SimpleFeatureType = sft
  override def getFeatureType: SimpleFeatureType = sft
  override def getName: Name = sft.getName

  override def getAttribute(name: Name): AnyRef = getAttribute(name.getLocalPart)
  override def getAttribute(name: String): AnyRef = {
    val index = sft.indexOf(name)
    if (index == -1) { null } else { getAttribute(index) }
  }

  override def getDefaultGeometry: AnyRef = getAttribute(geomIndex)
  override def getAttributeCount: Int = sft.getAttributeCount

  override def getBounds: BoundingBox = getDefaultGeometry match {
    case g: Geometry => new ReferencedEnvelope(g.getEnvelopeInternal, sft.getCoordinateReferenceSystem)
    case _           => new ReferencedEnvelope(sft.getCoordinateReferenceSystem)
  }

  override def getAttributes: jList[AnyRef] = {
    val attributes = new java.util.ArrayList[AnyRef](sft.getAttributeCount)
    var i = 0
    while (i < sft.getAttributeCount) {
      attributes.add(getAttribute(i))
      i += 1
    }
    attributes
  }

  override def getDefaultGeometryProperty: GeometryAttribute = throw new UnsupportedOperationException()
  override def getProperties: jCollection[Property] = throw new UnsupportedOperationException()
  override def getProperties(name: Name): jCollection[Property] = throw new UnsupportedOperationException()
  override def getProperties(name: String): jCollection[Property] = throw new UnsupportedOperationException()
  override def getProperty(name: Name): Property = throw new UnsupportedOperationException()
  override def getProperty(name: String): Property = throw new UnsupportedOperationException()
  override def getValue: jCollection[Property] = throw new UnsupportedOperationException()
  override def getDescriptor: AttributeDescriptor = throw new UnsupportedOperationException()

  override def setAttribute(name: Name, value: Object): Unit = throw new UnsupportedOperationException()
  override def setAttribute(name: String, value: Object): Unit = throw new UnsupportedOperationException()
  override def setAttribute(index: Int, value: Object): Unit = throw new UnsupportedOperationException()
  override def setAttributes(vals: jList[Object]): Unit = throw new UnsupportedOperationException()
  override def setAttributes(vals: Array[Object]): Unit = throw new UnsupportedOperationException()
  override def setDefaultGeometry(geo: Object): Unit = throw new UnsupportedOperationException()
  override def setDefaultGeometryProperty(geoAttr: GeometryAttribute): Unit = throw new UnsupportedOperationException()
  override def setValue(newValue: Object): Unit = throw new UnsupportedOperationException()
  override def setValue(values: jCollection[Property]): Unit = throw new UnsupportedOperationException()

  override def isNillable: Boolean = true
  override def validate(): Unit = throw new UnsupportedOperationException()

  override def toString: String = s"ArrowSimpleFeature:$getID:${getAttributes.asScala.mkString("|")}"

  override def hashCode: Int = Objects.hash(getID, getName, getAttributes)

  override def equals(obj: scala.Any): Boolean = obj match {
    case other: SimpleFeature =>
      getID == other.getID && getName == other.getName && getAttributes == other.getAttributes
    case _ => false
  }
}

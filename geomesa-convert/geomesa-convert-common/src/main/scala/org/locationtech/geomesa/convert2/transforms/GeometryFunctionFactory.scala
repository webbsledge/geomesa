/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php. 
 ***********************************************************************/

package org.locationtech.geomesa.convert2.transforms

import org.geotools.api.referencing.operation.MathTransform
import org.geotools.geometry.jts.{JTS, JTSFactoryFinder}
import org.geotools.referencing.CRS
import org.locationtech.geomesa.convert2.transforms.TransformerFunction.NamedTransformerFunction
import org.locationtech.geomesa.utils.text.{WKBUtils, WKTUtils}
import org.locationtech.jts.geom._

import java.util.concurrent.ConcurrentHashMap

class GeometryFunctionFactory extends TransformerFunctionFactory {

  import GeometryFunctionFactory.{coord, coordM, coordZ, coordZM}

  override def functions: Seq[TransformerFunction] =
    Seq(pointParserFn, pointMParserFn, multiPointParserFn, lineStringParserFn, multiLineStringParserFn,
      polygonParserFn, multiPolygonParserFn, geometryCollectionParserFn, geometryParserFn, projectFromParserFn)

  private val gf = JTSFactoryFinder.getGeometryFactory

  private val pointParserFn = TransformerFunction.pure("point") {
    case Array(g: Point) => g
    case Array(x: Number, y: Number) => gf.createPoint(coord(x, y))
    case Array(x: Number, y: Number, z: Number) => gf.createPoint(coordZ(x, y, z))
    case Array(x: Number, y: Number, z: Number, m: Number) => gf.createPoint(coordZM(x, y, z, m))
    case Array(g: String) => WKTUtils.read(g).asInstanceOf[Point]
    case Array(g: Array[Byte]) => WKBUtils.read(g).asInstanceOf[Point]
    case args if args.nonEmpty && args.lengthCompare(4) <= 0 && args.forall(_ == null) => null
    case args => throw new IllegalArgumentException(s"Invalid point conversion argument: ${args.mkString(",")}")
  }

  private val pointMParserFn = TransformerFunction.pure("pointM") {
    case Array(x: Number, y: Number, m: Number) => gf.createPoint(coordM(x, y, m))
    case Array(null, null, null) => null
    case args => throw new IllegalArgumentException(s"Invalid pointM conversion argument: ${args.mkString(",")}")
  }

  private val multiPointParserFn = TransformerFunction.pure("multipoint") {
    case Array(g: MultiPoint) => g
    case Array(g: String) => WKTUtils.read(g).asInstanceOf[MultiPoint]
    case Array(g: Array[Byte]) => WKBUtils.read(g).asInstanceOf[MultiPoint]
    case Array(x: java.util.List[_], y: java.util.List[_]) =>
      val coords = Array.ofDim[Coordinate](x.size)
      var i = 0
      while (i < coords.length) {
        coords(i) = new Coordinate(x.get(i).asInstanceOf[Number].doubleValue, y.get(i).asInstanceOf[Number].doubleValue)
        i += 1
      }
      gf.createMultiPointFromCoords(coords)
    case Array(null) => null
    case args => throw new IllegalArgumentException(s"Invalid multipoint conversion argument: ${args.mkString(",")}")
  }

  private val lineStringParserFn = TransformerFunction.pure("linestring") {
    case Array(g: LineString) => g
    case Array(g: String) => WKTUtils.read(g).asInstanceOf[LineString]
    case Array(g: Array[Byte]) => WKBUtils.read(g).asInstanceOf[LineString]
    case Array(x: java.util.List[_], y: java.util.List[_]) =>
      val coords = Array.ofDim[Coordinate](x.size)
      var i = 0
      while (i < coords.length) {
        coords(i) = new Coordinate(x.get(i).asInstanceOf[Number].doubleValue, y.get(i).asInstanceOf[Number].doubleValue)
        i += 1
      }
      gf.createLineString(coords)
    case Array(null) => null
    case args => throw new IllegalArgumentException(s"Invalid linestring conversion argument: ${args.mkString(",")}")
  }

  private val multiLineStringParserFn = TransformerFunction.pure("multilinestring") {
    case Array(g: MultiLineString) => g
    case Array(g: String) => WKTUtils.read(g).asInstanceOf[MultiLineString]
    case Array(g: Array[Byte]) => WKBUtils.read(g).asInstanceOf[MultiLineString]
    case Array(null) => null
    case args => throw new IllegalArgumentException(s"Invalid multilinestring conversion argument: ${args.mkString(",")}")
  }

  private val polygonParserFn = TransformerFunction.pure("polygon") {
    case Array(g: Polygon) => g
    case Array(g: String) => WKTUtils.read(g).asInstanceOf[Polygon]
    case Array(g: Array[Byte]) => WKBUtils.read(g).asInstanceOf[Polygon]
    case Array(null) => null
    case args => throw new IllegalArgumentException(s"Invalid polygon conversion argument: ${args.mkString(",")}")
  }

  private val multiPolygonParserFn = TransformerFunction.pure("multipolygon") {
    case Array(g: MultiPolygon) => g
    case Array(g: String) => WKTUtils.read(g).asInstanceOf[MultiPolygon]
    case Array(g: Array[Byte]) => WKBUtils.read(g).asInstanceOf[MultiPolygon]
    case Array(null) => null
    case args => throw new IllegalArgumentException(s"Invalid multipolygon conversion argument: ${args.mkString(",")}")
  }

  private val geometryCollectionParserFn = TransformerFunction.pure("geometrycollection") {
    case Array(g: GeometryCollection) => g
    case Array(g: String) => WKTUtils.read(g).asInstanceOf[GeometryCollection]
    case Array(g: Array[Byte]) => WKBUtils.read(g).asInstanceOf[GeometryCollection]
    case Array(null) => null
    case args => throw new IllegalArgumentException(s"Invalid geometrycollection conversion argument: ${args.mkString(",")}")
  }

  private val geometryParserFn = TransformerFunction.pure("geometry") {
    case Array(g: Geometry) => g
    case Array(g: String) => WKTUtils.read(g)
    case Array(g: Array[Byte]) => WKBUtils.read(g)
    case Array(null) => null
    case args => throw new IllegalArgumentException(s"Invalid geometry conversion argument: ${args.mkString(",")}")
  }

  private val projectFromParserFn: TransformerFunction = new NamedTransformerFunction(Seq("projectFrom"), pure = true) {

    import scala.collection.JavaConverters._

    private val cache = new ConcurrentHashMap[String, MathTransform].asScala

    override def apply(args: Array[AnyRef]): AnyRef = {
      import org.locationtech.geomesa.utils.geotools.CRS_EPSG_4326

      val geom = args(1).asInstanceOf[Geometry]
      if (geom == null) { null } else {
        val epsg = args(0).asInstanceOf[String]
        val lenient = if (args.length > 2) { java.lang.Boolean.parseBoolean(args(2).toString) } else { true }
        // transforms should be thread safe according to https://sourceforge.net/p/geotools/mailman/message/32123017/
        val transform = cache.getOrElseUpdate(s"$epsg:$lenient",
          CRS.findMathTransform(CRS.decode(epsg), CRS_EPSG_4326, lenient))
        JTS.transform(geom, transform)
      }
    }
  }
}

object GeometryFunctionFactory {

  private def coord(x: Number, y: Number): Coordinate = new CoordinateXY(x.doubleValue, y.doubleValue)

  private def coordZ(x: Number, y: Number, z: Number): Coordinate =
    new Coordinate(x.doubleValue, y.doubleValue, z.doubleValue)

  private def coordM(x: Number, y: Number, m: Number): Coordinate =
    new CoordinateXYM(x.doubleValue, y.doubleValue, m.doubleValue)

  private def coordZM(x: Number, y: Number, z: Number, m: Number): Coordinate =
    new CoordinateXYZM(x.doubleValue, y.doubleValue, z.doubleValue, m.doubleValue)
}

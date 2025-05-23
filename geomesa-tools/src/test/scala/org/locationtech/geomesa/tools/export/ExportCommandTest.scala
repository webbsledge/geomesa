/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.tools.`export`

import org.apache.commons.csv.CSVFormat
import org.apache.commons.io.IOUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.filter2.compat.FilterCompat
import org.geotools.api.data._
import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.geotools.data._
import org.geotools.data.collection.ListFeatureCollection
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.filter.text.ecql.ECQL
import org.geotools.util.URLs
import org.geotools.util.factory.Hints
import org.geotools.wfs.GML
import org.junit.runner.RunWith
import org.locationtech.geomesa.arrow.io.SimpleFeatureArrowFileReader
import org.locationtech.geomesa.convert.EvaluationContext
import org.locationtech.geomesa.convert.text.DelimitedTextConverter
import org.locationtech.geomesa.convert2.SimpleFeatureConverter
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.features.avro.io.AvroDataFileReader
import org.locationtech.geomesa.fs.storage.common.jobs.StorageConfiguration
import org.locationtech.geomesa.fs.storage.orc.OrcFileSystemReader
import org.locationtech.geomesa.fs.storage.parquet.ParquetPathReader
import org.locationtech.geomesa.index.TestGeoMesaDataStore
import org.locationtech.geomesa.tools.DataStoreRegistration
import org.locationtech.geomesa.tools.export.ExportCommand.ExportParams
import org.locationtech.geomesa.utils.bin.BinaryOutputEncoder
import org.locationtech.geomesa.utils.collection.SelfClosingIterator
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.io.{PathUtils, WithClose, WithStore}
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.Retries

import java.io.{File, FileInputStream, FileWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.{Collections, Date}
import scala.util.{Failure, Success}

@RunWith(classOf[JUnitRunner])
class ExportCommandTest extends Specification with Retries {

  import scala.collection.JavaConverters._

  val excludes = Seq(ExportFormat.Null)
  val formats = ExportFormat.Formats.filterNot(excludes.contains)

  val sft = SimpleFeatureTypes.createType("tools", "name:String,dtg:Date,*geom:Point:srid=4326")
  val features = List(
    // note: shapefiles don't support timestamps, so we leave them at 00:00...
    ScalaSimpleFeature.create(sft, "id2", "name2", "2016-01-02T00:00:00.000Z", "POINT(0 2)"),
    ScalaSimpleFeature.create(sft, "id1", "name1", "2016-01-01T00:00:00.000Z", "POINT(1 0)")
  )
  features.foreach(_.getUserData.put(Hints.USE_PROVIDED_FID, java.lang.Boolean.TRUE))

  var ds: TestGeoMesaDataStore = _

  private val counter = new AtomicInteger(0)

  def withCommand[T](fn: ExportCommand[DataStore] => T): T = {
    val key = s"${getClass.getName}:${counter.getAndIncrement()}"
    val command: ExportCommand[DataStore] = new ExportCommand[DataStore]() {
      override val params: ExportParams = new ExportParams() {
        override def featureName: String = sft.getTypeName
      }
      override def connection: Map[String, String] = Map(DataStoreRegistration.param.key -> key)
    }
    DataStoreRegistration.register(key, ds)
    try { fn(command) } finally {
      DataStoreRegistration.unregister(key, ds)
    }
  }

  var out: java.nio.file.Path = _

  step {
    out = Files.createTempDirectory("gm-export-fs-test")
    formats.foreach(f => new File(out.toFile, f.name).mkdirs())

    ds = new TestGeoMesaDataStore(looseBBox = true)
    ds.createSchema(sft)
    ds.getFeatureSource(sft.getTypeName).asInstanceOf[SimpleFeatureStore]
        .addFeatures(new ListFeatureCollection(sft, features.map(ScalaSimpleFeature.copy): _*))
  }

  "Export command" should {
    "export to different file formats" in {
      forall(formats) { format =>
        val file = s"$out/${format.name}/base/out.${format.extensions.head}"
        withCommand { command =>
          command.params.file = file
          command.execute()
        }
        readFeatures(format, file) must containTheSameElementsAs(features)
      }
    }
    "support filtering" in {
      forall(formats) { format =>
        val file = s"$out/${format.name}/filter/out.${format.extensions.head}"
        withCommand { command =>
          command.params.file = file
          command.params.cqlFilter = ECQL.toFilter("dtg = '2016-01-01T00:00:00.000Z'")
          command.execute()
        }
        readFeatures(format, file) mustEqual features.drop(1)
      }
    }
    "support relational projections" in {
      forall(formats) { format =>
        val file = s"$out/${format.name}/project/out.${format.extensions.head}"
        withCommand { command =>
          command.params.file = file
          command.params.attributes = List("dtg", "geom", "id").asJava
          command.execute()
        }
        val tsft = SimpleFeatureTypes.createType(sft.getTypeName, "dtg:Date,*geom:Point:srid=4326")
        readFeatures(format, file, tsft) must containTheSameElementsAs(features.map(ScalaSimpleFeature.retype(tsft, _)))
      }
    }
    "support sorting" in {
      forall(formats) { format =>
        val file = s"$out/${format.name}/sort/out.${format.extensions.head}"
        withCommand { command =>
          command.params.file = file
          command.params.sortFields = Collections.singletonList("dtg")
          command.execute()
        }
        readFeatures(format, file) mustEqual features.reverse
      }
      // exclude BIN as we only support sort in ascending order
      forall(formats.filter(_ != ExportFormat.Bin)) { format =>
        val file = s"$out/${format.name}/sort-rev/out.${format.extensions.head}"
        withCommand { command =>
          command.params.file = file
          command.params.sortFields = Collections.singletonList("dtg")
          command.params.sortDescending = true
          command.execute()
        }
        readFeatures(format, file) mustEqual features
      }
    }
    "support max features" in {
      // exclude arrow as max only gets applied to the binary features but not the encoded arrow buffers
      forall(formats.filter(_ != ExportFormat.Arrow)) { format =>
        val file = s"$out/${format.name}/max/out.${format.extensions.head}"
        withCommand { command =>
          command.params.file = file
          command.params.maxFeatures = 1
          command.execute()
        }
        val result = readFeatures(format, file)
        result must haveLength(1)
        features must contain(result.head)
      }
    }
    "suppress or allow empty output files" in {
      foreach(formats) { format =>
        val file = s"$out/${format.name}/empty/out.${format.extensions.head}"
        withCommand { command =>
          command.params.file = file
          command.params.suppressEmpty = true
          command.params.cqlFilter = org.geotools.api.filter.Filter.EXCLUDE
          command.execute()
        }
        val empty = new File(file)
        if (format == ExportFormat.Arrow) {
          // arrow will still write out header/footer info to the file, but results will be empty
          empty.exists() must beTrue
          readFeatures(format, file) must beEmpty
        } else {
          empty.exists() must beFalse
        }
        withCommand { command =>
          command.params.file = file
          command.params.force = true // overwrite empty arrow file without prompting
          command.params.suppressEmpty = false
          command.params.cqlFilter = org.geotools.api.filter.Filter.EXCLUDE
          command.execute()
        }
        empty.exists() must beTrue
        readFeatures(format, file) must beEmpty
      }
    }
    "support arrow with dictionaries and without feature ids" in {
      val format = ExportFormat.Arrow
      val file = s"$out/${format.name}/fid/out.${format.extensions.head}"
      withCommand { command =>
        command.params.file = file
        command.params.hints = Map("ARROW_INCLUDE_FID" -> "false", "ARROW_DICTIONARY_FIELDS" -> "name").asJava
        command.execute()
      }
      val result = readFeatures(format, file)
      result.map(_.getAttributes) must containTheSameElementsAs(features.map(_.getAttributes))
      foreach(features.map(_.getID))(id => result.map(_.getID) must not(contain(id)))
    }
  }

  step {
    PathUtils.deleteRecursively(out)
  }

  def readFeatures(format: ExportFormat, file: String, sft: SimpleFeatureType = this.sft): Seq[SimpleFeature] = {
    format match {
      case ExportFormat.Arrow      => readArrow(file)
      case ExportFormat.Avro       => readAvro(file)
      case ExportFormat.AvroNative => readAvro(file)
      case ExportFormat.Bin        => readBin(file, sft)
      case ExportFormat.Csv        => readCsv(file)
      case ExportFormat.Json       => readJson(file, sft)
      case ExportFormat.Leaflet    => readLeaflet(file, sft)
      case ExportFormat.Orc        => readOrc(file, sft)
      case ExportFormat.Parquet    => readParquet(file, sft)
      case ExportFormat.Shp        => readShp(file, sft)
      case ExportFormat.Tsv        => readTsv(file)
      case ExportFormat.Gml2       => readGml2(file, sft)
      case ExportFormat.Gml3       => readGml3(file, sft)
    }
  }

  def readArrow(file: String): Seq[SimpleFeature] = {
    WithClose(SimpleFeatureArrowFileReader.streaming(new FileInputStream(file))) { reader =>
      SelfClosingIterator(reader.features()).map(ScalaSimpleFeature.copy).toList
    }
  }

  def readAvro(file: String): Seq[SimpleFeature] =
    WithClose(new AvroDataFileReader(new FileInputStream(file)))(_.toList)

  def readBin(file: String, sft: SimpleFeatureType): Seq[SimpleFeature] = {
    val bytes = IOUtils.toByteArray(new FileInputStream(file))
    // hack - set id and name from original features since they aren't exported in the bin format
    bytes.grouped(16).map(BinaryOutputEncoder.decode).toSeq.map { values =>
      val dtg = new Date(values.dtg)
      val f1 = features.find(_.getAttribute("dtg") == dtg).get
      val attributes = sft.getAttributeDescriptors.asScala.map(_.getLocalName).map {
        case "geom" => s"POINT (${values.lon} ${values.lat})"
        case "dtg"  => dtg
        case "name" => f1.getAttribute("name")
      }
      ScalaSimpleFeature.create(sft, f1.getID, attributes.toSeq: _*)
    }
  }

  def readCsv(file: String): Seq[SimpleFeature] =
    DelimitedTextConverter.magicParsing(sft.getTypeName, new FileInputStream(file)).toList

  def readJson(file: String, sft: SimpleFeatureType): Seq[SimpleFeature] = {
    SimpleFeatureConverter.infer(() => new FileInputStream(file), None, EvaluationContext.inputFileParam(file)) match {
      case Failure(_) => Seq.empty // empty json file
      case Success((s, c)) =>
        val converter = SimpleFeatureConverter(s, c)
        val result = Seq.newBuilder[SimpleFeature]
        val names = sft.getAttributeDescriptors.asScala.map(_.getLocalName)
        WithClose(converter.process(new FileInputStream(file))) { features =>
          features.foreach { f =>
            val copy = new ScalaSimpleFeature(sft, f.getID)
            names.foreach(a => copy.setAttribute(a, f.getAttribute(a)))
            result += copy
          }
        }
        result.result()
    }
  }

  def readLeaflet(file: String, sft: SimpleFeatureType): Seq[SimpleFeature] = {
    val html = IOUtils.toString(new FileInputStream(file), StandardCharsets.UTF_8)
    val i = html.indexOf("var points = ") + 13
    val json = html.substring(i, html.indexOf(";", i))
    val tmp = Files.createTempFile("gm-export-leaflet", ".json").toFile
    try {
      WithClose(new FileWriter(tmp))(IOUtils.write(json, _))
      readJson(tmp.getAbsolutePath, sft)
    } finally {
      if (!tmp.delete()) {
        tmp.deleteOnExit()
      }
    }
  }

  def readOrc(file: String, sft: SimpleFeatureType): Seq[SimpleFeature] = {
    val path = new Path(PathUtils.getUrl(file).toURI)
    WithClose(new OrcFileSystemReader(sft, new Configuration, None, None).read(path)) { iter =>
      iter.map(ScalaSimpleFeature.copy).toList
    }
  }

  def readParquet(file: String, sft: SimpleFeatureType): Seq[SimpleFeature] = {
    val path = new Path(PathUtils.getUrl(file).toURI)
    val conf = new Configuration()
    StorageConfiguration.setSft(conf, sft)
    WithClose(new ParquetPathReader(conf, sft, FilterCompat.NOOP, None, _ => true, None).read(path)) { iter =>
      iter.map(ScalaSimpleFeature.copy).toList
    }
  }

  def readShp(file: String, sft: SimpleFeatureType): Seq[SimpleFeature] = {
    WithStore[ShapefileDataStore](Map("url" -> URLs.fileToUrl(new File(file)))) { ds =>
      // hack - set id from original features since USE_PROVIDED_FID is not supported in shapefiles
      SelfClosingIterator(ds.getFeatureReader).toList.map { f =>
        val dtg = f.getAttribute("dtg")
        val f1 = features.find(_.getAttribute("dtg") == dtg).get
        val attributes = sft.getAttributeDescriptors.asScala.map(_.getLocalName).map {
          case "geom" => f.getAttribute(0)
          case "dtg"  => dtg
          case "name" => f.getAttribute("name")
        }
        ScalaSimpleFeature.create(sft, f1.getID, attributes.toSeq: _*)
      }
    }
  }

  def readTsv(file: String): Seq[SimpleFeature] =
    DelimitedTextConverter.magicParsing(sft.getTypeName, new FileInputStream(file), CSVFormat.TDF).toList

  def readGml2(file: String, sft: SimpleFeatureType): Seq[SimpleFeature] = readGml3(file, sft)

  def readGml3(file: String, sft: SimpleFeatureType): Seq[SimpleFeature] = {
    SelfClosingIterator(new GML(GML.Version.GML3).decodeFeatureIterator(new FileInputStream(file))).toList.map { f =>
      ScalaSimpleFeature.copy(DataUtilities.reType(sft, f))
    }
  }
}

/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.convert2.simplefeature

import com.typesafe.config.Config
import com.typesafe.scalalogging.{LazyLogging, StrictLogging}
import org.geotools.api.feature.simple.SimpleFeatureType
import org.locationtech.geomesa.convert2.AbstractConverter.{BasicField, BasicOptions}
import org.locationtech.geomesa.convert2.AbstractConverterFactory._
import org.locationtech.geomesa.convert2.simplefeature.FeatureToFeatureConverterFactory.{FeatureToFeatureConfig, FeatureToFeatureConfigConvert, SftLookupVisitor}
import org.locationtech.geomesa.convert2.transforms.Expression
import org.locationtech.geomesa.convert2.transforms.Expression._
import org.locationtech.geomesa.convert2.transforms.ExpressionVisitor.ExpressionTreeVisitor
import org.locationtech.geomesa.convert2.{AbstractConverterFactory, ConverterConfig, ConverterName, SimpleFeatureConverter, SimpleFeatureConverterFactory}
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypeLoader
import pureconfig.error.ConfigReaderFailures
import pureconfig.{ConfigObjectCursor, ConfigSource}

import java.io.InputStream
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class FeatureToFeatureConverterFactory extends SimpleFeatureConverterFactory with LazyLogging {

  import FeatureToFeatureConverterFactory.TypeToProcess

  import scala.collection.JavaConverters._

  private implicit def configConvert: ConverterConfigConvert[FeatureToFeatureConfig] =
    FeatureToFeatureConfigConvert

  private implicit def fieldConvert: FieldConvert[BasicField] = BasicFieldConvert

  private implicit def optsConvert: ConverterOptionsConvert[BasicOptions] = BasicOptionsConvert

  override def apply(sft: SimpleFeatureType, conf: Config): Option[SimpleFeatureConverter] = {
    if (!conf.hasPath("type") || !conf.getString("type").equalsIgnoreCase(TypeToProcess)) { None } else {
      val (config, fields, opts) = try {
        val c = ConfigSource.fromConfig(AbstractConverterFactory.standardDefaults(conf, logger))
        val config = c.loadOrThrow[FeatureToFeatureConfig]
        val fields = c.loadOrThrow[Seq[BasicField]]
        val opts = c.loadOrThrow[BasicOptions]
        (config, fields, opts)
      } catch {
        case NonFatal(e) => throw new IllegalArgumentException(s"Invalid configuration: ${e.getMessage}")
      }

      val inputSft = SimpleFeatureTypeLoader.sftForName(config.inputSft).getOrElse {
        throw new IllegalArgumentException(s"Could not load input sft ${config.inputSft}")
      }
      // FID is put in as the last attribute, we copy it over here
      // TODO: does this have any implications for global params in the evaluation context?
      val id = if (config.idField.isDefined) { config } else {
        config.copy(idField = Some(Expression.Column(inputSft.getAttributeCount)))
      }

      // add transform expressions to look up the attribute
      val visitor = new SftLookupVisitor(inputSft)
      val columns = fields.map { field =>
        val transforms = field.transforms match {
          case Some(transforms) => Some(transforms.accept(visitor))
          case None =>
            val i = inputSft.indexOf(field.name)
            if (i == -1) { None } else { Some(Expression.Column(i)) }
        }
        field.copy(transforms = transforms)
      }

      // any matching fields that aren't explicitly defined will be copied over by default
      val defaults = {
        val names = fields.map(_.name).toSet
        sft.getAttributeDescriptors.asScala.map(_.getLocalName).filterNot(names.contains).map { name =>
          val i = inputSft.indexOf(name)
          if (i == -1) {
            BasicField(name, Some(Expression.LiteralNull))
          } else {
            BasicField(name, Some(Expression.Column(i)))
          }
        }
      }

      Some(new FeatureToFeatureConverter(sft, id, columns ++ defaults, opts))
    }
  }

  override def infer(
      is: InputStream,
      sft: Option[SimpleFeatureType],
      hints: Map[String, AnyRef]): Try[(SimpleFeatureType, Config)] = Failure(new UnsupportedOperationException())
}

object FeatureToFeatureConverterFactory {

  val TypeToProcess: String = "simple-feature"

  private val InputSftPath = "input-sft"

  case class FeatureToFeatureConfig(
      `type`: String,
      converterName: Option[String],
      inputSft: String,
      idField: Option[Expression],
      caches: Map[String, Config],
      userData: Map[String, Expression]
    ) extends ConverterConfig with ConverterName

  object FeatureToFeatureConfigConvert extends ConverterConfigConvert[FeatureToFeatureConfig] with StrictLogging {

    override protected def decodeConfig(
        cur: ConfigObjectCursor,
        `type`: String,
        idField: Option[Expression],
        caches: Map[String, Config],
        userData: Map[String, Expression]): Either[ConfigReaderFailures, FeatureToFeatureConfig] = {
      for {
        converterName <- converterName(cur).right
        sftName       <- cur.atKey(InputSftPath).right.flatMap(_.asString).right
      } yield {
        FeatureToFeatureConfig(`type`, converterName, sftName, idField, caches, userData)
      }
    }

    override protected def encodeConfig(config: FeatureToFeatureConfig, base: java.util.Map[String, AnyRef]): Unit =
      base.put(InputSftPath, config.inputSft)
  }

  /**
   * Replaces field name lookups with column lookups referencing the input sft
   *
   * @param sft input sft
   */
  private class SftLookupVisitor(sft: SimpleFeatureType) extends ExpressionTreeVisitor {
    override def visit(e: FieldLookup): Expression = {
      val i = sft.indexOf(e.n)
      if (i == -1) { e } else { Expression.Column(i) }
    }
  }
}

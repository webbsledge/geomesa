/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.convert

import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import com.typesafe.scalalogging.LazyLogging
import org.locationtech.geomesa.utils.conf.GeoMesaSystemProperties.SystemProperty

import java.net.URL
import java.util.{ServiceLoader, List => JList}
import scala.collection.JavaConverters._

object ConverterConfigLoader extends LazyLogging {

  val ConfigPathProperty: SystemProperty = SystemProperty("org.locationtech.geomesa.converter.config.path", "geomesa.converters")

  val ConverterNameKey = "converter-name"

  private val configProviders = {
    val pList = ServiceLoader.load(classOf[ConverterConfigProvider]).asScala.toList
    logger.debug(s"Found ${pList.size} SPI providers for ${classOf[ConverterConfigProvider].getName}" +
      s": ${pList.map(_.getClass.getName).mkString(", ")}")
    pList
  }

  def path: String = ConfigPathProperty.get

  // this is intentionally a method to allow reloading by the providers
  def confs: Map[String, Config] =
    configProviders.map(_.loadConfigs.asScala).reduce( _ ++ _).toMap.map { case (k, v) =>
      // add the key used to identify the converter (used for metrics)
      k -> (if (v.hasPath(ConverterNameKey)) { v } else { v.withValue(ConverterNameKey, ConfigValueFactory.fromAnyRef(k)) })
    }

  // Public API
  def listConverterNames: List[String] = confs.keys.toList
  def getAllConfigs: Map[String, Config] = confs
  def configForName(name: String): Option[Config] = confs.get(name)

  // Rebase a config to the converter root...allows standalone
  // configurations to start with "converter", "input-converter"
  // or optional other prefix string
  @deprecated("unused")
  def rebaseConfig(conf: Config, pathOverride: Option[String] = None): Config = {
    import org.locationtech.geomesa.utils.conf.ConfConversions._
    (pathOverride.toSeq ++ Seq(path, "converter", "input-converter"))
      .foldLeft(conf)( (c, p) => c.getConfigOpt(p).map(c.withFallback).getOrElse(c))
  }

}

trait GeoMesaConvertParser extends LazyLogging {
  def parseConf(config: Config): Map[String, Config] = {
    logger.trace(s"Attempting to load Converters from path ${ConverterConfigLoader.path}")
    if (!config.hasPath(ConverterConfigLoader.path)) {
      Map.empty[String, Config]
    } else {
      val confs = config.getConfig(ConverterConfigLoader.path)
      confs.root.keySet.asScala.map { k =>
        logger.trace(s"Found conf block $k")
        k -> confs.getConfig(k)
      }.toMap[String, Config]
    }
  }
}

object GeoMesaConvertParser {
  def isConvertConfig(conf: Config): Boolean = {
    conf.hasPath("type")
  }
}
/**
  * Provides access converter configs on the classpath
  */
class ClassPathConfigProvider extends ConverterConfigProvider with GeoMesaConvertParser {
  // intentionally keep as a method so we can reload dynamically
  override def loadConfigs(): java.util.Map[String, Config] = parseConf(ConfigFactory.load).asJava
}

/** Load Config from URLs */
class URLConfigProvider extends ConverterConfigProvider with GeoMesaConvertParser {
  import URLConfigProvider._

  override def loadConfigs(): java.util.Map[String, Config] = {
    val confs = configURLs.flatMap { url =>
      try {
        Some(ConfigFactory.parseURL(url).resolve())
      } catch {
        case e: Throwable =>
          logger.warn(s"Unable to load converter config from url $url")
          logger.trace(s"Unable to load converter config from url $url", e)
          None
      }
    }
    confs.reduceLeftOption(_.withFallback(_)).map(parseConf).getOrElse(Map.empty[String, Config]).asJava
  }

  // Will also pick things up from the SystemProperties
  def configURLs: Seq[URL] = {
    val config = ConfigFactory.load()
    if (config.hasPath(ConverterConfigURLs)) {
      config.getAnyRef(ConverterConfigURLs) match {
        case s: String          => s.split(',').map(_.trim).map(new URL(_))
        case lst: JList[String] => lst.asScala.map(new URL(_)).toSeq
      }
    } else {
      Seq.empty[URL]
    }
  }

}

object URLConfigProvider {
  val ConverterConfigURLs = "geomesa.convert.config.urls"
}

object SimpleConverterConfigParser extends GeoMesaConvertParser

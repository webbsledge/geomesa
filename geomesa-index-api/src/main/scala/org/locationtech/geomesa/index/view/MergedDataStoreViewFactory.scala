/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.view

import com.typesafe.config._
import org.geotools.api.data.DataAccessFactory.Param
import org.geotools.api.data.{DataStore, DataStoreFactorySpi, DataStoreFinder}
import org.geotools.api.filter.Filter
import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory.{GeoMesaDataStoreInfo, NamespaceParams}
import org.locationtech.geomesa.utils.classpath.ServiceLoader
import org.locationtech.geomesa.utils.geotools.GeoMesaParam
import org.locationtech.geomesa.utils.geotools.GeoMesaParam.ReadWriteFlag

import java.awt.RenderingHints
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
  * Data store factory for merged view
  */
class MergedDataStoreViewFactory extends DataStoreFactorySpi {

  import MergedDataStoreViewFactory._
  import org.locationtech.geomesa.utils.conf.ConfConversions.RichConfig

  import scala.collection.JavaConverters._

  override def canProcess(params: java.util.Map[String, _]): Boolean =
    MergedDataStoreViewFactory.canProcess(params)

  override def createDataStore(params: java.util.Map[String, _]): DataStore =
    createNewDataStore(params)

  override def createNewDataStore(params: java.util.Map[String, _]): DataStore = {
    val configs: Seq[Config] = {
      val explicit = Option(ConfigParam.lookup(params)).map(c => ConfigFactory.parseString(c).resolve())
      val loaded = ConfigLoaderParam.flatMap(_.lookupOpt(params)).flatMap { name =>
        ServiceLoader.load[MergedViewConfigLoader]().find(_.getClass.getName == name).map(_.load())
      }
      Seq(explicit, loaded).flatten.flatMap { config =>
        if (config.hasPath("stores")) { config.getConfigList("stores").asScala } else { Seq.empty }
      }
    }

    if (configs.isEmpty) {
      throw new IllegalArgumentException("No 'stores' element defined in configuration")
    }

    val namespace = NamespaceParam.lookupOpt(params)
    val nsConfig = namespace.map(ConfigValueFactory.fromAnyRef)

    val stores = Seq.newBuilder[(DataStore, Option[Filter])]
    stores.sizeHint(configs.length)

    try {
      configs.foreach { config =>
        lazy val error =
          new IllegalArgumentException(s"Could not load store using configuration:\n" +
              config.root().render(ConfigRenderOptions.concise().setFormatted(true)))
        // inject the namespace into the underlying stores
        val paramConf = nsConfig.map(config.withValue(NamespaceParam.key, _)).getOrElse(config)
        val storeParams = new java.util.HashMap[String, AnyRef](paramConf.root().unwrapped())
        // allow for unquoted keys
        storeParams.putAll(paramConf.toStringMap().asJava)
        val filter = try { StoreFilterParam.lookupOpt(storeParams).map(ECQL.toFilter) } catch {
          case NonFatal(e) =>
            throw new IllegalArgumentException(s"Invalid store filter '${storeParams.get(StoreFilterParam.key)}'", e)
        }
        Try(DataStoreFinder.getDataStore(storeParams)) match {
          case Success(null)  => throw error
          case Success(store) => stores += store -> filter
          case Failure(e)     => throw error.initCause(e)
        }
      }
    } catch {
      case NonFatal(e) => stores.result.foreach(_._1.dispose()); throw e
    }

    val deduplicate = DeduplicateParam.lookup(params).booleanValue()
    val parallel = ParallelScanParam.lookup(params).booleanValue()

    new MergedDataStoreView(stores.result, deduplicate, parallel, namespace)
  }

  override def getDisplayName: String = DisplayName

  override def getDescription: String = Description

  override def getParametersInfo: Array[Param] = Array(ParameterInfo :+ NamespaceParam: _*)

  override def isAvailable: Boolean = true

  override def getImplementationHints: java.util.Map[RenderingHints.Key, _] = null
}

object MergedDataStoreViewFactory extends GeoMesaDataStoreInfo with NamespaceParams {

  override val DisplayName: String = "Merged DataStore View (GeoMesa)"
  override val Description: String = "A merged, read-only view of multiple data stores"

  val StoreFilterParam = new GeoMesaParam[String]("geomesa.merged.store.filter", readWrite = ReadWriteFlag.ReadOnly)

  val ConfigLoaderParam: Option[GeoMesaParam[String]] = {
    val loaders = ServiceLoader.load[MergedViewConfigLoader]().map(_.getClass.getName)
    if (loaders.isEmpty) { None } else {
      val param =
        new GeoMesaParam[String](
          "geomesa.merged.loader",
          "Loader used to configure the underlying data stores to query",
          enumerations = loaders,
          readWrite = ReadWriteFlag.ReadOnly
        )
      Some(param)
    }
  }

  val ConfigParam =
    new GeoMesaParam[String](
      "geomesa.merged.stores",
      "Typesafe configuration defining the underlying data stores to query",
      optional = ConfigLoaderParam.isDefined,
      largeText = true,
      readWrite = ReadWriteFlag.ReadOnly
    )

  val DeduplicateParam =
    new GeoMesaParam[java.lang.Boolean](
      "geomesa.merged.deduplicate",
      "Deduplicate the features returned from each store",
      default = java.lang.Boolean.FALSE,
      readWrite = ReadWriteFlag.ReadOnly
    )

  val ParallelScanParam =
    new GeoMesaParam[java.lang.Boolean](
      "geomesa.merged.scan.parallel",
      "Scan each store in parallel, instead of sequentially",
      default = java.lang.Boolean.FALSE,
      readWrite = ReadWriteFlag.ReadOnly
    )

  override val ParameterInfo: Array[GeoMesaParam[_ <: AnyRef]] =
    ConfigLoaderParam.toArray ++ Array[GeoMesaParam[_ <: AnyRef]](ConfigParam, DeduplicateParam, ParallelScanParam)

  override def canProcess(params: java.util.Map[String, _]): Boolean =
    params.containsKey(ConfigParam.key) || ConfigLoaderParam.exists(p => params.containsKey(p.key))
}

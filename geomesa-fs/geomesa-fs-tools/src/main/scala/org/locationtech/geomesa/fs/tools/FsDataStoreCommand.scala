/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.fs.tools

import com.beust.jcommander.{IValueValidator, Parameter, ParameterException}
import org.apache.hadoop.conf.Configuration
import org.locationtech.geomesa.fs.data.FileSystemDataStore
import org.locationtech.geomesa.fs.data.FileSystemDataStoreFactory.FileSystemDataStoreParams
import org.locationtech.geomesa.fs.storage.api.FileSystemStorageFactory
import org.locationtech.geomesa.fs.tools.FsDataStoreCommand.FsParams
import org.locationtech.geomesa.tools.utils.NoopParameterSplitter
import org.locationtech.geomesa.tools.utils.ParameterConverters.{BytesValidator, KeyValueConverter}
import org.locationtech.geomesa.tools.{DataStoreCommand, DistributedCommand}
import org.locationtech.geomesa.utils.classpath.ClassPathUtils
import org.locationtech.geomesa.utils.io.PathUtils

import java.io.{File, StringWriter}
import java.util
import java.util.ServiceLoader

/**
 * Abstract class for FSDS commands
 */
trait FsDataStoreCommand extends DataStoreCommand[FileSystemDataStore] {

  import scala.collection.JavaConverters._

  override def params: FsParams

  override def connection: Map[String, String] = {
    val url = PathUtils.getUrl(params.path)
    val builder = Map.newBuilder[String, String]
    builder += (FileSystemDataStoreParams.PathParam.getName -> url.toString)
    if (params.configuration != null && !params.configuration.isEmpty) {
      val xml = {
        val conf = new Configuration(false)
        params.configuration.asScala.foreach { case (k, v) => conf.set(k, v) }
        val out = new StringWriter()
        conf.writeXml(out)
        out.toString
      }
      builder += (FileSystemDataStoreParams.ConfigsParam.getName -> xml)
    }
    if (params.auths != null) {
      builder += (FileSystemDataStoreParams.AuthsParam.getName -> params.auths)
    }
    builder.result()
  }
}

object FsDataStoreCommand {

  import scala.collection.JavaConverters._

  trait FsDistributedCommand extends FsDataStoreCommand with DistributedCommand {

    abstract override def libjarsFiles: Seq[String] =
      Seq("org/locationtech/geomesa/fs/tools/fs-libjars.list") ++ super.libjarsFiles

    abstract override def libjarsPaths: Iterator[() => Seq[File]] = Iterator(
      () => ClassPathUtils.getJarsFromEnvironment("GEOMESA_FS_HOME", "lib"),
      () => ClassPathUtils.getJarsFromClasspath(classOf[FileSystemDataStore])
    ) ++ super.libjarsPaths
  }

  trait FsParams {
    @Parameter(names = Array("--path", "-p"), description = "Path to root of filesystem datastore", required = true)
    var path: String = _

    @Parameter(
      names = Array("--config"),
      description = "Configuration properties, in the form k=v",
      converter = classOf[KeyValueConverter],
      splitter = classOf[NoopParameterSplitter])
    var configuration: java.util.List[(String, String)] = _

    @Parameter(names = Array("--auths"), description = "Authorizations used to read data")
    var auths: String = _
  }

  trait PartitionParam {
    @Parameter(names = Array("--partitions"), description = "Partitions to operate on (if empty all partitions will be used)")
    var partitions: java.util.List[String] = new util.ArrayList[String]()
  }

  trait OptionalEncodingParam {
    @Parameter(
      names = Array("--encoding", "-e"),
      description = "Encoding (parquet, orc, converter, etc)",
      validateValueWith = Array(classOf[EncodingValidator]))
    var encoding: String = _
  }

  trait OptionalSchemeParams {
    @Parameter(names = Array("--partition-scheme"), description = "PartitionScheme typesafe config string or file")
    var scheme: java.lang.String = _

    @Parameter(names = Array("--leaf-storage"), description = "Use Leaf Storage for Partition Scheme", arity = 1)
    var leafStorage: java.lang.Boolean = true

    @Parameter(
      names = Array("--storage-opt"),
      description = "Additional storage options to set as SimpleFeatureType user data, in the form key=value",
      converter = classOf[KeyValueConverter],
      splitter = classOf[NoopParameterSplitter])
    var storageOpts: java.util.List[(String, String)] = new java.util.ArrayList[(String, String)]()

    @Parameter(
      names = Array("--target-file-size"),
      description = "Target size for data files",
      validateValueWith = Array(classOf[BytesValidator]))
    var targetFileSize: String = _
  }

  class EncodingValidator extends IValueValidator[String] {
    override def validate(name: String, value: String): Unit = {
      val encodings = ServiceLoader.load(classOf[FileSystemStorageFactory]).asScala.map(_.encoding).toList
      if (!encodings.exists(_.equalsIgnoreCase(value))) {
        throw new ParameterException(s"$value is not a valid encoding for parameter $name." +
            s"Available encodings are: ${encodings.mkString(", ")}")
      }
    }
  }
}

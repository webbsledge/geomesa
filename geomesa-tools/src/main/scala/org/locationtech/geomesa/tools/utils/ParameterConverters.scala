/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.tools.utils

import com.beust.jcommander.converters.BaseConverter
import com.beust.jcommander.{IValueValidator, ParameterException}
import org.geotools.api.filter.Filter
import org.geotools.filter.text.ecql.ECQL
import org.locationtech.geomesa.convert.Modes.ErrorMode
import org.locationtech.geomesa.tools.`export`.ExportFormat
import org.locationtech.geomesa.utils.geotools.converters.FastConverter
import org.locationtech.geomesa.utils.text.Suffixes.Memory
import org.locationtech.geomesa.utils.text.{DurationParsing, TextTools}

import java.util.Date
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object ParameterConverters {

  class HintConverter(name: String) extends BaseConverter[java.util.Map[String, String]](name) {
    override def convert(value: String): java.util.Map[String, String] = {
      try {
        val map = new java.util.HashMap[String, String]()
        value.split(";").foreach { part =>
          val Array(k, v) = part.split("=")
          map.put(k.trim, v.trim)
        }
        map
      } catch {
        case NonFatal(e) => throw new ParameterException(getErrorString(value, s"hint map: $e"))
      }
    }
  }

  class DurationConverter(name: String) extends BaseConverter[Duration](name) {
    override def convert(value: String): Duration = {
      try { DurationParsing.caseInsensitive(value) } catch {
        case NonFatal(e) => throw new ParameterException(getErrorString(value, s"duration: $e"))
      }
    }
  }

  class FilterConverter(name: String) extends BaseConverter[Filter](name) {
    override def convert(value: String): Filter = {
      try {
        ECQL.toFilter(value)
      } catch {
        case NonFatal(e) => throw new ParameterException(getErrorString(value, s"filter: $e"))
      }
    }
  }

  class ExportFormatConverter(name: String) extends BaseConverter[ExportFormat](name) {
    override def convert(value: String): ExportFormat = {
      ExportFormat(value).getOrElse {
        val valid = ExportFormat.Formats.flatMap(f => f.extensions :+ f.name).distinct
        throw new ParameterException(getErrorString(value, s"an export format. Valid formats are: ${TextTools.wordList(valid)}"))
      }
    }
  }

  class KeyValueConverter(name: String) extends BaseConverter[(String, String)](name) {
    override def convert(value: String): (String, String) = {
      try {
        val i = value.indexOf('=')
        if (i == -1 || value.indexOf('=', i + 1) != -1) {
          throw new IllegalArgumentException("key-value pairs must be separated by a single '='")
        }
        (value.substring(0, i), value.substring(i + 1))
      } catch {
        case NonFatal(e) => throw new ParameterException(getErrorString(value, s"format: $e"))
      }
    }
  }

  class DateConverter(name: String) extends BaseConverter[Date](name) {
    override def convert(value: String): Date = {
      try {
        val date = FastConverter.convert(value, classOf[Date])
        if (date == null) {
          throw new IllegalArgumentException(s"Could not convert $value to date")
        }
        date
      } catch {
        case NonFatal(e) => throw new ParameterException(getErrorString(value, s"format: $e"))
      }
    }
  }

  class IntervalConverter(name: String) extends BaseConverter[(Date, Date)](name) {
    override def convert(value: String): (Date, Date) = {
      try {
        val i = value.indexOf('/')
        if (i == -1 || value.indexOf('/', i + 1) != -1) {
          throw new IllegalArgumentException("Interval from/to must be separated by a single '/'")
        }
        val start = FastConverter.convert(value.substring(0, i), classOf[Date])
        val end = FastConverter.convert(value.substring(i + 1), classOf[Date])
        if (start == null || end == null) {
          throw new IllegalArgumentException(s"Could not convert $value to date interval")
        }
        (start, end)
      } catch {
        case NonFatal(e) => throw new ParameterException(getErrorString(value, s"format: $e"))
      }
    }
  }

  class ErrorModeConverter(name: String) extends BaseConverter[ErrorMode](name) {
    override def convert(value: String): ErrorMode = {
      Try(ErrorMode(value)) match {
        case Success(m) => m
        case Failure(e) => throw new ParameterException(e.getMessage)
      }
    }
  }

  class BytesConverter(name: String) extends BaseConverter[java.lang.Long](name) {
    override def convert(value: String): java.lang.Long = {
      Memory.bytes(value) match {
        case Success(b) => b
        case Failure(e) => throw new ParameterException(s"Invalid byte string '$value'", e)
      }
    }
  }

  class BytesValidator extends IValueValidator[String] {
    override def validate(name: String, value: String): Unit =
      Memory.bytes(value).failed.foreach(e => throw new ParameterException(s"Invalid byte string '$value'", e))
  }
}

/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa

import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import io.micrometer.core.instrument.Timer
import org.locationtech.geomesa.convert.EvaluationContext
import org.locationtech.geomesa.convert.EvaluationContext.EvaluationError
import org.locationtech.geomesa.convert.Modes.{ErrorMode, ParseMode}
import org.locationtech.geomesa.convert2.transforms.Expression
import org.locationtech.geomesa.utils.collection.CloseableIterator

import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

package object convert2 {

  /**
   * Try a sequence of attempts. Return the first success. If there are no successes,
   * Failures will be added as suppressed exceptions.
   *
   * @param attempts attempts
   * @param failure umbrella exception in case of failure
   * @tparam T attempt type
   * @return
   */
  def multiTry[T](attempts: Iterator[Try[T]], failure: Throwable): Try[T] = {
    var res: Try[T] = Failure(failure)
    while (res.isFailure && attempts.hasNext) {
      val attempt = attempts.next
      if (attempt.isSuccess) {
        res = attempt
      } else {
        val e = attempt.failed.get
        // filter out UnsupportedOperationException, so we don't clutter up the logs with failed conversion attempts from
        // converter factories that haven't implemented schema inference
        if (!e.isInstanceOf[UnsupportedOperationException] && !Option(e.getCause).exists(_.isInstanceOf[UnsupportedOperationException])) {
          failure.addSuppressed(e)
        }
      }
    }
    res
  }

  trait ConverterConfig {
    def `type`: String
    def idField: Option[Expression]
    def caches: Map[String, Config]
    def userData: Map[String, Expression]
  }

  /**
   * This is an intermediate trait that allows configs to specify a name for metrics.
   * It will be folded into ConverterConfig in the next major release
   */
  trait ConverterName extends ConverterConfig {
    def converterName: Option[String]
  }

  object ConverterName {
    def unapply(name: ConverterName): Option[String] = name.converterName
  }

  trait Field {

    def name: String
    def transforms: Option[Expression]

    /**
     * Provides a chance for the field to select and filter the raw input values,
     * e.g. by applying an x-path expression or taking a sub-section of the input
     *
     * @return an optional function to use instead of the args
     */
    def fieldArg: Option[Array[AnyRef] => AnyRef]
  }

  trait ConverterOptions {
    def validators: Seq[String]
    @deprecated("Use micrometer global registry to manage reporters")
    def reporters: Seq[Config]
    def parseMode: ParseMode
    def errorMode: ErrorMode
    def encoding: Charset
  }

  /**
    * Handles errors from the underlying parsing of data, before converting to simple features
    *
    * @param underlying wrapped iterator
    * @param mode error mode
    * @param ec EvaluationContext for tracking errors
    * @param timer timer for tracking convert times
    */
  class ErrorHandlingIterator[T](
      underlying: CloseableIterator[T],
      mode: ErrorMode,
      ec: EvaluationContext,
      timer: Timer
    ) extends CloseableIterator[T] with LazyLogging {

    private var staged: T = _
    private var error = false

    override final def hasNext: Boolean = staged != null || {
      if (error) { false } else {
        // make sure that we successfully read an underlying record, so that we can always return
        // a valid record in `next`, otherwise failures will get double counted
        val start = System.nanoTime()
        try {
          if (underlying.hasNext) {
            staged = underlying.next
            true
          } else {
            false
          }
        } catch {
          case NonFatal(e) =>
            ec.stats.failure()
            mode match {
              case ErrorMode.LogErrors => logger.warn("Failed parsing input: ", e)
              case ErrorMode.ReturnErrors => ec.errors.add(EvaluationError(null, ec.line, new IOException("Failed parsing input: ", e)))
              case ErrorMode.RaiseErrors => throw new IOException("Failed parsing input: ", e)
            }
            error = true
            false // usually parsing can't continue if there is an exception in the underlying read
        } finally {
          timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
      }
    }

    override def next(): T = {
      if (!hasNext) { Iterator.empty.next() } else {
        val res = staged
        staged = null.asInstanceOf[T]
        res
      }
    }

    override def close(): Unit = underlying.close()
  }
}

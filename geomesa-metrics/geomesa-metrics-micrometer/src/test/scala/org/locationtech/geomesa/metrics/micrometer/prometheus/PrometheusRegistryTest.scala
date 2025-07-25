/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.metrics.micrometer
package prometheus

import com.typesafe.config.ConfigFactory
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.util.IOUtils
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.exporter.pushgateway.Format
import org.junit.runner.RunWith
import org.mortbay.jetty.handler.AbstractHandler
import org.mortbay.jetty.{Request, Server}
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.io.{BufferedReader, InputStreamReader}
import java.net.{ServerSocket, URL}
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import scala.collection.mutable.ArrayBuffer

@RunWith(classOf[JUnitRunner])
class PrometheusRegistryTest extends Specification {

  private def getFreePort: Int = {
    val socket = new ServerSocket(0)
    try { socket.getLocalPort } finally {
      socket.close()
    }
  }

  "Prometheus registry" should {
    "expose metrics over http" in {
      val port = getFreePort
      val conf = ConfigFactory.parseString(s"{ type = prometheus, port = $port }")
      val registry = MicrometerSetup.createRegistry(conf)
      try {
        registry must beAnInstanceOf[PrometheusMeterRegistry]
        registry.counter("foo").increment(10)

        val metrics = ArrayBuffer.empty[String]
        val url = new URL(s"http://localhost:$port/metrics")
        val reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))
        try {
          var line = reader.readLine()
          while (line != null) {
            metrics += line
            line = reader.readLine()
          }
        } finally {
          reader.close()
        }

        metrics must contain("foo_total 10.0")
      } finally {
        registry.close()
      }
    }

    "expose global metrics over http" in {
      val port = getFreePort
      val registration = PrometheusSetup.register(port)
      try {
        val id = "foo" + UUID.randomUUID().toString.replaceAll("-", "")
        Metrics.counter(id).increment(10)

        val metrics = ArrayBuffer.empty[String]
        val url = new URL(s"http://localhost:$port/metrics")
        val reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))
        try {
          var line = reader.readLine()
          while (line != null) {
            metrics += line
            line = reader.readLine()
          }
        } finally {
          reader.close()
        }

        metrics must contain(s"""${id}_total{application="geomesa"} 10.0""")
      } finally {
        registration.close()
      }
    }

    "expose metrics over http with custom tags" in {
      val port = getFreePort
      val conf = ConfigFactory.parseString(s"{ type = prometheus, port = $port, common-tags = { foo = bar }}")
      val registry = MicrometerSetup.createRegistry(conf)
      try {
        registry must beAnInstanceOf[PrometheusMeterRegistry]
        registry.counter("foo").increment(10)

        val metrics = ArrayBuffer.empty[String]
        val url = new URL(s"http://localhost:$port/metrics")
        val reader = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))
        try {
          var line = reader.readLine()
          while (line != null) {
            metrics += line
            line = reader.readLine()
          }
        } finally {
          reader.close()
        }

        metrics must contain("""foo_total{foo="bar"} 10.0""")
      } finally {
        registry.close()
      }
    }

    "push metrics to a gateway" in {
      val handler = new PgHandler()
      val jetty = new Server(0)
      jetty.setHandler(handler)
      try {
        jetty.start()
        val port = jetty.getConnectors()(0).getLocalPort
        val conf = ConfigFactory.parseString(s"""{ type = prometheus, push-gateway = { host = "localhost:$port", job = job1, format = prometheus_text }}""")
        val registry = MicrometerSetup.createRegistry(conf)
        try {
          registry must beAnInstanceOf[PrometheusMeterRegistry]
          registry.counter("foo").increment(10)
        } finally {
          registry.close()
        }
        handler.requests.keys must contain("/metrics/job/job1")
        val job1 = handler.requests("/metrics/job/job1")
        job1 must contain("foo_total 10")

        val id = "foo" + UUID.randomUUID().toString.replaceAll("-", "")
        val registration = PrometheusSetup.registerPushGateway(s"localhost:$port", "job2", format = Format.PROMETHEUS_TEXT)
        try {
          Metrics.counter(id).increment(10)
        } finally {
          registration.close()
        }
        handler.requests.keys must contain("/metrics/job/job2")
        val job2 = handler.requests("/metrics/job/job2")
        job2 must contain(s"""${id}_total{application="geomesa"} 10""")
      } finally {
        jetty.stop()
      }
    }

    "push metrics to a gateway in protobuf" in {
      val handler = new PgHandler()
      val jetty = new Server(0)
      jetty.setHandler(handler)
      try {
        jetty.start()
        val port = jetty.getConnectors()(0).getLocalPort
        val conf = ConfigFactory.parseString(s"""{ type = prometheus, push-gateway = { host = "localhost:$port", job = job1 }}""")
        val registry = MicrometerSetup.createRegistry(conf)
        try {
          registry must beAnInstanceOf[PrometheusMeterRegistry]
          registry.counter("foo").increment(10)
        } finally {
          registry.close()
        }
        handler.requests.keys must contain("/metrics/job/job1")
        // note: post is protobuf by default
        val job1 = handler.requests("/metrics/job/job1")
        job1 must contain("foo_total")
        job1 must not(contain("10")) // kind of a hacky way to verify things are protobuf encoded

        val id = "foo" + UUID.randomUUID().toString.replaceAll("-", "").replaceAll("10", "x") // so we don't match on the uuid, below
        val registration = PrometheusSetup.registerPushGateway(s"localhost:$port", "job2")
        try {
          Metrics.counter(id).increment(10)
        } finally {
          registration.close()
        }
        handler.requests.keys must contain("/metrics/job/job2")
        // note: post is protobuf by default
        val job2 = handler.requests("/metrics/job/job2")
        job2 must contain(s"${id}_total")
        job2 must not(contain("10")) // kind of a hacky way to verify things are protobuf encoded
      } finally {
        jetty.stop()
      }
    }

    "push metrics to a gateway with custom tags" in {
      val handler = new PgHandler()
      val jetty = new Server(0)
      jetty.setHandler(handler)
      try {
        jetty.start()
        val port = jetty.getConnectors()(0).getLocalPort
        val conf =
          ConfigFactory.parseString(
            s"""{ type = prometheus,  common-tags = { blu = baz }, push-gateway = { host = "localhost:$port", job = job1 }}""")
        val registry = MicrometerSetup.createRegistry(conf)
        try {
          registry must beAnInstanceOf[PrometheusMeterRegistry]
          registry.counter("foo").increment(10)
        } finally {
          registry.close()
        }
        handler.requests.keys must contain("/metrics/job/job1")
        // note: post is protobuf by default
        val metrics = handler.requests("/metrics/job/job1")
        metrics must contain("foo_total")
        metrics must contain("blu")
        metrics must contain("baz")
      } finally {
        jetty.stop()
      }
    }
  }

  class PgHandler extends AbstractHandler {
    val requests: scala.collection.mutable.Map[String, String] = scala.collection.mutable.Map.empty[String, String]
    override def handle(s: String, req: HttpServletRequest, resp: HttpServletResponse, i: Int): Unit = {
      val is = req.getInputStream
      try { requests += req.getPathInfo -> IOUtils.toString(is, StandardCharsets.UTF_8) } finally {
        is.close()
      }
      resp.setStatus(HttpServletResponse.SC_OK)
      req.asInstanceOf[Request].setHandled(true)
    }
  }
}

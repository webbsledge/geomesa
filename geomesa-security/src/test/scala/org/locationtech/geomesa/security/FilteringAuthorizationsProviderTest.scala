/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.security

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

import java.io.Serializable
import java.util
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class FilteringAuthorizationsProviderTest extends Specification {

  sequential

  val wrapped = new AuthorizationsProvider {
    override def configure(params: util.Map[String, _]): Unit = { }
    override def getAuthorizations: java.util.List[String] = util.Arrays.asList("user", "admin", "test")
  }

  "FilteringAuthorizationsProvider" should {
    "filter wrapped authorizations" in {
      val filter = new FilteringAuthorizationsProvider(wrapped)
      filter.configure(Map[String, Serializable]("geomesa.security.auths" -> "admin").asJava)
      val auths = filter.getAuthorizations.asScala

      auths should not be null
      auths.length mustEqual 1
      auths.contains("admin") must beTrue
    }

    "filter multiple authorizations" in {
      val filter = new FilteringAuthorizationsProvider(wrapped)
      filter.configure(Map[String, Serializable]("geomesa.security.auths" -> "user,test").asJava)
      val auths = filter.getAuthorizations

      auths should not be null
      auths.asScala.length mustEqual 2

      auths.contains("user") must beTrue
      auths.contains("test") must beTrue
    }

    "not filter if no filter is specified" in {
      val filter = new FilteringAuthorizationsProvider(wrapped)
      filter.configure(Map[String, Serializable]().asJava)
      val auths = filter.getAuthorizations
      auths should not be null
      auths.asScala.length mustEqual 3

      auths.contains("user") must beTrue
      auths.contains("admin") must beTrue
      auths.contains("test") must beTrue
    }
  }
}

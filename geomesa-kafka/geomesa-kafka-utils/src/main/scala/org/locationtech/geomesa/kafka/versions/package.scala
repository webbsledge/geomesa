/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.kafka

import java.lang.reflect.InvocationTargetException

package object versions {

  def tryInvocation[T](fn: => T): T = {
    try { fn } catch {
      case e: InvocationTargetException => throw e.getCause
    }
  }
}

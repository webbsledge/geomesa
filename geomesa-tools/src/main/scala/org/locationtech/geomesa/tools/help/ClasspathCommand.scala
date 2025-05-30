/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.tools.help

import com.beust.jcommander.Parameters
import org.locationtech.geomesa.tools.Command
import org.locationtech.geomesa.tools.help.ClasspathCommand.ClasspathParameters

/**
  * Note: this class is a placeholder for the 'classpath' function implemented in the 'geomesa-*' script, to get it
  * to show up in the JCommander help
  */
class ClasspathCommand extends Command {
  override val name = "classpath"
  override val params = new ClasspathParameters
  override def execute(): Unit = {}
}

object ClasspathCommand {
  @Parameters(commandDescription = "Display the GeoMesa classpath")
  class ClasspathParameters {}
}

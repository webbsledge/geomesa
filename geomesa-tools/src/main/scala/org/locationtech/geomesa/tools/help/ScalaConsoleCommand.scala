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
import org.locationtech.geomesa.tools.help.ScalaConsoleCommand.ConsoleParameters

/**
 * Note: this class is a placeholder for the 'scala-console' function implemented in the 'common-functions'
 * script, to get it to show up in the JCommander help
 */
class ScalaConsoleCommand extends Command {
  override val name = "scala-console"
  override val params = new ConsoleParameters
  override def execute(): Unit = {}
}

object ScalaConsoleCommand {
  @Parameters(commandDescription = "Run a Scala REPL with the GeoMesa classpath and configuration loaded")
  class ConsoleParameters {}
}

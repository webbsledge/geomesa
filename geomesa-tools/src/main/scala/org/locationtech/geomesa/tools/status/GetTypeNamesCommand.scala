/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.tools.status

import org.geotools.api.data.DataStore
import org.locationtech.geomesa.tools.{Command, DataStoreCommand}

trait GetTypeNamesCommand[DS <: DataStore] extends DataStoreCommand[DS] {

  override val name = "get-type-names"

  override def execute(): Unit = {
    Command.output.info("Current feature types:")
    withDataStore(_.getTypeNames.foreach(m => Command.output.info(m)))
  }
}

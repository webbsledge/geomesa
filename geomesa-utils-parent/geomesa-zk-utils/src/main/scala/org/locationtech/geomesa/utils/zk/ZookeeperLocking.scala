/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.utils.zk

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex
import org.locationtech.geomesa.index.utils.DistributedLocking
import org.locationtech.geomesa.utils.io.CloseQuietly

import java.io.Closeable
import java.util.concurrent.TimeUnit

trait ZookeeperLocking extends DistributedLocking {

  protected def zookeepers: String

  /**
    * Gets and acquires a distributed lock based on the key.
    * Make sure that you 'release' the lock in a finally block.
    *
    * @param key key to lock on - equivalent to a path in zookeeper
    * @return the lock
    */
  override protected def acquireDistributedLock(key: String): Closeable = {
    val (client, lock) = distributedLock(key)
    try {
      lock.acquire()
      ZookeeperLocking.releasable(lock, client)
    } catch {
      case e: Exception => CloseQuietly(client).foreach(e.addSuppressed); throw e
    }
  }

  /**
    * Gets and acquires a distributed lock based on the key.
    * Make sure that you 'release' the lock in a finally block.
    *
    * @param key key to lock on - equivalent to a path in zookeeper
    * @param timeOut how long to wait to acquire the lock, in millis
    * @return the lock, if obtained
    */
  override protected def acquireDistributedLock(key: String, timeOut: Long): Option[Closeable] = {
    val (client, lock) = distributedLock(key)
    try {
      if (lock.acquire(timeOut, TimeUnit.MILLISECONDS)) {
        Some(ZookeeperLocking.releasable(lock, client))
      } else {
        None
      }
    } catch {
      case e: Exception => CloseQuietly(client).foreach(e.addSuppressed); throw e
    }
  }

  private def distributedLock(key: String): (CuratorFramework, InterProcessSemaphoreMutex) = {
    val lockPath = if (key.startsWith("/")) key else s"/$key"
    val client = CuratorHelper.client(zookeepers).build()
    client.start()
    val lock = new InterProcessSemaphoreMutex(client, lockPath)
    (client, lock)
  }
}

object ZookeeperLocking {

  // delegate lock that will close the curator client upon release
  def releasable(lock: InterProcessSemaphoreMutex, client: CuratorFramework): Closeable =
    () => try { lock.release() } finally { client.close() }
}

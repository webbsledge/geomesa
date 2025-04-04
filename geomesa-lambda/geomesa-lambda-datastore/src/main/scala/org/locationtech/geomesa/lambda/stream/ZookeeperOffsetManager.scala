/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.lambda.stream

import com.typesafe.scalalogging.LazyLogging
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.cache.{PathChildrenCache, PathChildrenCacheEvent, PathChildrenCacheListener}
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex
import org.locationtech.geomesa.lambda.stream.OffsetManager.OffsetListener
import org.locationtech.geomesa.lambda.stream.ZookeeperOffsetManager.CuratorOffsetListener
import org.locationtech.geomesa.utils.index.ByteArrays
import org.locationtech.geomesa.utils.io.CloseWithLogging
import org.locationtech.geomesa.utils.zk.CuratorHelper

import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.concurrent.{Executors, TimeUnit}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class ZookeeperOffsetManager(zookeepers: String, namespace: String = "geomesa") extends OffsetManager {

  import ZookeeperOffsetManager.offsetsPath

  private val client = CuratorHelper.client(zookeepers).namespace(namespace).build()
  client.start()

  private val listeners = scala.collection.mutable.Map.empty[String, CuratorOffsetListener]

  override def getOffset(topic: String, partition: Int): Long = {
    val path = ZookeeperOffsetManager.offsetsPath(topic, partition)
    if (client.checkExists().forPath(path) == null) { -1L } else {
      ZookeeperOffsetManager.deserializeOffsets(client.getData.forPath(path))
    }
  }

  override def setOffset(topic: String, partition: Int, offset: Long): Unit = {
    val path = ZookeeperOffsetManager.offsetsPath(topic, partition)
    if (client.checkExists().forPath(path) == null) {
      client.create().creatingParentsIfNeeded().forPath(path)
    }
    client.setData().forPath(path, ZookeeperOffsetManager.serializeOffset(offset))
  }

  override def deleteOffsets(topic: String): Unit = {
    val path = ZookeeperOffsetManager.offsetsPath(topic)
    if (client.checkExists().forPath(path) != null) {
      client.delete().deletingChildrenIfNeeded().forPath(path)
    }
  }

  override def addOffsetListener(topic: String, listener: OffsetListener): Unit = synchronized {
    listeners.getOrElseUpdate(topic, new CuratorOffsetListener(client, offsetsPath(topic))).addListener(listener)
  }

  override def removeOffsetListener(topic: String, listener: OffsetListener): Unit = synchronized {
    listeners.get(topic).foreach(_.removeListener(listener))
  }

  override def close(): Unit = synchronized {
    CloseWithLogging(listeners.values)
    listeners.clear()
    CloseWithLogging(client)
  }

  override protected def acquireDistributedLock(path: String): Closeable =
    acquireLock(path, lock => { lock.acquire(); true })

  override protected def acquireDistributedLock(path: String, timeOut: Long): Option[Closeable] =
    Option(acquireLock(path, lock => lock.acquire(timeOut, TimeUnit.MILLISECONDS)))

  private def acquireLock(path: String, acquire: InterProcessSemaphoreMutex => Boolean): Closeable = {
    val lock = new InterProcessSemaphoreMutex(client, s"/$path/locks")
    if (acquire(lock)) {
      () => lock.release()
    } else {
      null
    }
  }
}

object ZookeeperOffsetManager {

  def serializeOffset(offset: Long): Array[Byte] = offset.toString.getBytes(StandardCharsets.UTF_8)
  def deserializeOffsets(bytes: Array[Byte]): Long = new String(bytes, StandardCharsets.UTF_8).toLong

  private def offsetsPath(topic: String): String = s"/$topic/offsets"
  private def offsetsPath(topic: String, partition: Int): String = s"${offsetsPath(topic)}/$partition"
  private def partitionFromPath(path: String): Int = path.substring(path.lastIndexOf("/") + 1).toInt

  private class CuratorOffsetListener(client: CuratorFramework, path: String)
      extends PathChildrenCacheListener with Closeable with LazyLogging {

    private val executor = Executors.newCachedThreadPool()

    private val listeners = scala.collection.mutable.Set.empty[OffsetListener]

    private var cache: PathChildrenCache = _

    def addListener(listener: OffsetListener): Unit = {
      closeCache()
      listeners += listener
      cache = new PathChildrenCache(client, path, true) {
        // override ensure path to avoid using containers, which aren't supported in zk 3.4
        override protected def ensurePath(): Unit = {
          if (client.checkExists().forPath(path) == null) {
            client.create().creatingParentsIfNeeded().forPath(path)
          }
        }
      }
      cache.getListenable.addListener(this)
      cache.start()
    }

    def removeListener(listener: OffsetListener): Unit = {
      listeners -= listener
      if (listeners.isEmpty) {
        closeCache()
      }
    }

    override def childEvent(client: CuratorFramework, event: PathChildrenCacheEvent): Unit = {
      import PathChildrenCacheEvent.Type.{CHILD_ADDED, CHILD_UPDATED}
      try {
        val eventPath = Option(event.getData).map(_.getPath).getOrElse("")
        if ((event.getType == CHILD_ADDED || event.getType == CHILD_UPDATED) && eventPath.startsWith(path)) {
          logger.trace(s"ZK event triggered for: $eventPath")
          val partition = partitionFromPath(eventPath)
          val data = event.getData.getData
          Try(ZookeeperOffsetManager.deserializeOffsets(data)) match {
            case Success(offset) =>
              listeners.foreach { listener =>
                executor.execute(() => {
                  try { listener.offsetChanged(partition, offset) } catch {
                    case NonFatal(e) => logger.warn("Error calling offset listener", e)
                  }
                })
              }

            case Failure(e) =>
              // for some reason we get IP addresses sometimes... seems to be something curator is doing.
              // we generally get the correct data immediately afterwards, so it appears to be harmless
              logger.warn(
                s"Error deserializing offset data " +
                  s"'${Try(new String(data, StandardCharsets.UTF_8)).getOrElse(ByteArrays.toHex(data))}': ${e.getClass.getName}")
          }
        }
      } catch {
        case NonFatal(e) => logger.warn("Error handling ZK event", e)
      }
    }

    override def close(): Unit = {
      executor.shutdown()
      closeCache()
    }

    private def closeCache(): Unit = synchronized {
      if (cache != null) {
        cache.getListenable.removeListener(this)
        CloseWithLogging(cache)
        cache = null
      }
    }
  }
}
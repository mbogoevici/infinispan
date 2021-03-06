/*
 * Copyright 2011 Red Hat, Inc. and/or its affiliates.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */

package org.infinispan.server.hotrod

import org.infinispan.test.AbstractCacheTest._
import java.lang.reflect.Method
import test.HotRodTestingUtil._
import org.testng.Assert._
import test.HotRodMagicKeyGenerator
import org.infinispan.server.hotrod.OperationStatus._
import org.testng.annotations.Test
import org.infinispan.test.TestingUtil
import org.infinispan.server.core.test.ServerTestingUtil
import org.infinispan.configuration.cache.{CacheMode, ConfigurationBuilder}
import org.infinispan.server.hotrod.Constants._

/**
 * Tests Hot Rod distribution mode when some of the cache managers do not have HotRod servers running.
 *
 * @author Dan Berindei
 * @since 5.2
 */
@Test(groups = Array("functional"), testName = "server.hotrod.HotRod11StorageOnlyNodesTest")
class HotRod11StorageOnlyNodesTest extends HotRodMultiNodeTest {

   override protected def cacheName = "distributed"

   override protected def createCacheConfig: ConfigurationBuilder = {
      val cfg = getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC, false)
      cfg.clustering().l1().disable() // Disable L1 explicitly
      cfg
   }

   override protected def protocolVersion : Byte = 11

   protected def virtualNodes = 1

   def testAddingStorageOnlyNode(m: Method) {
      val server1 = servers.head
      val server2 = servers.last
      val client1 = clients.head
      val client2 = clients.last

      var resp = client1.ping(INTELLIGENCE_HASH_DISTRIBUTION_AWARE, 0)
      assertStatus(resp, Success)
      assertHashTopologyReceived(resp.topologyResponse.get, servers, cacheName, 2, virtualNodes)
      val topologyId = resp.topologyResponse.get.topologyId

      val newCacheManager = addClusterEnabledCacheManager()
      try {
         newCacheManager.defineConfiguration(cacheName, createCacheConfig.build())
         newCacheManager.getCache(cacheName)
         TestingUtil.blockUntilViewsReceived(50000, true, manager(0), manager(1), manager(2))
         TestingUtil.waitForRehashToComplete(cache(0, cacheName), cache(1, cacheName), cache(2, cacheName))

         log.trace("Check that the clients do not receive a new topology")
         val key1 = HotRodMagicKeyGenerator.newKey(cache(0, cacheName))
         resp = client1.put(key1, 0, 0, v(m, "v1-"), INTELLIGENCE_HASH_DISTRIBUTION_AWARE, topologyId)
         assertStatus(resp, Success)
         assertFalse(resp.topologyResponse.isDefined)

         log.trace("Check that the clients can access a key for which the storage-only node is primary owner")
         val key2 = HotRodMagicKeyGenerator.newKey(cache(2, cacheName))
         resp = client1.put(key2, 0, 0, v(m, "v2-"), INTELLIGENCE_HASH_DISTRIBUTION_AWARE, topologyId)
         assertStatus(resp, Success)
         assertFalse(resp.topologyResponse.isDefined)

         assertSuccess(client2.get(key1, 0), v(m, "v1-"))
         assertSuccess(client2.get(key2, 0), v(m, "v2-"))

         log.trace("Force a topology change by shutting down one of the proper HotRod servers")
         ServerTestingUtil.killServer(server2)
         TestingUtil.killCacheManagers(servers.last.getCacheManager)
         TestingUtil.blockUntilViewsReceived(50000, false, manager(0), manager(2))
         TestingUtil.waitForRehashToComplete(cache(0, cacheName), cache(2, cacheName))

         // The client still doesn't receive a topology update because one of the nodes is not in the address cache
         // Note that this means further HotRod client requests may go to a dead node
         resp = client1.put(key1, 0, 0, v(m, "v3-"), INTELLIGENCE_HASH_DISTRIBUTION_AWARE, topologyId)
         assertStatus(resp, Success)
         assertFalse(resp.topologyResponse.isDefined)
      } finally {
         TestingUtil.killCacheManagers(newCacheManager)
      }

      log.trace("Check that only the topology id changes after the storage-only server is killed")
      resp = client1.put(k(m), 0, 0, v(m, "v4-"), INTELLIGENCE_HASH_DISTRIBUTION_AWARE, topologyId)
      assertStatus(resp, Success)
      assertHashTopologyReceived(resp.topologyResponse.get, List(server1), cacheName, 2, virtualNodes)

      assertSuccess(client1.get(k(m), 0), v(m, "v4-"))
   }


}
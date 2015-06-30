/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.internal.cache.tier.sockets;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.util.BridgeServer;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.Assert;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.Version;
import com.gemstone.gemfire.internal.cache.BridgeObserver;
import com.gemstone.gemfire.internal.cache.BridgeObserverAdapter;
import com.gemstone.gemfire.internal.cache.BridgeObserverHolder;
import com.gemstone.gemfire.internal.cache.tier.ConnectionProxy;
import com.gemstone.gemfire.cache.Region.Entry;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.client.internal.PoolImpl;
import com.gemstone.gemfire.internal.cache.tier.sockets.CacheClientProxy;

import dunit.DistributedTestCase;
import dunit.Host;
import dunit.VM;

/**
 * @author Pallavi
 * 
 * Test to verify that server serves different versioned clients with their
 * respective client-versions of messages (after execution of a command) .
 */

public class BackwardCompatibilityMessageDUnitDisabledTest extends DistributedTestCase {
  /** the cache */
  private static Cache cache = null;

  private static VM server1 = null;

  private static VM client1 = null;

  private static VM client2 = null;

  private static VM client3 = null;

  /** name of the test region */
  private static final String REGION_NAME = "BackwardCompatibilityMessageDUnitTest_Region";

  static int CLIENT_ACK_INTERVAL = 5000;

  private static final String k1 = "k1";

  private static final String k2 = "k2";

  private static final String client_k1 = "client-k1";

  private static final String client_k2 = "client-k2";

  /** constructor */
  public BackwardCompatibilityMessageDUnitDisabledTest(String name) {
    super(name);
  }

  public void setUp() throws Exception {
    super.setUp();
    final Host host = Host.getHost(0);
    server1 = host.getVM(0);
    client1 = host.getVM(1);
    client2 = host.getVM(2);
    client3 = host.getVM(3);
  }

  private void createCache(Properties props) throws Exception {
    DistributedSystem ds = getSystem(props);
    ds.disconnect();
    ds = getSystem(props);
    assertNotNull(ds);
    cache = CacheFactory.create(ds);
    assertNotNull(cache);
  }

  public static void createClientCache(String host, Integer port1)
      throws Exception {
    new BackwardCompatibilityMessageDUnitDisabledTest("temp");
    Properties props = new Properties();
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    props.setProperty(DistributionConfig.LOCATORS_NAME, "");
    new BackwardCompatibilityMessageDUnitDisabledTest("temp").createCache(props);
    PoolImpl p = (PoolImpl)PoolManager.createFactory().addServer(host,
        port1.intValue()).setSubscriptionEnabled(true)
        .setSubscriptionRedundancy(1).setThreadLocalConnections(true)
        .setMinConnections(6).setReadTimeout(20000).setPingInterval(10000)
        .setRetryAttempts(1).setSubscriptionAckInterval(CLIENT_ACK_INTERVAL)
        .create("BackwardCompatibilityMessageDUnitTest");

    AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    factory.setPoolName(p.getName());

    RegionAttributes attrs = factory.create();
    Region region = cache.createRegion(REGION_NAME, attrs);
    region.registerInterest("ALL_KEYS");

  }

  public static Integer createServerCache(String host) throws Exception {
    new BackwardCompatibilityMessageDUnitDisabledTest("temp")
        .createCache(new Properties());
    AttributesFactory factory = new AttributesFactory();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    factory.setDataPolicy(DataPolicy.REPLICATE);
    RegionAttributes attrs = factory.create();
    cache.createRegion(REGION_NAME, attrs);
    int port = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    BridgeServer server1 = cache.addBridgeServer();
    server1.setBindAddress(host);
    server1.setPort(port);
    server1.setNotifyBySubscription(true);
    server1.start();
    return new Integer(server1.getPort());
  }

  public void tearDown2() throws Exception {
    super.tearDown2();
    client2.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class,
        "unsetHandshakeVersionForTesting");
    client3.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class,
        "unsetHandshakeVersionForTesting");
    server1.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class,
        "unsetBridgeObserverForAfterMessageCreation");
    // close the clients first
    client1.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class, "closeCache");
    client2.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class, "closeCache");
    client3.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class, "closeCache");
    // then close the servers
    server1.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class, "closeCache");
  }

  public static void closeCache() {
    CacheClientProxy.AFTER_MESSAGE_CREATION_FLAG = false;
    if (cache != null && !cache.isClosed()) {
      cache.close();
      cache.getDistributedSystem().disconnect();
    }
  }

  /**
   * Verify that server serves different versioned clients with their respective
   * client-versions of messages (after execution of a command) .
   */
  public void testMessage() throws Exception {
    String serverHostName = getServerHostName(server1.getHost());
    server1.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class,
        "setTestCommands");
    Integer port1 = ((Integer)server1.invoke(
        BackwardCompatibilityMessageDUnitDisabledTest.class, "createServerCache",
        new Object[] { serverHostName }));

    client1.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class,
        "createClientCache", new Object[] { serverHostName, port1 });

    client2.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class,
        "setHandshakeVersionForTesting");
    client2.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class,
        "createClientCache", new Object[] { serverHostName, port1 });

    client3.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class,
        "setHandshakeVersionForTesting");
    client3.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class,
        "createClientCache", new Object[] { serverHostName, port1 });

    server1.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class,
        "setBridgeObserverForAfterMessageCreation");
    client2.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class, "put");
    Thread.sleep(10 * 1000);

    client1.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class, "checkCache");
    client3.invoke(BackwardCompatibilityMessageDUnitDisabledTest.class, "checkCache");
  }

  /*
   * Add commands for TEST_VERSION to CommandInitializer.ALL_COMMANDS.
   */
  public static void setTestCommands() throws Exception {
    getLogWriter().info("setTestCommands invoked");
    Map testCommands = new HashMap();
    testCommands.putAll((Map)CommandInitializer.ALL_COMMANDS
        .get(Version.GFE_57));
    CommandInitializer.testSetCommands(testCommands);
    getLogWriter().info("end of setTestCommands");
  }

  /*
   * Prepare to write TEST_VERSION byte from client to server during handshake.
   */
  public static void setHandshakeVersionForTesting() throws Exception {
    HandShake.setVersionForTesting(Version.TEST_VERSION.ordinal());
  }

  private static BridgeObserver origObserver;

  /*
   * Prepare to test that ClientMessage created at server is valid for current
   * client.
   */
  public static void setBridgeObserverForAfterMessageCreation()
      throws Exception {
    CacheClientProxy.AFTER_MESSAGE_CREATION_FLAG = true;
    origObserver = BridgeObserverHolder
        .setInstance(new BridgeObserverAdapter() {
          public void afterMessageCreation(Message msg) {
            getLogWriter().info("afterMessageCreation invoked");
            Assert.assertTrue((msg != null),
                "Valid Message not created for current client");

            getLogWriter().info("end of afterMessageCreation");
          }
        });
  }

  public static void put() {
    try {
      Region r1 = cache.getRegion(Region.SEPARATOR + REGION_NAME);
      assertNotNull(r1);

      r1.put(k1, client_k1);
      assertEquals(r1.getEntry(k1).getValue(), client_k1);
      r1.put(k2, client_k2);
      assertEquals(r1.getEntry(k2).getValue(), client_k2);
    }
    catch (Exception ex) {
      fail("failed while put", ex);
    }
  }

  public static void checkCache() {
    try {
     final Region r1 = cache.getRegion(Region.SEPARATOR + REGION_NAME);
     assertNotNull(r1);
     WaitCriterion ev = new WaitCriterion() {
       public boolean done() {
         Entry e = r1.getEntry(k1);
         return e != null;
       }
       public String description() {
         return null;
       }
     };
     DistributedTestCase.waitForCriterion(ev, 120 * 1000, 200, true);
     Entry e = r1.getEntry(k1);
     assertEquals(e.getValue(), client_k1);
     
     ev = new WaitCriterion() {
       public boolean done() {
         Entry e2 = r1.getEntry(k2);
         return e2 != null;
       }
       public String description() {
         return null;
       }
     };
     DistributedTestCase.waitForCriterion(ev, 120 * 1000, 200, true);
     e = r1.getEntry(k2);
     assertEquals(e.getValue(), client_k2);
    }
    catch (Exception ex) {
      fail("failed while checkCache", ex);
    }
  }

  /*
   * Prepare to write revert back to original version from client to server
   * during handshake.
   */
  public static void unsetHandshakeVersionForTesting() throws Exception {
    HandShake.setVersionForTesting(ConnectionProxy.VERSION.ordinal());
  }

  /*
   * Prepare to revert back testing ClientMessage created at server.
   */
  public static void unsetBridgeObserverForAfterMessageCreation()
      throws Exception {
    CacheClientProxy.AFTER_MESSAGE_CREATION_FLAG = false;
  }
}

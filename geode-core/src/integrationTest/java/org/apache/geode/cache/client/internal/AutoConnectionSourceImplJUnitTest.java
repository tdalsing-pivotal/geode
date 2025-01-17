/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.cache.client.internal;

import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.test.awaitility.GeodeAwaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.RestoreSystemProperties;
import org.junit.experimental.categories.Category;

import org.apache.geode.CancelCriterion;
import org.apache.geode.ToDataException;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.NoSubscriptionServersAvailableException;
import org.apache.geode.cache.client.NoAvailableLocatorsException;
import org.apache.geode.cache.client.NoAvailableServersException;
import org.apache.geode.cache.client.SocketFactory;
import org.apache.geode.cache.client.SubscriptionNotEnabledException;
import org.apache.geode.cache.client.internal.locator.ClientConnectionRequest;
import org.apache.geode.cache.client.internal.locator.ClientConnectionResponse;
import org.apache.geode.cache.client.internal.locator.LocatorListResponse;
import org.apache.geode.cache.client.internal.locator.ServerLocationRequest;
import org.apache.geode.cache.query.QueryService;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.distributed.internal.DistributionStats;
import org.apache.geode.distributed.internal.InternalDistributedSystem;
import org.apache.geode.distributed.internal.ProtocolCheckerImpl;
import org.apache.geode.distributed.internal.ServerLocation;
import org.apache.geode.distributed.internal.tcpserver.HostAndPort;
import org.apache.geode.distributed.internal.tcpserver.TcpClient;
import org.apache.geode.distributed.internal.tcpserver.TcpHandler;
import org.apache.geode.distributed.internal.tcpserver.TcpServer;
import org.apache.geode.distributed.internal.tcpserver.TcpSocketFactory;
import org.apache.geode.internal.AvailablePortHelper;
import org.apache.geode.internal.InternalDataSerializer;
import org.apache.geode.internal.cache.PoolStats;
import org.apache.geode.internal.cache.tier.InternalClientMembership;
import org.apache.geode.internal.net.SocketCreatorFactory;
import org.apache.geode.internal.security.SecurableCommunicationChannel;
import org.apache.geode.management.membership.ClientMembershipEvent;
import org.apache.geode.management.membership.ClientMembershipListener;
import org.apache.geode.test.junit.categories.ClientServerTest;
import org.apache.geode.util.internal.GeodeGlossary;

@Category(ClientServerTest.class)
public class AutoConnectionSourceImplJUnitTest {

  private Cache cache;
  private int port;
  private FakeHandler handler;
  private final FakePool pool = new FakePool();
  private AutoConnectionSourceImpl source;
  private TcpServer server;
  private ScheduledExecutorService background;
  private PoolStats poolStats;

  @Rule
  public RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties();

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty(MCAST_PORT, "0");
    props.setProperty(LOCATORS, "");

    cache = new CacheFactory(props).create();
    DistributedSystem distributedSystem = cache.getDistributedSystem();
    poolStats = new PoolStats(distributedSystem, "pool");
    port = AvailablePortHelper.getRandomAvailableTCPPort();

    handler = new FakeHandler();
    ArrayList<ServerLocation> responseLocators = new ArrayList<>();
    responseLocators.add(new ServerLocation(InetAddress.getLocalHost().getHostName(), port));
    handler.nextLocatorListResponse = new LocatorListResponse(responseLocators, false);

    background = Executors.newSingleThreadScheduledExecutor();

    InetAddress inetAddress = InetAddress.getLocalHost();
    List<HostAndPort> hostAndPortList = new ArrayList<>();
    hostAndPortList.add(new HostAndPort(inetAddress.getHostName(), port));
    source = new AutoConnectionSourceImpl(hostAndPortList, "", 60 * 1000, SocketFactory.DEFAULT);
    source.start(pool);
  }

  @After
  public void tearDown() {
    background.shutdownNow();
    try {
      cache.close();
    } catch (Exception e) {
      // do nothing
    }
    try {
      if (server != null && server.isAlive()) {
        try {
          issueStopRequest(port);
        } catch (ConnectException ignore) {
          // must not be running
        }
        server.join(60 * 1000);
      }
    } catch (Exception e) {
      // do nothing
    }

    try {
      Objects.requireNonNull(InternalDistributedSystem.getAnyInstance()).disconnect();
    } catch (Exception e) {
      // do nothing
    }
  }

  private void issueStopRequest(final int port)
      throws ConnectException, UnknownHostException {
    new TcpClient(SocketCreatorFactory
        .getSocketCreatorForComponent(SecurableCommunicationChannel.LOCATOR),
        InternalDataSerializer.getDSFIDSerializer().getObjectSerializer(),
        InternalDataSerializer.getDSFIDSerializer().getObjectDeserializer(),
        TcpSocketFactory.DEFAULT)
            .stop(new HostAndPort(InetAddress.getLocalHost().getHostName(), port));
  }

  /**
   * This test validates the AutoConnectionSourceImpl.addbadLocators method. That method adds
   * badLocator from badLocator list to new Locator list. And it make sure that new locator list
   * doesn't have similar entry. For that it checks hostname and port only.
   */
  @Test
  public void testAddBadLocator() {
    int port = 11011;
    InetSocketAddress fakeLocalHost1 = new InetSocketAddress("fakeLocalHost1", port);
    InetSocketAddress fakeLocalHost2 = new InetSocketAddress("fakeLocalHost2", port);
    List<HostAndPort> hostAndPortList = new ArrayList<>();
    hostAndPortList.add(new HostAndPort(fakeLocalHost1.getHostName(), fakeLocalHost1.getPort()));
    hostAndPortList.add(new HostAndPort(fakeLocalHost2.getHostName(), fakeLocalHost2.getPort()));
    AutoConnectionSourceImpl autoConnectionSource =
        new AutoConnectionSourceImpl(hostAndPortList, "", 60 * 1000, SocketFactory.DEFAULT);

    InetSocketAddress badLocator1 = new InetSocketAddress("fakeLocalHost1", port);
    InetSocketAddress badLocator2 = new InetSocketAddress("fakeLocalHost3", port);

    Set<HostAndPort> badLocators = new HashSet<>();
    badLocators.add(new HostAndPort(badLocator1.getHostName(), badLocator1.getPort()));
    badLocators.add(new HostAndPort(badLocator2.getHostName(), badLocator2.getPort()));

    autoConnectionSource.addbadLocators(hostAndPortList, badLocators);

    System.out.println("new locatores " + hostAndPortList);
    assertThat(hostAndPortList).hasSize(3);
  }

  @Test
  public void testNoRespondingLocators() {
    assertThatThrownBy(() -> source.findServer(null))
        .isInstanceOf(NoAvailableLocatorsException.class);
  }

  @Test
  public void testSourceHandlesToDataException() throws IOException, ClassNotFoundException {
    TcpClient mockConnection = mock(TcpClient.class);
    when(mockConnection.requestToServer(isA(HostAndPort.class), any(Object.class),
        isA(Integer.class), isA(Boolean.class))).thenThrow(new ToDataException("testing"));
    source.queryOneLocatorUsingConnection(new HostAndPort("locator[1234]", 1234), mock(
        ServerLocationRequest.class), mockConnection);
    verify(mockConnection).requestToServer(isA(HostAndPort.class),
        isA(ServerLocationRequest.class), isA(Integer.class), isA(Boolean.class));
  }

  @Test
  public void testServerLocationUsedInListenerNotification() throws Exception {
    final ClientMembershipEvent[] listenerEvents = new ClientMembershipEvent[1];

    ClientMembershipListener listener = new ClientMembershipListener() {

      @Override
      public void memberJoined(final ClientMembershipEvent event) {
        synchronized (listenerEvents) {
          listenerEvents[0] = event;
        }
      }

      @Override
      public void memberLeft(final ClientMembershipEvent event) {}

      @Override
      public void memberCrashed(final ClientMembershipEvent event) {}
    };
    InternalClientMembership.registerClientMembershipListener(listener);

    ServerLocation location = new ServerLocation("1.1.1.1", 0);

    InternalClientMembership.notifyServerJoined(location);
    await("wait for listener notification").until(() -> {
      synchronized (listenerEvents) {
        return listenerEvents[0] != null;
      }
    });

    assertThat(listenerEvents[0].getMember().getHost())
        .as("Host was null for " + listenerEvents[0].getMember())
        .isNotNull();

    InetAddress addr = InetAddress.getLocalHost();
    location = new ServerLocation(addr.getHostAddress(), 0);

    listenerEvents[0] = null;
    InternalClientMembership.notifyServerJoined(location);
    await("wait for listener notification").until(() -> {
      synchronized (listenerEvents) {
        return listenerEvents[0] != null;
      }
    });

    assertThat(listenerEvents[0].getMember().getHost()).isEqualTo(addr.getCanonicalHostName());
  }

  @Test
  public void testNoServers() throws Exception {
    startFakeLocator();
    handler.nextConnectionResponse = new ClientConnectionResponse(null);
    assertThatThrownBy(() -> source.findServer(null))
        .isInstanceOf(NoAvailableServersException.class);
  }

  @Test
  public void findServerWithEmptyLocatorListThrowsNoAvailableLocatorsException() {
    source = new AutoConnectionSourceImpl(new ArrayList<>(), "", 60 * 1000, SocketFactory.DEFAULT);
    source.start(pool);
    assertThatThrownBy(() -> source.findServer(null))
        .isInstanceOf(NoAvailableLocatorsException.class);
  }

  @Test
  public void testDiscoverServers() throws Exception {
    startFakeLocator();
    ServerLocation serverLocation = new ServerLocation("localhost", 4423);
    handler.nextConnectionResponse = new ClientConnectionResponse(serverLocation);
    assertThat(source.findServer(null)).isEqualTo(serverLocation);
  }

  /**
   * This tests that discovery works even after one of two locators was shut down
   */
  @Test
  public void test_DiscoverLocators_whenOneLocatorWasShutdown() throws Exception {
    startFakeLocator();
    int secondPort = AvailablePortHelper.getRandomAvailableTCPPort();

    TcpServer server2 =
        new TcpServer(secondPort, InetAddress.getLocalHost(), handler,
            "tcp server", new ProtocolCheckerImpl(),
            DistributionStats::getStatTime,
            Executors::newCachedThreadPool,
            SocketCreatorFactory
                .getSocketCreatorForComponent(SecurableCommunicationChannel.LOCATOR),
            InternalDataSerializer.getDSFIDSerializer().getObjectSerializer(),
            InternalDataSerializer.getDSFIDSerializer().getObjectDeserializer(),
            GeodeGlossary.GEMFIRE_PREFIX + "TcpServer.READ_TIMEOUT",
            GeodeGlossary.GEMFIRE_PREFIX + "TcpServer.BACKLOG");
    server2.start();

    try {
      ArrayList<ServerLocation> locators = new ArrayList<>();
      locators.add(new ServerLocation(InetAddress.getLocalHost().getHostName(), secondPort));
      handler.nextLocatorListResponse = new LocatorListResponse(locators, false);
      await().until(() -> !source.getOnlineLocators().isEmpty());
      try {
        issueStopRequest(port);
      } catch (ConnectException ignore) {
        // must not be running
      }
      server.join(1000);

      ServerLocation server1 = new ServerLocation("localhost", 10);
      handler.nextConnectionResponse = new ClientConnectionResponse(server1);
      assertThat(source.findServer(null)).isEqualTo(server1);
    } finally {
      try {
        issueStopRequest(secondPort);
      } catch (ConnectException ignore) {
        // must not be running
      }
      server.join(60 * 1000);
    }
  }

  @Test
  public void testDiscoverLocatorsConnectsToLocatorsAfterTheyStartUp() throws Exception {
    ArrayList<ServerLocation> locators = new ArrayList<>();
    locators.add(new ServerLocation(InetAddress.getLocalHost().getHostName(), port));
    handler.nextLocatorListResponse = new LocatorListResponse(locators, false);

    try {
      await().until(() -> source.getOnlineLocators().isEmpty());
      startFakeLocator();

      server.join(1000);

      await().until(() -> source.getOnlineLocators().size() == 1);
    } finally {
      try {
        issueStopRequest(port);
      } catch (ConnectException ignore) {
        // must not be running
      }
      server.join(60 * 1000);
    }
  }

  @Test
  public void testSysPropLocatorUpdateInterval() {
    long updateLocatorInterval = 543;
    System.setProperty(GeodeGlossary.GEMFIRE_PREFIX + "LOCATOR_UPDATE_INTERVAL",
        String.valueOf(updateLocatorInterval));
    source.start(pool);
    assertThat(source.getLocatorUpdateInterval()).isEqualTo(updateLocatorInterval);
  }

  @Test
  public void testDefaultLocatorUpdateInterval() {
    long updateLocatorInterval = pool.getPingInterval();
    source.start(pool);
    assertThat(source.getLocatorUpdateInterval()).isEqualTo(updateLocatorInterval);
  }

  @Test
  public void testLocatorUpdateIntervalZero() {
    long updateLocatorInterval = 0;
    System.setProperty(GeodeGlossary.GEMFIRE_PREFIX + "LOCATOR_UPDATE_INTERVAL",
        String.valueOf(updateLocatorInterval));
    source.start(pool);
    assertThat(source.getLocatorUpdateInterval()).isEqualTo(updateLocatorInterval);
  }

  private void startFakeLocator() throws IOException, InterruptedException {
    server = new TcpServer(port, InetAddress.getLocalHost(), handler,
        "Tcp Server", new ProtocolCheckerImpl(),
        DistributionStats::getStatTime,
        Executors::newCachedThreadPool,
        SocketCreatorFactory
            .getSocketCreatorForComponent(SecurableCommunicationChannel.LOCATOR),
        InternalDataSerializer.getDSFIDSerializer().getObjectSerializer(),
        InternalDataSerializer.getDSFIDSerializer().getObjectDeserializer(),
        GeodeGlossary.GEMFIRE_PREFIX + "TcpServer.READ_TIMEOUT",
        GeodeGlossary.GEMFIRE_PREFIX + "TcpServer.BACKLOG");
    server.start();
    Thread.sleep(500);
  }

  protected static class FakeHandler implements TcpHandler {
    volatile ClientConnectionResponse nextConnectionResponse;
    volatile LocatorListResponse nextLocatorListResponse;


    @Override
    public void init(TcpServer tcpServer) {}

    @Override
    public Object processRequest(Object request) {
      if (request instanceof ClientConnectionRequest) {
        return nextConnectionResponse;
      } else {
        return nextLocatorListResponse;
      }
    }

    @Override
    public void shutDown() {}

    @Override
    public void endRequest(Object request, long startTime) {}

    @Override
    public void endResponse(Object request, long startTime) {}
  }

  public class FakePool implements InternalPool {
    @Override
    public String getPoolOrCacheCancelInProgress() {
      return null;
    }

    @Override
    public boolean getKeepAlive() {
      return false;
    }

    @Override
    public int getPrimaryPort() {
      return 0;
    }

    @Override
    public int getConnectionCount() {
      return 0;
    }

    @Override
    public EndpointManager getEndpointManager() {
      return null;
    }

    @Override
    public String getName() {
      return null;
    }

    @Override
    public PoolStats getStats() {
      return poolStats;
    }

    @Override
    public void destroy() {

    }

    @Override
    public void detach() {}

    @Override
    public void destroy(boolean keepAlive) {

    }

    @Override
    public boolean isDurableClient() {
      return false;
    }

    @Override
    public boolean isDestroyed() {
      return false;
    }

    @Override
    public int getSocketConnectTimeout() {
      return 0;
    }

    @Override
    public int getFreeConnectionTimeout() {
      return 0;
    }

    @Override
    public int getServerConnectionTimeout() {
      return 0;
    }

    @Override
    public int getLoadConditioningInterval() {
      return 0;
    }

    @Override
    public int getSocketBufferSize() {
      return 0;
    }

    @Override
    public int getReadTimeout() {
      return 0;
    }

    @Override
    public boolean getSubscriptionEnabled() {
      return false;
    }

    @Override
    public boolean getPRSingleHopEnabled() {
      return false;
    }

    @Override
    public int getSubscriptionRedundancy() {
      return 0;
    }

    @Override
    public int getSubscriptionMessageTrackingTimeout() {
      return 0;
    }

    @Override
    public String getServerGroup() {
      return "";
    }

    @Override
    public List<InetSocketAddress> getLocators() {
      return new ArrayList<>();
    }

    @Override
    public List<InetSocketAddress> getOnlineLocators() {
      return new ArrayList<>();
    }

    @Override
    public List<InetSocketAddress> getServers() {
      return new ArrayList<>();
    }

    @Override
    public boolean getMultiuserAuthentication() {
      return false;
    }

    @Override
    public long getIdleTimeout() {
      return 0;
    }

    @Override
    public int getMaxConnections() {
      return 0;
    }

    @Override
    public int getMinConnections() {
      return 0;
    }

    @Override
    public long getPingInterval() {
      return 100;
    }

    @Override
    public int getStatisticInterval() {
      return -1;
    }

    @Override
    public int getRetryAttempts() {
      return 0;
    }

    @Override
    public Object execute(Op op) {
      return null;
    }

    @Override
    public Object executeOn(ServerLocation server, Op op) {
      return null;
    }

    @Override
    public Object executeOn(ServerLocation server, Op op, boolean accessed,
        boolean onlyUseExistingCnx) {
      return null;
    }

    @Override
    public Object executeOnPrimary(Op op) {
      return null;
    }

    @Override
    public Map getEndpointMap() {
      return null;
    }

    @Override
    public ScheduledExecutorService getBackgroundProcessor() {
      return background;
    }

    @Override
    public Object executeOn(Connection con, Op op) {
      return null;
    }

    @Override
    public Object executeOn(Connection con, Op op, boolean timeoutFatal) {
      return null;
    }

    @Override
    public RegisterInterestTracker getRITracker() {
      return null;
    }

    @Override
    public int getSubscriptionAckInterval() {
      return 0;
    }

    @Override
    public Object executeOnQueuesAndReturnPrimaryResult(Op op) {
      return null;
    }

    @Override
    public CancelCriterion getCancelCriterion() {
      return new CancelCriterion() {

        @Override
        public String cancelInProgress() {
          return null;
        }

        @Override
        public RuntimeException generateCancelledException(Throwable e) {
          return null;
        }

      };
    }

    @Override
    public void executeOnAllQueueServers(Op op)
        throws NoSubscriptionServersAvailableException, SubscriptionNotEnabledException {

    }

    @Override
    public Object execute(Op op, int retryAttempts) {
      return null;
    }

    @Override
    public QueryService getQueryService() {
      return null;
    }

    @Override
    public int getPendingEventCount() {
      return 0;
    }

    @Override
    public int getSubscriptionTimeoutMultiplier() {
      return 0;
    }

    @Override
    public SocketFactory getSocketFactory() {
      return null;
    }

    @Override
    public void setupServerAffinity(boolean allowFailover) {}

    @Override
    public void releaseServerAffinity() {}

    @Override
    public ServerLocation getServerAffinityLocation() {
      return null;
    }

    @Override
    public void setServerAffinityLocation(ServerLocation serverLocation) {}
  }
}

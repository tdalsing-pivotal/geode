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
package org.apache.geode.cache.wan.internal;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;

import org.apache.geode.CancelException;
import org.apache.geode.GemFireIOException;
import org.apache.geode.annotations.VisibleForTesting;
import org.apache.geode.cache.RegionDestroyedException;
import org.apache.geode.cache.client.ServerConnectivityException;
import org.apache.geode.cache.client.ServerOperationException;
import org.apache.geode.cache.client.internal.Connection;
import org.apache.geode.cache.client.internal.ExecutablePool;
import org.apache.geode.cache.client.internal.pooling.ConnectionDestroyedException;
import org.apache.geode.cache.wan.GatewayQueueEvent;
import org.apache.geode.cache.wan.GatewaySender;
import org.apache.geode.cache.wan.internal.client.locator.GatewaySenderBatchOp;
import org.apache.geode.cache.wan.internal.client.locator.SenderProxy;
import org.apache.geode.distributed.internal.ServerLocation;
import org.apache.geode.distributed.internal.ServerLocationAndMemberId;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.internal.cache.UpdateAttributesProcessor;
import org.apache.geode.internal.cache.tier.sockets.MessageTooLargeException;
import org.apache.geode.internal.cache.wan.AbstractGatewaySender;
import org.apache.geode.internal.cache.wan.AbstractGatewaySenderEventProcessor;
import org.apache.geode.internal.cache.wan.BatchException70;
import org.apache.geode.internal.cache.wan.GatewaySenderEventDispatcher;
import org.apache.geode.internal.cache.wan.GatewaySenderEventImpl;
import org.apache.geode.internal.cache.wan.GatewaySenderException;
import org.apache.geode.internal.cache.wan.GatewaySenderStats;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.pdx.PdxRegistryMismatchException;
import org.apache.geode.security.GemFireSecurityException;

/**
 * @since GemFire 7.0
 */
public class GatewaySenderEventRemoteDispatcher implements GatewaySenderEventDispatcher {

  private static final Logger logger = LogService.getLogger();

  protected final AbstractGatewaySenderEventProcessor processor;

  private volatile Connection connection;

  private final Set<String> notFoundRegions = new HashSet<String>();

  private final Object notFoundRegionsSync = new Object();

  private final AbstractGatewaySender sender;

  private AckReaderThread ackReaderThread;

  private ReentrantReadWriteLock connectionLifeCycleLock = new ReentrantReadWriteLock();

  protected static final String maxAttemptsReachedConnectingServerIdExceptionMessage =
      "Reached max attempts number trying to connect to desired server id";

  /*
   * Called after each attempt at processing an outbound (dispatch) or inbound (ack)
   * message, whether the attempt is successful or not. The purpose is testability.
   * Without this hook, negative tests, can't ensure that message processing was
   * attempted, so they wouldn't know how long to wait for some sort of failure.
   */
  public static volatile Consumer<Boolean> messageProcessingAttempted = isAck -> {
  };

  /**
   * This count is reset to 0 each time a successful connection is made.
   */
  private int failedConnectCount = 0;

  private static final int RETRY_WAIT_TIME = 100;

  void setAckReaderThread(AckReaderThread ackReaderThread) {
    this.ackReaderThread = ackReaderThread;
  }

  public GatewaySenderEventRemoteDispatcher(AbstractGatewaySenderEventProcessor eventProcessor) {
    this.processor = eventProcessor;
    this.sender = eventProcessor.getSender();
    try {
      initializeConnection();
    } catch (GatewaySenderException e) {
      // It is ok to ignore this exception. It is logged in the initializeConnection call.
    }
  }

  GatewaySenderEventRemoteDispatcher(AbstractGatewaySenderEventProcessor processor,
      Connection connection) {
    this.processor = processor;
    this.sender = processor.getSender();
    this.connection = connection;
  }

  protected GatewayAck readAcknowledgement() {
    SenderProxy sp = new SenderProxy(this.processor.getSender().getProxy());
    GatewayAck ack = null;
    Exception ex;
    try {
      connection = getConnection(false);
      if (logger.isDebugEnabled()) {
        logger.debug(" Receiving ack on the thread {}", connection);
      }
      getConnectionLifeCycleLock().readLock().lock();
      try {
        if (connection != null && !processor.isStopped()) {
          ack = (GatewayAck) sp.receiveAckFromReceiver(connection);
        }
      } finally {
        getConnectionLifeCycleLock().readLock().unlock();
      }

    } catch (Exception e) {
      Throwable t = e.getCause();
      if (t instanceof BatchException70) {
        // A BatchException has occurred.
        // Do not process the connection as dead since it is not dead.
        ex = (BatchException70) t;
      } else if (e instanceof GatewaySenderException) { // This Exception is thrown from
                                                        // getConnection
        ex = (Exception) e.getCause();
      } else {
        ex = e;
        // keep using the connection if we had a batch exception. Else, destroy
        // it
        destroyConnection();
      }
      if (this.sender.getProxy() == null || this.sender.getProxy().isDestroyed()) {
        // if our pool is shutdown then just be silent
      } else if (RecoverableExceptionPredicates.isRecoverableWhenReadingAck(ex)) {
        sleepBeforeRetry();
      } else {
        logAndStopProcessor(ex);
      }
    } finally {
      messageProcessingAttempted.accept(true);
    }

    return ack;
  }

  @Override
  public boolean dispatchBatch(List events, boolean removeFromQueueOnException, boolean isRetry) {
    GatewaySenderStats statistics = this.sender.getStatistics();
    boolean success = false;
    try {
      long start = statistics.startTime();
      success = _dispatchBatch(events, isRetry);
      if (success) {
        statistics.endBatch(start, events.size());
      }
    } catch (GatewaySenderException ge) {
      Throwable t = ge.getCause();
      if (this.sender.getProxy() == null || this.sender.getProxy().isDestroyed()) {
        // if our pool is shutdown then just be silent
      } else if (RecoverableExceptionPredicates.isRecoverableWhenDispatchingBatch(t)) {
        this.processor.handleException();
        sleepBeforeRetry();
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Failed to dispatch a batch with id {} due to non-fatal exception {}.  Retrying in {} ms",
              this.processor.getBatchId(), t, RETRY_WAIT_TIME);
        }
      } else {
        logAndStopProcessor(ge);
      }
    } catch (CancelException e) {
      logAndStopProcessor(e);
      throw e;
    } catch (Exception e) {
      logAndStopProcessor(e);
    } finally {
      messageProcessingAttempted.accept(false);
    }
    return success;
  }

  private boolean _dispatchBatch(List events, boolean isRetry) {
    Exception ex = null;
    int currentBatchId = this.processor.getBatchId();
    connection = getConnection(true);
    int batchIdForThisConnection = this.processor.getBatchId();
    GatewaySenderStats statistics = this.sender.getStatistics();
    // This means we are writing to a new connection than the previous batch.
    // i.e The connection has been reset. It also resets the batchId.
    if (currentBatchId != batchIdForThisConnection || this.processor.isConnectionReset()) {
      return false;
    }
    try {
      if (this.processor.isConnectionReset()) {
        isRetry = true;
      }
      SenderProxy sp = new SenderProxy(this.sender.getProxy());
      getConnectionLifeCycleLock().readLock().lock();
      try {
        if (connection != null && !connection.isDestroyed()) {
          sp.dispatchBatch_NewWAN(connection, events, currentBatchId,
              sender.isRemoveFromQueueOnException(), isRetry);
          if (logger.isDebugEnabled()) {
            logger.debug(
                "{} : Dispatched batch (id={}) of {} events, queue size: {} on connection {}",
                this.processor.getSender(), currentBatchId, events.size(),
                this.processor.getQueue().size(), connection);
          }
        } else {
          throw new ConnectionDestroyedException();
        }
      } finally {
        getConnectionLifeCycleLock().readLock().unlock();
      }
      return true;
    } catch (ServerOperationException e) {
      Throwable t = e.getCause();
      if (t instanceof BatchException70) {
        // A BatchException has occurred.
        // Do not process the connection as dead since it is not dead.
        ex = (BatchException70) t;
      } else {
        ex = e;
        // keep using the connection if we had a batch exception. Else, destroy it
        destroyConnection();
      }
      throw new GatewaySenderException(
          String.format("%s : Exception during processing batch %s on connection %s",
              new Object[] {this, Integer.valueOf(currentBatchId), connection}),
          ex);
    } catch (GemFireIOException e) {
      Throwable t = e.getCause();
      if (t instanceof MessageTooLargeException) {
        // A MessageTooLargeException has occurred.
        // Do not process the connection as dead since it is not dead.
        ex = (MessageTooLargeException) t;
        // Reduce the batch size by half of the configured batch size or number of events in the
        // current batch (whichever is less)
        int newBatchSize = Math.min(events.size(), this.processor.getBatchSize()) / 2;
        logger.warn(String.format(
            "The following exception occurred attempting to send a batch of %s events. The batch will be tried again after reducing the batch size to %s events.",
            events.size(), newBatchSize),
            e);
        this.processor.setBatchSize(newBatchSize);
        statistics.incBatchesResized();
      } else {
        ex = e;
        // keep using the connection if we had a MessageTooLargeException. Else, destroy it
        destroyConnection();
      }
      throw new GatewaySenderException(
          String.format("%s : Exception during processing batch %s on connection %s",
              new Object[] {this, Integer.valueOf(currentBatchId), connection}),
          ex);
    } catch (IllegalStateException e) {
      this.processor.setException(new GatewaySenderException(e));
      throw new GatewaySenderException(
          String.format("%s : Exception during processing batch %s on connection %s",
              new Object[] {this, Integer.valueOf(currentBatchId), connection}),
          e);
    } catch (Exception e) {
      // An Exception has occurred. Get its cause.
      Throwable t = e.getCause();
      if (t instanceof IOException) {
        // An IOException has occurred.
        ex = (IOException) t;
      } else {
        ex = e;
      }
      // the cause is not going to be BatchException70. So, destroy the connection
      destroyConnection();

      throw new GatewaySenderException(
          String.format("%s : Exception during processing batch %s on connection %s",
              new Object[] {this, Integer.valueOf(currentBatchId), connection}),
          ex);
    }
  }

  @VisibleForTesting
  ReentrantReadWriteLock getConnectionLifeCycleLock() {
    return this.connectionLifeCycleLock;
  }

  /**
   * Acquires or adds a new <code>Connection</code> to the corresponding <code>Gateway</code>
   *
   * @return the <code>Connection</code>
   *
   */
  public Connection getConnection(boolean startAckReaderThread) throws GatewaySenderException {
    if (this.processor.isStopped()) {
      stop();
      return null;
    }
    // IF the connection is null
    // OR the connection's ServerLocation doesn't match with the one stored in sender
    // THEN initialize the connection
    if (!this.sender.isParallel()) {
      boolean needToReconnect = false;
      getConnectionLifeCycleLock().readLock().lock();
      try {
        needToReconnect = this.connection == null || this.connection.isDestroyed()
            || this.connection.getServer() == null
            || !this.connection.getServer().equals(this.sender.getServerLocation());
      } finally {
        getConnectionLifeCycleLock().readLock().unlock();
      }
      if (needToReconnect) {
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Initializing new connection as serverLocation of old connection is : {} and the serverLocation to connect is {}",
              ((this.connection == null) ? "null" : this.connection.getServer()),
              this.sender.getServerLocation());
        }
        // Initialize the connection
        initializeConnection();
      }
    } else {
      if (this.connection == null || this.connection.isDestroyed()) {
        initializeConnection();
      }
    }

    // Here we might wait on a connection to another server if I was secondary
    // so don't start waiting until I am primary
    InternalCache cache = this.sender.getCache();
    if (cache != null && !cache.isClosed()) {
      if (this.sender.isPrimary() && (this.connection != null)) {
        if (this.ackReaderThread == null || !this.ackReaderThread.isRunning()) {
          this.ackReaderThread = new AckReaderThread(this.sender, this.processor);
          this.ackReaderThread.start();
          this.ackReaderThread.waitForRunningAckReaderThreadRunningState();
        }
      }
    }
    return this.connection;
  }

  public void destroyConnection() {
    getConnectionLifeCycleLock().writeLock().lock();
    try {
      Connection con = this.connection;
      if (con != null) {
        if (!con.isDestroyed()) {
          con.destroy();
          this.sender.getProxy().returnConnection(con);
        }

        // Reset the connection so the next time through a new one will be
        // obtained
        this.connection = null;
        this.sender.setServerLocation(null);
      }
    } finally {
      getConnectionLifeCycleLock().writeLock().unlock();
    }
  }

  Connection retryInitializeConnection(Connection con) {
    final boolean isDebugEnabled = logger.isDebugEnabled();
    String connectedServerId = con.getEndpoint().getMemberId().getUniqueId();
    String expectedServerId = this.processor.getExpectedReceiverUniqueId();

    if (expectedServerId.equals("")) {
      if (isDebugEnabled) {
        logger.debug("First dispatcher connected to server " + connectedServerId);
      }
      this.processor.setExpectedReceiverUniqueId(connectedServerId);
      return con;
    }

    int attempt = 0;
    final int attemptsPerServer = 5;
    int maxAttempts = attemptsPerServer;
    Vector<String> notExpectedServerIds = new Vector<String>();
    boolean connectedToExpectedReceiver = connectedServerId.equals(expectedServerId);
    while (!connectedToExpectedReceiver) {

      if (isDebugEnabled) {
        logger.debug("Dispatcher wants to connect to [" + expectedServerId
            + "] but got connection to [" + connectedServerId + "]");
      }
      attempt++;
      if (!notExpectedServerIds.contains(connectedServerId)) {
        if (isDebugEnabled) {
          logger.debug(
              "Increasing dispatcher connection max retries number due to connection to unknown server ("
                  + connectedServerId + ")");
        }
        notExpectedServerIds.add(connectedServerId);
        maxAttempts += attemptsPerServer;
      }

      if (attempt >= maxAttempts) {
        throw new ServerConnectivityException(maxAttemptsReachedConnectingServerIdExceptionMessage
            + " [" + expectedServerId + "] (" + maxAttempts + " attempts).");
      }

      con.destroy();
      this.sender.getProxy().returnConnection(con);
      con = this.sender.getProxy().acquireConnection();

      connectedServerId = con.getEndpoint().getMemberId().getUniqueId();
      if (connectedServerId.equals(expectedServerId)) {
        connectedToExpectedReceiver = true;
      }
    }

    if (isDebugEnabled) {
      logger.debug("Dispatcher connected to expected endpoint " + connectedServerId
          + " after " + attempt + " retries.");
    }
    return con;
  }

  /**
   * Initializes the <code>Connection</code>.
   *
   */
  @VisibleForTesting
  void initializeConnection() throws GatewaySenderException, GemFireSecurityException {
    final boolean isDebugEnabled = logger.isDebugEnabled();
    if (ackReaderThread != null) {
      ackReaderThread.shutDownAckReaderConnection(connection);
    }
    getConnectionLifeCycleLock().writeLock().lock();
    try {
      // Attempt to acquire a connection
      if (this.sender.getProxy() == null || this.sender.getProxy().isDestroyed()) {
        this.sender.initProxy();
      } else {
        this.processor.resetBatchId();
      }
      Connection con;
      try {
        if (this.sender.isParallel()) {
          /*
           * TODO - The use of acquireConnection should be removed from the gateway code. This
           * method is fine for tests, but these connections should really be managed inside the
           * pool code. If the gateway needs to persistent connection to a single server, which
           * should create have the OpExecutor that holds a reference to the connection. Use {@link
           * ExecutablePool#setupServerAffinity(boolean)} for gateway code
           */
          con = this.sender.getProxy().acquireConnection();
          // For parallel sender, setting server location will not matter.
          // everytime it will ask for acquire connection whenever it needs it. I
          // am saving this server location for command purpose
          sender.setServerLocation(con.getServer());
        } else {
          synchronized (this.sender.getLockForConcurrentDispatcher()) {
            ServerLocation server = this.sender.getServerLocation();
            if (server != null) {
              if (isDebugEnabled) {
                logger.debug("ServerLocation is: {}. Connecting to this serverLocation...", server);
              }
              con = this.sender.getProxy().acquireConnection(server);
            } else {
              if (isDebugEnabled) {
                logger.debug("ServerLocation is null. Creating new connection. ");
              }
              con = this.sender.getProxy().acquireConnection();
            }
            if (this.sender.getEnforceThreadsConnectSameReceiver()) {
              con = retryInitializeConnection(con);
            }
            if (this.sender.isPrimary()) {
              if (sender.getServerLocation() == null) {
                sender.setServerLocation(con.getServer());
              }
              new UpdateAttributesProcessor(this.sender).distribute(false);
            }
          }
        }
      } catch (ServerConnectivityException e) {
        // Get the exception to throw
        GatewaySenderException gse = getInitializeConnectionExceptionToThrow(e);

        // Set the serverLocation to null so that a new connection can be obtained in next attempt
        this.sender.setServerLocation(null);

        // Log the exception if necessary
        if (logConnectionFailure()) {
          // only log this message once; another msg is logged once we connect
          logger.warn("{} : Could not connect due to: {}",
              this.processor.getSender().getId(), gse.getCause().getMessage());
        }

        // Increment failed connection count
        this.failedConnectCount++;

        // Throw the exception
        throw gse;
      }
      if (this.failedConnectCount > 0) {
        Object[] logArgs =
            new Object[] {this.processor.getSender().getId(), con, this.failedConnectCount};
        logger.info("{}: Using {} after {} failed connect attempts",
            logArgs);
        this.failedConnectCount = 0;
      } else {
        Object[] logArgs = new Object[] {this.processor.getSender().getId(), con};
        logger.info("{}: Using {}", logArgs);
      }
      this.connection = con;
      this.processor.checkIfPdxNeedsResend(this.connection.getQueueStatus().getPdxSize());
    } catch (ConnectionDestroyedException e) {
      throw new GatewaySenderException(
          String.format("%s : Could not connect due to: %s",
              this.processor.getSender().getId(), e.getMessage()),
          e);
    } finally {
      getConnectionLifeCycleLock().writeLock().unlock();
    }
  }

  private GatewaySenderException getInitializeConnectionExceptionToThrow(
      ServerConnectivityException e) {
    GatewaySenderException gse = null;
    if (e.getCause() instanceof GemFireSecurityException) {
      gse = new GatewaySenderException(e.getCause());
    } else {
      List<ServerLocationAndMemberId> servers = this.sender.getProxy().getCurrentServers();
      String ioMsg;
      if (servers.size() == 0) {
        ioMsg = "There are no active servers.";
      } else {
        final StringBuilder buffer = new StringBuilder();
        for (ServerLocationAndMemberId server : servers) {
          String endpointName = String.valueOf(server);
          if (buffer.length() > 0) {
            buffer.append(", ");
          }
          buffer.append(endpointName);
        }
        ioMsg =
            String.format(
                "No available connection was found, but the following active servers exist: %s",
                buffer.toString());
      }
      if (this.sender.getEnforceThreadsConnectSameReceiver() && e.getMessage() != null) {
        if (Pattern.compile(maxAttemptsReachedConnectingServerIdExceptionMessage + ".*")
            .matcher(e.getMessage()).find()) {
          ioMsg += " " + e.getMessage();
        }
      }
      IOException ex = new IOException(ioMsg);
      gse = new GatewaySenderException(
          String.format("%s : Could not connect due to: %s",
              new Object[] {this.processor.getSender().getId(), ex.getMessage()}),
          ex);
    }
    return gse;
  }

  protected boolean logConnectionFailure() {
    // always log the first failure
    if (logger.isDebugEnabled() || this.failedConnectCount == 0) {
      return true;
    } else {
      // subsequent failures will be logged on 30th, 300th, 3000th try
      // each try is at 100millis from higher layer so this accounts for logging
      // after 3s, 30s and then every 5mins
      if (this.failedConnectCount >= 3000) {
        return (this.failedConnectCount % 3000) == 0;
      } else {
        return (this.failedConnectCount == 30 || this.failedConnectCount == 300);
      }
    }
  }

  public static class GatewayAck {
    private int batchId;

    private int numEvents;

    private BatchException70 be;

    public GatewayAck(BatchException70 be, int bId) {
      this.be = be;
      this.batchId = bId;
    }

    public GatewayAck(int batchId, int numEvents) {
      this.batchId = batchId;
      this.numEvents = numEvents;
    }

    /**
     * @return the numEvents
     */
    public int getNumEvents() {
      return numEvents;
    }

    /**
     * @return the batchId
     */
    public int getBatchId() {
      return batchId;
    }

    public BatchException70 getBatchException() {
      return this.be;
    }
  }

  class AckReaderThread extends Thread {

    private Object runningStateLock = new Object();

    /**
     * boolean to make a shutdown request
     */
    private volatile boolean shutdown = false;

    private final InternalCache cache;

    private volatile boolean ackReaderThreadRunning = false;

    public AckReaderThread(GatewaySender sender, AbstractGatewaySenderEventProcessor processor) {
      this(sender, processor.getName());
    }

    boolean isShutdown() {
      return shutdown;
    }

    public AckReaderThread(GatewaySender sender, String name) {
      super("AckReaderThread for : " + name);
      this.setDaemon(true);
      this.cache = ((AbstractGatewaySender) sender).getCache();
    }

    public void waitForRunningAckReaderThreadRunningState() {
      synchronized (runningStateLock) {
        while (!this.ackReaderThreadRunning) {
          try {
            if (shutdown) {
              break;
            }
            this.runningStateLock.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }

    private boolean checkCancelled() {
      if (shutdown) {
        return true;
      }

      if (cache.getCancelCriterion().isCancelInProgress()) {
        return true;
      }
      return false;
    }

    @Override
    public void run() {
      if (logger.isDebugEnabled()) {
        logger.debug("AckReaderThread started.. ");
      }

      synchronized (runningStateLock) {
        ackReaderThreadRunning = true;
        this.runningStateLock.notifyAll();
      }

      try {
        for (;;) {
          if (checkCancelled()) {
            break;
          }
          GatewayAck ack = readAcknowledgement();
          if (ack != null) {
            boolean gotBatchException = ack.getBatchException() != null;
            int batchId = ack.getBatchId();
            int numEvents = ack.getNumEvents();

            // If the batch is successfully processed, remove it from the
            // queue.
            if (gotBatchException) {
              logger.warn(
                  "Gateway Sender {} : Received ack for batch id {} with one or more exceptions",
                  processor.getSender(), ack.getBatchId());
              // If we get PDX related exception in the batch exception then try
              // to resend all the pdx events as well in the next batch.
              final GatewaySenderStats statistics = sender.getStatistics();
              statistics.incBatchesRedistributed();
              if (sender.isRemoveFromQueueOnException()) {
                // log the batchExceptions
                logBatchExceptions(ack.getBatchException());
                processor.handleSuccessBatchAck(batchId);
              } else {
                // log the batchExceptions. These are exceptions that were not retried on the remote
                // site (e.g. NotAuthorizedException)
                // @TODO Shoud anything else be done here to warn that events are lost even though
                // the boolean is false
                logBatchExceptions(ack.getBatchException());
                processor.handleSuccessBatchAck(batchId);
              }
            } // unsuccessful batch
            else { // The batch was successful.
              if (logger.isDebugEnabled()) {
                logger.debug("Gateway Sender {} : Received ack for batch id {} of {} events",
                    processor.getSender(), ack.getBatchId(), ack.getNumEvents());
              }
              processor.handleSuccessBatchAck(batchId);
            }
          } else {
            // If we have received IOException.
            if (logger.isDebugEnabled()) {
              logger.debug("{}: Received null ack from remote site.", processor.getSender());
            }
            processor.handleException();
            try { // This wait is before trying to getting new connection to
                  // receive ack. Without this there will be continuous call to
                  // getConnection
              Thread.sleep(GatewaySender.CONNECTION_RETRY_INTERVAL);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        }
      } catch (Exception e) {
        if (!checkCancelled()) {
          logger.fatal(
              "Stopping the processor because the following exception occurred while processing a batch:",
              e);
        }
        sender.getLifeCycleLock().writeLock().lock();
        try {
          processor.stopProcessing();
          sender.clearTempEventsAfterSenderStopped();
        } finally {
          sender.getLifeCycleLock().writeLock().unlock();
        }
        // destroyConnection();
      } finally {
        if (logger.isDebugEnabled()) {
          logger.debug("AckReaderThread exiting. ");
        }
        ackReaderThreadRunning = false;
      }
    }

    protected void logBatchExceptions(BatchException70 exception) {
      try {
        for (BatchException70 be : exception.getExceptions()) {
          boolean logWarning = true;
          if (be.getCause() instanceof RegionDestroyedException) {
            RegionDestroyedException rde = (RegionDestroyedException) be.getCause();
            synchronized (notFoundRegionsSync) {
              if (notFoundRegions.contains(rde.getRegionFullPath())) {
                logWarning = false;
              } else {
                notFoundRegions.add(rde.getRegionFullPath());
              }
            }
          } else if (be.getCause() instanceof IllegalStateException
              && be.getCause().getMessage().contains("Unknown pdx type")) {
            List<GatewaySenderEventImpl> pdxEvents =
                processor.getBatchIdToPDXEventsMap().get(be.getBatchId());
            if (logWarning) {
              logger.warn(String.format(
                  "A BatchException occurred processing PDX events. Index of array of Exception : %s",
                  be.getIndex()),
                  be);
            }
            if (pdxEvents != null) {
              for (GatewaySenderEventImpl senderEvent : pdxEvents) {
                senderEvent.setAcked(false);
              }
              GatewaySenderEventImpl gsEvent = pdxEvents.get(be.getIndex());
              if (logWarning) {
                logger.warn("The event being processed when the BatchException occurred was:  {}",
                    gsEvent);
              }
            }
            continue;
          }
          if (logWarning) {
            logger.warn(
                String.format(
                    "A BatchException occurred processing events. Index of Array of Exception : %s",
                    be.getIndex()),
                be);
          }
          List<GatewaySenderEventImpl>[] eventsArr =
              processor.getBatchIdToEventsMap().get(be.getBatchId());
          if (eventsArr != null) {
            List<GatewaySenderEventImpl> filteredEvents = eventsArr[1];
            GatewaySenderEventImpl gsEvent =
                (GatewaySenderEventImpl) filteredEvents.get(be.getIndex());
            if (logWarning) {
              logger.warn("The event being processed when the BatchException occurred was:  {}",
                  gsEvent);
            }
          }
        }
      } catch (Exception e) {
        logger.warn(
            "An unexpected exception occurred processing a BatchException. The thread will continue.",
            e);
      }
    }

    boolean isRunning() {
      return this.ackReaderThreadRunning;
    }

    public void shutdown() {
      // we need to destroy connection irrespective of we are listening on it or
      // not. No need to take lock as the reader thread may be blocked and we might not
      // get chance to destroy unless that returns.
      Connection conn = connection;
      if (conn != null) {
        shutDownAckReaderConnection(conn);
        if (!conn.isDestroyed()) {
          conn.destroy();
          sender.getProxy().returnConnection(conn);
        }
      }
      this.shutdown = true;
      boolean interrupted = Thread.interrupted();
      try {
        this.join(15 * 1000);
      } catch (InterruptedException e) {
        interrupted = true;
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      if (this.isAlive()) {
        logger.warn("AckReaderThread ignored cancellation");
      }
    }

    protected void shutDownAckReaderConnection(Connection connection) {
      Connection conn = connection;
      // attempt to unblock the ackReader thread by shutting down the inputStream, if it was stuck
      // on a read
      try {
        if (conn != null && conn.getInputStream() != null) {
          conn.getInputStream().close();
        }
      } catch (IOException e) {
        logger.warn("Unable to shutdown AckReaderThread Connection");
      } catch (ConnectionDestroyedException e) {
        logger.info("AckReader shutting down and connection already destroyed");
      }
    }
  }

  public void stopAckReaderThread() {
    if (this.ackReaderThread != null) {
      this.ackReaderThread.shutdown();
    }
  }

  @Override
  public boolean isRemoteDispatcher() {
    return true;
  }

  @Override
  public boolean isConnectedToRemote() {
    return connection != null && !connection.isDestroyed();
  }

  @Override
  public void shutDownAckReaderConnection() {
    if (ackReaderThread != null) {
      ackReaderThread.shutDownAckReaderConnection(connection);
      ackReaderThread.shutdown();
    }
  }

  @Override
  public void stop() {
    stopAckReaderThread();
    if (this.processor.isStopped()) {
      destroyConnection();
    }
  }

  private void sleepBeforeRetry() {
    try {
      Thread.sleep(RETRY_WAIT_TIME);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private void logAndStopProcessor(final Exception ex) {
    if (ex instanceof CancelException) {
      if (logger.isDebugEnabled()) {
        logger
            .debug("Stopping the processor because cancellation occurred while processing a batch");
      }
    } else {
      logger.fatal(
          "Stopping the processor because the following exception occurred while processing a batch:",
          ex);
    }
    this.processor.setIsStopped(true);
  }

  private static class RecoverableExceptionPredicates {

    static boolean isRecoverableWhenReadingAck(final Exception ex) {
      /*
       * It is considered non-recoverable if the PDX registry files are deleted from the sending
       * side of a WAN Gateway. This is determined by checking if the cause of the
       * {@link ServerConnectivityException} is caused by a {@link PdxRegistryMismatchException}
       */
      return isRecoverableInAllCases(ex)
          || (ex instanceof ServerConnectivityException
              && !(ex.getCause() instanceof PdxRegistryMismatchException));
    }

    static boolean isRecoverableWhenDispatchingBatch(final Throwable t) {
      /*
       * We consider {@link ServerConnectivityException} to be a temporary connectivity issue and
       * is therefore recoverable. The {@link IllegalStateException} can occur if off-heap is used,
       * and a GatewaySenderEventImpl is serialized after being freed. This can happen if the
       * region is destroyed concurrently while the gateway sender event is being processed.
       */
      return isRecoverableInAllCases(t)
          || t instanceof ServerConnectivityException
          || t instanceof IllegalStateException;
    }

    /**
     * Certain exception types are considered recoverable when either dispatching a batch or
     * reading an acknowledgement.
     */
    private static boolean isRecoverableInAllCases(final Throwable t) {
      /*
       * {@link IOException} and {@link ConnectionDestroyedException} can occur
       * due to temporary network issues and therefore are recoverable.
       * {@link GemFireSecurityException} represents an inability to authenticate with the
       * gateway receiver.
       *
       * By treating {@link GemFireSecurityException} as recoverable we are continuing to retry
       * in a couple situations:
       *
       * <ul>
       * <li>The implementation of the {@link SecurityManager} loses connectivity to the actual
       * authentication authority e.g. Active Directory</li> (expecting that connectivity will
       * later be restored)
       * <li>Credentials are invalid (expecting that they will later become valid)</li>
       * </ul>
       */
      return t instanceof IOException
          || t instanceof ConnectionDestroyedException
          || t instanceof GemFireSecurityException;
    }
  }

  @Override
  public void sendBatch(List<GatewayQueueEvent<?, ?>> events, Connection connection,
      ExecutablePool senderPool, int batchId, boolean removeFromQueueOnException)
      throws BatchException70 {
    GatewaySenderBatchOp.executeOn(connection, senderPool, events, batchId,
        removeFromQueueOnException, false);
    GatewaySenderEventRemoteDispatcher.GatewayAck ack =
        (GatewaySenderEventRemoteDispatcher.GatewayAck) GatewaySenderBatchOp.executeOn(connection,
            senderPool);
    if (ack == null) {
      throw new BatchException70("Unknown error sending batch", null, 0, batchId);
    }
    if (ack.getBatchException() != null) {
      throw ack.getBatchException();
    }
  }
}

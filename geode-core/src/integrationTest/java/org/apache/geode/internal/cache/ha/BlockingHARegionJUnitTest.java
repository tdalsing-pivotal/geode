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
package org.apache.geode.internal.cache.ha;

import static java.lang.Thread.sleep;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.internal.cache.ha.HARegionQueue.BLOCKING_HA_QUEUE;
import static org.apache.geode.internal.cache.ha.HARegionQueue.getHARegionQueueInstance;
import static org.apache.geode.internal.statistics.StatisticsClockFactory.disabledClock;
import static org.apache.geode.test.dunit.ThreadUtils.join;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.cache.CacheFactory;
import org.apache.geode.internal.cache.EventID;
import org.apache.geode.internal.cache.InternalCache;
import org.apache.geode.test.awaitility.GeodeAwaitility;
import org.apache.geode.test.dunit.ThreadUtils;
import org.apache.geode.test.junit.categories.ClientSubscriptionTest;

@Category({ClientSubscriptionTest.class})
public class BlockingHARegionJUnitTest {

  private static InternalCache cache = null;

  /** boolean to record an exception occurrence in another thread **/
  private static volatile boolean exceptionOccurred = false;
  /** StringBuffer to store the exception **/
  private static final StringBuffer exceptionString = new StringBuffer();
  /** boolen to quit the for loop **/
  private static volatile boolean quitForLoop = false;

  @Before
  public void setUp() throws Exception {

    if (cache != null) {
      cache.close(); // fault tolerance
    }
    CacheFactory cacheFactory = new CacheFactory();
    cache = (InternalCache) cacheFactory.set(MCAST_PORT, "0").create();

  }

  /**
   * This test has a scenario where the HAReqionQueue capacity is just 1. There will be two thread.
   * One doing a 1000 puts and the other doing a 1000 takes. The validation for this test is that it
   * should not encounter any exceptions
   */
  @Test
  public void testBoundedPuts() throws Exception {
    exceptionOccurred = false;
    HARegionQueueAttributes harqa = new HARegionQueueAttributes();
    harqa.setBlockingQueueCapacity(1);
    HARegionQueue hrq = HARegionQueue.getHARegionQueueInstance("BlockingHARegionJUnitTest_Region",
        cache, harqa, HARegionQueue.BLOCKING_HA_QUEUE, false, disabledClock());
    hrq.setPrimary(true);// fix for 40314 - capacity constraint is checked for primary only.
    Thread thread1 = new DoPuts(hrq, 1000);
    Thread thread2 = new DoTake(hrq, 1000);

    thread1.start();
    thread2.start();

    ThreadUtils.join(thread1, 30 * 1000);
    ThreadUtils.join(thread2, 30 * 1000);

    if (exceptionOccurred) {
      fail(" Test failed due to " + exceptionString);
    }

    cache.close();
  }

  /**
   * This test tests whether puts are blocked. There are two threads. One which is going to do 2
   * puts and one which is going to do take a single take. The capacity of the region is just 1. The
   * put thread is first started and it is then ensured that only one put has successfully made
   * through and that the thread is still alive. Then the take thread is started. This will cause
   * the region size to come down by one and the put thread waiting will go ahead and do the put.
   * The thread should then die and the region size should be validated to reflect that.
   */
  @Test
  public void testPutBeingBlocked() throws Exception {
    exceptionOccurred = false;
    quitForLoop = false;
    HARegionQueueAttributes harqa = new HARegionQueueAttributes();
    harqa.setBlockingQueueCapacity(1);
    final HARegionQueue hrq = getHARegionQueueInstance(
        "BlockingHARegionJUnitTest_Region", cache, harqa, BLOCKING_HA_QUEUE, false,
        disabledClock());
    hrq.setPrimary(true);// fix for 40314 - capacity constraint is checked for primary only.
    final Thread thread1 = new DoPuts(hrq, 2);
    thread1.start();
    GeodeAwaitility.await().until(() -> hrq.region.size() == 2);
    assertTrue(thread1.isAlive()); // thread should still be alive (in wait state)

    Thread thread2 = new DoTake(hrq, 1);
    thread2.start(); // start take thread

    // sleep. take will proceed and so will sleeping put
    GeodeAwaitility.await().until(() -> hrq.region.size() == 3);

    // thread should have died since put should have proceeded
    GeodeAwaitility.await().alias("thread1 still alive").until(() -> !thread1.isAlive());

    join(thread1, 30 * 1000); // for completeness
    join(thread2, 30 * 1000);
    if (exceptionOccurred) {
      fail(" Test failed due to " + exceptionString);
    }
    cache.close();
  }


  /**
   * This test tests that the region capacity is never exceeded even in highly concurrent
   * environments. The region capacity is set to 10000. Then 5 threads start doing put
   * simultaneously. They will reach a state where the queue is full and they will all go in a wait
   * state. the region size would be verified to be 20000 (10000 puts and 10000 DACE objects). then
   * the threads are interrupted and made to quit the loop
   */
  @Test
  public void testConcurrentPutsNotExceedingLimit() throws Exception {
    exceptionOccurred = false;
    quitForLoop = false;
    List<Thread> listOfThreads = new ArrayList<>();
    HARegionQueueAttributes harqa = new HARegionQueueAttributes();
    harqa.setBlockingQueueCapacity(10000);
    final HARegionQueue hrq = getHARegionQueueInstance(
        "BlockingHARegionJUnitTest_Region", cache, harqa, BLOCKING_HA_QUEUE, false,
        disabledClock());
    hrq.setPrimary(true);// fix for 40314 - capacity constraint is checked for primary only.
    listOfThreads.add(new DoPuts(hrq, 20000, 1));
    listOfThreads.add(new DoPuts(hrq, 20000, 2));
    listOfThreads.add(new DoPuts(hrq, 20000, 3));
    listOfThreads.add(new DoPuts(hrq, 20000, 4));
    listOfThreads.add(new DoPuts(hrq, 20000, 5));

    for (Thread thread : listOfThreads) {
      thread.start();
    }

    GeodeAwaitility.await().until(() -> hrq.region.size() == 20000);

    for (Thread thread : listOfThreads) {
      assertThat(thread.isAlive()).isTrue();
    }

    assertThat(hrq.region.size()).isEqualTo(20000);

    quitForLoop = true;

    for (Thread thread : listOfThreads) {
      thread.interrupt();
    }

    sleep(2000);

    for (Thread thread : listOfThreads) {
      join(thread, 10 * 60 * 1000);
    }

    cache.close();
  }

  /**
   * This test tests that the region capacity is never exceeded even in highly concurrent
   * environments. The region capacity is set to 10000. Then 5 threads start doing put
   * simultaneously. They will reach a state where the queue is full and they will all go in a wait
   * state. the region size would be verified to be 20000 (10000 puts and 10000 DACE objects). then
   * the threads are interrupted and made to quit the loop
   */
  @Ignore("TODO: test is disabled")
  @Test
  public void testConcurrentPutsTakesNotExceedingLimit() throws Exception {
    exceptionOccurred = false;
    quitForLoop = false;
    HARegionQueueAttributes harqa = new HARegionQueueAttributes();
    harqa.setBlockingQueueCapacity(10000);
    final HARegionQueue hrq = getHARegionQueueInstance(
        "BlockingHARegionJUnitTest_Region", cache, harqa, BLOCKING_HA_QUEUE, false,
        disabledClock());
    Thread thread1 = new DoPuts(hrq, 40000, 1);
    Thread thread2 = new DoPuts(hrq, 40000, 2);
    Thread thread3 = new DoPuts(hrq, 40000, 3);
    Thread thread4 = new DoPuts(hrq, 40000, 4);
    Thread thread5 = new DoPuts(hrq, 40000, 5);

    Thread thread6 = new DoTake(hrq, 5000);
    Thread thread7 = new DoTake(hrq, 5000);
    Thread thread8 = new DoTake(hrq, 5000);
    Thread thread9 = new DoTake(hrq, 5000);
    Thread thread10 = new DoTake(hrq, 5000);

    thread1.start();
    thread2.start();
    thread3.start();
    thread4.start();
    thread5.start();

    thread6.start();
    thread7.start();
    thread8.start();
    thread9.start();
    thread10.start();

    join(thread6, 30 * 1000);
    join(thread7, 30 * 1000);
    join(thread8, 30 * 1000);
    join(thread9, 30 * 1000);
    join(thread10, 30 * 1000);

    GeodeAwaitility.await().until(() -> hrq.region.size() == 20000);

    assertThat(thread1.isAlive()).isTrue();
    assertThat(thread2.isAlive()).isTrue();
    assertThat(thread3.isAlive()).isTrue();
    assertThat(thread4.isAlive()).isTrue();
    assertThat(thread5.isAlive()).isTrue();

    assertThat(hrq.region.size()).isEqualTo(20000);

    quitForLoop = true;

    sleep(2000);

    thread1.interrupt();
    thread2.interrupt();
    thread3.interrupt();
    thread4.interrupt();
    thread5.interrupt();

    sleep(2000);


    join(thread1, 30 * 1000);
    join(thread2, 30 * 1000);
    join(thread3, 30 * 1000);
    join(thread4, 30 * 1000);
    join(thread5, 30 * 1000);

    cache.close();
  }

  /**
   * Tests the bug in HARegionQueue where the take side put permit is not being incremented when the
   * event arriving at the queue which has optimistically decreased the put permit, is not
   * incrementing the take permit if the event has a sequence ID less than the last dispatched
   * sequence ID. This event is rightly rejected from entering the queue but the take permit also
   * needs to increase & a notify issued
   */
  @Test
  public void testHARQMaxCapacity_Bug37627() throws Exception {
    try {
      exceptionOccurred = false;
      quitForLoop = false;
      HARegionQueueAttributes harqa = new HARegionQueueAttributes();
      harqa.setBlockingQueueCapacity(1);
      harqa.setExpiryTime(180);
      final HARegionQueue hrq = HARegionQueue.getHARegionQueueInstance(
          "BlockingHARegionJUnitTest_Region", cache, harqa, HARegionQueue.BLOCKING_HA_QUEUE, false,
          disabledClock());
      hrq.setPrimary(true);// fix for 40314 - capacity constraint is checked for primary only.
      final EventID id1 = new EventID(new byte[] {1}, 1, 2); // violation
      final EventID ignore = new EventID(new byte[] {1}, 1, 1); //
      final EventID id2 = new EventID(new byte[] {1}, 1, 3); //
      Thread t1 = new Thread(() -> {
        try {
          hrq.put(new ConflatableObject("key1", "value1", id1, false, "region1"));
          hrq.take();
          hrq.put(new ConflatableObject("key2", "value1", ignore, false, "region1"));
          hrq.put(new ConflatableObject("key3", "value1", id2, false, "region1"));
        } catch (Exception e) {
          exceptionString.append("First Put in region queue failed");
          exceptionOccurred = true;
        }
      });
      t1.start();
      ThreadUtils.join(t1, 20 * 1000);
      if (exceptionOccurred) {
        fail(" Test failed due to " + exceptionString);
      }
    } finally {
      if (cache != null) {
        cache.close();
      }
    }
  }

  /**
   * class which does specified number of puts on the queue
   */
  private static class DoPuts extends Thread {
    HARegionQueue regionQueue;
    final int numberOfPuts;

    DoPuts(HARegionQueue haRegionQueue, int numberOfPuts) {
      this.regionQueue = haRegionQueue;
      this.numberOfPuts = numberOfPuts;
    }

    /**
     * region id can be specified to generate Thread unique events
     */
    int regionId = 0;

    DoPuts(HARegionQueue haRegionQueue, int numberOfPuts, int regionId) {
      this.regionQueue = haRegionQueue;
      this.numberOfPuts = numberOfPuts;
      this.regionId = regionId;
    }

    @Override
    public void run() {
      for (int i = 0; i < numberOfPuts; i++) {
        try {
          this.regionQueue.put(new ConflatableObject("" + i, "" + i,
              new EventID(new byte[regionId], i, i), false, "BlockingHARegionJUnitTest_Region"));
          if (quitForLoop) {
            break;
          }
          if (Thread.currentThread().isInterrupted()) {
            break;
          }
        } catch (Exception e) {
          exceptionOccurred = true;
          exceptionString.append(" Exception occurred due to ").append(e);
          break;
        }
      }
    }
  }

  /**
   * class which does a specified number of takes
   */
  private static class DoTake extends Thread {

    final HARegionQueue regionQueue;
    final int numberOfTakes;

    DoTake(HARegionQueue haRegionQueue, int numberOfTakes) {
      this.regionQueue = haRegionQueue;
      this.numberOfTakes = numberOfTakes;
    }

    @Override
    public void run() {
      for (int i = 0; i < numberOfTakes; i++) {
        try {
          assertNotNull(this.regionQueue.take());
          if (Thread.currentThread().isInterrupted()) {
            break;
          }
        } catch (Exception e) {
          exceptionOccurred = true;
          exceptionString.append(" Exception occurred due to ").append(e);
          break;
        }
      }
    }
  }

}

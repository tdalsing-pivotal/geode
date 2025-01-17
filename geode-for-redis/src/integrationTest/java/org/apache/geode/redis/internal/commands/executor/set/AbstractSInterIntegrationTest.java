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
package org.apache.geode.redis.internal.commands.executor.set;

import static org.apache.geode.redis.RedisCommandArgumentsTestHelper.assertAtLeastNArgs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.Protocol;

import org.apache.geode.redis.RedisIntegrationTest;
import org.apache.geode.test.awaitility.GeodeAwaitility;

public abstract class AbstractSInterIntegrationTest implements RedisIntegrationTest {
  private JedisCluster jedis;
  private static final int REDIS_CLIENT_TIMEOUT =
      Math.toIntExact(GeodeAwaitility.getTimeout().toMillis());

  @Before
  public void setUp() {
    jedis = new JedisCluster(new HostAndPort("localhost", getPort()), REDIS_CLIENT_TIMEOUT);
  }

  @After
  public void tearDown() {
    flushAll();
    jedis.close();
  }

  @Test
  public void sinterErrors_givenTooFewArguments() {
    assertAtLeastNArgs(jedis, Protocol.Command.SINTER, 1);
  }

  @Test
  public void sinterstoreErrors_givenTooFewArguments() {
    assertAtLeastNArgs(jedis, Protocol.Command.SINTERSTORE, 2);
  }

  @Test
  public void testSInter() {
    String[] firstSet = new String[] {"pear", "apple", "plum", "orange", "peach"};
    String[] secondSet = new String[] {"apple", "microsoft", "linux", "peach"};
    String[] thirdSet = new String[] {"luigi", "bowser", "peach", "mario"};
    jedis.sadd("{user1}set1", firstSet);
    jedis.sadd("{user1}set2", secondSet);
    jedis.sadd("{user1}set3", thirdSet);

    Set<String> resultSet = jedis.sinter("{user1}set1", "{user1}set2", "{user1}set3");

    String[] expected = new String[] {"peach"};
    assertThat(resultSet).containsExactlyInAnyOrder(expected);

    Set<String> emptyResultSet = jedis.sinter("{user1}nonexistent", "{user1}set2", "{user1}set3");
    assertThat(emptyResultSet).isEmpty();

    jedis.sadd("{user1}newEmpty", "born2die");
    jedis.srem("{user1}newEmpty", "born2die");
    Set<String> otherEmptyResultSet = jedis.sinter("{user1}set2", "{user1}newEmpty");
    assertThat(otherEmptyResultSet).isEmpty();
  }

  @Test
  public void testSInterStore() {
    String[] firstSet = new String[] {"pear", "apple", "plum", "orange", "peach"};
    String[] secondSet = new String[] {"apple", "microsoft", "linux", "peach"};
    String[] thirdSet = new String[] {"luigi", "bowser", "peach", "mario"};
    jedis.sadd("{user1}set1", firstSet);
    jedis.sadd("{user1}set2", secondSet);
    jedis.sadd("{user1}set3", thirdSet);

    Long resultSize =
        jedis.sinterstore("{user1}result", "{user1}set1", "{user1}set2", "{user1}set3");
    Set<String> resultSet = jedis.smembers("{user1}result");

    String[] expected = new String[] {"peach"};
    assertThat(resultSize).isEqualTo(expected.length);
    assertThat(resultSet).containsExactlyInAnyOrder(expected);

    Long otherResultSize = jedis.sinterstore("{user1}set1", "{user1}set1", "{user1}set2");
    Set<String> otherResultSet = jedis.smembers("{user1}set1");
    String[] otherExpected = new String[] {"apple", "peach"};
    assertThat(otherResultSize).isEqualTo(otherExpected.length);
    assertThat(otherResultSet).containsExactlyInAnyOrder(otherExpected);

    Long emptySetSize =
        jedis.sinterstore("{user1}newEmpty", "{user1}nonexistent", "{user1}set2", "{user1}set3");
    Set<String> emptyResultSet = jedis.smembers("{user1}newEmpty");
    assertThat(emptySetSize).isEqualTo(0L);
    assertThat(emptyResultSet).isEmpty();

    emptySetSize =
        jedis.sinterstore("{user1}set1", "{user1}nonexistent", "{user1}set2", "{user1}set3");
    emptyResultSet = jedis.smembers("{user1}set1");
    assertThat(emptySetSize).isEqualTo(0L);
    assertThat(emptyResultSet).isEmpty();

    Long copySetSize = jedis.sinterstore("{user1}copySet", "{user1}set2", "{user1}newEmpty");
    Set<String> copyResultSet = jedis.smembers("{user1}copySet");
    assertThat(copySetSize).isEqualTo(0);
    assertThat(copyResultSet).isEmpty();
  }

  @Test
  public void testSInterStore_withNonExistentKeys() {
    String[] firstSet = new String[] {"pear", "apple", "plum", "orange", "peach"};
    jedis.sadd("{user1}set1", firstSet);

    Long resultSize =
        jedis.sinterstore("{user1}set1", "{user1}nonExistent1", "{user1}nonExistent2");
    assertThat(resultSize).isEqualTo(0);
    assertThat(jedis.exists("{user1}set1")).isFalse();
  }

  @Test
  public void testSInterStore_withNonExistentKeys_andNonSetTarget() {
    jedis.set("string1", "stringValue");

    Long resultSize =
        jedis.sinterstore("{user1}string1", "{user1}nonExistent1", "{user1}nonExistent2");
    assertThat(resultSize).isEqualTo(0);
    assertThat(jedis.exists("{user1}set1")).isFalse();
  }

  @Test
  public void testSInterStore_withNonSetKey() {
    String[] firstSet = new String[] {"pear", "apple", "plum", "orange", "peach"};
    jedis.sadd("{user1}set1", firstSet);
    jedis.set("{user1}string1", "value1");

    assertThatThrownBy(() -> jedis.sinterstore("{user1}set1", "{user1}string1"))
        .hasMessage("WRONGTYPE Operation against a key holding the wrong kind of value");
    assertThat(jedis.exists("{user1}set1")).isTrue();
  }

  @Test
  public void testConcurrentSInterStore() throws InterruptedException {
    int ENTRIES = 100;
    int SUBSET_SIZE = 100;

    Set<String> masterSet = new HashSet<>();
    for (int i = 0; i < ENTRIES; i++) {
      masterSet.add("master-" + i);
    }

    List<Set<String>> otherSets = new ArrayList<>();
    for (int i = 0; i < ENTRIES; i++) {
      Set<String> oneSet = new HashSet<>();
      for (int j = 0; j < SUBSET_SIZE; j++) {
        oneSet.add("set-" + i + "-" + j);
      }
      otherSets.add(oneSet);
    }

    jedis.sadd("master", masterSet.toArray(new String[] {}));

    for (int i = 0; i < ENTRIES; i++) {
      jedis.sadd("set-" + i, otherSets.get(i).toArray(new String[] {}));
      jedis.sadd("set-" + i, masterSet.toArray(new String[] {}));
    }

    Runnable runnable1 = () -> {
      for (int i = 0; i < ENTRIES; i++) {
        jedis.sinterstore("master", "master", "set-" + i);
        Thread.yield();
      }
    };

    Runnable runnable2 = () -> {
      for (int i = 0; i < ENTRIES; i++) {
        jedis.sinterstore("master", "master", "set-" + i);
        Thread.yield();
      }
    };

    Thread thread1 = new Thread(runnable1);
    Thread thread2 = new Thread(runnable2);

    thread1.start();
    thread2.start();
    thread1.join();
    thread2.join();

    assertThat(jedis.smembers("master").toArray()).containsExactlyInAnyOrder(masterSet.toArray());
  }
}

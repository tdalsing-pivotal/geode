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

package org.apache.geode.redis.internal.commands.executor.key;

import static org.apache.geode.redis.internal.RedisConstants.ERROR_CURSOR;
import static org.apache.geode.redis.internal.RedisConstants.ERROR_NOT_INTEGER;
import static org.apache.geode.redis.internal.RedisConstants.ERROR_SYNTAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import org.apache.geode.redis.RedisIntegrationTest;
import org.apache.geode.test.awaitility.GeodeAwaitility;

public abstract class AbstractScanIntegrationTest implements RedisIntegrationTest {

  protected JedisCluster jedis;
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
  public void givenNoCursorArgument_returnsWrongNumberOfArgsError() {
    assertThatThrownBy(() -> jedis.sendCommand("key", Protocol.Command.SCAN))
        .hasMessageContaining("ERR wrong number of arguments for 'scan' command");
  }

  @Test
  public void givenCursorArgumentIsNotAnInteger_returnsCursorError() {
    assertThatThrownBy(() -> jedis.sendCommand("key", Protocol.Command.SCAN, "sljfs"))
        .hasMessageContaining(ERROR_CURSOR);
  }

  @Test
  public void givenArgumentsAreNotEven_returnsSyntaxError() {
    assertThatThrownBy(() -> jedis.sendCommand("key", Protocol.Command.SCAN, "0", "a*"))
        .hasMessageContaining(ERROR_SYNTAX);
  }

  @Test
  public void givenMatchOrCountKeywordNotSpecified_returnsSyntaxError() {
    assertThatThrownBy(() -> jedis.sendCommand("key", Protocol.Command.SCAN, "0", "a*", "1"))
        .hasMessageContaining(ERROR_SYNTAX);
  }

  @Test
  public void givenCount_whenCountParameterIsNotAnInteger_returnsNotIntegerError() {
    assertThatThrownBy(() -> jedis.sendCommand("key", Protocol.Command.SCAN, "0", "COUNT", "MATCH"))
        .hasMessageContaining(ERROR_NOT_INTEGER);
  }

  @Test
  public void givenCount_whenCountParameterIsZero_returnsSyntaxError() {
    assertThatThrownBy(() -> jedis.sendCommand("key", Protocol.Command.SCAN, "0", "COUNT", "0"))
        .hasMessageContaining(ERROR_SYNTAX);
  }

  @Test
  public void givenCount_whenCountParameterIsNegative_returnsSyntaxError() {
    assertThatThrownBy(() -> jedis.sendCommand("key", Protocol.Command.SCAN, "0", "COUNT", "-37"))
        .hasMessageContaining(ERROR_SYNTAX);
  }

  @Test
  public void givenMultipleCounts_whenAnyCountParameterIsNotAnInteger_returnsNotIntegerError() {
    assertThatThrownBy(() -> jedis.sendCommand("key", Protocol.Command.SCAN, "0", "COUNT", "2",
        "COUNT", "sjlfs", "COUNT", "1"))
            .hasMessageContaining(ERROR_NOT_INTEGER);
  }

  @Test
  public void givenMultipleCounts_whenAnyCountParameterIsLessThanOne_returnsSyntaxError() {
    assertThatThrownBy(() -> jedis.sendCommand("key", Protocol.Command.SCAN, "0", "COUNT", "2",
        "COUNT", "0", "COUNT", "1"))
            .hasMessageContaining(ERROR_SYNTAX);
  }

  @Test
  public void givenOneKeyInRegion_returnsKey() {
    jedis.set("{user1}a", "1");
    ScanResult<String> result = jedis.scan("0", new ScanParams().match("{user1}*"));

    assertThat(result.isCompleteIteration()).isTrue();
    assertThat(result.getResult()).containsExactly("{user1}a");
  }

  @Test
  public void givenEmptyRegion_returnsEmptyArray() {
    ScanResult<String> result = jedis.scan("0", new ScanParams().match("{user1}*"));

    assertThat(result.isCompleteIteration()).isTrue();
    assertThat(result.getResult()).isEmpty();
  }

  @Test
  public void givenMultipleKeysInRegion_returnsAllKeys() {
    jedis.set("{user1}a", "1");
    jedis.sadd("{user1}b", "green", "orange");
    jedis.hset("{user1}c", "potato", "sweet");
    ScanResult<String> result = jedis.scan("0", new ScanParams().match("{user1}*"));

    assertThat(result.isCompleteIteration()).isTrue();
    assertThat(result.getResult()).containsExactlyInAnyOrder("{user1}a", "{user1}b", "{user1}c");
  }

  @Test
  public void givenCount_returnsAllKeysWithoutDuplicates() {
    jedis.set("{user1}a", "1");
    jedis.sadd("{user1}b", "green", "orange");
    jedis.hset("{user1}c", "potato", "sweet");

    ScanParams scanParams = new ScanParams();
    scanParams.count(1);
    scanParams.match("{user1}*");
    String cursor = "0";
    ScanResult<String> result;
    List<String> allKeysFromScan = new ArrayList<>();

    do {
      result = jedis.scan(cursor, scanParams);
      allKeysFromScan.addAll(result.getResult());
      cursor = result.getCursor();
    } while (!result.isCompleteIteration());

    assertThat(allKeysFromScan).containsExactlyInAnyOrder("{user1}a", "{user1}b", "{user1}c");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenMultipleCounts_returnsAllKeysWithoutDuplicates() {
    jedis.set("{user1}a", "1");
    jedis.sadd("{user1}b", "green", "orange");
    jedis.hset("{user1}c", "potato", "sweet");

    String cursor = "0";
    List<Object> result;
    List<Object> allKeysFromScan = new ArrayList<>();

    do {
      result =
          (List<Object>) jedis.sendCommand("user1", Protocol.Command.SCAN, cursor, "COUNT", "2",
              "COUNT", "1");
      allKeysFromScan.addAll((List<byte[]>) result.get(1));
      cursor = new String((byte[]) result.get(0));
    } while (!Arrays.equals((byte[]) result.get(0), "0".getBytes()));

    assertThat((byte[]) result.get(0)).isEqualTo("0".getBytes());
    assertThat(allKeysFromScan).containsExactlyInAnyOrder("{user1}a".getBytes(),
        "{user1}b".getBytes(), "{user1}c".getBytes());
  }

  @Test
  public void givenMatch_returnsAllMatchingKeysWithoutDuplicates() {
    jedis.set("{user1}a", "1");
    jedis.sadd("{user1}b", "green", "orange");
    jedis.hset("{user1}c", "potato", "sweet");
    ScanParams scanParams = new ScanParams();
    scanParams.match("{user1}a*");

    ScanResult<String> result = jedis.scan("0", scanParams);

    assertThat(result.isCompleteIteration()).isTrue();
    assertThat(result.getResult()).containsExactly("{user1}a");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenMultipleMatches_returnsKeysMatchingLastMatchParameter() {
    jedis.set("{user1}a", "1");
    jedis.sadd("{user1}b", "green", "orange");
    jedis.hset("{user1}c", "potato", "sweet");

    List<Object> result =
        (List<Object>) jedis.sendCommand("user1", Protocol.Command.SCAN, "0", "MATCH", "{user1}b*",
            "MATCH", "{user1}a*");

    assertThat((byte[]) result.get(0)).isEqualTo("0".getBytes());
    assertThat((List<byte[]>) result.get(1)).containsExactly("{user1}a".getBytes());
  }

  @Test
  public void givenMatchAndCount_returnsAllMatchingKeysWithoutDuplicates() {
    jedis.set("{user1}a", "1");
    jedis.sadd("{user1}apple", "green", "orange");
    jedis.hset("{user1}c", "potato", "sweet");
    ScanParams scanParams = new ScanParams();
    scanParams.match("{user1}a*");
    scanParams.count(1);

    String cursor = "0";
    ScanResult<String> result;
    List<String> allKeysFromScan = new ArrayList<>();

    do {
      result = jedis.scan(cursor, scanParams);
      allKeysFromScan.addAll(result.getResult());
      cursor = result.getCursor();
    } while (!result.isCompleteIteration());

    assertThat(result.isCompleteIteration()).isTrue();
    assertThat(allKeysFromScan).containsExactlyInAnyOrder("{user1}a", "{user1}apple");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void givenMultipleCountsAndMatches_returnsKeysMatchingLastMatchParameter() {
    jedis.set("{user1}a", "1");
    jedis.sadd("{user1}b", "green", "orange");
    jedis.hset("{user1}aardvark", "potato", "sweet");

    String cursor = "0";
    List<Object> result;
    List<Object> allKeysFromScan = new ArrayList<>();

    do {
      result =
          (List<Object>) jedis.sendCommand("{user1}", Protocol.Command.SCAN, cursor, "COUNT", "37",
              "MATCH", "{user1}b*", "COUNT", "2", "COUNT", "1", "MATCH", "{user1}a*");
      allKeysFromScan.addAll((List<byte[]>) result.get(1));
      cursor = new String((byte[]) result.get(0));
    } while (!Arrays.equals((byte[]) result.get(0), "0".getBytes()));

    assertThat((byte[]) result.get(0)).isEqualTo("0".getBytes());
    assertThat(allKeysFromScan).containsExactlyInAnyOrder("{user1}a".getBytes(),
        "{user1}aardvark".getBytes());
  }

  @Test
  public void givenNegativeCursor_returnsKeysUsingAbsoluteValueOfCursor() {
    jedis.set("{user1}a", "1");
    jedis.sadd("{user1}b", "green", "orange");
    jedis.hset("{user1}c", "potato", "sweet");

    List<String> allEntries = new ArrayList<>();

    String cursor = "-100";
    ScanResult<String> result;
    do {
      result = jedis.scan(cursor, new ScanParams().match("{user1}*"));
      allEntries.addAll(result.getResult());
      cursor = result.getCursor();
    } while (!result.isCompleteIteration());

    assertThat(allEntries).containsExactlyInAnyOrder("{user1}a", "{user1}b", "{user1}c");
  }

  @Test
  public void givenCursorGreaterThanUnsignedLongCapacity_returnsCursorError() {
    assertThatThrownBy(() -> jedis.scan("18446744073709551616", new ScanParams().match("{a}*")))
        .hasMessageContaining(ERROR_CURSOR);
  }

  @Test
  public void givenNegativeCursorGreaterThanUnsignedLongCapacity_returnsCursorError() {
    assertThatThrownBy(() -> jedis.scan("-18446744073709551616", new ScanParams().match("{a}*")))
        .hasMessageContaining(ERROR_CURSOR);
  }

  @Test
  public void givenInvalidRegexSyntax_returnsEmptyArray() {
    ScanParams scanParams = new ScanParams();
    scanParams.count(1);
    scanParams.match("{user1}\\p");

    ScanResult<String> result = jedis.scan("0", scanParams);

    assertThat(result.getResult()).isEmpty();
  }
}

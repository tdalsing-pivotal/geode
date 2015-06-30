/*=========================================================================
 * Copyright (c) 2010-2014 Pivotal Software, Inc. All Rights Reserved.
 * This product is protected by U.S. and international copyright
 * and intellectual property laws. Pivotal products are covered by
 * one or more patents listed at http://www.pivotal.io/patents.
 *=========================================================================
 */
package com.gemstone.gemfire.internal.cache;

import java.io.File;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.*;

import junit.framework.TestCase;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.distributed.*;
import com.gemstone.gemfire.test.junit.categories.IntegrationTest;

/**
 * This test tests Illegal arguements being passed to create disk regions. The
 * creation of the DWA object should throw a relevant exception if the
 * arguements specified are incorrect.
 * 
 * @author mbid
 *  
 */
@Category(IntegrationTest.class)
public class DiskRegionIllegalArguementsJUnitTest
{

  protected static Cache cache = null;

  protected static DistributedSystem ds = null;
  protected static Properties props = new Properties();

  static {
    props.setProperty("mcast-port", "0");
    props.setProperty("locators", "");
    props.setProperty("log-level", "config"); // to keep diskPerf logs smaller
    props.setProperty("statistic-sampling-enabled", "true");
    props.setProperty("enable-time-statistics", "true");
    props.setProperty("statistic-archive-file", "stats.gfs");
  }

  @Before
  public void setUp() throws Exception {
    cache = new CacheFactory(props).create();
    ds = cache.getDistributedSystem();
  }

  @After
  public void tearDown() throws Exception {
    cache.close();
  }
  
  /**
   * test Illegal max oplog size
   */

  @Test
  public void testMaxOplogSize()
  {
    DiskStoreFactory dsf = cache.createDiskStoreFactory();
    try {
      dsf.setMaxOplogSize(-1);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }
    dsf.setMaxOplogSize(1);
    assertEquals(1, dsf.create("test").getMaxOplogSize());
  }

  @Test
  public void testCompactionThreshold() {
    DiskStoreFactory dsf = cache.createDiskStoreFactory();

    try {
      dsf.setCompactionThreshold(-1);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }

    try {
      dsf.setCompactionThreshold(101);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }

    dsf.setCompactionThreshold(0);
    dsf.setCompactionThreshold(100);
    assertEquals(100, dsf.create("test").getCompactionThreshold());
  }

  @Test
  public void testAutoCompact()
  {
    DiskStoreFactory dsf = cache.createDiskStoreFactory();
    dsf.setAutoCompact(true);
    assertEquals(true, dsf.create("test").getAutoCompact());

    dsf.setAutoCompact(false);
    assertEquals(false, dsf.create("test2").getAutoCompact());
  }

  @Test
  public void testAllowForceCompaction()
  {
    DiskStoreFactory dsf = cache.createDiskStoreFactory();
    dsf.setAllowForceCompaction(true);
    assertEquals(true, dsf.create("test").getAllowForceCompaction());

    dsf.setAllowForceCompaction(false);
    assertEquals(false, dsf.create("test2").getAllowForceCompaction());
  }
  
  @Test
  public void testDiskDirSize()
  {

    File file1 = new File("file1");

    File file2 = new File("file2");
    File file3 = new File("file3");
    File file4 = new File("file4");
    file1.mkdir();
    file2.mkdir();
    file3.mkdir();
    file4.mkdir();
    file1.deleteOnExit();
    file2.deleteOnExit();
    file3.deleteOnExit();
    file4.deleteOnExit();

    File[] dirs = { file1, file2, file3, file4 };

    int[] ints = { 1, 2, 3, -4 };

    DiskStoreFactory dsf = cache.createDiskStoreFactory();
    try {
      dsf.setDiskDirsAndSizes(dirs, ints);
      fail("expected IllegalArgumentException");
    }
    catch (IllegalArgumentException ok) {
    }

    int[] ints1 = { 1, 2, 3 };

    try {
      dsf.setDiskDirsAndSizes(dirs, ints1);
      fail("expected IllegalArgumentException");
    }
    catch (IllegalArgumentException ok) {
    }
    ints[3] = 4;
    dsf.setDiskDirsAndSizes(dirs, ints);
  }

  @Test
  public void testDiskDirs()
  {
    File file1 = new File("file6");

    File file2 = new File("file7");
    File file3 = new File("file8");
    File file4 = new File("file9");

    File[] dirs = { file1, file2, file3, file4 };

    int[] ints = { 1, 2, 3, 4 };

    DiskStoreFactory dsf = cache.createDiskStoreFactory();
    try {
      dsf.setDiskDirsAndSizes(dirs, ints);
      //The disk store would create the disk store directories.
      //fail("expected IllegalArgumentException");
      
    } catch (IllegalArgumentException e) {
    }

    int[] ints1 = { 1, 2, 3 };
    file1.mkdir();
    file2.mkdir();
    file3.mkdir();
    file4.mkdir();
    file1.deleteOnExit();
    file2.deleteOnExit();
    file3.deleteOnExit();
    file4.deleteOnExit();

    try {
      dsf.setDiskDirsAndSizes(dirs, ints1);
      //The disk store would create the disk store directories.

      //fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }
    dsf.setDiskDirsAndSizes(dirs, ints);
  }

  @Test
  public void testQueueSize()
  {
    DiskStoreFactory dsf = cache.createDiskStoreFactory();
    try {
      dsf.setQueueSize(-1);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }

    dsf.setQueueSize(1);
    assertEquals(1, dsf.create("test").getQueueSize(), 1);
  }

  @Test
  public void testTimeInterval()
  {
    DiskStoreFactory dsf = cache.createDiskStoreFactory();
    try {
      dsf.setTimeInterval(-1);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }

    dsf.setTimeInterval(1);
    assertEquals(dsf.create("test").getTimeInterval(), 1);
  }
  
  @Test
  public void testDiskUsageWarningPercentage() {
    DiskStoreFactory dsf = cache.createDiskStoreFactory();
    try {
      dsf.setDiskUsageWarningPercentage(-1);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }

    try {
      dsf.setDiskUsageWarningPercentage(101);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }

    dsf.setDiskUsageWarningPercentage(50);
    assertEquals(50.0f, dsf.create("test").getDiskUsageWarningPercentage(), 0.01);
  }

  @Test
  public void testDiskUsageCriticalPercentage() {
    DiskStoreFactory dsf = cache.createDiskStoreFactory();
    try {
      dsf.setDiskUsageCriticalPercentage(-1);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }

    try {
      dsf.setDiskUsageCriticalPercentage(101);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
    }

    dsf.setDiskUsageCriticalPercentage(50);
    assertEquals(50.0f, dsf.create("test").getDiskUsageCriticalPercentage(), 0.01);
  }
}

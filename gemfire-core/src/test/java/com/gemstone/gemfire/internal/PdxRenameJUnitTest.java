package com.gemstone.gemfire.internal;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.Collection;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.DiskStoreFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionFactory;
import com.gemstone.gemfire.cache.RegionShortcut;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.pdx.PdxReader;
import com.gemstone.gemfire.pdx.PdxSerializable;
import com.gemstone.gemfire.pdx.PdxWriter;
import com.gemstone.gemfire.pdx.internal.EnumInfo;
import com.gemstone.gemfire.pdx.internal.PdxType;
import com.gemstone.gemfire.test.junit.categories.IntegrationTest;

@Category(IntegrationTest.class)
public class PdxRenameJUnitTest {
  @Test
  public void testGetPdxTypes() throws Exception {
    String DS_NAME = "PdxRenameJUnitTestDiskStore";
    Properties props = new Properties();
    props.setProperty("mcast-port", "0");
    props.setProperty("locators", "");
    File f = new File(DS_NAME);
    f.mkdir();
    try {
      final Cache cache = (new CacheFactory(props)).setPdxPersistent(true).setPdxDiskStore(DS_NAME).create();
      try {
        DiskStoreFactory dsf = cache.createDiskStoreFactory();
        dsf.setDiskDirs(new File[]{f});
        dsf.create(DS_NAME);
        RegionFactory<String, PdxValue> rf1 = cache.createRegionFactory(RegionShortcut.LOCAL_PERSISTENT);    
        rf1.setDiskStoreName(DS_NAME);
        Region<String, PdxValue> region1 = rf1.create("region1");
        region1.put("key1", new PdxValue(1));
        cache.close();

        Collection<PdxType> types = DiskStoreImpl.getPdxTypes(DS_NAME, new File[]{f});
        assertEquals(1, types.size());
        assertEquals(PdxValue.class.getName(), types.iterator().next().getClassName());
      } finally {
        if (!cache.isClosed()) {
          cache.close();
        }
      }
    } finally {
      FileUtil.delete(f);
    }
  }
    
  @Test
  public void testPdxRename() throws Exception {
    String DS_NAME = "PdxRenameJUnitTestDiskStore";
    Properties props = new Properties();
    props.setProperty("mcast-port", "0");
    props.setProperty("locators", "");
    File f = new File(DS_NAME);
    f.mkdir();
    try {
      final Cache cache = (new CacheFactory(props)).setPdxPersistent(true).setPdxDiskStore(DS_NAME).create();
      try {
        DiskStoreFactory dsf = cache.createDiskStoreFactory();
        dsf.setDiskDirs(new File[]{f});
        dsf.create(DS_NAME);
        RegionFactory<String, PdxValue> rf1 = cache.createRegionFactory(RegionShortcut.LOCAL_PERSISTENT);    
        rf1.setDiskStoreName(DS_NAME);
        Region<String, PdxValue> region1 = rf1.create("region1");
        region1.put("key1", new PdxValue(1));
        cache.close();

        Collection<Object> renameResults = DiskStoreImpl.pdxRename(DS_NAME, new File[]{f}, "gemstone", "pivotal");
        assertEquals(2, renameResults.size());
        
        for(Object o : renameResults) {
          if(o instanceof PdxType) {
            PdxType t = (PdxType)o;
            assertEquals("com.pivotal.gemfire.internal.PdxRenameJUnitTest$PdxValue", t.getClassName());
          } else {
            EnumInfo ei = (EnumInfo) o;
            assertEquals("com.pivotal.gemfire.internal.PdxRenameJUnitTest$Day", ei.getClassName());
          }
        }
        Collection<PdxType> types = DiskStoreImpl.getPdxTypes(DS_NAME, new File[]{f});
        assertEquals(1, types.size());
        assertEquals("com.pivotal.gemfire.internal.PdxRenameJUnitTest$PdxValue", types.iterator().next().getClassName());

      } finally {
        if (!cache.isClosed()) {
          cache.close();
        }
      }
    } finally {
      FileUtil.delete(f);
    }
  }
  
  @Test
  public void testRegEx() {
    Pattern pattern = DiskStoreImpl.createPdxRenamePattern("foo");
    assertEquals(null, DiskStoreImpl.replacePdxRenamePattern(pattern, "", "FOOBAR"));
    assertEquals(null, DiskStoreImpl.replacePdxRenamePattern(pattern, "afoob", "FOOBAR"));
    assertEquals(null, DiskStoreImpl.replacePdxRenamePattern(pattern, "foob", "FOOBAR"));
    assertEquals(null, DiskStoreImpl.replacePdxRenamePattern(pattern, "afoob", "FOOBAR"));
    assertEquals("bar", DiskStoreImpl.replacePdxRenamePattern(pattern, "foo", "bar"));
    assertEquals(".bar", DiskStoreImpl.replacePdxRenamePattern(pattern, ".foo", "bar"));
    assertEquals("bar.", DiskStoreImpl.replacePdxRenamePattern(pattern, "foo.", "bar"));
    assertEquals("Class$bar", DiskStoreImpl.replacePdxRenamePattern(pattern, "Class$foo", "bar"));
    assertEquals("Class$showMeThe$1.", DiskStoreImpl.replacePdxRenamePattern(pattern, "Class$foo.", "showMeThe$1"));
    pattern = DiskStoreImpl.createPdxRenamePattern("foo.bar");
    assertEquals("com.pivotal.Hello", DiskStoreImpl.replacePdxRenamePattern(pattern, "com.foo.bar.Hello", "pivotal"));
  }
    
  enum Day {
    Sunday, Monday, Tuesday, Wednesday, Thursday, Friday, Saturday;
  }
  
  class PdxValue implements PdxSerializable {
    private int value;
    public Day aDay;
    public PdxValue(int v) {
      this.value = v;
      aDay = Day.Sunday;
    }

    @Override
    public void toData(PdxWriter writer) {
      writer.writeInt("value", this.value);
      writer.writeObject("aDay", aDay);
    }

    @Override
    public void fromData(PdxReader reader) {
      this.value = reader.readInt("value");
      this.aDay = (Day) reader.readObject("aDay");
    }
  }
}

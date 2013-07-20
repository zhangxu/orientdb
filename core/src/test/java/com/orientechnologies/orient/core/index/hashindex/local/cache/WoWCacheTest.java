package com.orientechnologies.orient.core.index.hashindex.local.cache;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListMap;

import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.orientechnologies.common.directmemory.ODirectMemory;
import com.orientechnologies.common.directmemory.ODirectMemoryFactory;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.config.OStorageSegmentConfiguration;
import com.orientechnologies.orient.core.storage.fs.OFileFactory;
import com.orientechnologies.orient.core.storage.impl.local.paginated.OLocalPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OWALRecordsFactory;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OWriteAheadLog;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.WriteAheadLogTest;

public class WoWCacheTest {
  private int                    systemOffset = 2 * (OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);

  private OReadWriteCache        cache;
  private OLocalPaginatedStorage storageLocal;
  private ODirectMemory          directMemory;
  private String                 fileName;
  private OWriteAheadLog         writeAheadLog;
  private OWoWCache              writeCache;
  private byte                   seed;

  @BeforeClass
  public void beforeClass() throws IOException {

    OGlobalConfiguration.FILE_LOCK.setValue(Boolean.FALSE);
    directMemory = ODirectMemoryFactory.INSTANCE.directMemory();

    String buildDirectory = System.getProperty("buildDirectory");
    if (buildDirectory == null)
      buildDirectory = ".";

    storageLocal = (OLocalPaginatedStorage) Orient.instance().loadStorage("plocal:" + buildDirectory + "/ReadWriteCacheTest");

    fileName = "o2QCacheTest.tst";

    seed = (byte) (new Random().nextInt() & 0xFF);

    OWALRecordsFactory.INSTANCE.registerNewRecord((byte) 128, WriteAheadLogTest.TestRecord.class);

  }

  @BeforeMethod
  public void beforeMethod() throws IOException {
    closeBufferAndDeleteFile();

    initBuffer();
  }

  private void closeBufferAndDeleteFile() throws IOException {
    if (cache != null) {
      cache.close();
      cache = null;
    }

    if (writeAheadLog != null) {
      writeAheadLog.delete();
      writeAheadLog = null;
    }

    File file = new File(storageLocal.getConfiguration().getDirectory() + "/o2QCacheTest.tst");
    if (file.exists()) {
      boolean delete = file.delete();
      Assert.assertTrue(delete);
    }
  }

  @AfterClass
  public void afterClass() throws IOException {
    if (cache != null) {
      cache.close();
      cache = null;
    }

    if (writeAheadLog != null) {
      writeAheadLog.delete();
      writeAheadLog = null;
    }

    storageLocal.delete();

    File file = new File(storageLocal.getConfiguration().getDirectory() + "/o2QCacheTest.tst");
    if (file.exists()) {
      Assert.assertTrue(file.delete());
      Assert.assertTrue(file.getParentFile().delete());
    }

  }

  private void initBuffer() throws IOException {
    cache = new OReadWriteCache(64 * (8 + systemOffset), 15000, directMemory, null, 8 + systemOffset, storageLocal, true, false);
    writeCache = cache.getWriteCache();

    final OStorageSegmentConfiguration segmentConfiguration = new OStorageSegmentConfiguration(storageLocal.getConfiguration(),
        "oRWCacheTest", 0);
    segmentConfiguration.fileType = OFileFactory.CLASSIC;
  }

  @Test
  public void testCacheShouldContainsRecordsAfterLoadMethod() throws Exception {
    long fileId = cache.openFile(fileName);

    OCacheEntry cacheEntry = writeCache.markDirty(fileId, 0);
    Assert.assertNotNull(cacheEntry);
    Assert.assertEquals(cacheEntry.fileId, fileId);
    Assert.assertEquals(cacheEntry.pageIndex, 0);
    Assert.assertFalse(ODirectMemory.NULL_POINTER == cacheEntry.dataPointer);
    Assert.assertTrue(cacheEntry.inWriteCache);
    Assert.assertTrue(cacheEntry.recentlyChanged);
    Assert.assertEquals(cacheEntry.usageCounter, 0);

  }

  @Test
  public void testFlushOneWriteGroup() throws Exception {
    long fileId = cache.openFile(fileName);
    for (int i = 0; i < 4; ++i) {
      writeCache.markDirty(fileId, i);
    }
    ConcurrentSkipListMap<OReadWriteCache.FileLockKey, OCacheEntry> internalCache = writeCache.getCache();
    Collection<OCacheEntry> values = internalCache.values();
    for (OCacheEntry value : values) {
      Assert.assertTrue(value.recentlyChanged);
    }
    writeCache.flushFile(fileId);
    for (OCacheEntry value : values) {
      Assert.assertFalse(value.recentlyChanged);
    }
  }

  @Test
  public void testFlushShouldRemoveRecordFromCache() throws Exception {
    long fileId = cache.openFile(fileName);
    for (int i = 0; i < 4; ++i) {
      writeCache.markDirty(fileId, i);
    }
    ConcurrentSkipListMap<OReadWriteCache.FileLockKey, OCacheEntry> internalCache = writeCache.getCache();
    Collection<OCacheEntry> values = Collections.unmodifiableCollection(internalCache.values());
    for (OCacheEntry value : values) {
      Assert.assertTrue(value.recentlyChanged);
    }
    writeCache.flushFile(fileId);
    for (OCacheEntry value : values) {
      Assert.assertFalse(value.recentlyChanged);
    }
    Assert.assertTrue(writeCache.getCache().isEmpty());
  }

  @Test
  public void testRecordShouldBeMarkedAsRecentlyChangedAfterMarkDirtyMethod_InternalCheck() throws Exception {
    long fileId = cache.openFile(fileName);
    writeCache.markDirty(fileId, 0);

    ConcurrentSkipListMap<OReadWriteCache.FileLockKey, OCacheEntry> internalCache = writeCache.getCache();
    Collection<OCacheEntry> values = Collections.unmodifiableCollection(internalCache.values());
    Assert.assertEquals(values.size(), 1);
    for (OCacheEntry value : values) {
      Assert.assertTrue(value.recentlyChanged);
    }
  }

  @Test
  public void testInWriteCacheFlagShouldBeTrueAfterMarkDirtyMethod_InternalCheck() throws Exception {
    long fileId = cache.openFile(fileName);
    writeCache.markDirty(fileId, 0);

    ConcurrentSkipListMap<OReadWriteCache.FileLockKey, OCacheEntry> internalCache = writeCache.getCache();
    Collection<OCacheEntry> values = Collections.unmodifiableCollection(internalCache.values());
    Assert.assertEquals(values.size(), 1);
    for (OCacheEntry value : values) {
      Assert.assertTrue(value.inWriteCache);
    }
  }

  @Test
  public void testRecordShouldBeMarkedAsRecentlyChangedAfterMarkDirtyMethod_ReturnValueCheck() throws Exception {
    long fileId = cache.openFile(fileName);
    OCacheEntry cacheEntry = writeCache.markDirty(fileId, 0);

    Assert.assertTrue(cacheEntry.recentlyChanged);
  }

  @Test
  public void testInWriteCacheFlagShouldBeTrueAfterMarkDirtyMethod_ReturnValueCheck() throws Exception {
    long fileId = cache.openFile(fileName);
    OCacheEntry cacheEntry = writeCache.markDirty(fileId, 0);

    Assert.assertTrue(cacheEntry.inWriteCache);
  }

  @Test
  public void testRecordShouldBeMarkedAsRecentlyChangedAfterMarkDirtyMethod_RecordAlreadyLoadedCase() throws Exception {
    long fileId = cache.openFile(fileName);
    OCacheEntry cacheEntry = cache.getReadCache().load(fileId, 0);
    writeCache.markDirty(cacheEntry);

    Assert.assertTrue(cacheEntry.recentlyChanged);
  }

  @Test
  public void testInWriteCacheFlagShouldBeTrueAfterMarkDirtyMethod_RecordAlreadyLoadedCase() throws Exception {
    long fileId = cache.openFile(fileName);
    OCacheEntry cacheEntry = cache.getReadCache().load(fileId, 0);

    writeCache.markDirty(cacheEntry);

    Assert.assertTrue(cacheEntry.inWriteCache);
  }

  @Test
  public void testMarkDirtyShouldThrowExceptionIfRecordNotExists() throws Exception {
    try {
      writeCache.markDirty(null);
      Assert.fail();
    } catch (IllegalStateException e) {
      Assert.assertEquals(e.getMessage(), "Requested page is not in cache");
    }
  }

  @Test
  public void testCacheSizeIsAlwaysLessThenOrEqualsToMaxCacheSize() throws Exception {
    long fileId = cache.openFile(fileName);
    for (int i = 0; i < 5; ++i) {
      writeCache.markDirty(fileId, i);
    }
    Assert.assertTrue(writeCache.getCache().size() <= 4);
  }

  @Test
  public void testClearMethodShouldEraseAllContentOfCache() throws Exception {
    long fileId = cache.openFile(fileName);
    writeCache.markDirty(fileId, 0);
    Assert.assertEquals(cache.getWriteCache().getCache().size(), 1);
    writeCache.clear();
    Assert.assertEquals(cache.getWriteCache().getCache().size(), 0);
  }

  @Test
  public void testReadExistingInformationShouldWorkEvenIfPageIsNotInReadCache() throws Exception {
    long fileId = cache.openFile(fileName);
    long pointer = cache.load(fileId, 0);
    cache.markDirty(fileId, 0);
    byte[] value = new byte[] { 1, 2, 3, 99, 5, 6, 7, seed };
    directMemory.set(pointer + systemOffset, value, 0, 8);
    cache.release(fileId, 0);
    cache.flushBuffer();
    cache.close();
    Assert.assertEquals(cache.getWriteCache().getSize(), 0);
    Assert.assertEquals(cache.getReadCache().getSize(), 0);

    fileId = cache.openFile(fileName);
    OCacheEntry cacheEntry = writeCache.markDirty(fileId, 0);
    byte[] storedValue = directMemory.get(cacheEntry.dataPointer + systemOffset, 8);

    Assert.assertEquals(storedValue, value);
  }

  @Test
  public void testRemoveShouldRemoveRecordFromCache() throws Exception {
    long fileId = cache.openFile(fileName);
    cache.load(fileId, 0);
    cache.markDirty(fileId, 0);
    cache.release(fileId, 0);

    Assert.assertTrue(writeCache.get(fileId, 0).inWriteCache);
    writeCache.remove(fileId, 0);
    Assert.assertNull(writeCache.get(fileId, 0));
  }

  @Test
  public void testRemoveShouldSetInWriteCacheFlagToFalse() throws Exception {
    long fileId = cache.openFile(fileName);
    cache.load(fileId, 0);
    cache.markDirty(fileId, 0);
    cache.release(fileId, 0);

    OCacheEntry cacheEntry = writeCache.get(fileId, 0);
    Assert.assertTrue(cacheEntry.inWriteCache);
    writeCache.remove(fileId, 0);
    Assert.assertFalse(cacheEntry.inWriteCache);
  }

  @Test
  public void testRemoveShouldFreeMemoryIfRecordIsNotInReadCache() throws Exception {
    long fileId = cache.openFile(fileName);
    cache.load(fileId, 0);
    cache.markDirty(fileId, 0);
    cache.release(fileId, 0);

    cache.getReadCache().clear();

    OCacheEntry cacheEntry = writeCache.get(fileId, 0);
    Assert.assertTrue(cacheEntry.inWriteCache);
    writeCache.remove(fileId, 0);
    Assert.assertFalse(cacheEntry.inWriteCache);
  }

  @Test
  public void testRemoveShouldNotAffectUsedSetInWriteCacheFlagToFalse() throws Exception {
    long fileId = cache.openFile(fileName);
    cache.load(fileId, 0);
    cache.markDirty(fileId, 0);

    Assert.assertTrue(writeCache.get(fileId, 0).inWriteCache);
    writeCache.remove(fileId, 0);
    Assert.assertTrue(writeCache.get(fileId, 0).inWriteCache);

    cache.release(fileId, 0);
  }

  @Test
  public void testWhenSomeRecordsAreLockedFlushFileShouldThrowException() throws Exception {
    long fileId = cache.openFile(fileName);
    cache.load(fileId, 0);
    cache.markDirty(fileId, 0);
    try {
      writeCache.flushFile(fileId);
      Assert.fail();
    } catch (OBlockedPageException e) {
      Assert.assertTrue(e.getMessage().matches("Unable to perform flush file because page \\[\\d, \\d\\] is in use."));
    } finally {
      cache.release(fileId, 0);
    }
  }

}
/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.index.hashindex.local.cache;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.orientechnologies.common.directmemory.ODirectMemory;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.orient.core.command.OCommandOutputListener;
import com.orientechnologies.orient.core.memory.OMemoryWatchDog;
import com.orientechnologies.orient.core.storage.fs.OFileClassic;
import com.orientechnologies.orient.core.storage.impl.local.OStorageLocalAbstract;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.ODirtyPage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OWriteAheadLog;

/**
 * @author Artem Loginov
 * @since 14.03.13
 */
public class OReadWriteCache implements ODiskCache {
  public static final long                                MAGIC_NUMBER = 0xFACB03FEL;

  private int                                             maxSize;

  private final int                                       pageSize;

  private final Map<Long, OFileClassic>                   files;

  /**
   * Contains all pages in cache for given file, not only dirty onces.
   */
  private final Map<Long, Set<Long>>                      filePages;

  private final Object                                    syncObject;
  private final OStorageLocalAbstract                     storageLocal;

  private long                                            fileCounter  = 1;

  final private OWoWCache                                 writeCache;
  final private O2QCache                                  readCache;

  final private ConcurrentMap<FileLockKey, ReadWriteLock> entriesLocks;

  public OReadWriteCache(long maxMemory, int writeQueueLength, ODirectMemory directMemory, OWriteAheadLog writeAheadLog,
      int pageSize, OStorageLocalAbstract storageLocal, boolean syncOnPageFlush) {
    this(maxMemory, writeQueueLength, directMemory, writeAheadLog, pageSize, storageLocal, syncOnPageFlush, true);
  }

  public OReadWriteCache(long maxMemory, int writeQueueLength, ODirectMemory directMemory, OWriteAheadLog writeAheadLog,
      int pageSize, OStorageLocalAbstract storageLocal, boolean syncOnPageFlush, boolean startFlush) {
    this.pageSize = pageSize;
    this.storageLocal = storageLocal;
    this.files = new ConcurrentHashMap<Long, OFileClassic>();
    this.filePages = new HashMap<Long, Set<Long>>();
    this.entriesLocks = new ConcurrentHashMap<FileLockKey, ReadWriteLock>();

    long tmpMaxSize = maxMemory / pageSize;
    if (tmpMaxSize >= Integer.MAX_VALUE) {
      maxSize = Integer.MAX_VALUE;
    } else {
      maxSize = (int) tmpMaxSize;
    }

    assert maxSize >= 16;

    writeCache = new OWoWCache(maxSize >> 4, syncOnPageFlush, writeAheadLog, directMemory, pageSize, writeQueueLength, files,
        entriesLocks);

    readCache = new O2QCache((maxSize - (maxSize >> 4)), files, filePages, pageSize, directMemory, entriesLocks);

    syncObject = new Object();
    if (startFlush)
      writeCache.startFlush();
  }

  @Override
  public long openFile(String fileName) throws IOException {
    synchronized (syncObject) {
      long fileId = fileCounter++;

      OFileClassic fileClassic = new OFileClassic();
      String path = storageLocal.getVariableParser().resolveVariables(storageLocal.getStoragePath() + File.separator + fileName);
      fileClassic.init(path, storageLocal.getMode());

      if (fileClassic.exists())
        fileClassic.open();
      else
        fileClassic.create(-1);

      files.put(fileId, fileClassic);

      filePages.put(fileId, new HashSet<Long>());
      writeCache.fillDirtyPages(fileId);

      return fileId;
    }
  }

  @Override
  public void markDirty(long fileId, long pageIndex) throws IOException {
    synchronized (syncObject) {
      OCacheEntry cacheEntry = readCache.get(fileId, pageIndex);
      writeCache.markDirty(cacheEntry);
    }
  }

  @Override
  public long load(long fileId, long pageIndex) throws IOException {
    synchronized (syncObject) {
      FileLockKey fileLock = new FileLockKey(fileId, pageIndex);
      entriesLocks.putIfAbsent(fileLock, new ReentrantReadWriteLock());
      ReadWriteLock lock = entriesLocks.get(fileLock);
      lock.readLock().lock();
      try {
        OCacheEntry cacheEntry = readCache.get(fileId, pageIndex);
        cacheEntry = storeRecordInReadCache(fileId, pageIndex, cacheEntry);

        cacheEntry.usageCounter++;

        return cacheEntry.dataPointer;
      } finally {
        lock.readLock().unlock();
      }
    }
  }

  private OCacheEntry storeRecordInReadCache(long fileId, long pageIndex, OCacheEntry cacheEntry) throws IOException {
    if (cacheEntry == null) {
      OCacheEntry dirtyEntry = writeCache.get(fileId, pageIndex);
      if (dirtyEntry == null) {
        cacheEntry = readCache.load(fileId, pageIndex);
      } else {
        cacheEntry = readCache.load(dirtyEntry);
      }
    }
    return cacheEntry;
  }

  @Override
  public void release(long fileId, long pageIndex) {
    synchronized (syncObject) {
      OCacheEntry lruEntry = readCache.get(fileId, pageIndex);
      if (lruEntry == null)
        lruEntry = writeCache.get(fileId, pageIndex);
      if (lruEntry != null)
        lruEntry.usageCounter--;
      else
        throw new IllegalStateException("Record that should be released (fileId = " + fileId + ", pageIndex = " + pageIndex
            + ") is not in cache!");
    }
  }

  @Override
  public long getFilledUpTo(long fileId) throws IOException {
    synchronized (syncObject) {
      return files.get(fileId).getFilledUpTo() / pageSize;
    }
  }

  @Override
  public void flushFile(long fileId) throws IOException {
    synchronized (syncObject) {
      writeCache.flushFile(fileId);
    }
  }

  @Override
  public void closeFile(final long fileId) throws IOException {
    closeFile(fileId, true);
  }

  @Override
  public void closeFile(final long fileId, boolean flush) throws IOException {
    synchronized (syncObject) {
      OFileClassic fileClassic = files.get(fileId);
      if (fileClassic == null || !fileClassic.isOpen())
        return;

      readCache.closeFile(fileId, filePages.get(fileId));
      writeCache.closeFile(fileId, filePages.get(fileId), flush);

      fileClassic.close();
    }
  }

  @Override
  public void deleteFile(long fileId) throws IOException {
    synchronized (syncObject) {
      if (!files.containsKey(fileId))
        return;

      if (isOpen(fileId))
        truncateFile(fileId);

      files.get(fileId).delete();

      files.remove(fileId);
      filePages.remove(fileId);
      writeCache.deleteFile(fileId);
    }
  }

  @Override
  public void truncateFile(long fileId) throws IOException {
    synchronized (syncObject) {
      final Set<Long> pageEntries = filePages.get(fileId);
      for (Long pageIndex : pageEntries) {
        writeCache.remove(fileId, pageIndex);
      }

      writeCache.clearDirtyPages(fileId);

      pageEntries.clear();
      files.get(fileId).shrink(0);
    }
  }

  @Override
  public void renameFile(long fileId, String oldFileName, String newFileName) throws IOException {
    synchronized (syncObject) {
      if (!files.containsKey(fileId))
        return;

      final OFileClassic file = files.get(fileId);
      final String osFileName = file.getName();
      if (osFileName.startsWith(oldFileName)) {
        final File newFile = new File(storageLocal.getStoragePath() + File.separator + newFileName
            + osFileName.substring(osFileName.lastIndexOf(oldFileName) + oldFileName.length()));
        boolean renamed = file.renameTo(newFile);
        while (!renamed) {
          OMemoryWatchDog.freeMemoryForResourceCleanup(100);
          renamed = file.renameTo(newFile);
        }
      }
    }
  }

  @Override
  public void flushBuffer() throws IOException {
    synchronized (syncObject) {
      for (long fileId : files.keySet())
        flushFile(fileId);
    }
  }

  @Override
  public void clear() throws IOException {
    synchronized (syncObject) {
      flushBuffer();
      writeCache.clear();
      readCache.clear();
    }
  }

  @Override
  public void close() throws IOException {
    synchronized (syncObject) {
      clear();
      writeCache.stopFlush();
      for (OFileClassic fileClassic : files.values()) {
        if (fileClassic.isOpen()) {
          fileClassic.synch();
          fileClassic.close();
        }
      }
    }

  }

  @Override
  public boolean wasSoftlyClosed(long fileId) throws IOException {
    synchronized (syncObject) {
      OFileClassic fileClassic = files.get(fileId);
      if (fileClassic == null)
        return false;

      return fileClassic.wasSoftlyClosed();
    }

  }

  @Override
  public void setSoftlyClosed(long fileId, boolean softlyClosed) throws IOException {
    synchronized (syncObject) {
      OFileClassic fileClassic = files.get(fileId);
      if (fileClassic != null)
        fileClassic.setSoftlyClosed(softlyClosed);
    }
  }

  @Override
  public boolean isOpen(long fileId) {
    synchronized (syncObject) {
      OFileClassic fileClassic = files.get(fileId);
      if (fileClassic != null)
        return fileClassic.isOpen();
    }

    return false;
  }

  @Override
  public OPageDataVerificationError[] checkStoredPages(OCommandOutputListener commandOutputListener) {
    final int notificationTimeOut = 5000;
    final List<OPageDataVerificationError> errors = new ArrayList<OPageDataVerificationError>();

    synchronized (syncObject) {
      for (long fileId : files.keySet()) {

        OFileClassic fileClassic = files.get(fileId);

        boolean fileIsCorrect;
        try {

          if (commandOutputListener != null)
            commandOutputListener.onMessage("Flashing file " + fileClassic.getName() + "... ");

          flushFile(fileId);

          if (commandOutputListener != null)
            commandOutputListener.onMessage("Start verification of content of " + fileClassic.getName() + "file ...");

          long time = System.currentTimeMillis();

          long filledUpTo = fileClassic.getFilledUpTo();
          fileIsCorrect = true;

          for (long pos = 0; pos < filledUpTo; pos += pageSize) {
            boolean checkSumIncorrect = false;
            boolean magicNumberIncorrect = false;

            byte[] data = new byte[pageSize];

            fileClassic.read(pos, data, data.length);

            long magicNumber = OLongSerializer.INSTANCE.deserializeNative(data, 0);

            if (magicNumber != MAGIC_NUMBER) {
              magicNumberIncorrect = true;
              if (commandOutputListener != null)
                commandOutputListener.onMessage("Error: Magic number for page " + (pos / pageSize) + " in file "
                    + fileClassic.getName() + " does not much !!!");
              fileIsCorrect = false;
            }

            final int storedCRC32 = OIntegerSerializer.INSTANCE.deserializeNative(data, OLongSerializer.LONG_SIZE);

            final int calculatedCRC32 = OCRCCalculator.calculatePageCrc(data);
            if (storedCRC32 != calculatedCRC32) {
              checkSumIncorrect = true;
              if (commandOutputListener != null)
                commandOutputListener.onMessage("Error: Checksum for page " + (pos / pageSize) + " in file "
                    + fileClassic.getName() + " is incorrect !!!");
              fileIsCorrect = false;
            }

            if (magicNumberIncorrect || checkSumIncorrect)
              errors.add(new OPageDataVerificationError(magicNumberIncorrect, checkSumIncorrect, pos / pageSize, fileClassic
                  .getName()));

            if (commandOutputListener != null && System.currentTimeMillis() - time > notificationTimeOut) {
              time = notificationTimeOut;
              commandOutputListener.onMessage((pos / pageSize) + " pages were processed ...");
            }
          }
        } catch (IOException ioe) {
          if (commandOutputListener != null)
            commandOutputListener.onMessage("Error: Error during processing of file " + fileClassic.getName() + ". "
                + ioe.getMessage());

          fileIsCorrect = false;
        }

        if (!fileIsCorrect) {
          if (commandOutputListener != null)
            commandOutputListener.onMessage("Verification of file " + fileClassic.getName() + " is finished with errors.");
        } else {
          if (commandOutputListener != null)
            commandOutputListener.onMessage("Verification of file " + fileClassic.getName() + " is successfully finished.");
        }
      }

      return errors.toArray(new OPageDataVerificationError[errors.size()]);
    }
  }

  @Override
  public Set<ODirtyPage> logDirtyPagesTable() throws IOException {
    synchronized (syncObject) {
      return writeCache.logDirtyPagesTable();
    }
  }

  @Override
  public void forceSyncStoredChanges() throws IOException {
    synchronized (syncObject) {
      for (OFileClassic fileClassic : files.values())
        fileClassic.synch();
    }
  }

  int getMaxSize() {
    return maxSize;
  }

  LRUList getAm() {
    return readCache.getAm();
  }

  LRUList getA1in() {
    return readCache.getA1in();
  }

  LRUList getA1out() {
    return readCache.getA1out();
  }

  OWoWCache getWriteCache() {
    return writeCache;
  }

  O2QCache getReadCache() {
    return readCache;
  }

  static final class FileLockKey implements Comparable<FileLockKey> {
    final long fileId;
    final long pageIndex;

    FileLockKey(long fileId, long pageIndex) {
      this.fileId = fileId;
      this.pageIndex = pageIndex;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o)
        return true;
      if (o == null || getClass() != o.getClass())
        return false;

      FileLockKey that = (FileLockKey) o;

      if (fileId != that.fileId)
        return false;
      if (pageIndex != that.pageIndex)
        return false;

      return true;
    }

    @Override
    public int hashCode() {
      int result = (int) (fileId ^ (fileId >>> 32));
      result = 31 * result + (int) (pageIndex ^ (pageIndex >>> 32));
      return result;
    }

    @Override
    public int compareTo(FileLockKey otherKey) {
      if (fileId > otherKey.fileId)
        return 1;
      if (fileId < otherKey.fileId)
        return -1;

      if (pageIndex > otherKey.pageIndex)
        return 1;
      if (pageIndex < otherKey.pageIndex)
        return -1;

      return 0;
    }
  }
}
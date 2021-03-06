/*
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.utils;

import com.github.ambry.clustermap.HelixVcrUtil;
import com.github.ambry.server.StatsHeader;
import com.github.ambry.server.StatsReportType;
import com.github.ambry.server.StatsSnapshot;
import com.github.ambry.server.StatsWrapper;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.helix.HelixAdmin;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.zookeeper.zkclient.ZkServer;
import org.apache.helix.zookeeper.zkclient.exception.ZkException;
import org.apache.helix.zookeeper.zkclient.exception.ZkInterruptedException;
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;


/**
 * A class consisting of common util methods useful for tests.
 */
public class TestUtils {
  public static final long TTL_SECS = TimeUnit.DAYS.toSeconds(7);
  public static final Random RANDOM = new Random();
  public static final List<Boolean> BOOLEAN_VALUES = Collections.unmodifiableList(Arrays.asList(true, false));
  private static final int CHECK_INTERVAL_IN_MS = 100;
  private static final String CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  private static final Logger logger = LoggerFactory.getLogger(TestUtils.class);

  /**
   * Return the number of threads currently running with a name containing the given pattern.
   * @param pattern the pattern to compare
   * @return the number of threads currently running with a name containing the given pattern.
   */
  public static int numThreadsByThisName(String pattern) {
    int count = 0;
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (t.getName().contains(pattern)) {
        count++;
      }
    }
    return count;
  }

  /**
   * Return the thread with a name that contains the given name. If there are multiple such threads,
   * return the first such thread.
   * @param pattern the pattern to compare
   * @return the first thread with a name that contains the given pattern.
   */
  public static Thread getThreadByThisName(String pattern) {
    Thread thread = null;
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (t.getName().contains(pattern)) {
        thread = t;
        break;
      }
    }
    return thread;
  }

  /**
   * Return all the threads with a name that contains the given name.
   * @param pattern the pattern to compare
   * @return all the threads with a name that contains the given pattern.
   */
  public static List<Thread> getAllThreadsByThisName(String pattern) {
    List<Thread> threads = new ArrayList<>();
    for (Thread t : Thread.getAllStackTraces().keySet()) {
      if (t.getName().contains(pattern)) {
        threads.add(t);
      }
    }
    return threads;
  }

  /**
   * Gets a byte array of length {@code size} with random bytes.
   * @param size the required length of the random byte array.
   * @return a byte array of length {@code size} with random bytes.
   */
  public static byte[] getRandomBytes(int size) {
    byte[] bytes = new byte[size];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  /**
   * Gets a random element from the given array of elements.
   * @param elements the array of elements.
   * @param <T> the type of the elements.
   */
  public static <T> T getRandomElement(T[] elements) {
    return elements[RANDOM.nextInt(elements.length)];
  }

  /**
   * Awaits on the passed-in {@link CountDownLatch}. If times out throws an exception.
   * @param latch The latch to await on.
   * @param timeoutMs Timeout in millisecond.
   * @throws TimeoutException If awaits for more than the specified time, throw a {@link TimeoutException}.
   * @throws InterruptedException If wait is interrupted.
   */
  public static void awaitLatchOrTimeout(CountDownLatch latch, long timeoutMs)
      throws TimeoutException, InterruptedException {
    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
      throw new TimeoutException("Too long time to complete operation.");
    }
  }

  /**
   * Waits until the HardDeleter thread is in the {@code expectedState} for the specified {@code timeoutMs} time.
   * @param thread the thread whose state needs to be checked.
   * @param expectedState Expected HardDeleter thread state
   * @param timeoutMs time in ms after which the check is considered failed if {@code expectedState} is not reached.
   */
  public static boolean waitUntilExpectedState(Thread thread, Thread.State expectedState, long timeoutMs)
      throws InterruptedException {
    long timeSoFar = 0;
    while (expectedState != thread.getState()) {
      Thread.sleep(10);
      timeSoFar += 10;
      if (timeSoFar >= timeoutMs) {
        return false;
      }
    }
    return true;
  }

  /**
   * Succeed if the {@code body} throws an exception of type {@code exceptionClass}, otherwise fail.
   * @param exceptionClass the type of exception that should occur.
   * @param body the body to execute. This should throw an exception of type {@code exceptionClass}
   * @param errorAction if non-null and the exception class matches, execute this action.
   * @throws Exception when an unexpected exception occurs.
   */
  public static <E extends Exception> void assertException(Class<E> exceptionClass, ThrowingRunnable body,
      ThrowingConsumer<E> errorAction) throws Exception {
    try {
      body.run();
      Assert.fail("Should have thrown exception");
    } catch (Exception e) {
      if (exceptionClass.isInstance(e)) {
        if (errorAction != null) {
          errorAction.accept(exceptionClass.cast(e));
        }
      } else {
        throw e;
      }
    }
  }

  /**
   * Asserts that {@code actual} and {@code expect} are equal. Checks that {@code actual}
   * contains no extra data if {@code checkActualComplete} is {@code true}.
   */
  public static void assertInputStreamEqual(InputStream expect, InputStream actual, int size,
      boolean checkActualComplete) throws IOException {
    byte[] actualBuf = Utils.readBytesFromStream(actual, size);
    if (checkActualComplete) {
      int finalRead = actual.read();
      // some InputStream impls in Ambry return 0 instead of -1 when they end
      assertTrue("Actual stream had more bytes than expected", finalRead == 0 || finalRead == -1);
    }
    byte[] expectBuf = Utils.readBytesFromStream(expect, size);
    assertArrayEquals("Data from actual stream does not match expected", expectBuf, actualBuf);
  }

  /**
   * Verify that the {@code inputStream} satisfies basic properties of the contract.
   * @param inputStream
   * @throws Exception
   */
  public static void validateInputStreamContract(InputStream inputStream) throws Exception {
    int numBytes = 8;
    byte[] bytes = new byte[numBytes];
    assertException(NullPointerException.class, () -> inputStream.read(null, 0, 5), null);
    assertException(IndexOutOfBoundsException.class, () -> inputStream.read(bytes, -1, 5), null);
    assertException(IndexOutOfBoundsException.class, () -> inputStream.read(bytes, 0, -1), null);
    assertException(IndexOutOfBoundsException.class, () -> inputStream.read(bytes, numBytes, 1), null);
    assertException(IndexOutOfBoundsException.class, () -> inputStream.read(bytes, 1, numBytes), null);
    Assert.assertEquals(0, inputStream.read(bytes, 0, 0));
  }

  /**
   * Read through the {@code inputStream} using the no-arg read method until {@code -1} is returned,
   * and verify that the expected number of bytes {@code expectedLength} is read.
   * @param inputStream
   * @param expectedLength
   * @throws IOException
   */
  public static void readInputStreamAndValidateSize(InputStream inputStream, long expectedLength) throws IOException {
    int readVal = 0;
    long numRead = 0;
    do {
      readVal = inputStream.read();
      numRead++;
    } while (readVal != -1);
    numRead--;
    Assert.assertEquals("Unexpected inputstream read length", expectedLength, numRead);
  }

  /**
   * Gets a temporary directory with the given prefix. The directory will be deleted when the virtual machine terminates.
   * @param prefix The prefix for the name of the temporary directory.
   * @return The absolute path of the generated temporary directory.
   * @throws IOException
   */
  public static String getTempDir(String prefix) throws IOException {
    File tempDir = Files.createTempDirectory(prefix + RANDOM.nextInt(1000)).toFile();
    tempDir.deleteOnExit();
    return tempDir.getAbsolutePath();
  }

  /**
   * Generates and returns a random Hex String of the specified size
   * @param size expected key hex string size
   * @return the hex string thus generated
   */
  public static String getRandomKey(int size) {
    StringBuilder sb = new StringBuilder();
    while (sb.length() < size) {
      sb.append(Integer.toHexString(TestUtils.RANDOM.nextInt()));
    }
    sb.setLength(size);
    return sb.toString();
  }

  public static String getRandomString(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
    }
    return sb.toString();
  }

  /**
   * A wrapper class to start and shutdown {@link ZooKeeperServer}. The code is from {@link org.apache.helix.zookeeper.zkclient.ZkServer}.
   * We maintain this class to speed up tests because function calls to NetworkUtil.getLocalHostNames() in
   * {@link org.apache.helix.zookeeper.zkclient.ZkServer} takes time in Mac OS.
   * {@link org.apache.helix.zookeeper.zkclient.ZkServer} calls NetworkUtil.getLocalHostNames() to log and make sure "localhost" is in
   * the list of NetworkUtil.getLocalHostNames(), which are not necessary in tests.
   */
  static class ZkServerWrapper {
    private ZooKeeperServer zk;
    private NIOServerCnxnFactory nioFactory;
    private int port;
    private File dataDir;
    private File dataLogDir;

    public ZkServerWrapper(String dataDir, String logDir, int port) {
      this.dataDir = new File(dataDir);
      this.dataLogDir = new File(logDir);
      this.dataDir.mkdirs();
      this.dataLogDir.mkdirs();
      this.port = port;
    }

    public void start() {
      try {
        zk = new ZooKeeperServer(dataDir, dataLogDir, ZkServer.DEFAULT_TICK_TIME);
        zk.setMinSessionTimeout(ZkServer.DEFAULT_MIN_SESSION_TIMEOUT);
        nioFactory = new NIOServerCnxnFactory();
        int maxClientConnections = 0; // 0 means unlimited
        nioFactory.configure(new InetSocketAddress(port), maxClientConnections);
        nioFactory.startup(zk);
      } catch (IOException e) {
        throw new ZkException("Unable to start single ZooKeeper server.", e);
      } catch (InterruptedException e) {
        throw new ZkInterruptedException(e);
      }
      logger.info("ZooKeeperServer started successfully.");
    }

    public void shutdown() {
      logger.info("Shutting down ZkServer...");
      if (nioFactory != null) {
        nioFactory.shutdown();
        try {
          nioFactory.join();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        nioFactory = null;
      }
      if (zk != null) {
        zk.shutdown();
        zk = null;
      }
      logger.info("Shutting down ZooKeeperServer...done");
    }
  }

  /**
   * A class to initialize and hold information about each Zk Server.
   */
  public static class ZkInfo {
    private String dcName;
    private byte id;
    private int port;
    private String dataDir;
    private String logDir;
    private ZkServerWrapper zkServer;
    private boolean isZkServerStarted = false;

    /**
     * Instantiate by starting a Zk server.
     * @param tempDirPath the temporary directory string to use.
     * @param dcName the name of the datacenter.
     * @param id the id of the datacenter.
     * @param port the port at which this Zk server should run on localhost.
     */
    public ZkInfo(String tempDirPath, String dcName, byte id, int port, boolean start) {
      this.dcName = dcName;
      this.id = id;
      this.port = port;
      this.dataDir = tempDirPath + "/dataDir";
      this.logDir = tempDirPath + "/logDir";
      if (start) {
        startZkServer(port, dataDir, logDir);
      }
    }

    public void startZkServer() {
      if (zkServer != null) {
        zkServer.start();
        isZkServerStarted = true;
        logger.info("ZooKeeperServer started successfully.");
      }
    }

    private void startZkServer(int port, String dataDir, String logDir) {
      // start zookeeper
      zkServer = new ZkServerWrapper(dataDir, logDir, port);
      zkServer.start();
      isZkServerStarted = true;
    }

    public int getPort() {
      return port;
    }

    public void setPort(int port) {
      this.port = port;
    }

    public String getDcName() {
      return dcName;
    }

    public byte getId() {
      return id;
    }

    public void shutdown() {
      if (zkServer != null) {
        zkServer.shutdown();
        isZkServerStarted = false;
      }
    }

    public boolean isZkServerStarted() {
      return isZkServerStarted;
    }
  }

  /**
   * Similar to {@link Runnable}, but able to throw checked exceptions.
   */
  public interface ThrowingRunnable {
    /**
     * Run the action.
     * @throws Exception
     */
    void run() throws Exception;
  }

  /**
   * Periodically check expectedValue and actualValue until timeout.
   * @param expectedValue the expected value.
   * @param expressionToCheck the expression to check.
   * @param timeoutInMs the time out in millisecond.
   * @return true if value match.
   */
  public static <T> boolean checkAndSleep(T expectedValue, Supplier<T> expressionToCheck, int timeoutInMs) {
    long startTime = System.currentTimeMillis();
    try {
      while (!Objects.equals(expectedValue, expressionToCheck.get())) {
        if (System.currentTimeMillis() - startTime >= timeoutInMs) {
          return false;
        }
        Thread.sleep(CHECK_INTERVAL_IN_MS);
      }
    } catch (InterruptedException e) {
      return false;
    }
    return true;
  }

  /**
   * Periodically check boolean condition until timeout.
   * @param conditionToCheck the condition to check.
   * @param timeoutInMs the time out in millisecond.
   * @return true if condition is true before timeout.
   */
  public static boolean checkAndSleep(Supplier<Boolean> conditionToCheck, int timeoutInMs) {
    return checkAndSleep(true, conditionToCheck, timeoutInMs);
  }

  /**
   * Generate certain type of {@link StatsSnapshot} based on the given parameters that would have been produced by a
   * {@link com.github.ambry.store.Store}.
   * @param accountCount number of account entry in the {@link StatsSnapshot}
   * @param containerCount number of container entry in the {@link StatsSnapshot}
   * @param random the random generator to be used
   * @param type the type of stats report to be generated for the store
   * @return the generated store level {@link StatsSnapshot}
   */
  public static StatsSnapshot generateStoreStats(int accountCount, int containerCount, Random random,
      StatsReportType type) {
    Map<String, StatsSnapshot> subMap = new HashMap<>();
    long totalSize = 0;
    for (int i = 0; i < accountCount; i++) {
      Map<String, StatsSnapshot> containerMap = new HashMap<>();
      long subTotalSize = 0;
      for (int j = 0; j < containerCount; j++) {
        long validSize = random.nextInt(2501) + 500;
        subTotalSize += validSize;
        if (type == StatsReportType.ACCOUNT_REPORT) {
          containerMap.put(Utils.statsContainerKey((short) j), new StatsSnapshot(validSize, null));
        } else if (type == StatsReportType.PARTITION_CLASS_REPORT) {
          subMap.put(Utils.partitionClassStatsAccountContainerKey((short) i, (short) j),
              new StatsSnapshot(validSize, null));
        }
      }
      totalSize += subTotalSize;
      if (type == StatsReportType.ACCOUNT_REPORT) {
        subMap.put(Utils.statsAccountKey((short) i), new StatsSnapshot(subTotalSize, containerMap));
      }
    }
    return new StatsSnapshot(totalSize, subMap);
  }

  /**
   * Given a {@link List} of {@link StatsSnapshot}s and a timestamp generate a {@link StatsWrapper} that would have been
   * produced by a node.
   * @param storeSnapshots a {@link List} of store level {@link StatsSnapshot}s.
   * @param timestamp the timestamp to be attached to the generated {@link StatsWrapper}
   * @param type the type of stats report to generate on this node
   * @return the generated node level {@link StatsWrapper}
   */
  public static StatsWrapper generateNodeStats(List<StatsSnapshot> storeSnapshots, long timestamp,
      StatsReportType type) {
    long total = 0;
    int numbOfPartitions = storeSnapshots.size();
    Map<String, StatsSnapshot> partitionMap = new HashMap<>();
    Map<String, StatsSnapshot> partitionClassMap = new HashMap<>();
    String[] PARTITION_CLASS = new String[]{"PartitionClass1", "PartitionClass2"};
    for (int i = 0; i < numbOfPartitions; i++) {
      String partitionIdStr = Utils.statsPartitionKey(i);
      StatsSnapshot partitionSnapshot = storeSnapshots.get(i);
      partitionMap.put(partitionIdStr, partitionSnapshot);
      total += partitionSnapshot.getValue();
      if (type == StatsReportType.PARTITION_CLASS_REPORT) {
        String partitionClassStr = PARTITION_CLASS[i % PARTITION_CLASS.length];
        StatsSnapshot partitionClassSnapshot =
            partitionClassMap.getOrDefault(partitionClassStr, new StatsSnapshot(0L, new HashMap<>()));
        partitionClassSnapshot.setValue(partitionClassSnapshot.getValue() + partitionSnapshot.getValue());
        partitionClassSnapshot.getSubMap().put(partitionIdStr, partitionSnapshot);
        partitionClassMap.put(partitionClassStr, partitionClassSnapshot);
      }
    }
    StatsSnapshot nodeSnapshot = null;
    if (type == StatsReportType.ACCOUNT_REPORT) {
      nodeSnapshot = new StatsSnapshot(total, partitionMap);
    } else if (type == StatsReportType.PARTITION_CLASS_REPORT) {
      nodeSnapshot = new StatsSnapshot(total, partitionClassMap);
    }
    StatsHeader header =
        new StatsHeader(StatsHeader.StatsDescription.STORED_DATA_SIZE, timestamp, numbOfPartitions, numbOfPartitions,
            Collections.emptyList());
    return new StatsWrapper(header, nodeSnapshot);
  }

  /**
   * Create a container storage map. This map will have to levels. First level's key is the account id. Second level's
   * key is the container id. Account ids and container ids are both ranging from 1. The value would the storage usage,
   * which will be greater than or equal to {@code minValue} and less than {@code maxValue}.
   * @param numAccounts The number of accounts in the returned map.
   * @param numContainerPerAccount The number of container under each account
   * @param maxValue The maximum value for storage usage.
   * @param minValue The minimum value for storage usage.
   * @return A map representing the container storage usage.
   */
  public static Map<String, Map<String, Long>> makeStorageMap(int numAccounts, int numContainerPerAccount,
      long maxValue, long minValue) {
    Random random = new Random();
    Map<String, Map<String, Long>> accountMap = new HashMap<>();

    short accountId = 1;
    for (int i = 0; i < numAccounts; i++) {
      Map<String, Long> containerMap = new HashMap<>();
      accountMap.put(String.valueOf(accountId), containerMap);

      short containerId = 1;
      for (int j = 0; j < numContainerPerAccount; j++) {
        long usage = Math.abs(random.nextLong()) % (maxValue - minValue) + minValue;
        containerMap.put(String.valueOf(containerId), usage);
        containerId++;
      }
      accountId++;
    }
    return accountMap;
  }

  /**
   * Generating an account stats snapshot from the given container storage map.
   * @param containerStorageMap The container storage map.
   * @return An account {@link StatsSnapshot}.
   */
  public static StatsSnapshot makeAccountStatsSnapshotFromContainerStorageMap(
      Map<String, Map<String, Long>> containerStorageMap) {
    Map<String, StatsSnapshot> accountSnapshots = new HashMap<>();
    long sumOfAccountUsage = 0;
    for (Map.Entry<String, Map<String, Long>> accountStorageUsageEntry : containerStorageMap.entrySet()) {
      String accountId = accountStorageUsageEntry.getKey();
      Map<String, StatsSnapshot> containerSnapshots = new HashMap<>();
      long sumOfContainerUsage = 0;
      for (Map.Entry<String, Long> containerStorageUsageEntry : accountStorageUsageEntry.getValue().entrySet()) {
        String containerId = containerStorageUsageEntry.getKey();
        long usage = containerStorageUsageEntry.getValue();
        sumOfContainerUsage += usage;
        containerSnapshots.put(Utils.statsContainerKey(Short.valueOf(containerId)), new StatsSnapshot(usage, null));
      }
      accountSnapshots.put(Utils.statsAccountKey(Short.valueOf(accountId)),
          new StatsSnapshot(sumOfContainerUsage, containerSnapshots));
      sumOfAccountUsage += sumOfContainerUsage;
    }
    return new StatsSnapshot(sumOfAccountUsage, accountSnapshots);
  }

  /**
   * Generating a partition class snapshot from the given container storage map.
   * @param containerStorageMap The container storage map.
   * @return A partition class {@link StatsSnapshot}.
   */
  public static StatsSnapshot makePartitionClassSnasphotFromContainerStorageMap(
      Map<String, Map<String, Map<String, Long>>> containerStorageMap) {
    Map<String, StatsSnapshot> partitionClassSnapshots = new HashMap<>();
    long sumOfPartitionClassUsage = 0;
    for (Map.Entry<String, Map<String, Map<String, Long>>> partitionClassUsageEntry : containerStorageMap.entrySet()) {
      String partitionClassName = partitionClassUsageEntry.getKey();
      Map<String, StatsSnapshot> accountContainerSnapshots = new HashMap<>();
      long sumOfAccountContainerUsage = 0;
      Map<String, Map<String, Long>> accountContainerStorageUsage = partitionClassUsageEntry.getValue();
      for (Map.Entry<String, Map<String, Long>> accountContainerEntry : accountContainerStorageUsage.entrySet()) {
        String accountId = accountContainerEntry.getKey();
        for (Map.Entry<String, Long> containerEntry : accountContainerEntry.getValue().entrySet()) {
          String containerId = containerEntry.getKey();
          accountContainerSnapshots.put(
              Utils.partitionClassStatsAccountContainerKey(Short.valueOf(accountId), Short.valueOf(containerId)),
              new StatsSnapshot(containerEntry.getValue(), null));
          sumOfAccountContainerUsage += containerEntry.getValue();
        }
      }
      partitionClassSnapshots.put(partitionClassName,
          new StatsSnapshot(sumOfAccountContainerUsage, accountContainerSnapshots));
      sumOfPartitionClassUsage += sumOfAccountContainerUsage;
    }
    return new StatsSnapshot(sumOfPartitionClassUsage, partitionClassSnapshots);
  }

  /**
   * Generating an aggregated partition class stats. {@code partitionClassNames} provides a list of partition class names
   * and for each partition class name, {@code numAccount} * {@code numContainer} container will be created.
   * @param partitionClassNames The list of partition class names.
   * @param numAccount The number of accounts.
   * @param numContainer The number of containers per account.
   * @return An aggregated partition class {@link StatsSnapshot}.
   */
  public static StatsSnapshot makeAggregatedPartitionClassStats(String[] partitionClassNames, int numAccount,
      int numContainer) {
    Random random = new Random();
    long maxValue = 10000;
    StatsSnapshot finalStats = new StatsSnapshot(0L, new HashMap<>());
    for (String className : partitionClassNames) {
      StatsSnapshot classNameStats = new StatsSnapshot(0L, new HashMap<>());
      finalStats.getSubMap().put(className, classNameStats);
      for (int ia = 0; ia < numAccount; ia++) {
        for (int ic = 0; ic < numContainer; ic++) {
          String key = Utils.partitionClassStatsAccountContainerKey((short) ia, (short) ic);
          classNameStats.getSubMap().put(key, new StatsSnapshot(Math.abs(random.nextLong() % maxValue), null));
        }
      }
      long classNameValue = classNameStats.getSubMap().values().stream().mapToLong(StatsSnapshot::getValue).sum();
      classNameStats.setValue(classNameValue);
    }
    finalStats.setValue(finalStats.getSubMap().values().stream().mapToLong(StatsSnapshot::getValue).sum());
    return finalStats;
  }

  /**
   * A method to verify resources and partitions in src cluster and dest cluster are same.
   */
  public static boolean isSrcDestSync(String srcZkString, String srcClusterName, String destZkString,
      String destClusterName) {

    HelixAdmin srcAdmin = new ZKHelixAdmin(srcZkString);
    Set<String> srcResources = new HashSet<>(srcAdmin.getResourcesInCluster(srcClusterName));
    HelixAdmin destAdmin = new ZKHelixAdmin(destZkString);
    Set<String> destResources = new HashSet<>(destAdmin.getResourcesInCluster(destClusterName));

    for (String resource : srcResources) {
      if (HelixVcrUtil.ignoreResourceKeyWords.stream().anyMatch(resource::contains)) {
        System.out.println("Resource " + resource + " from src cluster is ignored");
        continue;
      }
      if (destResources.contains(resource)) {
        // check if every partition exist.
        Set<String> srcPartitions = srcAdmin.getResourceIdealState(srcClusterName, resource).getPartitionSet();
        Set<String> destPartitions = destAdmin.getResourceIdealState(destClusterName, resource).getPartitionSet();
        for (String partition : srcPartitions) {
          if (!destPartitions.contains(partition)) {
            return false;
          }
        }
      } else {
        return false;
      }
    }
    return true;
  }
}

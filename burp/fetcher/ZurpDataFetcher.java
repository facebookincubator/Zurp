/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.persistence.PersistedList;
import burp.api.montoya.persistence.PersistedObject;
import burp.zurp.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class ZurpDataFetcher {

  /** What a fetch attempt should do to the item's place in the queue. */
  protected enum FetchOutcome {
    STORED,
    FAILED,
    /** Leave queued for a later tick. Callers must bound their own retries. */
    RETRY,
  }

  private final String dataTypeName;
  private final PersistedObject extensionData;
  public PersistedObject fetcherData;
  private final ExecutorService executorService;
  private final ScheduledExecutorService scheduledExecutor;

  /**
   * Authoritative in memory, mirrored to the project file by {@link #persist}. Burp's PersistedList
   * is a write sink only: it hands its own element type back out, so using one as a live collection
   * means every read of an element, and every contains or remove against a String, silently
   * misbehaves. Concurrent because the editor tabs read these from the Swing thread while the pool
   * threads settle.
   */
  public final Set<String> dataQueued;

  public final Set<String> dataFailed;
  public final Set<String> dataStored;

  private boolean dirty;

  /** Items handed to the pool but not yet settled, so a later tick does not refetch them. */
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  public ZurpDataFetcher() {
    this.dataTypeName = getDataTypeName();
    this.extensionData = Zurp.extensionData;
    this.fetcherData = getFetcherData();

    this.dataQueued = loadStringSet("data_queued");
    this.dataFailed = loadStringSet("data_failed");
    this.dataStored = loadStringSet("data_stored");

    this.executorService = Executors.newFixedThreadPool(5); // Using a thread pool of 5 threads

    this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
    scheduledExecutor.scheduleWithFixedDelay(
        () -> {
          // scheduleWithFixedDelay cancels every future run if the task throws, which would
          // silently disable the fetcher for the rest of the session.
          try {
            fetchDataAsynchronously();
          } catch (Exception e) {
            ZurpLog.caught("[" + dataTypeName + "] fetch tick failed", e);
          }
        },
        0,
        10,
        TimeUnit.SECONDS);
  }

  protected abstract String getDataTypeName();

  /** Public so the settings tab can label and key this fetcher's toggle without hardcoding it. */
  public String dataTypeName() {
    return dataTypeName;
  }

  private PersistedObject getFetcherData() {
    PersistedObject fetcherData = extensionData.getChildObject(dataTypeName);
    if (fetcherData == null) {
      fetcherData = PersistedObject.persistedObject();
      extensionData.setChildObject(dataTypeName, fetcherData);
    }
    return fetcherData;
  }

  private Set<String> loadStringSet(String listName) {
    return toStringSet(fetcherData.getStringList(listName));
  }

  /**
   * Read as Object rather than String: a PersistedList yields Burp's own element type, so the cast
   * the compiler inserts for a String loop variable throws ClassCastException on the first item.
   */
  static Set<String> toStringSet(List<?> persisted) {
    Set<String> values = ConcurrentHashMap.newKeySet();
    if (persisted == null) {
      return values;
    }
    for (Object value : persisted) {
      if (value instanceof String) {
        values.add((String) value);
      } else if (value != null) {
        values.add(value.toString());
      }
    }
    return values;
  }

  /** Mirrors the in-memory sets into the project file. Cheap when nothing has changed. */
  private synchronized void persist() {
    if (!dirty) {
      return;
    }
    storeStringSet("data_queued", dataQueued);
    storeStringSet("data_failed", dataFailed);
    storeStringSet("data_stored", dataStored);
    dirty = false;
  }

  private void storeStringSet(String listName, Set<String> values) {
    PersistedList<String> list = PersistedList.persistedStringList();
    // Element at a time rather than addAll, to lean on as little of Burp's List implementation as
    // possible: its toArray is what broke this to begin with.
    for (String value : values) {
      list.add(value);
    }
    fetcherData.setStringList(listName, list);
  }

  public void fetchDataAsynchronously() {
    // Once a tick rather than per settled item, which would rewrite the whole list each time.
    persist();
    // Checked once per tick rather than per item: a fetcher that is off with a full queue would
    // otherwise submit one task per queued item every tick just to have each decline to run.
    if (!isFetchingEnabled()) {
      return;
    }
    // Snapshot under the lock. Burp's HTTP thread appends via addToQueue and the pool threads
    // remove as they settle, so iterating the live set races with both.
    List<String> batch;
    synchronized (this) {
      batch = new ArrayList<>(dataQueued);
    }
    if (batch.isEmpty()) {
      onQueueDrained();
      return;
    }
    ZurpLog.output("fetchDataAsynchronously - " + dataTypeName + " (" + batch.size() + ")");

    for (String dataItem : batch) {
      // A tick fires every 10s whether or not the previous one settled; without this the same
      // item is fetched once per tick until it lands.
      if (!inFlight.add(dataItem)) {
        continue;
      }
      executorService.submit(
          () -> {
            try {
              // addToQueue already excludes anything stored or failed, so no re-check here: on a
              // long queue those two scans are the bulk of the work.
              FetchOutcome outcome = fetchData(dataItem);
              ZurpLog.debug("[" + dataTypeName + "] " + dataItem + " -> " + outcome);
              synchronized (this) { // Synchronizing to prevent concurrent modifications
                if (outcome == FetchOutcome.STORED) {
                  dataStored.add(dataItem);
                } else if (outcome == FetchOutcome.FAILED) {
                  dataFailed.add(dataItem);
                }
                // RETRY stays queued for a later tick; dequeuing it would settle it nowhere.
                if (outcome != FetchOutcome.RETRY) {
                  dataQueued.remove(dataItem);
                }
                dirty = true;
              }
            } catch (Exception e) {
              ZurpLog.caught("[" + dataTypeName + "] fetch failed for " + dataItem, e);
            } finally {
              inFlight.remove(dataItem);
            }
          });
    }
  }

  /**
   * Override to distinguish a transient failure worth retrying from a permanent one. The default
   * keeps the two-state behaviour of {@link #fetchDataAndStore}.
   */
  protected FetchOutcome fetchData(String dataItem) {
    return fetchDataAndStore(dataItem) ? FetchOutcome.STORED : FetchOutcome.FAILED;
  }

  // method the child must implement to get the data and store it
  protected abstract boolean fetchDataAndStore(String dataItem);

  /**
   * Override to skip a whole tick for reasons of your own, e.g. a spent quota or a source that is
   * refusing us; call super to keep honouring the researcher's toggle.
   */
  protected boolean isFetchingEnabled() {
    return ZurpUtils.isFetcherEnabled(dataTypeName);
  }

  /** Called on a tick that found nothing queued, for work that should not compete with fetches. */
  protected void onQueueDrained() {}

  public void shutdown() {
    executorService.shutdown();
    scheduledExecutor.shutdown();
    // Last tick's settles have not been mirrored yet, and nothing else will run to do it.
    persist();
  }

  // Called from Burp's HTTP thread, so it takes the same lock the pool threads use to settle.
  public synchronized void addToQueue(String dataItem) {
    // A disabled fetcher does not bank work: re-enabling it should pick up what the researcher
    // browses next, not replay everything they browsed while it was off.
    if (!ZurpUtils.isFetcherEnabled(dataTypeName)) {
      return;
    }
    if (!dataQueued.contains(dataItem)
        && !dataFailed.contains(dataItem)
        && !dataStored.contains(dataItem)) {
      dataQueued.add(dataItem);
      dirty = true;
    }
  }
}

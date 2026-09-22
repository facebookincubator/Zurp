/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.utilities.json.JsonNode;
import burp.api.montoya.utilities.json.JsonObjectNode;
import burp.models.SpartaFindingModel;
import burp.models.SpartaTarget;
import burp.zurp.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fetches the SPARTA findings disclosed to the researcher for each target seen in proxied traffic —
 * a persisted GraphQL doc id, a GraphQL operation shortname, or an XController name — and caches
 * them in the Burp project file.
 *
 * <p>Once the queue drains, sweeps the unfiltered list endpoint. That single sweep returns every
 * finding disclosed to this researcher, so while it is fresh the per-target queries are answered
 * from the cache instead of the network: a browsing session turns up far more distinct targets than
 * the hourly rate limit would allow us to ask about one at a time.
 */
public class SpartaFindingFetcher extends ZurpDataFetcher {

  private static final String ENDPOINT = "bug_bounty/sparta_findings";

  /** FBStefiBBSpartaFindingListPaginationPolicy caps the page size here. */
  private static final int PAGE_LIMIT = 100;

  private static final int MAX_SWEEP_PAGES = 50;

  /**
   * FBStefiBBResearcherRateLimitingPolicy allows each researcher 1000 hits an hour on this
   * endpoint, and the budget is per endpoint, so this does not compete with the asset lookups. Half
   * is deliberate: it leaves the researcher as much again for their own calls.
   */
  private static final int HOURLY_CALL_BUDGET = 500;

  private static final long CATALOG_TTL_MILLIS = TimeUnit.HOURS.toMillis(1);
  private static final int MAX_ATTEMPTS = 3;

  private static final String KEY_SWEPT_AT = "catalog_swept_at";
  private static final String KEY_SWEEP_COMPLETE = "catalog_complete";

  // The base constructor schedules the first tick before subclass fields are initialised, so these
  // are created on demand rather than in an initialiser.
  private SpartaFindingStore store;
  private HourlyCallBudget budget;
  private SpartaFindingOrganizer organizer;

  /** Set when the endpoint answers 403: the researcher is not on its allowlist, so stop asking. */
  private volatile boolean forbidden;

  private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

  @Override
  protected String getDataTypeName() {
    return "Sparta Findings";
  }

  private synchronized SpartaFindingStore store() {
    if (store == null) {
      store = new SpartaFindingStore(fetcherData);
      budget = new HourlyCallBudget(HOURLY_CALL_BUDGET);
      organizer = new SpartaFindingOrganizer(fetcherData);
    }
    return store;
  }

  private synchronized HourlyCallBudget budget() {
    store();
    return budget;
  }

  private synchronized SpartaFindingOrganizer organizer() {
    store();
    return organizer;
  }

  public void addToQueue(SpartaTarget target) {
    if (target != null) {
      addToQueue(target.key());
    }
  }

  public List<SpartaFindingModel> getFindings(SpartaTarget target) {
    return target == null ? new ArrayList<>() : store().forTarget(target.key());
  }

  /** Everything cached, including findings for products the researcher has not browsed yet. */
  public List<SpartaFindingModel> getAllFindings() {
    return store().all();
  }

  @Override
  protected boolean isFetchingEnabled() {
    return super.isFetchingEnabled() && !forbidden && budget().remaining() > 0;
  }

  @Override
  protected FetchOutcome fetchData(String dataItem) {
    SpartaTarget target = SpartaTarget.fromKey(dataItem);
    if (target == null) {
      return FetchOutcome.FAILED;
    }

    if (catalogIsFresh()) {
      // The sweep already indexed every disclosed finding, including this target's.
      organizer().publish(store().forTarget(dataItem));
      return FetchOutcome.STORED;
    }

    if (!budget().tryAcquire()) {
      return FetchOutcome.RETRY;
    }

    Map<String, String> params = new LinkedHashMap<>();
    params.put("target_id", target.id);
    params.put("target_type", target.type.wireValue());
    params.put("limit", String.valueOf(PAGE_LIMIT));

    GraphApiRequester.ApiResponse response = Zurp.requester.makeGetRequest(ENDPOINT, params);
    if (!response.isOk()) {
      return handleError(response.statusCode, dataItem);
    }

    List<SpartaFindingModel> page = storePage(response.body);
    List<String> ids = new ArrayList<>();
    for (SpartaFindingModel finding : page) {
      ids.add(finding.bbFindingId);
    }
    // Indexed even when empty, so the UI can tell "asked, nothing found" from "never asked".
    store().index(dataItem, ids);
    organizer().publish(page);
    attempts.remove(dataItem);
    ZurpLog.output(
        "[SpartaFindingFetcher] Fetched: " + dataItem + ", " + ids.size() + " finding(s)");
    return FetchOutcome.STORED;
  }

  /** Not the entry point here: {@link #fetchData} is overridden to report the third outcome. */
  @Override
  protected boolean fetchDataAndStore(String dataItem) {
    return fetchData(dataItem) == FetchOutcome.STORED;
  }

  private FetchOutcome handleError(int statusCode, String dataItem) {
    if (statusCode == 403) {
      forbidden = true;
      ZurpLog.output(
          "[SpartaFindingFetcher] Forbidden: this account is not on the SPARTA finding API "
              + "allowlist. No further lookups will be attempted.");
      return FetchOutcome.RETRY;
    }
    if (statusCode == GraphApiRequester.NOT_ATTEMPTED) {
      // No server answered, so this says nothing about the target. Unset credentials are the
      // common case, and they would otherwise blacklist every target seen before they are typed in.
      return FetchOutcome.RETRY;
    }
    int attempt =
        attempts.computeIfAbsent(dataItem, unused -> new AtomicInteger()).incrementAndGet();
    if (attempt >= MAX_ATTEMPTS) {
      attempts.remove(dataItem);
      ZurpLog.output(
          "[SpartaFindingFetcher] Giving up on " + dataItem + " after " + attempt + " attempts");
      return FetchOutcome.FAILED;
    }
    return FetchOutcome.RETRY;
  }

  /** Stores every finding on the page and returns them in the order the API sent them. */
  private List<SpartaFindingModel> storePage(JsonObjectNode body) {
    List<SpartaFindingModel> page = new ArrayList<>();
    if (!body.hasArray("data")) {
      return page;
    }
    for (JsonNode element : body.get("data").asArray().asList()) {
      if (!element.isObject()) {
        continue;
      }
      SpartaFindingModel finding = SpartaFindingStore.parse(element.asObject());
      if (finding == null) {
        continue;
      }
      store().put(finding);
      page.add(finding);
    }
    return page;
  }

  private static String nextCursor(JsonObjectNode body) {
    if (!body.hasObject("paging")) {
      return null;
    }
    JsonObjectNode paging = body.get("paging").asObject();
    if (!paging.hasObject("cursors")) {
      return null;
    }
    JsonObjectNode cursors = paging.get("cursors").asObject();
    if (!cursors.hasString("after")) {
      return null;
    }
    String after = cursors.getString("after");
    return after == null || after.isEmpty() ? null : after;
  }

  private synchronized boolean catalogIsFresh() {
    if (!Boolean.TRUE.equals(fetcherData.getBoolean(KEY_SWEEP_COMPLETE))) {
      return false;
    }
    Long sweptAt = fetcherData.getLong(KEY_SWEPT_AT);
    return sweptAt != null && System.currentTimeMillis() - sweptAt < CATALOG_TTL_MILLIS;
  }

  @Override
  protected void onQueueDrained() {
    // Re-checked rather than inherited from the tick's own check: draining the queue is what turns
    // a 403 into `forbidden`, and the researcher can untick the box from the Swing thread
    // meanwhile.
    if (!isFetchingEnabled() || catalogIsFresh()) {
      return;
    }
    synchronized (this) {
      Long sweptAt = fetcherData.getLong(KEY_SWEPT_AT);
      if (sweptAt != null && System.currentTimeMillis() - sweptAt < CATALOG_TTL_MILLIS) {
        // A sweep that failed part way still stamps the clock, so a broken endpoint cannot be
        // re-swept every ten seconds.
        return;
      }
      fetcherData.setLong(KEY_SWEPT_AT, System.currentTimeMillis());
      fetcherData.setBoolean(KEY_SWEEP_COMPLETE, false);
    }
    sweepCatalog();
  }

  /**
   * Pages the unfiltered list endpoint, indexing each finding under the target it names. Marked
   * complete only when the API runs out of pages, since a truncated sweep is not a full picture.
   *
   * <p>Nothing is sent to the Organizer from here. The sweep is cache warming for endpoints the
   * researcher has not visited, and emptying the whole disclosed catalog into their queue at
   * startup would bury the findings for what they are actually looking at.
   */
  private void sweepCatalog() {
    String after = null;
    int pages = 0;
    int stored = 0;
    boolean complete = false;

    while (pages < MAX_SWEEP_PAGES) {
      if (!budget().tryAcquire()) {
        break;
      }

      Map<String, String> params = new LinkedHashMap<>();
      params.put("limit", String.valueOf(PAGE_LIMIT));
      if (after != null) {
        params.put("after", after);
      }

      GraphApiRequester.ApiResponse response = Zurp.requester.makeGetRequest(ENDPOINT, params);
      if (!response.isOk()) {
        if (response.statusCode == 403) {
          forbidden = true;
        }
        ZurpLog.output(
            "[SpartaFindingFetcher] Catalog sweep stopped on status " + response.statusCode);
        break;
      }

      for (SpartaFindingModel finding : storePage(response.body)) {
        SpartaTarget target = finding.target();
        if (target != null) {
          store().index(target.key(), Collections.singletonList(finding.bbFindingId));
        }
        stored++;
      }

      pages++;
      after = nextCursor(response.body);
      if (after == null) {
        complete = true;
        break;
      }
    }

    synchronized (this) {
      fetcherData.setBoolean(KEY_SWEEP_COMPLETE, complete);
    }
    ZurpLog.output(
        "[SpartaFindingFetcher] Catalog sweep "
            + (complete ? "complete" : "truncated")
            + ": "
            + stored
            + " finding(s) over "
            + pages
            + " page(s), "
            + store().size()
            + " cached in total");
  }
}

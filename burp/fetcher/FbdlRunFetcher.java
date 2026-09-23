/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.utilities.json.JsonNode;
import burp.api.montoya.utilities.json.JsonObjectNode;
import burp.models.FbdlRunModel;
import burp.zurp.Zurp;
import burp.zurp.ZurpLog;
import burp.zurp.ZurpUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Caches the researcher's FBDL runs so their results are available offline and, later, referable
 * from a request.
 *
 * <p>Unlike the other fetchers this one is not fed by proxied traffic: an FBDL run is the
 * researcher's own and only the API knows it exists. The queue is filled by a periodic sweep of the
 * list endpoint, so a run created in the FBDL web UI is picked up just as well as one Zurp created.
 */
public class FbdlRunFetcher extends ZurpDataFetcher {

  private static final String ENDPOINT = "bug_bounty/fbdl_runs";

  /** FBStefiBBFBDLRunListPaginationPolicy takes a limit per page. */
  private static final int PAGE_LIMIT = 100;

  private static final int MAX_SWEEP_PAGES = 50;

  /**
   * FBStefiBBResearcherRateLimitingPolicy allows each researcher 1000 hits an hour on this
   * endpoint, and the budget is per endpoint, so this does not compete with the asset or finding
   * lookups. Half is deliberate: it leaves the researcher as much again for their own calls.
   */
  private static final int HOURLY_CALL_BUDGET = 500;

  private static final long CATALOG_TTL_MILLIS = TimeUnit.HOURS.toMillis(1);

  /**
   * How long a run may stay unsettled before it stops being polled. A run normally finishes in
   * seconds; one still running a day later is stuck server-side, and asking every ten seconds
   * forever would spend the whole budget on it.
   */
  private static final long MAX_PENDING_MILLIS = TimeUnit.DAYS.toMillis(1);

  private static final String KEY_SWEPT_AT = "catalog_swept_at";

  private static final String F_ID = "id";
  private static final String F_FBDL_CODE = "fbdl_code";
  private static final String F_NOTE = "note";

  // The base constructor schedules the first tick before subclass fields are initialised, so these
  // are created on demand rather than in an initialiser.
  private FbdlRunStore store;
  private HourlyCallBudget budget;

  /** Set when the endpoint answers 403: the researcher is not on FBDL_API_ACCESS_GK. */
  private volatile boolean forbidden;

  @Override
  protected String getDataTypeName() {
    return "FBDL Runs";
  }

  private synchronized FbdlRunStore store() {
    if (store == null) {
      store = new FbdlRunStore(fetcherData);
      budget = new HourlyCallBudget(HOURLY_CALL_BUDGET);
    }
    return store;
  }

  private synchronized HourlyCallBudget budget() {
    store();
    return budget;
  }

  /** Every run cached so far, newest first. */
  public List<FbdlRunModel> getRuns() {
    return store().all();
  }

  /** Null when the run has not been fetched. */
  public FbdlRunModel getRun(String runId) {
    return runId == null ? null : store().get(runId);
  }

  /**
   * Submits a script and queues the new run so the normal tick collects its result. Blocks on the
   * network, so it must not be called from the Swing thread.
   *
   * @return the new run id, or null if the server refused it
   */
  public String createRun(String fbdlCode, String note) {
    JsonObjectNode body = JsonObjectNode.jsonObjectNode();
    body.putString(F_FBDL_CODE, fbdlCode);
    body.putString(F_NOTE, note);

    GraphApiRequester.ApiResponse response = Zurp.requester.makePostRequest(ENDPOINT, body);
    if (!response.isOk()) {
      ZurpLog.error("[FbdlRunFetcher] Create failed with status " + response.statusCode);
      return null;
    }

    String runId = response.body.hasString(F_ID) ? response.body.getString(F_ID) : "";
    if (runId.isEmpty()) {
      // Without this the empty id reaches the queue and every tick asks for /fbdl_runs/, whose
      // complaint is about the path rather than about what actually went wrong here.
      ZurpLog.error("[FbdlRunFetcher] Create returned no run id");
      return null;
    }

    ZurpLog.output("[FbdlRunFetcher] Created run " + runId);
    // The run is executing, so there is nothing to fetch yet. Queueing it means the tick polls it
    // to completion under the same budget and retry rules as everything else.
    addToQueue(runId);
    if (!ZurpUtils.isFetcherEnabled(getDataTypeName())) {
      // addToQueue drops it silently when the fetcher is off, which would leave the run executing
      // server-side with nothing ever collecting its result.
      ZurpLog.error(
          "[FbdlRunFetcher] Run "
              + runId
              + " was created but Fetch: "
              + getDataTypeName()
              + " is off, so its result will not be collected.");
    }
    return runId;
  }

  /**
   * Archives a run, which deletes the test assets it minted. Blocks on the network, so it must not
   * be called from the Swing thread.
   *
   * @return the archived run's id, or null if the server refused
   */
  public String archiveRun(String runId) {
    if (runId == null || runId.isEmpty()) {
      return null;
    }

    GraphApiRequester.ApiResponse response =
        Zurp.requester.makePostRequest(ENDPOINT + "/" + runId + "/archive", null);
    if (!response.isOk()) {
      ZurpLog.error(
          "[FbdlRunFetcher] Archive of " + runId + " failed with status " + response.statusCode);
      return null;
    }

    String archivedId = response.body.hasString(F_ID) ? response.body.getString(F_ID) : runId;
    ZurpLog.output("[FbdlRunFetcher] Archived run " + runId + " -> " + archivedId);
    return archivedId;
  }

  @Override
  protected boolean isFetchingEnabled() {
    return super.isFetchingEnabled() && !forbidden && budget().remaining() > 0;
  }

  @Override
  protected FetchOutcome fetchData(String dataItem) {
    if (!budget().tryAcquire()) {
      return FetchOutcome.RETRY;
    }

    GraphApiRequester.ApiResponse response =
        Zurp.requester.makeGetRequest(ENDPOINT + "/" + dataItem, new LinkedHashMap<>());
    if (!response.isOk()) {
      return handleError(response.statusCode, dataItem);
    }

    FbdlRunModel run = FbdlRunStore.parse(response.body);
    if (run == null) {
      // A 200 that does not describe a run will not start doing so on a later tick.
      ZurpLog.output("[FbdlRunFetcher] " + dataItem + " returned no run id, giving up");
      return FetchOutcome.FAILED;
    }

    store().put(run);

    if (!run.status.isTerminal()) {
      // Requeued rather than polled in place: the tick is already a timer, and a Thread.sleep here
      // would hold one of the five pool threads for the life of the run.
      if (isStale(run)) {
        ZurpLog.output(
            "[FbdlRunFetcher] " + dataItem + " still " + run.status.wireValue() + ", giving up");
        return FetchOutcome.FAILED;
      }
      return FetchOutcome.RETRY;
    }

    ZurpLog.output(
        "[FbdlRunFetcher] Fetched: "
            + dataItem
            + ", "
            + run.status.wireValue()
            + ", "
            + run.results.size()
            + " label(s)");
    return FetchOutcome.STORED;
  }

  /** Not the entry point here: {@link #fetchData} is overridden to report the third outcome. */
  @Override
  protected boolean fetchDataAndStore(String dataItem) {
    return fetchData(dataItem) == FetchOutcome.STORED;
  }

  /** Creation time is unix seconds; a run with none reads as 0 and is treated as stale. */
  static boolean isStale(FbdlRunModel run, long nowMillis) {
    return nowMillis - TimeUnit.SECONDS.toMillis(run.creationTime) > MAX_PENDING_MILLIS;
  }

  private static boolean isStale(FbdlRunModel run) {
    return isStale(run, System.currentTimeMillis());
  }

  private FetchOutcome handleError(int statusCode, String dataItem) {
    if (statusCode == 403) {
      forbidden = true;
      ZurpLog.output(
          "[FbdlRunFetcher] Forbidden: this account is not on the FBDL API allowlist. "
              + "No further lookups will be attempted.");
      return FetchOutcome.RETRY;
    }
    if (statusCode == 404) {
      // The run is gone, most likely archived. It will not come back.
      ZurpLog.output("[FbdlRunFetcher] " + dataItem + " no longer exists");
      return FetchOutcome.FAILED;
    }
    // Everything else says nothing about the run: an unset token, a 429, a 5xx. dataFailed is
    // terminal for the life of the project file, so none of them may land there.
    return FetchOutcome.RETRY;
  }

  @Override
  protected void onQueueDrained() {
    // Re-checked rather than inherited from the tick's own check: draining the queue is what turns
    // a 403 into `forbidden`, and the researcher can untick the box from the Swing thread
    // meanwhile.
    if (!isFetchingEnabled()) {
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
    }
    sweepRuns();
  }

  /**
   * Pages the list endpoint, storing each summary and queueing the runs whose detail is missing.
   *
   * <p>The list omits result and exception_stack, so every run needs a second call to be useful.
   * Queueing rather than fetching inline keeps that on the pool threads and under the same budget
   * and retry rules as everything else.
   */
  private void sweepRuns() {
    String after = null;
    int pages = 0;
    int listed = 0;
    int queued = 0;

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
        ZurpLog.output("[FbdlRunFetcher] Run sweep stopped on status " + response.statusCode);
        break;
      }

      for (FbdlRunModel run : storePage(response.body)) {
        listed++;
        if (needsDetail(run)) {
          addToQueue(run.id);
          queued++;
        }
      }

      pages++;
      after = nextCursor(response.body);
      if (after == null) {
        break;
      }
    }

    ZurpLog.output(
        "[FbdlRunFetcher] Run sweep: "
            + listed
            + " listed over "
            + pages
            + " page(s), "
            + queued
            + " queued for detail, "
            + store().size()
            + " cached in total");
  }

  /**
   * A summary is worth a detail call while the run is unsettled, or once it has settled and its
   * detail has never been fetched. A stale unsettled run is skipped so the sweep does not requeue
   * what {@link #fetchData} just gave up on.
   */
  private boolean needsDetail(FbdlRunModel run) {
    if (!run.status.isTerminal()) {
      return !isStale(run);
    }
    return !store().hasDetail(run.id);
  }

  /** Stores every summary on the page and returns them in the order the API sent them. */
  private List<FbdlRunModel> storePage(JsonObjectNode body) {
    List<FbdlRunModel> page = new ArrayList<>();
    if (!body.hasArray("data")) {
      return page;
    }
    for (JsonNode element : body.get("data").asArray().asList()) {
      if (!element.isObject()) {
        continue;
      }
      FbdlRunModel run = FbdlRunStore.parse(element.asObject());
      if (run == null) {
        continue;
      }
      // Summary only: storing it would blank the detail of a run already fetched in full.
      if (!store().hasDetail(run.id)) {
        store().put(run);
      }
      page.add(run);
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
}

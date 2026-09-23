/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.utilities.json.JsonArrayNode;
import burp.api.montoya.utilities.json.JsonNode;
import burp.api.montoya.utilities.json.JsonObjectNode;
import burp.zurp.Zurp;
import burp.zurp.ZurpLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resolves one identifier a researcher saw in traffic — an FBID, a URL, a persisted doc id — to the
 * code assets behind it, through {@code /bug_bounty/assets}.
 *
 * <p>One identifier can name several assets of several kinds at once: a URL carrying an FBID in its
 * query string is both an XController and an Ent. Callers keep the kinds they have a model for.
 *
 * <p>Shared by every fetcher that reads this endpoint rather than one instance each, because the
 * rate limit and the allowlist are the endpoint's, not the caller's.
 */
public final class AssetResolver {

  private static final String ENDPOINT = "bug_bounty/assets";

  private static final String BATCH_ENDPOINT = "bug_bounty/assets/batch";

  /** The endpoint rejects a longer list rather than truncating it. */
  private static final int MAX_BATCH_QUERIES = 200;

  /**
   * FBStefiBBResearcherBatchRateLimitingPolicy allows 100 batches an hour. Half, on the same
   * reasoning as the single-lookup budget -- which still leaves 10,000 resolutions an hour against
   * the 500 a one-at-a-time drain can manage.
   */
  private static final int HOURLY_BATCH_BUDGET = 50;

  /** Asset kinds, spelled as {@code WhitehatAssetUtils} returns them. */
  static final String ENT_OR_NODE = "ent_or_node";

  static final String XCONTROLLER = "xcontroller";

  static final String GRAPHQL = "graphql";

  static final String GRAPH_EDGE = "graph_edge";

  private static final int MAX_ATTEMPTS = 3;

  /**
   * FBStefiBBResearcherRateLimitingPolicy allows each researcher 1000 hits an hour on this
   * endpoint. Half is deliberate: background lookups must leave the researcher as much again for
   * their own calls. Do not raise this to the server limit — a spent budget makes every fetcher
   * that shares it idle, and the researcher's own requests would 429 alongside it.
   */
  private static final int HOURLY_CALL_BUDGET = 500;

  private final HourlyCallBudget budget = new HourlyCallBudget(HOURLY_CALL_BUDGET);

  private final HourlyCallBudget batchBudget = new HourlyCallBudget(HOURLY_BATCH_BUDGET);

  /**
   * What the last batch said about each identifier, consumed by the {@link #lookup} that follows.
   * Most identifiers scraped out of traffic resolve to nothing -- timestamps, doc ids read as
   * object ids -- and the point of the batch is to learn that for 1/200th of a call each.
   */
  private final Map<String, Assets> primed = new ConcurrentHashMap<>();

  /** Set when the endpoint answers 403: this account is not allowlisted, so stop asking. */
  private volatile boolean forbidden;

  private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

  /**
   * How long to stop calling for after the server rate-limits us. Without this a 429 is retried on
   * the next tick, and because a 429 reaches the server it also spends a client slot -- so a queue
   * of any size turns into hundreds of refused calls a minute that can never succeed.
   */
  private static final long RATE_LIMIT_COOLDOWN_MILLIS = 5 * 60 * 1000L;

  /** When the server last rate-limited the single lookup, plus the cooldown. */
  private final AtomicLong rateLimitedUntil = new AtomicLong();

  /**
   * The same for the batch path, kept apart because the two are separate buckets server side --
   * 1000 an hour against 100. Sharing one deadline switched batching off exactly when a spent
   * single-lookup budget made it the only way left to resolve anything.
   */
  private final AtomicLong batchRateLimitedUntil = new AtomicLong();

  /**
   * False once the endpoint has refused us, once this hour's budget is spent, or while backing off
   * from a 429.
   */
  /**
   * False once the endpoint has refused us, or once neither way of reaching it can run: the single
   * lookup and the batch have their own budgets and their own cooldowns, and either alone is enough
   * to make a tick worthwhile.
   */
  boolean isAvailable() {
    return !forbidden && (singleAvailable() || batchAvailable());
  }

  private boolean singleAvailable() {
    return budget.remaining() > 0 && System.currentTimeMillis() >= rateLimitedUntil.get();
  }

  private boolean batchAvailable() {
    return batchBudget.remaining() > 0 && System.currentTimeMillis() >= batchRateLimitedUntil.get();
  }

  /**
   * The outcome of one lookup. {@link #assets} is null when nothing came back, and {@link #outcome}
   * then says whether the identifier is worth asking about again; a caller that got assets decides
   * that for itself.
   */
  static final class Lookup {
    final ZurpDataFetcher.FetchOutcome outcome;
    final Assets assets;

    private Lookup(ZurpDataFetcher.FetchOutcome outcome, Assets assets) {
      this.outcome = outcome;
      this.assets = assets;
    }
  }

  /** What one identifier resolved to. */
  static final class Assets {
    private final Map<String, List<String>> byKind = new LinkedHashMap<>();

    /** True when the identifier named nothing at all, which is the common case for scraped ids. */
    boolean isEmpty() {
      return byKind.isEmpty();
    }

    /** The object's vanity name, or "" — most identifiers have none. */
    private String objectName = "";

    /** Ignores either half being absent: a kind with no name names nothing. */
    void add(String kind, String name) {
      if (kind == null || kind.isEmpty() || name == null || name.isEmpty()) {
        return;
      }
      byKind.computeIfAbsent(kind, unused -> new ArrayList<>()).add(name);
    }

    void setObjectName(String name) {
      objectName = name == null ? "" : name;
    }

    String objectName() {
      return objectName;
    }

    /**
     * The one asset of this kind to keep, or "" when the identifier named none. Every model here is
     * single valued, so a second asset of the same kind is reported rather than shown.
     */
    String one(String kind, String context) {
      List<String> names = byKind.get(kind);
      if (names == null || names.isEmpty()) {
        return "";
      }
      if (names.size() > 1) {
        ZurpLog.debug(context + " named " + names.size() + " " + kind + " assets: " + names);
      }
      return names.get(0);
    }
  }

  /**
   * Resolves everything in {@code queries} in as few calls as the batch endpoint allows, so the
   * {@link #lookup} that follows for each one can answer without a call of its own. Best effort:
   * anything not primed simply falls through to a single lookup.
   *
   * <p>A primed answer is complete except for {@code object_name}, which the batch endpoint omits
   * because the vanity costs a profile-alias read each. A caller that needs it still pays for one
   * lookup -- but only for the identifiers that resolved to something, which is the small minority.
   */
  void prime(List<String> queries) {
    if (forbidden || !batchAvailable() || queries == null || queries.isEmpty()) {
      return;
    }
    for (int from = 0; from < queries.size(); from += MAX_BATCH_QUERIES) {
      List<String> chunk =
          queries.subList(from, Math.min(from + MAX_BATCH_QUERIES, queries.size()));
      if (!batchBudget.tryAcquire()) {
        return;
      }
      JsonObjectNode request = JsonObjectNode.jsonObjectNode();
      JsonArrayNode wanted = JsonArrayNode.jsonArrayNode();
      for (String query : chunk) {
        wanted.addString(query);
      }
      request.put("queries", wanted);

      GraphApiRequester.ApiResponse response =
          Zurp.requester.makePostRequest(BATCH_ENDPOINT, request);
      if (!response.isOk()) {
        if (response.statusCode == GraphApiRequester.NOT_ATTEMPTED) {
          batchBudget.release();
        }
        // Deliberately not recorded against the identifiers: the single lookups that follow will
        // meet the same refusal and settle them, so a batch failure costs a batch and nothing else.
        outcomeFor(response.statusCode, "batch of " + chunk.size());
        return;
      }
      primeFrom(response.body);
    }
  }

  private void primeFrom(JsonObjectNode body) {
    if (!body.hasArray("results")) {
      ZurpLog.output(
          "[AssetResolver] Batch response carried no results array: "
              + GraphApiRequester.excerpt(body.toJsonString()));
      return;
    }
    for (JsonNode element : body.get("results").asArray().asList()) {
      if (!element.isObject()) {
        continue;
      }
      JsonObjectNode entry = element.asObject();
      String query = string(entry, "query");
      if (!query.isEmpty()) {
        primed.put(query, parse(entry));
      }
    }
  }

  /** Never throws: a caller on a fetch tick gets an outcome, not an exception. */
  Lookup lookup(String query) {
    return lookup(query, true);
  }

  /**
   * @param needsObjectName whether the caller reads {@link Assets#objectName}, which only the
   *     single lookup returns. When it does not, a primed answer is served whole and costs nothing.
   */
  Lookup lookup(String query, boolean needsObjectName) {
    // Peeked rather than taken: a caller that needs object_name has to ask again anyway, and
    // evicting here would throw the batch answer away and make the next tick re-batch for it.
    Assets fromBatch = primed.get(query);
    if (fromBatch != null && (fromBatch.isEmpty() || !needsObjectName)) {
      primed.remove(query);
      return new Lookup(ZurpDataFetcher.FetchOutcome.STORED, fromBatch);
    }
    if (forbidden) {
      return new Lookup(ZurpDataFetcher.FetchOutcome.RETRY, null);
    }
    // Checked here and not only per tick: a tick submits the whole queue at once, so without this
    // the first 429 would set the cooldown while every task already in flight went out anyway.
    if (System.currentTimeMillis() < rateLimitedUntil.get()) {
      return new Lookup(ZurpDataFetcher.FetchOutcome.RETRY, null);
    }
    if (!budget.tryAcquire()) {
      return new Lookup(ZurpDataFetcher.FetchOutcome.RETRY, null);
    }

    Map<String, String> params = new LinkedHashMap<>();
    params.put("q", query);

    GraphApiRequester.ApiResponse response = Zurp.requester.makeGetRequest(ENDPOINT, params);
    if (!response.isOk()) {
      if (response.statusCode == GraphApiRequester.NOT_ATTEMPTED) {
        // Nothing reached the server, so nothing was spent. Without the refund an unset token
        // drains the whole hour on requeues and lookups stay dead after one is finally entered.
        // A 429 or 403 did reach it and did count, so those keep the slot.
        budget.release();
      }
      return new Lookup(outcomeFor(response.statusCode, query), null);
    }

    attempts.remove(query);
    // The single answer supersedes anything the batch said, and leaving it would keep a stale copy
    // for an identifier that is now settled.
    primed.remove(query);
    return new Lookup(ZurpDataFetcher.FetchOutcome.STORED, parse(response.body));
  }

  private ZurpDataFetcher.FetchOutcome outcomeFor(int statusCode, String query) {
    if (statusCode == 403) {
      forbidden = true;
      ZurpLog.output(
          "[AssetResolver] Forbidden: this account is not on the bug bounty research API "
              + "allowlist. No further asset lookups will be attempted.");
      return ZurpDataFetcher.FetchOutcome.RETRY;
    }
    if (statusCode == 429) {
      // The researcher's own hourly allowance is gone, not this identifier's fault. Back off
      // rather than retry on the next tick: the client budget resets when the extension reloads
      // and the server's does not, so the two disagree and only the server's answer counts.
      long now = System.currentTimeMillis();
      // Whoever moves the deadline from a lapsed value is the one episode's start, so only that
      // thread says so. A plain read-then-write would let every task in flight log it.
      long previous = rateLimitedUntil.getAndSet(now + RATE_LIMIT_COOLDOWN_MILLIS);
      if (previous <= now) {
        ZurpLog.output(
            "[AssetResolver] Rate limited by the server. Pausing asset lookups for "
                + (RATE_LIMIT_COOLDOWN_MILLIS / 60000)
                + " minutes.");
      }
      return ZurpDataFetcher.FetchOutcome.RETRY;
    }
    if (statusCode == GraphApiRequester.NOT_ATTEMPTED) {
      // Says nothing about the identifier. Unset credentials are common, and would otherwise
      // blacklist every identifier seen while they lasted.
      return ZurpDataFetcher.FetchOutcome.RETRY;
    }
    int attempt = attempts.computeIfAbsent(query, unused -> new AtomicInteger()).incrementAndGet();
    if (attempt < MAX_ATTEMPTS) {
      return ZurpDataFetcher.FetchOutcome.RETRY;
    }
    attempts.remove(query);
    ZurpLog.output(
        "[AssetResolver] Giving up on "
            + query
            + " after "
            + attempt
            + " attempts (last status "
            + statusCode
            + ")");
    return ZurpDataFetcher.FetchOutcome.FAILED;
  }

  private static Assets parse(JsonObjectNode body) {
    Assets assets = new Assets();
    if (body.hasArray("assets")) {
      for (JsonNode element : body.get("assets").asArray().asList()) {
        if (element.isObject()) {
          JsonObjectNode asset = element.asObject();
          assets.add(string(asset, "type"), string(asset, "name"));
        }
      }
    } else {
      // The endpoint declares `assets` required, so its absence is drift rather than a miss. Said
      // out loud because the caller reads an empty result as terminal and blacklists the id.
      ZurpLog.output(
          "[AssetResolver] Response carried no assets array: "
              + GraphApiRequester.excerpt(body.toJsonString()));
    }
    assets.setObjectName(string(body, "object_name"));
    return assets;
  }

  private static String string(JsonObjectNode json, String field) {
    return json.hasString(field) ? json.getString(field) : "";
  }
}

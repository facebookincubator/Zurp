/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

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

  /** Asset kinds, spelled as {@code WhitehatAssetUtils} returns them. */
  static final String ENT_OR_NODE = "ent_or_node";

  static final String XCONTROLLER = "xcontroller";

  private static final int MAX_ATTEMPTS = 3;

  /**
   * FBStefiBBResearcherRateLimitingPolicy allows each researcher 1000 hits an hour on this
   * endpoint. Half is deliberate: background lookups must leave the researcher as much again for
   * their own calls. Do not raise this to the server limit — a spent budget makes every fetcher
   * that shares it idle, and the researcher's own requests would 429 alongside it.
   */
  private static final int HOURLY_CALL_BUDGET = 500;

  private final HourlyCallBudget budget = new HourlyCallBudget(HOURLY_CALL_BUDGET);

  /** Set when the endpoint answers 403: this account is not allowlisted, so stop asking. */
  private volatile boolean forbidden;

  private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

  /** False once the endpoint has refused us, or once this hour's budget is spent. */
  boolean isAvailable() {
    return !forbidden && budget.remaining() > 0;
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

  /** Never throws: a caller on a fetch tick gets an outcome, not an exception. */
  Lookup lookup(String query) {
    if (forbidden) {
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
    if (statusCode == GraphApiRequester.NOT_ATTEMPTED || statusCode == 429) {
      // Says nothing about the identifier. Unset credentials and a spent quota are both common,
      // and either would otherwise blacklist every identifier seen while it lasted.
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

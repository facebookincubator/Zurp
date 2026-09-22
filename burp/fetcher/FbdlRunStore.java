/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.persistence.PersistedList;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.utilities.json.JsonNode;
import burp.api.montoya.utilities.json.JsonObjectNode;
import burp.models.FbdlRunModel;
import burp.models.FbdlRunStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds FBDL runs in the Burp project file, one child object per run keyed by run id.
 *
 * <p>A run's results are a keyed child object rather than a list, so a single label reads back in
 * one call: the placeholder rewriter will want that on the request path. Server order is not a
 * property of a keyed object, so the labels are additionally kept as an ordered list for display.
 */
class FbdlRunStore {

  private static final String RUNS = "runs";
  private static final String RESULTS = "results";
  private static final String RESULT_LABELS = "result_labels";

  private static final String F_ID = "id";
  private static final String F_RUN_STATUS = "run_status";
  private static final String F_CREATION_TIME = "creation_time";
  private static final String F_RUN_CODE = "run_code";
  private static final String F_NOTE = "note";
  private static final String F_EXCEPTION_STACK = "exception_stack";
  private static final String F_RESULT = "result";
  private static final String F_LABEL = "label";
  private static final String F_VALUE = "value";

  private final PersistedObject runs;

  FbdlRunStore(PersistedObject fetcherData) {
    this.runs = childObject(fetcherData, RUNS);
  }

  private static PersistedObject childObject(PersistedObject parent, String name) {
    PersistedObject child = parent.getChildObject(name);
    if (child == null) {
      child = PersistedObject.persistedObject();
      parent.setChildObject(name, child);
    }
    return child;
  }

  /** Null when the payload is missing the id everything else is keyed on. */
  static FbdlRunModel parse(JsonObjectNode json) {
    String id = string(json, F_ID);
    if (id.isEmpty()) {
      return null;
    }
    return new FbdlRunModel(
        id,
        FbdlRunStatus.fromWireValue(string(json, F_RUN_STATUS)),
        json.hasNumber(F_CREATION_TIME) ? json.getLong(F_CREATION_TIME) : 0L,
        string(json, F_RUN_CODE),
        string(json, F_NOTE),
        parseResults(json),
        string(json, F_EXCEPTION_STACK));
  }

  /** Absent on a list response, which sends summaries only. */
  private static Map<String, String> parseResults(JsonObjectNode json) {
    Map<String, String> results = new LinkedHashMap<>();
    if (!json.hasArray(F_RESULT)) {
      return results;
    }
    for (JsonNode element : json.get(F_RESULT).asArray().asList()) {
      if (!element.isObject()) {
        continue;
      }
      JsonObjectNode entry = element.asObject();
      String label = string(entry, F_LABEL);
      if (!label.isEmpty()) {
        results.put(label, string(entry, F_VALUE));
      }
    }
    return results;
  }

  private static String string(JsonObjectNode json, String field) {
    return json.hasString(field) ? json.getString(field) : "";
  }

  synchronized void put(FbdlRunModel run) {
    PersistedObject stored = PersistedObject.persistedObject();
    stored.setString(F_ID, run.id);
    stored.setString(F_RUN_STATUS, run.status.wireValue());
    stored.setLong(F_CREATION_TIME, run.creationTime);
    stored.setString(F_RUN_CODE, run.runCode);
    stored.setString(F_NOTE, run.note);
    stored.setString(F_EXCEPTION_STACK, run.exceptionStack);

    PersistedObject results = PersistedObject.persistedObject();
    PersistedList<String> labels = PersistedList.persistedStringList();
    for (Map.Entry<String, String> result : run.results.entrySet()) {
      results.setString(result.getKey(), result.getValue());
      labels.add(result.getKey());
    }
    stored.setChildObject(RESULTS, results);
    stored.setStringList(RESULT_LABELS, labels);

    runs.setChildObject(run.id, stored);
  }

  synchronized FbdlRunModel get(String runId) {
    PersistedObject stored = runs.getChildObject(runId);
    if (stored == null) {
      return null;
    }
    Long creationTime = stored.getLong(F_CREATION_TIME);
    return new FbdlRunModel(
        stored.getString(F_ID),
        FbdlRunStatus.fromWireValue(stored.getString(F_RUN_STATUS)),
        creationTime == null ? 0L : creationTime,
        stored.getString(F_RUN_CODE),
        stored.getString(F_NOTE),
        readResults(stored),
        stored.getString(F_EXCEPTION_STACK));
  }

  private static Map<String, String> readResults(PersistedObject stored) {
    Map<String, String> results = new LinkedHashMap<>();
    PersistedObject values = stored.getChildObject(RESULTS);
    if (values == null) {
      return results;
    }
    for (String label : toStringList(stored.getStringList(RESULT_LABELS))) {
      results.put(label, values.getString(label));
    }
    return results;
  }

  /**
   * Reads as Object and keeps the order, which {@link ZurpDataFetcher#toStringSet} cannot: a
   * PersistedList yields Burp's own element type, so a String loop variable throws
   * ClassCastException on the first item.
   */
  private static List<String> toStringList(List<?> persisted) {
    List<String> values = new ArrayList<>();
    if (persisted == null) {
      return values;
    }
    for (Object value : persisted) {
      if (value != null) {
        values.add(value.toString());
      }
    }
    return values;
  }

  /** Whether the full run has been fetched, as opposed to just the summary from a list. */
  synchronized boolean hasResults(String runId) {
    PersistedObject stored = runs.getChildObject(runId);
    return stored != null && !toStringList(stored.getStringList(RESULT_LABELS)).isEmpty();
  }

  /** Newest first, which is the order the FBDL tab will want. */
  synchronized List<FbdlRunModel> all() {
    List<FbdlRunModel> result = new ArrayList<>();
    for (String id : runs.childObjectKeys()) {
      FbdlRunModel run = get(id);
      if (run != null) {
        result.add(run);
      }
    }
    result.sort((left, right) -> Long.compare(right.creationTime, left.creationTime));
    return result;
  }

  synchronized int size() {
    return runs.childObjectKeys().size();
  }
}

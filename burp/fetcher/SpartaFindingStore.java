/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.persistence.PersistedList;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.utilities.json.JsonObjectNode;
import burp.models.SpartaFindingModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Holds disclosed findings in the Burp project file: one copy of each finding body keyed by
 * bb_finding_id, plus an index from target key to the ids raised against it. The per-target fetches
 * and the unfiltered catalog sweep both write here, so they share a single copy of each finding.
 */
class SpartaFindingStore {

  private static final String FINDINGS = "findings";
  private static final String BY_TARGET = "by_target";
  private static final String FINDING_IDS = "finding_ids";

  private static final String F_BB_FINDING_ID = "bb_finding_id";
  private static final String F_TITLE = "title";
  private static final String F_SUMMARY = "summary";
  private static final String F_PRIORITY = "priority";
  private static final String F_TARGET_ID = "target_id";
  private static final String F_TARGET_TYPE = "target_type";
  private static final String F_POC_DOC_ID = "poc_doc_id";
  private static final String F_POC_VARIABLES_JSON = "poc_variables_json";
  private static final String F_POC_PLACEHOLDERS_JSON = "poc_placeholders_json";
  private static final String F_PUBLISHED_AT = "published_at";

  private final PersistedObject findings;
  private final PersistedObject byTarget;

  SpartaFindingStore(PersistedObject fetcherData) {
    this.findings = childObject(fetcherData, FINDINGS);
    this.byTarget = childObject(fetcherData, BY_TARGET);
  }

  private static PersistedObject childObject(PersistedObject parent, String name) {
    PersistedObject child = parent.getChildObject(name);
    if (child == null) {
      child = PersistedObject.persistedObject();
      parent.setChildObject(name, child);
    }
    return child;
  }

  /** Null when the payload is missing the one field everything else is keyed on. */
  static SpartaFindingModel parse(JsonObjectNode json) {
    String id = string(json, F_BB_FINDING_ID);
    if (id.isEmpty()) {
      return null;
    }
    return new SpartaFindingModel(
        id,
        string(json, F_TITLE),
        string(json, F_SUMMARY),
        string(json, F_PRIORITY),
        string(json, F_TARGET_ID),
        string(json, F_TARGET_TYPE),
        string(json, F_POC_DOC_ID),
        string(json, F_POC_VARIABLES_JSON),
        string(json, F_POC_PLACEHOLDERS_JSON),
        json.hasNumber(F_PUBLISHED_AT) ? json.getLong(F_PUBLISHED_AT) : 0L);
  }

  private static String string(JsonObjectNode json, String field) {
    return json.hasString(field) ? json.getString(field) : "";
  }

  synchronized void put(SpartaFindingModel finding) {
    PersistedObject stored = PersistedObject.persistedObject();
    stored.setString(F_BB_FINDING_ID, finding.bbFindingId);
    stored.setString(F_TITLE, finding.title);
    stored.setString(F_SUMMARY, finding.summary);
    stored.setString(F_PRIORITY, finding.priority);
    stored.setString(F_TARGET_ID, finding.targetId);
    stored.setString(F_TARGET_TYPE, finding.targetType);
    stored.setString(F_POC_DOC_ID, finding.pocDocId);
    stored.setString(F_POC_VARIABLES_JSON, finding.pocVariablesJson);
    stored.setString(F_POC_PLACEHOLDERS_JSON, finding.pocPlaceholdersJson);
    stored.setLong(F_PUBLISHED_AT, finding.publishedAt);
    findings.setChildObject(finding.bbFindingId, stored);
  }

  /**
   * Unions rather than replaces: a per-target query returns only its first page, so it must not
   * drop ids the catalog sweep already indexed under the same target.
   */
  synchronized void index(String targetKey, List<String> findingIds) {
    Set<String> merged = new LinkedHashSet<>();
    PersistedObject entry = byTarget.getChildObject(targetKey);
    if (entry != null) {
      PersistedList<String> existing = entry.getStringList(FINDING_IDS);
      if (existing != null) {
        merged.addAll(existing);
      }
    } else {
      entry = PersistedObject.persistedObject();
    }
    merged.addAll(findingIds);

    PersistedList<String> ids = PersistedList.persistedStringList();
    ids.addAll(merged);
    entry.setStringList(FINDING_IDS, ids);
    byTarget.setChildObject(targetKey, entry);
  }

  synchronized SpartaFindingModel get(String bbFindingId) {
    PersistedObject stored = findings.getChildObject(bbFindingId);
    if (stored == null) {
      return null;
    }
    Long publishedAt = stored.getLong(F_PUBLISHED_AT);
    return new SpartaFindingModel(
        stored.getString(F_BB_FINDING_ID),
        stored.getString(F_TITLE),
        stored.getString(F_SUMMARY),
        stored.getString(F_PRIORITY),
        stored.getString(F_TARGET_ID),
        stored.getString(F_TARGET_TYPE),
        stored.getString(F_POC_DOC_ID),
        stored.getString(F_POC_VARIABLES_JSON),
        stored.getString(F_POC_PLACEHOLDERS_JSON),
        publishedAt == null ? 0L : publishedAt);
  }

  synchronized List<SpartaFindingModel> forTarget(String targetKey) {
    List<SpartaFindingModel> result = new ArrayList<>();
    PersistedObject entry = byTarget.getChildObject(targetKey);
    if (entry == null) {
      return result;
    }
    PersistedList<String> ids = entry.getStringList(FINDING_IDS);
    if (ids == null) {
      return result;
    }
    for (String id : ids) {
      SpartaFindingModel finding = get(id);
      if (finding != null) {
        result.add(finding);
      }
    }
    return result;
  }

  /**
   * Newest first. Sorted here rather than taken as read: the list endpoint orders per researcher,
   * not by publication date.
   */
  synchronized List<SpartaFindingModel> all() {
    List<SpartaFindingModel> result = new ArrayList<>();
    for (String id : findings.childObjectKeys()) {
      SpartaFindingModel finding = get(id);
      if (finding != null) {
        result.add(finding);
      }
    }
    result.sort((left, right) -> Long.compare(right.publishedAt, left.publishedAt));
    return result;
  }

  synchronized int size() {
    return findings.childObjectKeys().size();
  }
}

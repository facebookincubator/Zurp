/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import burp.models.SpartaTarget;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the identifiers {@link SpartaTargetExtractor} already pulls out of a request into queries
 * the asset endpoint can answer.
 *
 * <p>The two APIs want the same identifiers spelled differently, so this adapts rather than
 * re-extracts. A second set of regexes over the same request would drift from the first.
 */
public final class MetaAssetQueries {

  private static final String DOC_ID_PREFIX = "doc_id=";

  private MetaAssetQueries() {}

  /** Asset queries for the identifiers in {@code targets}, in the order they were found. */
  public static List<String> fromTargets(Set<SpartaTarget> targets) {
    List<String> queries = new ArrayList<>();
    if (targets == null) {
      return queries;
    }
    Set<String> seen = new LinkedHashSet<>();
    for (SpartaTarget target : targets) {
      String query = queryFor(target);
      if (query != null && seen.add(query)) {
        queries.add(query);
      }
    }
    return queries;
  }

  /** Null when the identifier is not one the asset endpoint can resolve. */
  static String queryFor(SpartaTarget target) {
    if (target == null || target.id == null || target.id.isEmpty()) {
      return null;
    }
    switch (target.type) {
      case PUBLISHED_DOC_ID:
        // The prefix has to survive into the query: the endpoint only reads the digits as a
        // document id when they follow doc_id, and a bare number is read as an object id.
        return DOC_ID_PREFIX + target.id;
      case ENDPOINT_NAME:
        return isGraphqlOperation(target.id) ? target.id : null;
      default:
        return null;
    }
  }

  /**
   * The asset endpoint only treats a name as GraphQL when it ends this way. SPARTA has no such
   * rule, so its extractor also yields controller names and other shortnames, which resolve to
   * nothing here and would spend budget finding that out.
   */
  static boolean isGraphqlOperation(String name) {
    return name.endsWith("Query") || name.endsWith("Mutation");
  }
}

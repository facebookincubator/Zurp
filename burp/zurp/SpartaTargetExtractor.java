/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import burp.models.SpartaTarget;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the identifiers SPARTA keys its findings on out of a Meta request: the persisted GraphQL
 * document id, and the operation shortname www echoes back as fb_api_req_friendly_name.
 *
 * <p>Both travel as urlencoded form fields from the web clients, as query params from a few
 * GET-shaped callers, and as JSON members from the mobile clients, so each key is matched wherever
 * it appears rather than by parsing one body shape.
 */
public final class SpartaTargetExtractor {

  private static final Pattern DOC_ID =
      Pattern.compile("(?:^|[?&\"])doc_id\"?\\s*[=:]\\s*\"?(\\d{5,})");

  private static final Pattern FRIENDLY_NAME =
      Pattern.compile(
          "(?:^|[?&\"])fb_api_req_friendly_name\"?\\s*[=:]\\s*\"?([A-Za-z0-9_]{3,128})");

  // A request body large enough to matter here is a file upload, which carries neither key.
  private static final int MAX_SCAN_LENGTH = 256 * 1024;

  private SpartaTargetExtractor() {}

  public static Set<SpartaTarget> extract(String url, String body) {
    Set<SpartaTarget> targets = new LinkedHashSet<>();
    collect(targets, url);
    collect(targets, body);
    return targets;
  }

  private static void collect(Set<SpartaTarget> targets, String text) {
    if (text == null || text.isEmpty()) {
      return;
    }
    if (text.length() > MAX_SCAN_LENGTH) {
      text = text.substring(0, MAX_SCAN_LENGTH);
    }

    Matcher docIds = DOC_ID.matcher(text);
    while (docIds.find()) {
      targets.add(SpartaTarget.publishedDocId(docIds.group(1)));
    }

    Matcher names = FRIENDLY_NAME.matcher(text);
    while (names.find()) {
      targets.add(SpartaTarget.endpointName(names.group(1)));
    }
  }
}

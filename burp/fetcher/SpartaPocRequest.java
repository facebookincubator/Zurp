/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.models.SpartaFindingModel;
import burp.models.SpartaTarget;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Turns a disclosed SPARTA finding into the GraphQL call its proof of concept describes.
 *
 * <p>The disclosed variables hold {@code {{TOKEN}}} placeholders rather than identifiers, so what
 * comes out of here reproduces nothing until the researcher substitutes values of their own. That
 * is why the tokens are left unescaped in the body and repeated in the annotation.
 */
public final class SpartaPocRequest {

  /** Every target SPARTA scans is a www GraphQL document, so there is one endpoint to call. */
  private static final String GRAPHQL_URL = "https://www.facebook.com/api/graphql/";

  /** Priority values as the API sends them. */
  private static final String HIGH = "high";

  private static final String MEDIUM = "medium";

  private SpartaPocRequest() {}

  /** Null when the finding names neither a document id nor an operation to call. */
  public static HttpRequest build(SpartaFindingModel finding) {
    String body = body(finding);
    if (body == null) {
      return null;
    }
    return HttpRequest.httpRequestFromUrl(GRAPHQL_URL)
        .withMethod("POST")
        .withHeader("Content-Type", "application/x-www-form-urlencoded")
        .withBody(body);
  }

  public static Annotations annotations(SpartaFindingModel finding) {
    return Annotations.annotations(notes(finding), highlight(finding));
  }

  /**
   * The form body www expects, or null when there is nothing to call.
   *
   * <p>fb_dtsg is left as a placeholder rather than filled in here: {@link burp.csrf.CsrfRewriter}
   * substitutes a live token when the request is finally sent, which is the only moment one is
   * fresh.
   */
  static String body(SpartaFindingModel finding) {
    if (finding == null) {
      return null;
    }
    String docId = docId(finding);
    String friendlyName = friendlyName(finding);
    if (docId == null && friendlyName == null) {
      return null;
    }

    // Ordered as the web client sends them, so the request reads like the traffic around it.
    StringBuilder body = new StringBuilder("fb_dtsg={{fb_dtsg}}");
    if (friendlyName != null) {
      body.append("&fb_api_req_friendly_name=").append(encode(friendlyName));
    }
    body.append("&variables=").append(encodeVariables(finding.pocVariablesJson));
    if (docId != null) {
      body.append("&doc_id=").append(encode(docId));
    }
    return body.toString();
  }

  /** What the Organizer shows in its notes column, since the finding's prose is not in the body. */
  static String notes(SpartaFindingModel finding) {
    StringBuilder notes = new StringBuilder("SPARTA");
    if (has(finding.priority)) {
      notes.append(' ').append(finding.priority.toUpperCase(Locale.ROOT));
    }
    if (has(finding.title)) {
      notes.append(": ").append(finding.title);
    }
    if (has(finding.bbFindingId)) {
      notes.append(" [").append(finding.bbFindingId).append(']');
    }
    if (has(finding.pocPlaceholdersJson)) {
      notes.append("\nSubstitute before sending: ").append(finding.pocPlaceholdersJson);
    }
    return notes.toString();
  }

  private static HighlightColor highlight(SpartaFindingModel finding) {
    if (HIGH.equalsIgnoreCase(finding.priority)) {
      return HighlightColor.RED;
    }
    return MEDIUM.equalsIgnoreCase(finding.priority) ? HighlightColor.ORANGE : HighlightColor.NONE;
  }

  /**
   * The PoC's own document id, falling back to the target the finding was raised against when the
   * PoC names an operation instead.
   */
  private static String docId(SpartaFindingModel finding) {
    if (isDocId(finding.pocDocId)) {
      return finding.pocDocId;
    }
    return SpartaTarget.Type.PUBLISHED_DOC_ID.wireValue().equals(finding.targetType)
            && isDocId(finding.targetId)
        ? finding.targetId
        : null;
  }

  private static String friendlyName(SpartaFindingModel finding) {
    if (has(finding.pocDocId) && !isDocId(finding.pocDocId)) {
      return finding.pocDocId;
    }
    return SpartaTarget.Type.ENDPOINT_NAME.wireValue().equals(finding.targetType)
            && has(finding.targetId)
        ? finding.targetId
        : null;
  }

  /** A persisted document id is all digits; anything else in that field is an operation name. */
  private static boolean isDocId(String value) {
    if (!has(value)) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Encoded as a form value, except for the placeholder braces: the researcher has to find and
   * replace every token by hand, and www's parser takes a brace either way.
   */
  private static String encodeVariables(String variablesJson) {
    String variables = has(variablesJson) ? variablesJson : "{}";
    return encode(variables).replace("%7B%7B", "{{").replace("%7D%7D", "}}");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean has(String value) {
    return value != null && !value.isEmpty();
  }
}

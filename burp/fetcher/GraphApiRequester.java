/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.utilities.json.JsonNode;
import burp.api.montoya.utilities.json.JsonObjectNode;
import burp.zurp.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class GraphApiRequester {

  public GraphApiRequester() {}

  /**
   * Status alongside the body, so a caller can tell an authorization failure it should stop
   * retrying from a transient one it should not give up on.
   */
  /** No server answered, e.g. credentials unset, or a TLS or DNS failure. */
  public static final int NOT_ATTEMPTED = 0;

  public static final class ApiResponse {
    /** {@link #NOT_ATTEMPTED}, or the HTTP status the server replied with. */
    public final int statusCode;

    public final JsonObjectNode body;

    ApiResponse(int statusCode, JsonObjectNode body) {
      this.statusCode = statusCode;
      this.body = body;
    }

    public boolean isOk() {
      return isSuccess(statusCode) && body != null;
    }
  }

  /**
   * Any 2xx rather than 200 alone: create and archive answer 201, and the new run id is in that
   * body, so treating only 200 as success would discard it.
   */
  static boolean isSuccess(int statusCode) {
    return statusCode >= 200 && statusCode < 300;
  }

  public ApiResponse makeGetRequest(String endpoint, Map<String, String> queryParams) {
    return send("GET", endpoint, queryParams, null);
  }

  /**
   * Sends {@code body} as JSON. Built through {@link JsonObjectNode} rather than assembled as a
   * string: an FBDL script is arbitrary source, full of quotes, backslashes and newlines, and
   * hand-escaping it is the kind of thing that works until the first script that does not.
   */
  public ApiResponse makePostRequest(String endpoint, JsonObjectNode body) {
    return send("POST", endpoint, new LinkedHashMap<>(), body);
  }

  private ApiResponse send(
      String method, String endpoint, Map<String, String> queryParams, JsonObjectNode jsonBody) {
    // Read per call rather than caching in the constructor: the extension builds this once at
    // startup, so a cached value would ignore whatever the researcher later types in the Zurp tab.
    String baseUrl = ZurpUtils.getApiBaseUrl();
    String accessToken = ZurpUtils.getAccessToken();
    if (accessToken.isEmpty()) {
      ZurpLog.error("Access token not set, skipping: " + endpoint);
      return new ApiResponse(NOT_ATTEMPTED, null);
    }

    // Safe to log and to carry into errors: the token travels in a header, not in the URL.
    String requestUrl = join(baseUrl, endpoint) + query(queryParams);

    try {
      HttpRequest request =
          HttpRequest.httpRequestFromUrl(requestUrl)
              .withMethod(method)
              // Stefi reads the bearer token from this header only. Its query-string equivalent is
              // an opt-in legacy trait for Graph redirection, and this endpoint does not use it.
              .withHeader("Authorization", "Bearer " + accessToken);
      if (jsonBody != null) {
        request =
            request
                .withHeader("Content-Type", "application/json")
                .withBody(jsonBody.toJsonString());
      }

      HttpRequestResponse result = Zurp.http.sendRequest(request);
      if (!result.hasResponse()) {
        // Burp attaches no reason to a send that produced nothing; its Logger tab has the detail.
        ZurpLog.error(method + " " + requestUrl + " got no response");
        return new ApiResponse(NOT_ATTEMPTED, null);
      }

      HttpResponse response = result.response();
      int statusCode = response.statusCode();
      ZurpLog.output(method + " " + requestUrl + " -> " + statusCode);
      if (!isSuccess(statusCode)) {
        // The body names the capability or allowlist Stefi wanted and is the first thing worth
        // seeing on a 4xx, but it is far too much to print on every call. Guarded rather than left
        // to ZurpLog, so an unread body is never materialised.
        if (ZurpLog.isDebug()) {
          ZurpLog.debug(method + " " + requestUrl + " body: " + excerpt(response.bodyToString()));
        }
        return new ApiResponse(statusCode, null);
      }
      String body = response.bodyToString();
      ZurpLog.debug(method + " " + requestUrl + " returned " + body.length() + " bytes");
      // A 2xx whose JSON is not an object is still the server's answer, so it counts as an attempt;
      // letting asObject() throw would report it as NOT_ATTEMPTED and requeue it forever.
      JsonNode parsed = JsonNode.jsonNode(body);
      return new ApiResponse(statusCode, parsed.isObject() ? parsed.asObject() : null);
    } catch (Exception e) {
      // Names the URL, so a failure is attributable rather than an anonymous stack trace.
      ZurpLog.caught(method + " " + requestUrl + " failed", e);
      return new ApiResponse(NOT_ATTEMPTED, null);
    }
  }

  private static final int EXCERPT_LIMIT = 512;

  static String excerpt(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= EXCERPT_LIMIT
        ? body
        : body.substring(0, EXCERPT_LIMIT) + "… (" + body.length() + " bytes)";
  }

  /** Tolerates either side owning the separator, since the base URL is typed in by hand. */
  static String join(String baseUrl, String endpoint) {
    int end = baseUrl.length();
    while (end > 0 && baseUrl.charAt(end - 1) == '/') {
      end--;
    }
    int start = 0;
    while (start < endpoint.length() && endpoint.charAt(start) == '/') {
      start++;
    }
    return baseUrl.substring(0, end) + '/' + endpoint.substring(start);
  }

  static String query(Map<String, String> queryParams) {
    StringBuilder query = new StringBuilder();
    for (Map.Entry<String, String> param : queryParams.entrySet()) {
      if (param.getValue() == null) {
        continue;
      }
      query
          .append(query.length() == 0 ? '?' : '&')
          .append(URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
    }
    return query.toString();
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fbdl;

import burp.api.montoya.http.message.ContentType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.models.FbdlRunModel;
import burp.zurp.Zurp;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes values produced by an FBDL run into an outgoing request.
 *
 * <p>A placeholder names the run it reads from: {@code {{fbdl.<run id>.<label>}}}. Nothing resolves
 * without one. An FBDL run mints its own test assets, so two runs of the same script produce the
 * same labels with different values, and researchers name them conventionally -- UserOne, PageOne.
 * Left to resolve on their own, an unrelated run would silently repoint a saved request at another
 * engagement's user, which fails as a request that succeeds against the wrong thing.
 *
 * <p>The run id is written by the Pin action rather than typed. See {@link
 * FbdlContextMenuProvider}.
 *
 * <p>Rewrites operate on the query string and body as text rather than through
 * withUpdatedParameters, following {@link burp.csrf.CsrfRewriter}: the Montoya API does not specify
 * whether parameter values are inserted verbatim or percent-encoded, and a wrong guess would
 * double-encode the value.
 */
public final class FbdlRewriter {

  /**
   * The run id is optional so the Pin action can find unpinned placeholders to rewrite, but only a
   * pinned one resolves.
   *
   * <p>Deliberately wider than ZurpUtils.FBID_PATTERN's 15 to 18. That bound exists to spot ids in
   * arbitrary traffic without matching every long number, so it trades recall for precision. Here
   * the digits are already delimited by {@code fbdl.} and a dot, so precision is free and the bound
   * only has to be too long for a label that happens to start with a number. Observed run ids are
   * already 15 and 16 digits, so the range is not uniform and guessing it narrowly would mean
   * writing an id that this pattern then refuses to read back.
   *
   * <p>It still has to be a run of digits rather than anything, because labels contain dots too:
   * UserOne.uid is a single label, not a run and a field.
   */
  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\{\\{\\s*fbdl\\.(?:(\\d{10,20})\\.)?(.+?)\\s*\\}\\}");

  private FbdlRewriter() {}

  /** Returns null when nothing changed, so the caller can forward the original request. */
  public static HttpRequest rewrite(HttpRequest request) {
    String originalPath = request.path();
    String originalBody = request.bodyToString();

    // withPath is the only way to reach the query string, and the API does not state whether path()
    // includes it. If it does not, rewriting the path would silently drop the query, so verify that
    // path() round-trips before touching it.
    boolean pathIsSafeToRewrite = originalPath.indexOf('?') >= 0 || request.url().indexOf('?') < 0;

    // A JSON or multipart body takes the value verbatim; only a form-encoded body escapes it.
    boolean encodeBody = request.contentType() == ContentType.URL_ENCODED;

    String path = pathIsSafeToRewrite ? substitute(originalPath, true) : originalPath;
    String body = substitute(originalBody, encodeBody);

    if (path.equals(originalPath) && body.equals(originalBody)) {
      return null;
    }

    HttpRequest rewritten = request;
    if (!path.equals(originalPath)) {
      rewritten = rewritten.withPath(path);
    }
    if (!body.equals(originalBody)) {
      // withBody rather than a whole-request rebuild, so Content-Length follows the new body.
      rewritten = rewritten.withBody(body);
    }
    return rewritten;
  }

  /**
   * Replaces pinned placeholders with the value their run recorded. An unpinned placeholder, or one
   * naming a run or label Zurp has not cached, is left exactly as it is: a request that visibly
   * still says {@code {{fbdl...}}} is easier to diagnose than one that silently sent nothing.
   */
  static String substitute(String surface, boolean encode) {
    if (surface == null || surface.isEmpty() || surface.indexOf("{{") < 0) {
      return surface;
    }

    Matcher matcher = PLACEHOLDER.matcher(surface);
    StringBuilder out = null;
    int last = 0;
    // One request can carry several labels from the same run; look each run up once.
    Map<String, FbdlRunModel> runs = new HashMap<>();

    while (matcher.find()) {
      String runId = matcher.group(1);
      if (runId == null) {
        continue;
      }
      FbdlRunModel run = runs.computeIfAbsent(runId, id -> Zurp.fbdlRunFetcher.getRun(id));
      if (run == null) {
        continue;
      }
      String value = run.results.get(matcher.group(2));
      if (value == null) {
        continue;
      }
      if (out == null) {
        out = new StringBuilder(surface.length() + 64);
      }
      out.append(surface, last, matcher.start());
      out.append(encode ? urlEncode(value) : value);
      last = matcher.end();
    }

    if (out == null) {
      return surface;
    }
    out.append(surface, last, surface.length());
    return out.toString();
  }

  /** Rewrites every FBDL placeholder to read from {@code runId}, pinned already or not. */
  public static HttpRequest pin(HttpRequest request, String runId) {
    String originalPath = request.path();
    String originalBody = request.bodyToString();
    boolean pathIsSafeToRewrite = originalPath.indexOf('?') >= 0 || request.url().indexOf('?') < 0;

    String path = pathIsSafeToRewrite ? pin(originalPath, runId) : originalPath;
    String body = pin(originalBody, runId);

    HttpRequest pinned = request;
    if (!path.equals(originalPath)) {
      pinned = pinned.withPath(path);
    }
    if (!body.equals(originalBody)) {
      pinned = pinned.withBody(body);
    }
    return pinned;
  }

  static String pin(String surface, String runId) {
    if (surface == null || surface.isEmpty() || surface.indexOf("{{") < 0) {
      return surface;
    }

    Matcher matcher = PLACEHOLDER.matcher(surface);
    StringBuilder out = new StringBuilder(surface.length() + 32);
    int last = 0;
    while (matcher.find()) {
      out.append(surface, last, matcher.start());
      out.append("{{fbdl.").append(runId).append('.').append(matcher.group(2)).append("}}");
      last = matcher.end();
    }
    out.append(surface, last, surface.length());
    return out.toString();
  }

  /** True when the request has anything for {@link #pin} to act on. */
  public static boolean hasPlaceholder(HttpRequest request) {
    return PLACEHOLDER.matcher(request.path()).find()
        || PLACEHOLDER.matcher(request.bodyToString()).find();
  }

  /** Every label the request refers to, so a pin can report the ones its run cannot fill. */
  public static List<String> labelsIn(HttpRequest request) {
    List<String> labels = new ArrayList<>();
    collectLabels(request.path(), labels);
    collectLabels(request.bodyToString(), labels);
    return labels;
  }

  private static void collectLabels(String surface, List<String> labels) {
    if (surface == null || surface.indexOf("{{") < 0) {
      return;
    }
    Matcher matcher = PLACEHOLDER.matcher(surface);
    while (matcher.find()) {
      labels.add(matcher.group(2));
    }
  }

  private static String urlEncode(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      return value;
    }
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.csrf;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts CSRF tokens and the sprinkle configuration out of Meta responses.
 *
 * <p>All three ServerJS payloads ride the same {@code ["<module>",[],{...}]} define tuple, so a
 * single marker works across the inline requireLazy, {@code data-sjs} JSON and {@code __bbox}
 * scheduled wrappers.
 *
 * <p>Key order within a payload is HHVM shape insertion order, and only DTSGInitialData has a test
 * locking it. Extraction is therefore keyed by name rather than by position.
 */
public final class CsrfScraper {

  private static final String DTSG_INIT_DATA = "\"DTSGInitData\",[],{";
  private static final String DTSG_INITIAL_DATA = "\"DTSGInitialData\",[],{";
  private static final String LSD_DATA = "\"LSD\",[],{";
  private static final String SPRINKLE_CONFIG = "\"SprinkleConfig\",[],{";
  private static final String DTSG_INPUT = "name=\"fb_dtsg\"";

  /** Reported as a changed name alongside the tokens. */
  public static final String SPRINKLE = "sprinkle";

  private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]*)\"");
  private static final Pattern ASYNC_GET_TOKEN =
      Pattern.compile("\"async_get_token\":\"([^\"]*)\"");
  private static final Pattern PARAM_NAME = Pattern.compile("\"param_name\":\"([^\"]*)\"");
  private static final Pattern VERSION = Pattern.compile("\"version\":(\\d+)");
  private static final Pattern SHOULD_RANDOMIZE =
      Pattern.compile("\"should_randomize\":(true|false)");
  private static final Pattern INPUT_VALUE = Pattern.compile("value=\"([^\"]*)\"");

  // Guards against a malformed or truncated payload turning into an unbounded scan.
  private static final int MAX_PAYLOAD_SPAN = 4096;

  private CsrfScraper() {}

  /**
   * Returns a comma-separated list of the tokens whose cached value actually changed, or null if
   * nothing changed, so the caller can log sparingly and say what moved.
   */
  public static String scrape(CsrfTokenStore store, String host, String account, String body) {
    if (host == null || host.isEmpty() || body == null || body.isEmpty()) {
      return null;
    }

    // Tokens belong to the account the response was served to; the sprinkle config belongs to the
    // host, since it rotates by sitevar rather than by who is logged in.
    CsrfTokenStore.HostEntry entry = store.forHost(host);
    CsrfTokenStore.SessionTokens tokens = entry.forSession(account);

    StringBuilder changed = new StringBuilder();

    int idx = body.indexOf(DTSG_INIT_DATA);
    if (idx >= 0) {
      int start = idx + DTSG_INIT_DATA.length();
      int end = objectEnd(body, start);
      capture(body, start, end, TOKEN, tokens, CsrfTokenStore.FB_DTSG, changed);
      capture(body, start, end, ASYNC_GET_TOKEN, tokens, CsrfTokenStore.FB_DTSG_AG, changed);
    }

    idx = body.indexOf(DTSG_INITIAL_DATA);
    if (idx >= 0) {
      int start = idx + DTSG_INITIAL_DATA.length();
      capture(body, start, objectEnd(body, start), TOKEN, tokens, CsrfTokenStore.FB_DTSG, changed);
    }

    idx = body.indexOf(LSD_DATA);
    if (idx >= 0) {
      int start = idx + LSD_DATA.length();
      capture(body, start, objectEnd(body, start), TOKEN, tokens, CsrfTokenStore.LSD, changed);
    }

    idx = body.indexOf(DTSG_INPUT);
    if (idx >= 0) {
      int tagStart = body.lastIndexOf('<', idx);
      int tagEnd = body.indexOf('>', idx);
      if (tagStart >= 0 && tagEnd > tagStart) {
        capture(body, tagStart, tagEnd, INPUT_VALUE, tokens, CsrfTokenStore.FB_DTSG, changed);
      }
    }

    idx = body.indexOf(SPRINKLE_CONFIG);
    if (idx >= 0) {
      int start = idx + SPRINKLE_CONFIG.length();
      if (scrapeSprinkleConfig(entry, body, start, objectEnd(body, start))) {
        append(changed, SPRINKLE);
      }
    }

    return changed.length() == 0 ? null : changed.toString();
  }

  private static boolean scrapeSprinkleConfig(
      CsrfTokenStore.HostEntry entry, String body, int start, int end) {
    String paramName = firstGroup(body, start, end, PARAM_NAME);
    String version = firstGroup(body, start, end, VERSION);
    String randomize = firstGroup(body, start, end, SHOULD_RANDOMIZE);

    int parsedVersion = SprinkleChecksum.DEFAULT_VERSION;
    if (version != null) {
      try {
        parsedVersion = Integer.parseInt(version);
      } catch (NumberFormatException e) {
        // Bounded by \d+ above, but a pathologically long run would overflow parseInt.
        parsedVersion = SprinkleChecksum.DEFAULT_VERSION;
      }
    }

    return entry.putSprinkleConfig(paramName, parsedVersion, Boolean.parseBoolean(randomize));
  }

  private static boolean capture(
      String body,
      int start,
      int end,
      Pattern pattern,
      CsrfTokenStore.SessionTokens tokens,
      String tokenName,
      StringBuilder changed) {
    String value = firstGroup(body, start, end, pattern);
    if (value == null || value.isEmpty()) {
      return false;
    }
    if (!tokens.put(tokenName, value)) {
      return false;
    }
    append(changed, tokenName);
    return true;
  }

  private static void append(StringBuilder changed, String name) {
    if (changed.length() > 0) {
      changed.append(", ");
    }
    changed.append(name);
  }

  /** Uses a matcher region rather than a substring so no copy of the response is made. */
  private static String firstGroup(String body, int start, int end, Pattern pattern) {
    if (start < 0 || end <= start) {
      return null;
    }
    Matcher matcher = pattern.matcher(body);
    matcher.region(start, end);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static int objectEnd(String body, int start) {
    int close = body.indexOf('}', start);
    int limit = Math.min(body.length(), start + MAX_PAYLOAD_SPAN);
    if (close < 0 || close > limit) {
      return limit;
    }
    return close;
  }
}

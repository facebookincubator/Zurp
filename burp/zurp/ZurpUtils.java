/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import burp.api.montoya.core.ToolType;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ZurpUtils {
  public static final String BASE_DOMAINS_PATTERN = "(facebook\\.com|meta\\.com)";
  // this pattern is to exclude the verbose useless urls that are often requested when browsing our
  // sites
  public static final Pattern EXCLUDE_URL_PATTERN =
      Pattern.compile(
          // "(/ajax/bootloader-endpoint/|/ajax/bnzai|/ajax/bulk-route-definitions/|/ajax/webstorage/process_keys/|/ajax/qm/|edge-chat\\.facebook.com/chat?|/rsrc-translations\\.php|/a/bz?)");
          "(.*)(\\/ajax\\/bootloader-endpoint\\/|\\/ajax\\/bnzai|\\/ajax\\/bulk-route-definitions\\/|\\/ajax\\/webstorage\\/process_keys\\/|\\/ajax\\/qm\\/|edge-chat\\.facebook.com\\/chat\\?|\\/rsrc-translations\\.php|\\/a\\/bz?)(.*)");
  public static final Pattern URL_PATTERN =
      Pattern.compile("https?://([a-zA-Z0-9.-]+\\.)?" + BASE_DOMAINS_PATTERN + "(/.*)?");

  /**
   * Candidates for an id lookup, not a decision that one is an FBID: only the server can tell,
   * since an id has to fall in an allocated range and land on a live shard.
   *
   * <p>The ceiling was 16, which silently hid every 17 and 18 digit object, including the whole Instagram
   * media range (17841400000000000 upward) among them. 14 is a deliberate floor rather than the
   * true one: OIDs start around 2.2e9, but 10 digits is also unix seconds and 13 is milliseconds,
   * so reaching down there costs far more noise than it finds. 19 digits would be nanoseconds.
   *
   * <p>14 rather than 15 because accounts made before the 100... scheme are 14 digits, and they are
   * disproportionately what a researcher looks up. The endpoint itself recognises an FBID from 11
   * digits, but 13 is milliseconds and nearly every request carries one, so 14 is the lowest floor
   * that sits in the gap between the two timestamp bands.
   */
  public static final Pattern FBID_PATTERN = Pattern.compile("\\b\\d{14,18}\\b");

  /**
   * An ad account id, whose digits {@link #FBID_PATTERN} cannot see: {@code _} is a word character,
   * so there is no word boundary between the prefix and the number, and {@code act_120...} never
   * matches. The digits are the FBID. The prefix is how Ads surfaces spell it, not part of the id,
   * and the asset endpoint wants it stripped.
   *
   * <p>No lower floor than the endpoint's own 11 digits is needed here. The floor on a bare number
   * exists to keep clock readings out; a number carrying this prefix is an ad account whatever its
   * length.
   */
  public static final Pattern AD_ACCOUNT_PATTERN = Pattern.compile("\\bact_([1-9]\\d{10,})\\b");

  public static boolean isMetaUrl(String url) {
    Matcher matcherExclude = ZurpUtils.EXCLUDE_URL_PATTERN.matcher(url);
    if (matcherExclude.matches()) {
      return false;
    }
    Matcher matcher = ZurpUtils.URL_PATTERN.matcher(url);
    return matcher.matches();
  }

  public static boolean isCsrfPlaceholderEnabled(ToolType tool) {
    return readBoolPref(
        ZurpPrefEnum.csrfPlaceholderKey(tool), ZurpPrefEnum.csrfPlaceholderDefault(tool));
  }

  public static boolean isCsrfAutoRefreshEnabled(ToolType tool) {
    return readBoolPref(
        ZurpPrefEnum.csrfAutoRefreshKey(tool), ZurpPrefEnum.csrfAutoRefreshDefault(tool));
  }

  public static boolean isFetcherEnabled(String dataTypeName) {
    return readBoolPref(ZurpPrefEnum.fetchKey(dataTypeName), ZurpPrefEnum.FETCH_DEFAULT);
  }

  public static boolean isOrganizerPushEnabled() {
    return readBoolPref(
        ZurpPrefEnum.ORGANIZER_PUSH.getValue(), ZurpPrefEnum.ORGANIZER_PUSH_DEFAULT);
  }

  public static ZurpLog.Level getLogLevel() {
    return ZurpLog.Level.parse(
        readStringPref(ZurpPrefEnum.LOG_LEVEL.getValue(), null), ZurpPrefEnum.LOG_LEVEL_DEFAULT);
  }

  public static String getApiBaseUrl() {
    return readStringPref(ZurpPrefEnum.API_BASE_URL.getValue(), ZurpPrefEnum.API_BASE_URL_DEFAULT);
  }

  /** Empty when unset, since a caller can do nothing with a token either way. */
  public static String getAccessToken() {
    return readStringPref(ZurpPrefEnum.ACCESS_TOKEN.getValue(), "");
  }

  /**
   * Preference, then environment, then the built-in default. Defaults are resolved on read rather
   * than written at startup, because a default that was never persisted comes back as null and
   * would otherwise silently read as false.
   */
  private static boolean readBoolPref(String key, boolean fallback) {
    Boolean stored = Zurp.preferences.getBoolean(key);
    if (stored != null) {
      return stored;
    }
    String seeded = ZurpEnv.lookup(key);
    if (seeded == null) {
      return fallback;
    }
    Boolean parsed = ZurpEnv.asBoolean(seeded);
    return parsed == null ? fallback : parsed;
  }

  /** Blank counts as unset, so clearing a field in the tab falls back rather than sticking. */
  private static String readStringPref(String key, String fallback) {
    String stored = Zurp.preferences.getString(key);
    if (stored != null && !stored.isBlank()) {
      return stored.trim();
    }
    String seeded = ZurpEnv.lookup(key);
    return seeded == null ? fallback : seeded;
  }
}

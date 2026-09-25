/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import burp.api.montoya.core.ToolType;

public enum ZurpPrefEnum {
  ACCESS_TOKEN("Access Token"),
  API_BASE_URL("API Base URL"),
  LOG_LEVEL("Log Level"),
  ORGANIZER_PUSH("Send PoCs to Organizer");

  /**
   * CSRF preferences are per tool, so their keys are built from a prefix rather than enumerated.
   * Keeping them here preserves the convention that every preference key originates in this file.
   */
  public static final String CSRF_PLACEHOLDER_PREFIX = "CSRF Placeholder: ";

  public static final String CSRF_AUTO_REFRESH_PREFIX = "CSRF Auto-refresh: ";

  /** The API host. Only change this if you are told to. */
  public static final String API_BASE_URL_DEFAULT = "https://api.facebook.com/";

  /** Per-fetcher kill switch, keyed off the fetcher's own data type name. */
  public static final String FETCH_PREFIX = "Fetch: ";

  /**
   * All fetchers run unless the researcher says otherwise. The endpoints are allowlisted server
   * side, so an account without access costs one rejection per fetcher and then nothing.
   */
  public static final boolean FETCH_DEFAULT = true;

  /** Silent until the researcher opts in: Burp's Logger is theirs, not Zurp's. */
  public static final ZurpLog.Level LOG_LEVEL_DEFAULT = ZurpLog.Level.NONE;

  /**
   * On, like the fetchers whose output it is: a researcher who does not want SPARTA data in their
   * project turns the Sparta Findings fetcher off and this stops with it. Only findings for
   * endpoints they actually browsed are sent, so the Organizer tracks their session rather than the
   * whole disclosed catalog.
   */
  public static final boolean ORGANIZER_PUSH_DEFAULT = true;

  /** Tools that can meaningfully replay a Meta request. */
  public static final ToolType[] CSRF_TOOLS = {
    ToolType.PROXY, ToolType.REPEATER, ToolType.INTRUDER, ToolType.SCANNER
  };

  private final String value;

  ZurpPrefEnum(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static String csrfPlaceholderKey(ToolType tool) {
    return CSRF_PLACEHOLDER_PREFIX + tool.name();
  }

  public static String csrfAutoRefreshKey(ToolType tool) {
    return CSRF_AUTO_REFRESH_PREFIX + tool.name();
  }

  public static String fetchKey(String dataTypeName) {
    return FETCH_PREFIX + dataTypeName;
  }

  /** Placeholders are on where a researcher hand-crafts requests; everything else is opt-in. */
  public static boolean csrfPlaceholderDefault(ToolType tool) {
    return tool == ToolType.REPEATER || tool == ToolType.INTRUDER;
  }

  public static boolean csrfAutoRefreshDefault(ToolType tool) {
    return false;
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import java.util.HashMap;
import java.util.Map;

/**
 * Lets a preference be set from outside Burp, as a JVM system property in Burp's `.vmoptions` file
 * or as an environment variable, so a fresh install comes up already configured. These are
 * defaults: anything the researcher has actually set in the Zurp tab wins.
 */
public final class ZurpEnv {

  private static final String PROPERTY_PREFIX = "zurp.";
  private static final String ENV_PREFIX = "ZURP_";

  /**
   * Read once. Neither source can change while the JVM runs, and these reads sit on Burp's request
   * path through the CSRF checkboxes, where {@code System.getProperty} is a synchronized Hashtable
   * lookup that is not worth paying per request.
   */
  private static final Map<String, String> SEEDS = readSeeds();

  private ZurpEnv() {}

  /** The value set for this preference outside Burp, or null if neither source names it. */
  static String lookup(String prefKey) {
    return SEEDS.get(canonical(prefKey));
  }

  /**
   * Recognises what a person would plausibly write for a boolean. Null when it is none of them, so
   * a typo falls back to the built-in default rather than silently reading as off.
   */
  static Boolean asBoolean(String value) {
    switch (value.toLowerCase()) {
      case "true":
      case "1":
      case "yes":
      case "on":
        return Boolean.TRUE;
      case "false":
      case "0":
      case "no":
      case "off":
        return Boolean.FALSE;
      default:
        return null;
    }
  }

  private static Map<String, String> readSeeds() {
    Map<String, String> seeds = new HashMap<>();
    for (Map.Entry<String, String> variable : System.getenv().entrySet()) {
      if (variable.getKey().startsWith(ENV_PREFIX)) {
        put(seeds, variable.getKey().substring(ENV_PREFIX.length()), variable.getValue());
      }
    }
    // Second, so a -D next to the launcher beats an exported variable: it is the more deliberate
    // of the two, and the one attached to this particular Burp.
    for (String name : System.getProperties().stringPropertyNames()) {
      if (name.startsWith(PROPERTY_PREFIX)) {
        put(seeds, name.substring(PROPERTY_PREFIX.length()), System.getProperty(name));
      }
    }
    return seeds;
  }

  private static void put(Map<String, String> seeds, String name, String value) {
    if (value != null && !value.isBlank()) {
      seeds.put(canonical(name), value.trim());
    }
  }

  /**
   * Reduces a name to letters and digits separated by dots, so the one preference key can be
   * spelled three ways and still match: `Access Token`, `ZURP_ACCESS_TOKEN`, `zurp.access.token`.
   */
  static String canonical(String name) {
    StringBuilder canonical = new StringBuilder(name.length());
    boolean separatorPending = false;
    for (int i = 0; i < name.length(); i++) {
      char character = name.charAt(i);
      if (!Character.isLetterOrDigit(character)) {
        separatorPending = true;
        continue;
      }
      if (separatorPending && canonical.length() > 0) {
        canonical.append('.');
      }
      separatorPending = false;
      canonical.append(Character.toLowerCase(character));
    }
    return canonical.toString();
  }
}

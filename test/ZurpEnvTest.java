/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * A preference key, an environment variable and a system property are three spellings of the same
 * setting. These pin the reduction that has to make all three meet.
 */
public class ZurpEnvTest {

  @Test
  public void testPreferenceKeyAndItsEnvironmentVariableAgree() {
    assertEquals(
        ZurpEnv.canonical("ACCESS_TOKEN"), ZurpEnv.canonical(ZurpPrefEnum.ACCESS_TOKEN.getValue()));
    assertEquals(
        ZurpEnv.canonical("API_BASE_URL"), ZurpEnv.canonical(ZurpPrefEnum.API_BASE_URL.getValue()));
    assertEquals(
        ZurpEnv.canonical("LOG_LEVEL"), ZurpEnv.canonical(ZurpPrefEnum.LOG_LEVEL.getValue()));
  }

  @Test
  public void testPreferenceKeyAndItsSystemPropertyAgree() {
    assertEquals(
        ZurpEnv.canonical("access.token"), ZurpEnv.canonical(ZurpPrefEnum.ACCESS_TOKEN.getValue()));
  }

  /**
   * The names the README tells a researcher to export. Spelled out because the derivation is not
   * quite guessable: {@code Meta Url Info} is one word per capital, not {@code META_URL}.
   */
  @Test
  public void testEveryDocumentedFetcherVariableReachesItsFetcher() {
    assertEquals(
        ZurpEnv.canonical("FETCH_SPARTA_FINDINGS"),
        ZurpEnv.canonical(ZurpPrefEnum.fetchKey("Sparta Findings")));
    assertEquals(
        ZurpEnv.canonical("FETCH_META_OBJECT_INFO"),
        ZurpEnv.canonical(ZurpPrefEnum.fetchKey("Meta Object Info")));
    assertEquals(
        ZurpEnv.canonical("FETCH_META_URL_INFO"),
        ZurpEnv.canonical(ZurpPrefEnum.fetchKey("Meta Url Info")));
  }

  @Test
  public void testCompositeKeysAgreeToo() {
    // Spelled out rather than built through csrfPlaceholderKey, which would drag Burp's ToolType
    // onto a test classpath that has stayed free of Burp.
    assertEquals(
        ZurpEnv.canonical("CSRF_PLACEHOLDER_REPEATER"),
        ZurpEnv.canonical(ZurpPrefEnum.CSRF_PLACEHOLDER_PREFIX + "REPEATER"));
    // The hyphen in "Auto-refresh" is a separator like any other.
    assertEquals(
        ZurpEnv.canonical("CSRF_AUTO_REFRESH_PROXY"),
        ZurpEnv.canonical(ZurpPrefEnum.CSRF_AUTO_REFRESH_PREFIX + "PROXY"));
  }

  @Test
  public void testDistinctSettingsStayDistinct() {
    assertEquals("csrf.placeholder.proxy", ZurpEnv.canonical("CSRF Placeholder: PROXY"));
    assertEquals("csrf.auto.refresh.proxy", ZurpEnv.canonical("CSRF Auto-refresh: PROXY"));
  }

  @Test
  public void testRunsOfSeparatorsCollapseAndEdgesAreDropped() {
    assertEquals("api.base.url", ZurpEnv.canonical("__API___BASE__URL__"));
    assertEquals("api.base.url", ZurpEnv.canonical("  API Base URL  "));
  }

  @Test
  public void testDigitsSurvive() {
    assertEquals("oauth2.token", ZurpEnv.canonical("OAuth2 Token"));
  }

  @Test
  public void testEmptyNameDoesNotThrow() {
    assertEquals("", ZurpEnv.canonical(""));
    assertEquals("", ZurpEnv.canonical("___"));
  }

  @Test
  public void testEveryPlausibleSpellingOfTrueAndFalseIsUnderstood() {
    for (String yes : new String[] {"true", "TRUE", "1", "yes", "on", "On"}) {
      assertEquals(Boolean.TRUE, ZurpEnv.asBoolean(yes), yes);
    }
    for (String no : new String[] {"false", "FALSE", "0", "no", "off", "Off"}) {
      assertEquals(Boolean.FALSE, ZurpEnv.asBoolean(no), no);
    }
  }

  @Test
  public void testAnUnrecognisedBooleanIsNotSilentlyFalse() {
    assertNull(ZurpEnv.asBoolean("enabled"));
    assertNull(ZurpEnv.asBoolean(""));
    assertNull(ZurpEnv.asBoolean("2"));
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.csrf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Fixtures mirror the three wrappers www uses to emit ServerJS payloads: inline requireLazy, a
 * data-sjs JSON script tag, and the __bbox scheduled form.
 */
public class CsrfScraperTest {

  private static final String HOST = "www.facebook.com";
  private static final String ACCOUNT = "100012345678901";
  private static final String TOKEN =
      "NAfv09fpTv1Ka-H9s8_aSIZglfGcmrrW6zwhf3ZlF3h571MjbQ2PznA:56:1752837045";

  private CsrfTokenStore store;

  private CsrfTokenStore store() {
    if (store == null) {
      store = new CsrfTokenStore();
    }
    return store;
  }

  private CsrfTokenStore.SessionTokens scrape(String body) {
    CsrfTokenStore s = store();
    CsrfScraper.scrape(s, HOST, ACCOUNT, body);
    return s.forHost(HOST).forSession(ACCOUNT);
  }

  /** The sprinkle config is the host's, not the account's: it rotates by sitevar, not by login. */
  private CsrfTokenStore.HostEntry scrapeHost(String body) {
    CsrfTokenStore s = store();
    CsrfScraper.scrape(s, HOST, ACCOUNT, body);
    return s.forHost(HOST);
  }

  @Test
  public void testChangedNamesAreReported() {
    CsrfTokenStore s = store();
    String changed =
        CsrfScraper.scrape(
            s,
            HOST,
            ACCOUNT,
            "[\"DTSGInitData\",[],{\"token\":\"" + TOKEN + "\",\"async_get_token\":\"AQ\"},1]");
    assertEquals("fb_dtsg, fb_dtsg_ag", changed);
  }

  @Test
  public void testLsdChurnIsReportedAsLsdOnly() {
    // Logged in, LSD.php emits a fresh random token per page load. The handler suppresses the log
    // when lsd is the only thing that moved, so the name must be reported exactly.
    CsrfTokenStore s = store();
    assertEquals(
        CsrfTokenStore.LSD,
        CsrfScraper.scrape(s, HOST, ACCOUNT, "[\"LSD\",[],{\"token\":\"aaa\"},1]"));
    assertEquals(
        CsrfTokenStore.LSD,
        CsrfScraper.scrape(s, HOST, ACCOUNT, "[\"LSD\",[],{\"token\":\"bbb\"},1]"));
  }

  @Test
  public void testSprinkleChangeIsNamed() {
    CsrfTokenStore s = store();
    String changed =
        CsrfScraper.scrape(
            s,
            HOST,
            ACCOUNT,
            "[\"SprinkleConfig\",[],{\"param_name\":\"jazoest\",\"version\":7,"
                + "\"should_randomize\":false},1]");
    assertEquals(CsrfScraper.SPRINKLE, changed);
  }

  @Test
  public void testInlineRequireLazyWrapper() {
    String body =
        "<script>requireLazy([\"TimeSliceImpl\",\"ServerJS\"],function(TimeSlice,ServerJS){"
            + "var s=(new ServerJS());s.handle({\"define\":[[\"DTSGInitData\",[],"
            + "{\"token\":\""
            + TOKEN
            + "\",\"async_get_token\":\"AQ_async_token\"},258]]});});</script>";

    CsrfTokenStore.SessionTokens tokens = scrape(body);
    assertEquals(TOKEN, tokens.get(CsrfTokenStore.FB_DTSG));
    assertEquals("AQ_async_token", tokens.get(CsrfTokenStore.FB_DTSG_AG));
  }

  @Test
  public void testDataSjsJsonWrapper() {
    String body =
        "<script type=\"application/json\" data-content-len=\"120\" data-sjs>"
            + "{\"define\":[[\"DTSGInitialData\",[],{\"token\":\""
            + TOKEN
            + "\"},258]]}</script>";

    assertEquals(TOKEN, scrape(body).get(CsrfTokenStore.FB_DTSG));
  }

  @Test
  public void testBboxScheduledWrapper() {
    String body =
        "{\"require\":[[\"ScheduledServerJS\",\"handle\",null,[{\"__bbox\":{\"define\":"
            + "[[\"LSD\",[],{\"token\":\"AVqbGa9y_lM\"},227]]}}]]]}";

    assertEquals("AVqbGa9y_lM", scrape(body).get(CsrfTokenStore.LSD));
  }

  @Test
  public void testHiddenInputFallback() {
    String body = "<form><input type=\"hidden\" name=\"fb_dtsg\" value=\"" + TOKEN + "\"></form>";
    assertEquals(TOKEN, scrape(body).get(CsrfTokenStore.FB_DTSG));
  }

  @Test
  public void testSprinkleConfigIsRead() {
    String body =
        "[\"SprinkleConfig\",[],{\"param_name\":\"jazoest\",\"version\":2,"
            + "\"should_randomize\":false},259]";

    CsrfTokenStore.HostEntry host = scrapeHost(body);
    assertEquals("jazoest", host.sprinkleParamName());
    assertEquals(2, host.sprinkleVersion());
    assertFalse(host.sprinkleRandomized());
  }

  @Test
  public void testSprinkleConfigRotatedParamName() {
    String body =
        "[\"SprinkleConfig\",[],{\"param_name\":\"logging\",\"version\":3,"
            + "\"should_randomize\":true},259]";

    CsrfTokenStore.SessionTokens tokens = scrape(body);
    CsrfTokenStore.HostEntry host = store().forHost(HOST);
    assertEquals("logging", host.sprinkleParamName());
    assertEquals(3, host.sprinkleVersion());
    assertTrue(host.sprinkleRandomized());
    // Randomized means the version prefix is dropped. Read through the session, which is how the
    // rewriter reaches it.
    assertEquals("5461", tokens.checksumFor(TOKEN));
  }

  @Test
  public void testKeyOrderIsNotAssumed() {
    // Only DTSGInitialData has a test locking its key order, so extraction must be name-keyed.
    String body =
        "[\"SprinkleConfig\",[],{\"should_randomize\":false,\"version\":2,"
            + "\"param_name\":\"jazoest\"},259]";

    CsrfTokenStore.SessionTokens tokens = scrape(body);
    assertEquals("jazoest", store().forHost(HOST).sprinkleParamName());
    assertEquals(2, store().forHost(HOST).sprinkleVersion());
  }

  @Test
  public void testAsyncGetTokenBeforeToken() {
    String body =
        "[\"DTSGInitData\",[],{\"async_get_token\":\"AQ_async\",\"token\":\"" + TOKEN + "\"},258]";

    CsrfTokenStore.SessionTokens tokens = scrape(body);
    assertEquals(TOKEN, tokens.get(CsrfTokenStore.FB_DTSG));
    assertEquals("AQ_async", tokens.get(CsrfTokenStore.FB_DTSG_AG));
  }

  @Test
  public void testLoggedOutEmptyStringsAreNotCached() {
    // DTSGInitData emits empty strings rather than omitting keys when logged out.
    String body = "[\"DTSGInitData\",[],{\"token\":\"\",\"async_get_token\":\"\"},258]";

    CsrfTokenStore.SessionTokens tokens = scrape(body);
    assertNull(tokens.get(CsrfTokenStore.FB_DTSG));
    assertNull(tokens.get(CsrfTokenStore.FB_DTSG_AG));
    assertFalse(tokens.hasAnyToken());
  }

  @Test
  public void testLoggedOutDtsgInitialDataOmitsTheKey() {
    String body = "[\"DTSGInitialData\",[],{},258]";
    assertNull(scrape(body).get(CsrfTokenStore.FB_DTSG));
  }

  @Test
  public void testTokenColonsAreNotTruncated() {
    String body = "[\"DTSGInitialData\",[],{\"token\":\"" + TOKEN + "\"},258]";
    String cached = scrape(body).get(CsrfTokenStore.FB_DTSG);
    assertTrue(cached.endsWith(":56:1752837045"), "session suffix must survive: " + cached);
  }

  @Test
  public void testHostsAreIsolated() {
    // Tokens are site-bound, so an intern token must never be offered to www.
    CsrfTokenStore s = store();
    CsrfScraper.scrape(
        s, "www.facebook.com", ACCOUNT, "[\"DTSGInitialData\",[],{\"token\":\"WWW\"},1]");
    CsrfScraper.scrape(
        s, "business.facebook.com", ACCOUNT, "[\"DTSGInitialData\",[],{\"token\":\"OTHERHOST\"},1]");

    assertEquals(
        "WWW", s.forHost("www.facebook.com").forSession(ACCOUNT).get(CsrfTokenStore.FB_DTSG));
    assertEquals(
        "OTHERHOST", s.forHost("business.facebook.com").forSession(ACCOUNT).get(CsrfTokenStore.FB_DTSG));
    assertEquals(2, s.hostCount());
  }

  @Test
  public void testUnrelatedResponseIsIgnored() {
    CsrfTokenStore s = store();
    assertNull(CsrfScraper.scrape(s, HOST, ACCOUNT, "{\"payload\":{\"unrelated\":true}}"));
    assertNull(CsrfScraper.scrape(s, HOST, ACCOUNT, ""));
  }

  @Test
  public void testRepeatedIdenticalScrapeReportsNoChange() {
    // The handler logs on the return value, so an unchanged token must not report a hit.
    String body = "[\"DTSGInitialData\",[],{\"token\":\"" + TOKEN + "\"},258]";
    CsrfTokenStore s = store();
    assertEquals(CsrfTokenStore.FB_DTSG, CsrfScraper.scrape(s, HOST, ACCOUNT, body));
    assertNull(CsrfScraper.scrape(s, HOST, ACCOUNT, body));
  }

  @Test
  public void testNewTokenOverwritesStale() {
    CsrfTokenStore s = store();
    CsrfScraper.scrape(s, HOST, ACCOUNT, "[\"DTSGInitialData\",[],{\"token\":\"STALE\"},1]");
    CsrfScraper.scrape(s, HOST, ACCOUNT, "[\"DTSGInitialData\",[],{\"token\":\"FRESH\"},1]");
    assertEquals("FRESH", s.forHost(HOST).forSession(ACCOUNT).get(CsrfTokenStore.FB_DTSG));
  }
}

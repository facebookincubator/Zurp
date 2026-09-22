/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.csrf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the per-account keying. Reading the account off a live request needs Burp's parser, so
 * that half is verified in Burp.
 */
public class CsrfTokenStorePerAccountTest {

  private static final String HOST = "www.facebook.com";
  private static final String VICTIM = "100012345678901";
  private static final String ATTACKER = "100067890123456";

  private static final String VICTIM_BODY =
      "[\"DTSGInitialData\",[],{\"token\":\"VICTIM_TOKEN\"},1]";
  private static final String ATTACKER_BODY =
      "[\"DTSGInitialData\",[],{\"token\":\"ATTACKER_TOKEN\"},1]";

  @Test
  public void testTwoAccountsOnOneHostKeepSeparateTokens() {
    // The reason this keying exists. Sharing a slot here does not fail loudly: the request goes
    // out and succeeds as the wrong user.
    CsrfTokenStore store = new CsrfTokenStore();
    CsrfScraper.scrape(store, HOST, VICTIM, VICTIM_BODY);
    CsrfScraper.scrape(store, HOST, ATTACKER, ATTACKER_BODY);

    assertEquals("VICTIM_TOKEN", store.peek(HOST, VICTIM).get(CsrfTokenStore.FB_DTSG));
    assertEquals("ATTACKER_TOKEN", store.peek(HOST, ATTACKER).get(CsrfTokenStore.FB_DTSG));
  }

  @Test
  public void testBrowsingOneAccountDoesNotDisturbTheOther() {
    CsrfTokenStore store = new CsrfTokenStore();
    CsrfScraper.scrape(store, HOST, VICTIM, VICTIM_BODY);
    CsrfScraper.scrape(store, HOST, ATTACKER, ATTACKER_BODY);
    // Browsing the attacker again must not move the victim's token, which is exactly what the
    // host-keyed store did.
    CsrfScraper.scrape(store, HOST, ATTACKER, "[\"DTSGInitialData\",[],{\"token\":\"NEWER\"},1]");

    assertEquals("VICTIM_TOKEN", store.peek(HOST, VICTIM).get(CsrfTokenStore.FB_DTSG));
    assertEquals("NEWER", store.peek(HOST, ATTACKER).get(CsrfTokenStore.FB_DTSG));
  }

  @Test
  public void testUnseenAccountResolvesToNothingRatherThanSomeoneElse() {
    // No fallback to another account on the same host: that is the bug, not the mitigation.
    CsrfTokenStore store = new CsrfTokenStore();
    CsrfScraper.scrape(store, HOST, VICTIM, VICTIM_BODY);

    assertNull(store.peek(HOST, ATTACKER));
    assertNull(store.peek(HOST, CsrfTokenStore.ANONYMOUS));
  }

  @Test
  public void testUnseenHostResolvesToNothing() {
    CsrfTokenStore store = new CsrfTokenStore();
    CsrfScraper.scrape(store, HOST, VICTIM, VICTIM_BODY);

    assertNull(store.peek("www.internalfb.com", VICTIM));
  }

  @Test
  public void testSameAccountOnTwoHostsIsStillSeparate() {
    // Tokens are site-bound as well as session-bound; adding accounts must not weaken that.
    CsrfTokenStore store = new CsrfTokenStore();
    CsrfScraper.scrape(store, HOST, VICTIM, VICTIM_BODY);
    CsrfScraper.scrape(
        store, "www.internalfb.com", VICTIM, "[\"DTSGInitialData\",[],{\"token\":\"INTERN\"},1]");

    assertEquals("VICTIM_TOKEN", store.peek(HOST, VICTIM).get(CsrfTokenStore.FB_DTSG));
    assertEquals("INTERN", store.peek("www.internalfb.com", VICTIM).get(CsrfTokenStore.FB_DTSG));
    assertEquals(2, store.hostCount());
  }

  @Test
  public void testSprinkleConfigIsSharedAcrossAccountsOnAHost() {
    // It rotates by sitevar, not by login. Keeping it per account would make every newly seen
    // account compute a wrong checksum until it happened to observe the config itself.
    CsrfTokenStore store = new CsrfTokenStore();
    CsrfScraper.scrape(
        store,
        HOST,
        VICTIM,
        "[\"SprinkleConfig\",[],{\"param_name\":\"logging\",\"version\":3,"
            + "\"should_randomize\":true},1]");
    CsrfScraper.scrape(store, HOST, ATTACKER, ATTACKER_BODY);

    CsrfTokenStore.SessionTokens attacker = store.peek(HOST, ATTACKER);
    assertNotNull(attacker);
    assertEquals("logging", attacker.sprinkleParamName());
    // Reached through the account that never saw the config itself.
    assertEquals(store.peek(HOST, VICTIM).checksumFor("x"), attacker.checksumFor("x"));
  }

  @Test
  public void testAccountsAreListedForTheUi() {
    CsrfTokenStore store = new CsrfTokenStore();
    CsrfScraper.scrape(store, HOST, VICTIM, VICTIM_BODY);
    CsrfScraper.scrape(store, HOST, ATTACKER, ATTACKER_BODY);

    assertEquals(2, store.forHost(HOST).accounts().size());
    assertTrue(store.forHost(HOST).accounts().contains(VICTIM));
    assertTrue(store.hosts().contains(HOST));
  }

  @Test
  public void testEachAccountGetsItsOwnTokenSet() {
    CsrfTokenStore store = new CsrfTokenStore();
    CsrfTokenStore.HostEntry host = store.forHost(HOST);
    assertNotSame(host.forSession(VICTIM), host.forSession(ATTACKER));
    // Same account, same slot: forSession must not mint a fresh set each call.
    assertEquals(host.forSession(VICTIM), host.forSession(VICTIM));
  }

  @Test
  public void testEmptyTokenSetReportsNoTokens() {
    CsrfTokenStore store = new CsrfTokenStore();
    assertFalse(store.forHost(HOST).forSession(VICTIM).hasAnyToken());
  }
}

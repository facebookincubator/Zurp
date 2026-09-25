/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/**
 * The pattern only picks candidates out of traffic. Whether one is really an FBID is the server's
 * call. These pin the width, because both edges cost something: too narrow and whole object
 * families are invisible, too wide and the hourly budget goes on clock readings.
 *
 * <p>The in-range values below are real allocated id range boundaries, not invented digit strings.
 */
public class ZurpFbidPatternTest {

  private static boolean matchesWhole(String candidate) {
    Matcher matcher = ZurpUtils.FBID_PATTERN.matcher(candidate);
    return matcher.matches();
  }

  private static List<String> findAll(String haystack) {
    List<String> found = new ArrayList<>();
    Matcher matcher = ZurpUtils.FBID_PATTERN.matcher(haystack);
    while (matcher.find()) {
      found.add(matcher.group());
    }
    return found;
  }

  @Test
  public void testFifteenDigitObjectIdMatches() {
    assertTrue(matchesWhole("219749654756339"));
  }

  @Test
  public void testSeventeenDigitObjectIdMatches() {
    // fb_fbid_in_oid_range says true for this one; the old 16 digit ceiling never saw it.
    assertTrue(matchesWhole("10150322842613303"));
  }

  @Test
  public void testInstagramMediaIdMatches() {
    // The 17841400000000000 range is Instagram's. A bug bounty proxy that cannot see it is
    // blind to every Instagram object the researcher is looking at.
    assertTrue(matchesWhole("17841400999120633"));
  }

  @Test
  public void testEighteenDigitObjectIdMatches() {
    // The top allocated OID range runs to 120199999999999999.
    assertTrue(matchesWhole("100200000000000001"));
  }

  @Test
  public void testUnixSecondsDoNotMatch() {
    // 10 digits. Real OIDs do start this low, but so does every clock in every payload.
    assertFalse(matchesWhole("1787616000"));
  }

  @Test
  public void testMillisecondTimestampDoesNotMatch() {
    assertFalse(matchesWhole("1787616000000"));
  }

  @Test
  public void testNanosecondTimestampDoesNotMatch() {
    // 19 digits, one past the ceiling.
    assertFalse(matchesWhole("1787616000000000000"));
  }

  @Test
  public void testMicrosecondTimestampStillMatches() {
    // Known and unavoidable here: 1.78e15 sits inside an allocated GENERAL_64 OID range and even
    // derives a plausible shard, so no client-side rule can exclude it. Only the server knows.
    assertTrue(matchesWhole("1787616000000000"));
  }

  @Test
  public void testIdsAreFoundInsideJson() {
    List<String> found =
        findAll("{\"id\":\"17841400999120633\",\"ts\":1787616000,\"owner\":219749654756339}");

    assertEquals(List.of("17841400999120633", "219749654756339"), found);
  }

  @Test
  public void testALongerRunOfDigitsIsNotSplitIntoIds() {
    // \b at both ends: a 30 digit blob must not yield an 18 digit prefix.
    assertTrue(findAll("123456789012345678901234567890").isEmpty());
  }

  /**
   * Accounts predating the 100... scheme are 14 digits. Both of the real accounts this was first
   * tested against were, so the old 15 floor made exactly the objects a researcher looks up
   * invisible.
   */
  @Test
  public void testFourteenDigitAccountMatches() {
    assertTrue(matchesWhole("59646010200194"));
    assertTrue(matchesWhole("59833010181715"));
  }

  /**
   * The floor stops at 14 because 13 is unix milliseconds, which nearly every request carries.
   * Reaching one digit lower would queue a clock reading per request for nothing.
   */
  @Test
  public void testMillisecondTimestampStaysOutOfRange() {
    assertFalse(matchesWhole("1789952697827"));
    assertEquals(
        List.of("59833010181715"), findAll("{\"id\":59833010181715,\"timestamp\":1789952697827}"));
  }

  private static List<String> findAdAccounts(String haystack) {
    List<String> found = new ArrayList<>();
    Matcher matcher = ZurpUtils.AD_ACCOUNT_PATTERN.matcher(haystack);
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }

  /**
   * The whole point of the separate pattern: {@code _} is a word character, so FBID_PATTERN finds
   * nothing at all in an ad account id however many digits it carries.
   */
  @Test
  public void testTheBareScanCannotSeeAnAdAccount() {
    assertEquals(List.of(), findAll("act_120210000000000001"));
    assertEquals(List.of(), findAll("{\"account_id\":\"act_120210000000000001\"}"));
  }

  @Test
  public void testAnAdAccountYieldsItsFbidWithoutThePrefix() {
    assertEquals(List.of("120210000000000001"), findAdAccounts("act_120210000000000001"));
    assertEquals(
        List.of("120210000000000001"),
        findAdAccounts("{\"account_id\":\"act_120210000000000001\"}"));
    assertEquals(
        List.of("1234567890123456"),
        findAdAccounts(
            "https://adsmanager.facebook.com/adsmanager/manage/campaigns?act=1"
                + "&nav_entry_point=x&act_1234567890123456"));
  }

  @Test
  public void testSeveralAdAccountsInOneBodyAreAllFound() {
    assertEquals(
        List.of("120210000000000001", "120210000000000002"),
        findAdAccounts("from act_120210000000000001 to act_120210000000000002"));
  }

  /** A leading zero is not an FBID, and the endpoint says so explicitly. */
  @Test
  public void testALeadingZeroIsNotAnAdAccount() {
    assertEquals(List.of(), findAdAccounts("act_012345678901234"));
  }

  /** Below the endpoint's own floor there is nothing worth asking about. */
  @Test
  public void testTooShortToBeAnId() {
    assertEquals(List.of(), findAdAccounts("act_12345"));
  }
}

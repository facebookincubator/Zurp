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
 * The pattern only picks candidates out of traffic — whether one is really an FBID is the server's
 * call. These pin the width, because both edges cost something: too narrow and whole object
 * families are invisible, too wide and the hourly budget goes on clock readings.
 *
 * <p>The in-range values below are real allocated OID range boundaries from
 * common/fbid/db_spec.cpp, not invented digit strings.
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
}

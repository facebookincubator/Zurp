/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Two thirds of the ids a page load offers are microsecond clock readings, and each costs a lookup
 * and then a permanent entry in the failed set. These pin the line between one and a real id,
 * because both are 16 digits and only the leading value tells them apart.
 */
public class MetaHttpHandlerClockTest {

  private static String microsecondsFromNow(long offsetMillis) {
    return String.valueOf((System.currentTimeMillis() + offsetMillis) * 1000L + 123L);
  }

  @Test
  public void testAClockReadingIsRecognised() {
    assertTrue(MetaHttpHandler.isMicrosecondClock(microsecondsFromNow(0)));
    assertTrue(MetaHttpHandler.isMicrosecondClock(microsecondsFromNow(-24 * 60 * 60 * 1000L)));
  }

  @Test
  public void testAnIdThatIsNotNearNowIsKept() {
    // A real 16-digit object id. Its leading digits are nowhere near the current epoch.
    assertFalse(MetaHttpHandler.isMicrosecondClock("1086620026436307"));
    assertFalse(MetaHttpHandler.isMicrosecondClock("1287839056366337"));
  }

  @Test
  public void testOnlySixteenDigitsAreConsidered() {
    // Milliseconds are 13 and never reach the object scan; 15 and 18 digit ids must not be judged
    // by a rule about 16-digit clocks.
    assertFalse(MetaHttpHandler.isMicrosecondClock("100071634354523"));
    assertFalse(MetaHttpHandler.isMicrosecondClock("221287839056366337"));
    assertFalse(MetaHttpHandler.isMicrosecondClock(""));
  }
}

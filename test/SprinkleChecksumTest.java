/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.csrf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** Mirrors SprinkleUtils::calculateHashFromToken in www. */
public class SprinkleChecksumTest {

  private static final String LIVE_TOKEN =
      "NAfv09fpTv1Ka-H9s8_aSIZglfGcmrrW6zwhf3ZlF3h571MjbQ2PznA:56:1752837045";

  @Test
  public void testKnownGoodVector() {
    assertEquals("25461", SprinkleChecksum.compute(LIVE_TOKEN, 2));
  }

  @Test
  public void testVersionIsConcatenatedNotAdded() {
    assertEquals("15461", SprinkleChecksum.compute(LIVE_TOKEN, 1));
    assertEquals("5461", SprinkleChecksum.computeWithoutVersion(LIVE_TOKEN));
  }

  @Test
  public void testSumCoversTheCryptoPrefix() {
    // The leading N is part of the token, so dropping it must change the checksum.
    assertNotEquals(
        SprinkleChecksum.compute(LIVE_TOKEN, 2),
        SprinkleChecksum.compute(LIVE_TOKEN.substring(1), 2));
  }

  @Test
  public void testSumCoversTheSessionSuffix() {
    // :session_id:session_creation_time is summed too; nothing is stripped.
    String withoutSuffix = LIVE_TOKEN.substring(0, LIVE_TOKEN.indexOf(':'));
    assertNotEquals(
        SprinkleChecksum.compute(LIVE_TOKEN, 2), SprinkleChecksum.compute(withoutSuffix, 2));
  }

  @Test
  public void testSimpleAsciiSum() {
    // 97 + 98 + 99
    assertEquals("2294", SprinkleChecksum.compute("abc", 2));
  }

  @Test
  public void testEmptyToken() {
    assertEquals("20", SprinkleChecksum.compute("", 2));
  }

  @Test
  public void testSumsBytesNotCodeUnits() {
    // U+00C3 is two bytes in UTF-8 (195 + 131), but a single 195 code unit. The server sums bytes.
    assertEquals("2326", SprinkleChecksum.compute("\u00C3", 2));
  }
}

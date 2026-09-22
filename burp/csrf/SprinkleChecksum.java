/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.csrf;

import java.nio.charset.StandardCharsets;

/**
 * Computes the sprinkle checksum (historically the "jazoest" parameter) that accompanies a CSRF
 * token on the wire.
 *
 * <p>Mirrors SprinkleUtils::calculateHashFromToken in www.
 */
public final class SprinkleChecksum {

  public static final int DEFAULT_VERSION = 2;

  private SprinkleChecksum() {}

  /**
   * @param token the complete token as it appears on the wire, including any crypto prefix and the
   *     trailing {@code :session_id:session_creation_time}
   */
  public static String compute(String token, int version) {
    return Integer.toString(version) + byteSum(token);
  }

  /**
   * Used when the sitevar has randomization enabled, in which case the version prefix is absent.
   */
  public static String computeWithoutVersion(String token) {
    return Integer.toString(byteSum(token));
  }

  // The server sums raw bytes, so a non-ASCII token must not be summed as UTF-16 code units.
  private static int byteSum(String token) {
    int sum = 0;
    for (byte b : token.getBytes(StandardCharsets.UTF_8)) {
      sum += (b & 0xFF);
    }
    return sum;
  }
}

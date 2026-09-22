/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import burp.zurp.ZurpLog.Level;
import org.junit.jupiter.api.Test;

/**
 * The level round-trips through a Preferences string, so anything at all can come back out of it —
 * including a value written by a version of Zurp that named its levels differently.
 */
public class ZurpLogLevelTest {

  @Test
  public void testUnwrittenPreferenceFallsBackRatherThanGoingSilent() {
    assertEquals(Level.VERBOSE, Level.parse(null, Level.VERBOSE));
  }

  @Test
  public void testUnrecognisedValueFallsBack() {
    assertEquals(Level.NONE, Level.parse("TRACE", Level.NONE));
  }

  @Test
  public void testEmptyValueFallsBack() {
    assertEquals(Level.NONE, Level.parse("", Level.NONE));
  }

  @Test
  public void testEveryLevelRoundTripsThroughItsName() {
    for (Level level : Level.values()) {
      assertEquals(level, Level.parse(level.name(), Level.NONE));
    }
  }

  @Test
  public void testCaseAndSurroundingSpaceAreTolerated() {
    assertEquals(Level.DEBUG, Level.parse("  debug ", Level.NONE));
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.models.FbdlRunModel;
import burp.models.FbdlRunStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Covers the status mapping and the staleness bound. The store and the HTTP path are Burp-provided
 * and cannot be exercised offline, so they are verified in Burp instead.
 */
public class FbdlRunFetcherTest {

  private static final long NOW = TimeUnit.DAYS.toMillis(1000);

  private static FbdlRunModel run(FbdlRunStatus status, long creationTimeSeconds) {
    return new FbdlRunModel(
        "1075679148163220",
        status,
        creationTimeSeconds,
        "run_code",
        "note",
        new LinkedHashMap<>(),
        "");
  }

  @Test
  public void testStatusesParseFromTheirWireValues() {
    assertEquals(FbdlRunStatus.RUNNING, FbdlRunStatus.fromWireValue("Running"));
    assertEquals(FbdlRunStatus.COMPLETED, FbdlRunStatus.fromWireValue("Completed"));
    assertEquals(FbdlRunStatus.FAILED, FbdlRunStatus.fromWireValue("Failed"));
    assertEquals(FbdlRunStatus.UNKNOWN, FbdlRunStatus.fromWireValue("Unknown"));
  }

  @Test
  public void testUnrecognisedStatusReadsAsUnknown() {
    // A status added server-side must not throw on a researcher running an older build.
    assertEquals(FbdlRunStatus.UNKNOWN, FbdlRunStatus.fromWireValue("Cancelled"));
    assertEquals(FbdlRunStatus.UNKNOWN, FbdlRunStatus.fromWireValue(""));
    assertEquals(FbdlRunStatus.UNKNOWN, FbdlRunStatus.fromWireValue(null));
  }

  @Test
  public void testWireValuesAreNotTheEnumNames() {
    // The API sends title case; comparing against name() would silently never match.
    assertEquals("Running", FbdlRunStatus.RUNNING.wireValue());
    assertEquals("Completed", FbdlRunStatus.COMPLETED.wireValue());
  }

  @Test
  public void testOnlySettledStatusesAreTerminal() {
    assertTrue(FbdlRunStatus.COMPLETED.isTerminal());
    assertTrue(FbdlRunStatus.FAILED.isTerminal());
    assertFalse(FbdlRunStatus.RUNNING.isTerminal());
    // Unknown means the server could not say, which is a reason to ask again.
    assertFalse(FbdlRunStatus.UNKNOWN.isTerminal());
  }

  @Test
  public void testFreshRunIsNotStale() {
    long oneHourAgo = TimeUnit.MILLISECONDS.toSeconds(NOW - TimeUnit.HOURS.toMillis(1));
    assertFalse(FbdlRunFetcher.isStale(run(FbdlRunStatus.RUNNING, oneHourAgo), NOW));
  }

  @Test
  public void testRunPendingLongerThanADayIsStale() {
    long twoDaysAgo = TimeUnit.MILLISECONDS.toSeconds(NOW - TimeUnit.DAYS.toMillis(2));
    assertTrue(FbdlRunFetcher.isStale(run(FbdlRunStatus.RUNNING, twoDaysAgo), NOW));
  }

  @Test
  public void testRunWithNoCreationTimeIsStale() {
    // Reads as 0, i.e. 1970, so it must not be polled forever.
    assertTrue(FbdlRunFetcher.isStale(run(FbdlRunStatus.RUNNING, 0L), NOW));
  }

  @Test
  public void testCreationTimeIsReadAsSecondsNotMillis() {
    // Treating unix seconds as millis would make every run look 55 years old, so every pending
    // run would be abandoned on its first tick.
    long nowInSeconds = TimeUnit.MILLISECONDS.toSeconds(NOW);
    assertFalse(FbdlRunFetcher.isStale(run(FbdlRunStatus.RUNNING, nowInSeconds), NOW));
  }

  @Test
  public void testResultsAreExposedInServerOrder() {
    Map<String, String> results = new LinkedHashMap<>();
    results.put("UserOne.uid", "61593714297140");
    results.put("UserOne.password", "707e63vam5a");
    FbdlRunModel model =
        new FbdlRunModel("1", FbdlRunStatus.COMPLETED, 1L, "code", "note", results, "");
    assertEquals("[UserOne.uid, UserOne.password]", model.results.keySet().toString());
  }
}

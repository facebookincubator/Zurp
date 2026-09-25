/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Burp's PersistedList yields its own element type, not String. These pin the conversion that keeps
 * that off the rest of the fetcher.
 */
public class ZurpDataFetcherLoadTest {

  /** Stands in for the internal type a real PersistedList hands back. */
  private static final class NotAString {
    private final String value;

    NotAString(String value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return value;
    }
  }

  @Test
  public void testPlainStringsSurviveUnchanged() {
    Set<String> values = ZurpDataFetcher.toStringSet(Arrays.asList("123", "456"));
    assertEquals(Set.of("123", "456"), values);
  }

  @Test
  public void testForeignElementTypeIsConvertedRatherThanThrowing() {
    List<Object> persisted = new ArrayList<>();
    persisted.add(new NotAString("789"));
    Set<String> values = ZurpDataFetcher.toStringSet(persisted);
    assertEquals(Set.of("789"), values);
  }

  @Test
  public void testMixedElementTypesAllLand() {
    List<Object> persisted = new ArrayList<>();
    persisted.add("123");
    persisted.add(new NotAString("456"));
    assertEquals(Set.of("123", "456"), ZurpDataFetcher.toStringSet(persisted));
  }

  @Test
  public void testConvertedValuesAreUsableAsLookupKeys() {
    List<Object> persisted = new ArrayList<>();
    persisted.add(new NotAString("789"));
    // The bug this replaces: contains() against a String never matched, so nothing was ever
    // recognised as already queued, failed or stored.
    assertTrue(ZurpDataFetcher.toStringSet(persisted).contains("789"));
  }

  @Test
  public void testDuplicatesCollapse() {
    assertEquals(1, ZurpDataFetcher.toStringSet(Arrays.asList("123", "123")).size());
  }

  @Test
  public void testNullElementsAreDropped() {
    List<Object> persisted = new ArrayList<>();
    persisted.add(null);
    persisted.add("123");
    assertEquals(Set.of("123"), ZurpDataFetcher.toStringSet(persisted));
  }

  @Test
  public void testAbsentListReadsAsEmpty() {
    assertTrue(ZurpDataFetcher.toStringSet(null).isEmpty());
  }
}

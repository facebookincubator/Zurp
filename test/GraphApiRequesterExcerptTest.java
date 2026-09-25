/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** DEBUG prints error bodies, and an error body can be a whole HTML page. */
public class GraphApiRequesterExcerptTest {

  @Test
  public void testShortBodyIsPrintedWhole() {
    assertEquals("{\"error\":\"nope\"}", GraphApiRequester.excerpt("{\"error\":\"nope\"}"));
  }

  @Test
  public void testEmptyBodyStaysEmpty() {
    assertEquals("", GraphApiRequester.excerpt(""));
  }

  @Test
  public void testNullBodyDoesNotThrow() {
    assertEquals("", GraphApiRequester.excerpt(null));
  }

  @Test
  public void testLongBodyIsTruncatedAndSaysHowLongItWas() {
    String body = "x".repeat(2000);
    String excerpt = GraphApiRequester.excerpt(body);

    assertTrue(excerpt.startsWith("x".repeat(512)), excerpt);
    assertTrue(excerpt.endsWith("… (2000 bytes)"), excerpt);
  }

  @Test
  public void testBodyExactlyAtTheLimitIsNotTruncated() {
    String body = "x".repeat(512);
    assertEquals(body, GraphApiRequester.excerpt(body));
  }
}

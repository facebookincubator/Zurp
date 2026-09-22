/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers which statuses count as success. Sending itself needs Burp's HTTP stack and is verified
 * there.
 */
public class GraphApiRequesterStatusTest {

  @Test
  public void testOkIsSuccess() {
    assertTrue(GraphApiRequester.isSuccess(200));
  }

  @Test
  public void testCreatedIsSuccess() {
    // Create and archive answer 201 and put the new run id in the body. Treating only 200 as
    // success would drop it and report the run as refused.
    assertTrue(GraphApiRequester.isSuccess(201));
  }

  @Test
  public void testOtherTwoHundredsAreSuccess() {
    assertTrue(GraphApiRequester.isSuccess(202));
    assertTrue(GraphApiRequester.isSuccess(204));
    assertTrue(GraphApiRequester.isSuccess(299));
  }

  @Test
  public void testRedirectsAreNotSuccess() {
    // Burp follows redirects itself, so one reaching here is not an answer we can parse.
    assertFalse(GraphApiRequester.isSuccess(300));
    assertFalse(GraphApiRequester.isSuccess(302));
  }

  @Test
  public void testClientAndServerErrorsAreNotSuccess() {
    assertFalse(GraphApiRequester.isSuccess(400));
    assertFalse(GraphApiRequester.isSuccess(403));
    assertFalse(GraphApiRequester.isSuccess(404));
    assertFalse(GraphApiRequester.isSuccess(429));
    assertFalse(GraphApiRequester.isSuccess(500));
  }

  @Test
  public void testNotAttemptedIsNotSuccess() {
    // Zero is Zurp's own "nobody answered", not a status a server ever sends.
    assertFalse(GraphApiRequester.isSuccess(GraphApiRequester.NOT_ATTEMPTED));
    assertFalse(GraphApiRequester.isSuccess(199));
  }
}

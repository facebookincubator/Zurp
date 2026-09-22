/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The base URL is typed in by hand, so the join has to survive both spellings of it. */
public class GraphApiRequesterUrlTest {

  private static final String ENDPOINT = "bug_bounty/sparta_findings";

  @Test
  public void testProdDefaultReachesTheStefiPath() {
    assertEquals(
        "https://api.facebook.com/bug_bounty/sparta_findings",
        GraphApiRequester.join("https://api.facebook.com/", ENDPOINT));
  }

  @Test
  public void testDevserverOverrideReachesTheStefiPath() {
    assertEquals(
        "https://api.devvm60696.lla0.facebook.com/bug_bounty/sparta_findings",
        GraphApiRequester.join("https://api.devvm60696.lla0.facebook.com/", ENDPOINT));
  }

  @Test
  public void testMissingTrailingSlashStillJoins() {
    assertEquals(
        "https://api.facebook.com/bug_bounty/sparta_findings",
        GraphApiRequester.join("https://api.facebook.com", ENDPOINT));
  }

  @Test
  public void testRepeatedTrailingSlashesCollapse() {
    assertEquals(
        "https://api.facebook.com/bug_bounty/sparta_findings",
        GraphApiRequester.join("https://api.facebook.com///", ENDPOINT));
  }

  @Test
  public void testLeadingSlashOnEndpointIsNotDoubled() {
    assertEquals(
        "https://api.facebook.com/bug_bounty/sparta_findings",
        GraphApiRequester.join("https://api.facebook.com/", "/" + ENDPOINT));
  }

  @Test
  public void testPathPrefixOnBaseUrlIsPreserved() {
    assertEquals(
        "https://api.facebook.com/v1/bug_bounty/sparta_findings",
        GraphApiRequester.join("https://api.facebook.com/v1/", ENDPOINT));
  }

  @Test
  public void testFirstQueryParamOpensTheQueryString() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("target_id", "SomeController");
    params.put("limit", "100");
    assertEquals("?target_id=SomeController&limit=100", GraphApiRequester.query(params));
  }

  @Test
  public void testNoQueryParamsMeansNoQuestionMark() {
    assertEquals("", GraphApiRequester.query(new LinkedHashMap<>()));
  }

  @Test
  public void testNullValuedParamIsSkippedWithoutStrandingASeparator() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("after", null);
    params.put("limit", "100");
    assertEquals("?limit=100", GraphApiRequester.query(params));
  }

  @Test
  public void testQueryValuesAreEncoded() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("target_id", "a b&c=d");
    assertEquals("?target_id=a+b%26c%3Dd", GraphApiRequester.query(params));
  }
}

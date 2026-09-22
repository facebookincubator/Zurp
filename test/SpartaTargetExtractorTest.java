/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.models.SpartaTarget;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Fixtures mirror the three shapes a GraphQL call reaches www in: the urlencoded form body Comet
 * posts, a query string, and the JSON body the mobile clients send.
 */
public class SpartaTargetExtractorTest {

  private static final String URL = "https://www.facebook.com/api/graphql/";

  private Set<String> keys(String url, String body) {
    return SpartaTargetExtractor.extract(url, body).stream()
        .map(SpartaTarget::key)
        .collect(Collectors.toSet());
  }

  @Test
  public void testFormBodyYieldsDocIdAndFriendlyName() {
    Set<String> keys =
        keys(
            URL,
            "av=100000000000001&fb_dtsg=NAf&fb_api_req_friendly_name=CometHomeRootQuery"
                + "&variables=%7B%7D&doc_id=24012345678901234");

    assertEquals(
        new HashSet<>(
            Set.of("published_doc_id|24012345678901234", "endpoint_name|CometHomeRootQuery")),
        keys);
  }

  @Test
  public void testQueryStringYieldsTargets() {
    Set<String> keys =
        keys(URL + "?doc_id=98765432109876&fb_api_req_friendly_name=ProfileCometQuery", null);

    assertTrue(keys.contains("published_doc_id|98765432109876"));
    assertTrue(keys.contains("endpoint_name|ProfileCometQuery"));
  }

  @Test
  public void testJsonBodyYieldsTargets() {
    Set<String> keys =
        keys(
            URL,
            "{\"fb_api_req_friendly_name\": \"IGAppQuery\", \"doc_id\": \"12345678901\","
                + " \"variables\": {}}");

    assertTrue(keys.contains("published_doc_id|12345678901"));
    assertTrue(keys.contains("endpoint_name|IGAppQuery"));
  }

  @Test
  public void testUnquotedJsonNumberDocIdIsFound() {
    assertTrue(keys(URL, "{\"doc_id\":12345678901}").contains("published_doc_id|12345678901"));
  }

  @Test
  public void testBodyStartingWithTheKeyIsFound() {
    assertTrue(keys(URL, "doc_id=12345678901&av=1").contains("published_doc_id|12345678901"));
  }

  @Test
  public void testSuffixedParameterIsNotMistakenForDocId() {
    // The delimiter class excludes '_', so a longer parameter ending in doc_id must not match.
    assertTrue(keys(URL, "&attachment_doc_id=12345678901").isEmpty());
  }

  @Test
  public void testShortNumbersAreIgnored() {
    // Persisted document ids are long; a short value is some other field that happens to be named
    // doc_id, and querying it would only spend rate limit.
    assertTrue(keys(URL, "&doc_id=42").isEmpty());
  }

  @Test
  public void testRepeatedTargetsAreDeduplicated() {
    assertEquals(
        1, keys(URL + "?doc_id=12345678901", "doc_id=12345678901&doc_id=12345678901").size());
  }

  @Test
  public void testNoTargetsInOrdinaryTraffic() {
    assertTrue(keys("https://www.facebook.com/", "<html><body>hello</body></html>").isEmpty());
  }

  @Test
  public void testNullInputsAreSafe() {
    assertTrue(keys(null, null).isEmpty());
  }
}

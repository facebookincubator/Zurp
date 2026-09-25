/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.models.SpartaTarget;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Covers the adaptation from SPARTA's identifiers to the ones the asset endpoint accepts. */
public class MetaAssetQueriesTest {

  private static Set<SpartaTarget> targets(SpartaTarget... values) {
    return new LinkedHashSet<>(List.of(values));
  }

  @Test
  public void testDocIdKeepsItsPrefix() {
    // the asset endpoint matches the digits with a lookbehind on doc_id. Sent bare, the same
    // number is read as an object id and resolves to something else entirely.
    assertEquals(
        "doc_id=24123456789",
        MetaAssetQueries.queryFor(SpartaTarget.publishedDocId("24123456789")));
  }

  @Test
  public void testOperationNameEndingInQueryIsKept() {
    assertEquals(
        "CometFeedQuery", MetaAssetQueries.queryFor(SpartaTarget.endpointName("CometFeedQuery")));
  }

  @Test
  public void testOperationNameEndingInMutationIsKept() {
    assertEquals(
        "AdAccountCreateMutation",
        MetaAssetQueries.queryFor(SpartaTarget.endpointName("AdAccountCreateMutation")));
  }

  @Test
  public void testNameWithoutTheSuffixIsDropped() {
    // SPARTA accepts any shortname; the asset endpoint only treats a name as GraphQL when it ends
    // this way, so anything else would spend budget to resolve nothing.
    assertNull(MetaAssetQueries.queryFor(SpartaTarget.endpointName("CometFeedRoute")));
    assertNull(MetaAssetQueries.queryFor(SpartaTarget.endpointName("BusinessManagerController")));
  }

  @Test
  public void testSuffixMustEndTheName() {
    assertTrue(MetaAssetQueries.isGraphqlOperation("FooQuery"));
    assertTrue(MetaAssetQueries.isGraphqlOperation("FooMutation"));
    assertTrue(!MetaAssetQueries.isGraphqlOperation("QueryFoo"));
    assertTrue(!MetaAssetQueries.isGraphqlOperation("MutationBuilder"));
  }

  @Test
  public void testEmptyIdentifierIsDropped() {
    assertNull(MetaAssetQueries.queryFor(SpartaTarget.endpointName("")));
    assertNull(MetaAssetQueries.queryFor(null));
  }

  @Test
  public void testBothKindsComeBackInOrder() {
    List<String> queries =
        MetaAssetQueries.fromTargets(
            targets(
                SpartaTarget.publishedDocId("1001"),
                SpartaTarget.endpointName("ShopQuery"),
                SpartaTarget.endpointName("NotGraphql")));
    assertEquals(List.of("doc_id=1001", "ShopQuery"), queries);
  }

  @Test
  public void testTheSameQueryIsNotRepeated() {
    // A doc id can appear in both the URL and the body of one request.
    List<String> queries =
        MetaAssetQueries.fromTargets(
            targets(SpartaTarget.publishedDocId("1001"), SpartaTarget.endpointName("AQuery")));
    assertEquals(2, queries.size());
    assertEquals(List.of("doc_id=1001", "AQuery"), queries);
  }

  @Test
  public void testNoTargetsIsAnEmptyListNotNull() {
    assertTrue(MetaAssetQueries.fromTargets(targets()).isEmpty());
    assertTrue(MetaAssetQueries.fromTargets(null).isEmpty());
  }

  @Test
  public void testRealObservedOperationNames() {
    assertEquals(
        List.of("doc_id=8902413049861666", "JustKnobsAsyncGetBoolWithoutHashvalQuery"),
        MetaAssetQueries.fromTargets(
            targets(
                SpartaTarget.publishedDocId("8902413049861666"),
                SpartaTarget.endpointName("JustKnobsAsyncGetBoolWithoutHashvalQuery"))));
    assertEquals(
        List.of("doc_id=24417057497954600", "useUserSessionDataMutation"),
        MetaAssetQueries.fromTargets(
            targets(
                SpartaTarget.publishedDocId("24417057497954600"),
                SpartaTarget.endpointName("useUserSessionDataMutation"))));
  }
}

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

import org.junit.jupiter.api.Test;

/**
 * One identifier resolves to several assets of several kinds, and every Zurp model holds exactly
 * one. These pin the narrowing the fetchers rely on, in particular that a miss reads as "" rather
 * than null: both of them branch on isEmpty().
 */
public class AssetResolverAssetsTest {

  private static final String CONTEXT = "10006412345678";

  @Test
  public void testTheAssetOfTheWantedKindIsReturned() {
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assets.add(AssetResolver.ENT_OR_NODE, "EntPhoto");

    assertEquals("EntPhoto", assets.one(AssetResolver.ENT_OR_NODE, CONTEXT));
  }

  @Test
  public void testAnUnresolvedKindReadsAsEmptyRatherThanNull() {
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assets.add(AssetResolver.XCONTROLLER, "SomeXController");

    assertEquals("", assets.one(AssetResolver.ENT_OR_NODE, CONTEXT));
  }

  @Test
  public void testNothingResolvedAtAllReadsAsEmpty() {
    assertEquals("", new AssetResolver.Assets().one(AssetResolver.XCONTROLLER, CONTEXT));
  }

  @Test
  public void
      testKindsDoNotBleedIntoEachOther() { // The case that makes one endpoint viable for both
    // fetchers: a URL carrying an FBID in its
    // query string resolves to both kinds at once, and each fetcher must see only its own.
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assets.add(AssetResolver.XCONTROLLER, "SomeXController");
    assets.add(AssetResolver.ENT_OR_NODE, "EntPhoto");

    assertEquals("SomeXController", assets.one(AssetResolver.XCONTROLLER, CONTEXT));
    assertEquals("EntPhoto", assets.one(AssetResolver.ENT_OR_NODE, CONTEXT));
  }

  @Test
  public void testTheFirstOfSeveralOfAKindWins() {
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assets.add(AssetResolver.XCONTROLLER, "FirstXController");
    assets.add(AssetResolver.XCONTROLLER, "SecondXController");

    // Not joined: MetaUrlInfoModel.controllerName is handed to SpartaTarget.endpointName, which
    // would then query for a controller by a name no controller has.
    assertEquals("FirstXController", assets.one(AssetResolver.XCONTROLLER, CONTEXT));
  }

  @Test
  public void testAnAssetMissingEitherHalfIsDropped() {
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assets.add(AssetResolver.ENT_OR_NODE, null);
    assets.add(AssetResolver.ENT_OR_NODE, "");
    assets.add(null, "EntPhoto");
    assets.add("", "EntPhoto");

    assertEquals("", assets.one(AssetResolver.ENT_OR_NODE, CONTEXT));
  }

  @Test
  public void testADroppedAssetDoesNotHideALaterGoodOne() {
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assets.add(AssetResolver.ENT_OR_NODE, "");
    assets.add(AssetResolver.ENT_OR_NODE, "EntPhoto");

    assertEquals("EntPhoto", assets.one(AssetResolver.ENT_OR_NODE, CONTEXT));
  }

  @Test
  public void testAnUnknownKindIsCarriedRatherThanRejected() {
    // graphql and bloks have no model yet. They must still survive the parse, so adding one later
    // is a new reader and nothing else.
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assets.add("graphql", "SomeQuery");

    assertEquals("SomeQuery", assets.one("graphql", CONTEXT));
  }

  @Test
  public void testAbsentVanityReadsAsEmptyRatherThanNull() {
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assertEquals("", assets.objectName());

    // What the endpoint sends for an object with no vanity, which is most of them.
    assets.setObjectName(null);
    assertTrue(assets.objectName().isEmpty());
  }

  @Test
  public void testVanityIsCarriedWhenThereIsOne() {
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assets.setObjectName("zuck");

    assertEquals("zuck", assets.objectName());
  }

  @Test
  public void testTheNewerKindsAreReadBackToo() {
    // One request can name a GraphQL operation and a Graph edge as well as the two older kinds.
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assets.add(AssetResolver.GRAPHQL, "CometFeedQuery");
    assets.add(AssetResolver.GRAPH_EDGE, "/me/accounts");

    assertEquals("CometFeedQuery", assets.one(AssetResolver.GRAPHQL, CONTEXT));
    assertEquals("/me/accounts", assets.one(AssetResolver.GRAPH_EDGE, CONTEXT));
    assertEquals("", assets.one(AssetResolver.XCONTROLLER, CONTEXT));
  }

  @Test
  public void testAnUnknownKindIsDataRatherThanAFailure() {
    // The type field is an open string: the server can grow a kind at any time, and a client that
    // does not know it yet must not treat the response as broken.
    AssetResolver.Assets assets = new AssetResolver.Assets();
    assets.add("some_future_kind", "Whatever");

    assertEquals("Whatever", assets.one("some_future_kind", CONTEXT));
    assertEquals("", assets.one(AssetResolver.GRAPHQL, CONTEXT));
  }

  /**
   * The batch path settles an identifier on emptiness alone, without a second call, so the
   * difference between "resolved to nothing" and "carries assets" has to be exact.
   */
  @Test
  public void testEmptyIsTheAbsenceOfEveryKind() {
    AssetResolver.Assets none = new AssetResolver.Assets();
    assertTrue(none.isEmpty());

    // Rejected entries leave it empty: a blank name is not an asset.
    none.add(AssetResolver.GRAPHQL, "");
    none.add("", "SomethingQuery");
    assertTrue(none.isEmpty());

    none.add(AssetResolver.GRAPHQL, "ShopQuery");
    assertFalse(none.isEmpty());
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.models.SpartaFindingModel;
import org.junit.jupiter.api.Test;

/** Covers the SPARTA detail text. Selection wiring is Swing and is verified in Burp. */
public class SpartaDetailTest {

  private static SpartaFindingModel finding() {
    return new SpartaFindingModel(
        "7f3d9c2e-1a4b-4c8d-9e0f-2b3a4c5d6e7f",
        "Account contact details returned to an unrelated caller",
        "An ownership check is missing on the requested account.",
        "high",
        "24012345678901234",
        "published_doc_id",
        "24012345678901234",
        "{\"id\":\"1\"}",
        "{\"{{TEST_USER_ID}}\":\"FBID of a whitehat test user you own\"}",
        1785161743L);
  }

  @Test
  public void testNullFindingPromptsASelection() {
    assertEquals(
        "Select a finding to see its summary and proof of concept.",
        ZurpTabComponent.spartaDetailText(null));
  }

  @Test
  public void testDetailCarriesSummaryAndProofOfConcept() {
    String detail = ZurpTabComponent.spartaDetailText(finding());

    assertTrue(detail.contains("SPARTA HIGH"));
    assertTrue(detail.contains("Account contact details returned to an unrelated caller"));
    assertTrue(detail.contains("An ownership check is missing"));
    assertTrue(detail.contains("{\"id\":\"1\"}"));
    assertTrue(detail.contains("{{TEST_USER_ID}}"));
  }

  @Test
  public void testBareFindingOmitsEmptySections() {
    SpartaFindingModel bare =
        new SpartaFindingModel("id-1", "", "", "", "", "", "", "", "", 0L);

    assertEquals("SPARTA [id-1]", ZurpTabComponent.spartaDetailText(bare));
  }
}

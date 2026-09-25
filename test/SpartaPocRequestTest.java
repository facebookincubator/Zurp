/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.models.SpartaFindingModel;
import org.junit.jupiter.api.Test;

/**
 * The body and the notes only; assembling the {@code HttpRequest} around them needs Burp's own
 * factory, which exists only inside a loaded extension.
 */
public class SpartaPocRequestTest {

  private static final String FINDING_ID = "7f3d9c2e-1a4b-4c8d-9e0f-2b3a4c5d6e7f";

  private static SpartaFindingModel finding(
      String pocDocId, String variablesJson, String targetId, String targetType) {
    return new SpartaFindingModel(
        FINDING_ID,
        "Account contact details returned to an unrelated caller",
        "An ownership check is missing on the requested account.",
        "high",
        targetId,
        targetType,
        pocDocId,
        variablesJson,
        "{\"{{TEST_USER_ID}}\":\"FBID of a whitehat test user you own\"}",
        1785161743L);
  }

  private static SpartaFindingModel docIdFinding(String variablesJson) {
    return finding("24012345678901234", variablesJson, "24012345678901234", "published_doc_id");
  }

  @Test
  public void testDocumentPocBecomesAFormBody() {
    assertEquals(
        "fb_dtsg={{fb_dtsg}}&variables=%7B%22id%22%3A%221%22%7D&doc_id=24012345678901234",
        SpartaPocRequest.body(docIdFinding("{\"id\":\"1\"}")));
  }

  @Test
  public void testPlaceholderBracesStayReadable() {
    // The researcher has to find and replace each token by hand, so %7B%7B would defeat the point.
    assertTrue(
        SpartaPocRequest.body(docIdFinding("{\"user_id\":\"{{TEST_USER_ID}}\"}"))
            .contains("{{TEST_USER_ID}}"));
  }

  @Test
  public void testAValueCannotBreakOutOfTheFormBody() {
    // Only the braces are unescaped. Anything else that would start a new parameter stays encoded.
    String body = SpartaPocRequest.body(docIdFinding("{\"q\":\"a&doc_id=99\"}"));

    assertTrue(body.contains("a%26doc_id%3D99"));
    assertTrue(body.endsWith("&doc_id=24012345678901234"));
  }

  @Test
  public void testAbsentVariablesBecomeAnEmptyObject() {
    assertTrue(SpartaPocRequest.body(docIdFinding("")).contains("&variables=%7B%7D&"));
  }

  @Test
  public void testOperationPocIsCalledByName() {
    String body = SpartaPocRequest.body(finding("CometHomeRootQuery", "{}", "", ""));

    assertTrue(body.contains("&fb_api_req_friendly_name=CometHomeRootQuery"));
    // No persisted id to send, and a made-up one would be worse than none.
    assertFalse(body.contains("doc_id="));
  }

  @Test
  public void testATargetSuppliesWhateverThePocDoesNot() {
    // A PoC named after its operation, raised against a document: both are known, so send both.
    String body =
        SpartaPocRequest.body(
            finding("CometHomeRootQuery", "{}", "24012345678901234", "published_doc_id"));

    assertTrue(body.contains("&fb_api_req_friendly_name=CometHomeRootQuery"));
    assertTrue(body.contains("&doc_id=24012345678901234"));
  }

  @Test
  public void testAnEndpointTargetNamesTheOperation() {
    String body = SpartaPocRequest.body(finding("", "{}", "ProfileCometQuery", "endpoint_name"));

    assertTrue(body.contains("&fb_api_req_friendly_name=ProfileCometQuery"));
  }

  @Test
  public void testNothingToCallProducesNoRequest() {
    assertNull(SpartaPocRequest.body(finding("", "{}", "", "")));
    assertNull(SpartaPocRequest.body(null));
  }

  @Test
  public void testNotesCarryWhatTheBodyCannot() {
    String notes = SpartaPocRequest.notes(docIdFinding("{}"));

    assertEquals(
        "SPARTA HIGH: Account contact details returned to an unrelated caller ["
            + FINDING_ID
            + "]\nSubstitute before sending: "
            + "{\"{{TEST_USER_ID}}\":\"FBID of a whitehat test user you own\"}",
        notes);
  }

  @Test
  public void testNotesTolerateAFindingWithNoProse() {
    SpartaFindingModel bare =
        new SpartaFindingModel(FINDING_ID, "", "", "", "", "", "", "", "", 0L);

    assertEquals("SPARTA [" + FINDING_ID + "]", SpartaPocRequest.notes(bare));
  }
}

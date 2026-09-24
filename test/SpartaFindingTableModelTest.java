/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Covers the table model's pure parts. The table itself is Swing and is verified in Burp. */
public class SpartaFindingTableModelTest {

  private static SpartaFindingModel finding(String id) {
    return new SpartaFindingModel(
        id,
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
  public void testResetReplacesRatherThanAppends() {
    SpartaFindingTableModel model = new SpartaFindingTableModel();
    model.reset(List.of(finding("id-1"), finding("id-2")));
    model.reset(List.of(finding("id-3")));
    assertEquals(1, model.getRowCount());
    assertEquals("id-3", model.getValueAt(0, 6));
  }

  @Test
  public void testRowOutOfRangeIsNullRatherThanThrowing() {
    // The selection listener can fire against a row the model no longer has, after a Refresh.
    SpartaFindingTableModel model = new SpartaFindingTableModel();
    model.reset(List.of(finding("id-1")));
    assertNull(model.getRow(-1));
    assertNull(model.getRow(1));
    assertEquals("id-1", model.getRow(0).bbFindingId);
  }

  @Test
  public void testDuplicateFindingIdsShownOnce() {
    SpartaFindingTableModel model = new SpartaFindingTableModel();
    model.reset(List.of(finding("id-1"), finding("id-1")));
    assertEquals(1, model.getRowCount());
  }
}

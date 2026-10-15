/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import burp.models.SpartaFindingModel;
import burp.models.SpartaFindingTableModel;
import java.awt.Color;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Covers the color lookup. Painting itself is Swing and is verified in Burp. */
public class SpartaFindingTableTest {

  @Test
  public void testNamedColorsResolve() {
    assertEquals(
        new Color(255, 214, 214), SpartaFindingTable.colorForName("Red"));
    assertEquals(
        new Color(255, 235, 214), SpartaFindingTable.colorForName("Orange"));
    assertEquals(
        new Color(255, 248, 214), SpartaFindingTable.colorForName("Yellow"));
    assertEquals(
        new Color(214, 245, 214), SpartaFindingTable.colorForName("Green"));
    assertEquals(
        new Color(214, 232, 255), SpartaFindingTable.colorForName("Blue"));
    assertEquals(
        new Color(232, 214, 255), SpartaFindingTable.colorForName("Purple"));
  }

  @Test
  public void testUnknownNameLeavesTheRowToTheTheme() {
    assertNull(SpartaFindingTable.colorForName("Invisible"));
    assertNull(SpartaFindingTable.colorForName(""));
    assertNull(SpartaFindingTable.colorForName(null));
  }

  @Test
  public void testTintStaysOnItsOwnRow() {
    // The cell renderer is shared, so a tint left on it bleeds into every row after.
    SpartaFindingTableModel model = new SpartaFindingTableModel();
    model.reset(List.of(finding("id-1"), finding("id-2")));
    SpartaFindingTable table = new SpartaFindingTable(model);

    table.setRowColor("id-1", SpartaFindingTable.colorForName("Red"));

    // Read the color at once: both calls return the same shared renderer instance.
    Color row0 = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0).getBackground();
    Color row1 = table.prepareRenderer(table.getCellRenderer(1, 0), 1, 0).getBackground();

    assertEquals(SpartaFindingTable.colorForName("Red"), row0);
    assertEquals(table.getBackground(), row1);
  }

  @Test
  public void testSelectedCellsTextCopiesARangeAsTsv() {
    SpartaFindingTableModel model = new SpartaFindingTableModel();
    model.reset(List.of(finding("id-1"), finding("id-2"), finding("id-3")));
    SpartaFindingTable table = new SpartaFindingTable(model);

    // Priority and Target of the first two findings.
    table.setRowSelectionInterval(0, 1);
    table.setColumnSelectionInterval(0, 1);

    assertEquals("high\t1\nhigh\t1", table.selectedCellsText());
  }

  @Test
  public void testSelectedCellsTextIsEmptyWithoutSelection() {
    SpartaFindingTableModel model = new SpartaFindingTableModel();
    model.reset(List.of(finding("id-1")));
    SpartaFindingTable table = new SpartaFindingTable(model);

    assertEquals("", table.selectedCellsText());
  }

  @Test
  public void testSelectedFindingsTextCoversEverySelectedRow() {
    SpartaFindingTableModel model = new SpartaFindingTableModel();
    model.reset(List.of(finding("id-1"), finding("id-2")));
    SpartaFindingTable table = new SpartaFindingTable(model);

    table.setRowSelectionInterval(0, 1);

    String text = table.selectedFindingsText();
    assertTrue(text.contains("Finding ID: id-1"));
    assertTrue(text.contains("Finding ID: id-2"));
    // A blank line separates one finding's details from the next.
    assertTrue(text.contains("\n\nFinding ID: id-2"));
  }

  private static SpartaFindingModel finding(String id) {
    return new SpartaFindingModel(
        id, "t", "s", "high", "1", "published_doc_id", "1", "{}", "{}", 0L);
  }
}

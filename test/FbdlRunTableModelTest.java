/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Covers the table model's pure parts. The table itself is Swing and is verified in Burp. */
public class FbdlRunTableModelTest {

  private static final ZoneId UTC = ZoneId.of("UTC");

  private static FbdlRunModel run(String id, long creationTime) {
    Map<String, String> results = new LinkedHashMap<>();
    results.put("UserOne.uid", "61593714297140");
    return new FbdlRunModel(id, FbdlRunStatus.COMPLETED, creationTime, "code", "note", results, "");
  }

  @Test
  public void testCreationTimeIsFormattedFromUnixSeconds() {
    assertEquals("2026-08-25 16:30", FbdlRunTableModel.formatCreated(1787675400L, UTC));
  }

  @Test
  public void testMissingCreationTimeShowsBlankNotNineteenSeventy() {
    assertEquals("", FbdlRunTableModel.formatCreated(0L, UTC));
    assertEquals("", FbdlRunTableModel.formatCreated(-1L, UTC));
  }

  @Test
  public void testResetReplacesRatherThanAppends() {
    FbdlRunTableModel model = new FbdlRunTableModel();
    model.reset(List.of(run("1", 100L), run("2", 200L)));
    model.reset(List.of(run("3", 300L)));
    assertEquals(1, model.getRowCount());
    assertEquals("3", model.getValueAt(0, 4));
  }

  @Test
  public void testRowOutOfRangeIsNullRatherThanThrowing() {
    // The selection listener can fire against a row the model no longer has, after a Refresh.
    FbdlRunTableModel model = new FbdlRunTableModel();
    model.reset(List.of(run("1", 100L)));
    assertNull(model.getRow(-1));
    assertNull(model.getRow(1));
    assertEquals("1", model.getRow(0).id);
  }

  @Test
  public void testColumnsReportStatusLabelCountAndId() {
    FbdlRunTableModel model = new FbdlRunTableModel();
    model.reset(List.of(run("1075679148163220", 1787675400L)));
    assertEquals("Completed", model.getValueAt(0, 0));
    assertEquals("note", model.getValueAt(0, 2));
    assertEquals(1, model.getValueAt(0, 3));
    assertEquals("1075679148163220", model.getValueAt(0, 4));
  }
}

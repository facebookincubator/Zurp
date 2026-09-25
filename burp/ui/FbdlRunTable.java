/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.ui;

import burp.models.FbdlRunTableModel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;

public class FbdlRunTable extends JTable {

  public FbdlRunTable(FbdlRunTableModel data) {
    super(data);
    setAutoCreateRowSorter(true);
    // Notes run long; scroll horizontally rather than squeeze every column.
    setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    // One run is shown in the detail pane below, so more than one selection has nothing to mean.
    setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
  }

  /** The model row behind the selection, which differs from the view row once sorted. */
  public int selectedModelRow() {
    int viewRow = getSelectedRow();
    return viewRow < 0 ? -1 : convertRowIndexToModel(viewRow);
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.ui;

import burp.models.MetaObjectInfoTableModel;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.*;

public class MetaObjectInfoTable extends JTable {

  public MetaObjectInfoTable(MetaObjectInfoTableModel data) {
    super(data);
    setAutoCreateRowSorter(true);

    // Double click to select the cell
    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              int row = rowAtPoint(e.getPoint());
              int col = columnAtPoint(e.getPoint());
              changeSelection(row, col, false, false);
            }
          }
        });
  }
}

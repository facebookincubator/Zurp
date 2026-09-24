/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.swing.table.AbstractTableModel;

public class SpartaFindingTableModel extends AbstractTableModel {

  private static final String[] COLUMNS = {
    "Priority", "Target", "Title", "Summary", "PoC Doc ID", "PoC Variables", "Finding ID",
  };

  private final List<SpartaFindingModel> data = new ArrayList<>();
  private final LinkedHashSet<String> findingIds = new LinkedHashSet<>();

  @Override
  public synchronized int getRowCount() {
    return data.size();
  }

  @Override
  public int getColumnCount() {
    return COLUMNS.length;
  }

  @Override
  public String getColumnName(int column) {
    return column >= 0 && column < COLUMNS.length ? COLUMNS[column] : "";
  }

  @Override
  public synchronized Object getValueAt(int rowIndex, int columnIndex) {
    SpartaFindingModel finding = data.get(rowIndex);

    switch (columnIndex) {
      case 0:
        return finding.priority;
      case 1:
        return finding.targetId;
      case 2:
        return finding.title;
      case 3:
        return finding.summary;
      case 4:
        return finding.pocDocId;
      case 5:
        return finding.pocVariablesJson;
      case 6:
        return finding.bbFindingId;
      default:
        return "";
    }
  }

  /** Null when nothing is selected, or when the table has been reset under the selection. */
  public synchronized SpartaFindingModel getRow(int rowIndex) {
    return rowIndex >= 0 && rowIndex < data.size() ? data.get(rowIndex) : null;
  }

  /** A finding can be reached through more than one target of the same request; show it once. */
  public synchronized void add(SpartaFindingModel finding) {
    if (finding != null && findingIds.add(finding.bbFindingId)) {
      data.add(finding);
      fireTableRowsInserted(data.size() - 1, data.size() - 1);
    }
  }

  public synchronized void reset(List<SpartaFindingModel> findings) {
    data.clear();
    findingIds.clear();
    for (SpartaFindingModel finding : findings) {
      if (finding != null && findingIds.add(finding.bbFindingId)) {
        data.add(finding);
      }
    }
    fireTableDataChanged();
  }
}

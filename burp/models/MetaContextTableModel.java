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

/** What one request's identifiers resolved to, a row per asset. */
public class MetaContextTableModel extends AbstractTableModel {

  private static final String[] COLUMNS = {"Type", "Name", "From"};

  /** Type, name and the identifier it was resolved from. */
  private final List<String[]> data = new ArrayList<>();

  private final LinkedHashSet<String> seen = new LinkedHashSet<>();

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
    String[] row = data.get(rowIndex);
    return columnIndex >= 0 && columnIndex < row.length ? row[columnIndex] : "";
  }

  /**
   * Ignores an asset with no name: a kind that resolved to nothing is not a row. The same asset can
   * be reached from more than one identifier in a request, so rows are deduped on all three values.
   */
  public synchronized void add(String type, String name, String from) {
    if (name == null || name.isEmpty()) {
      return;
    }
    String[] row = {type, name, from};
    if (seen.add(type + "\u0000" + name + "\u0000" + from)) {
      data.add(row);
      fireTableRowsInserted(data.size() - 1, data.size() - 1);
    }
  }

  public synchronized void reset() {
    data.clear();
    seen.clear();
    fireTableDataChanged();
  }
}

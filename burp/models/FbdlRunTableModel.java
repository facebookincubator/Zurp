/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

public class FbdlRunTableModel extends AbstractTableModel {

  private static final String[] COLUMNS = {"Status", "Created", "Note", "Labels", "Run ID"};

  private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final List<FbdlRunModel> data = new ArrayList<>();

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
    FbdlRunModel run = data.get(rowIndex);

    switch (columnIndex) {
      case 0:
        return run.status.wireValue();
      case 1:
        return formatCreated(run.creationTime, ZoneId.systemDefault());
      case 2:
        return run.note;
      case 3:
        return run.results.size();
      case 4:
        return run.id;
      default:
        return "";
    }
  }

  /** Blank rather than 1970 for a run the API sent no creation time for. */
  static String formatCreated(long unixSeconds, ZoneId zone) {
    if (unixSeconds <= 0) {
      return "";
    }
    return CREATED.format(Instant.ofEpochSecond(unixSeconds).atZone(zone));
  }

  /** Null when nothing is selected, or when the table has been reset under the selection. */
  public synchronized FbdlRunModel getRow(int rowIndex) {
    return rowIndex >= 0 && rowIndex < data.size() ? data.get(rowIndex) : null;
  }

  public synchronized void reset(List<FbdlRunModel> runs) {
    data.clear();
    for (FbdlRunModel run : runs) {
      if (run != null) {
        data.add(run);
      }
    }
    fireTableDataChanged();
  }
}

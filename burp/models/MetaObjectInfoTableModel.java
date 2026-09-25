/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.table.AbstractTableModel;

public class MetaObjectInfoTableModel extends AbstractTableModel {
  // Indexed list, because Swing addresses rows by index and repaints every visible cell; listIds
  // carries the uniqueness the set used to provide.
  private List<MetaObjectInfoModel> data;
  private Set<String> listIds;

  public MetaObjectInfoTableModel() {
    this.data = new ArrayList<>();
    this.listIds = new HashSet<>();
  }

  @Override
  public synchronized int getRowCount() {
    return data.size();
  }

  @Override
  public int getColumnCount() {
    return 3;
  }

  @Override
  public String getColumnName(int column) {
    switch (column) {
      case 0:
        return "FBID";
      case 1:
        return "Type";
      case 2:
        return "Name";
      default:
        return "";
    }
  }

  @Override
  public synchronized Object getValueAt(int rowIndex, int columnIndex) {
    MetaObjectInfoModel metaObject = data.get(rowIndex);

    switch (columnIndex) {
      case 0:
        return metaObject.id;
      case 1:
        return metaObject.type;
      case 2:
        return metaObject.name;
      default:
        return "";
    }
  }

  public synchronized void add(MetaObjectInfoModel metaObject) {
    if (listIds.add(metaObject.id)) {
      data.add(metaObject);
      fireTableRowsInserted(data.size() - 1, data.size() - 1);
    }
  }

  public synchronized MetaObjectInfoModel get(int rowIndex) {
    return data.get(rowIndex);
  }
}

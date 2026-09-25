/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.ui;

import burp.models.MetaContextTableModel;
import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;

/**
 * Everything the current request's identifiers resolved to.
 *
 * <p>A table rather than fixed fields because one request can name any number of assets of several
 * kinds: a URL is an XController or a Graph edge, its query string can carry an object id, and a
 * GraphQL call names a persisted document and an operation.
 */
public class MetaRequestContextInfo extends JPanel {

  public MetaRequestContextInfo(MetaContextTableModel assets) {
    setLayout(new BorderLayout());

    if (assets.getRowCount() == 0) {
      // Distinct from a failure: most requests carry identifiers that name nothing.
      add(new JLabel("No assets resolved for this request."), BorderLayout.NORTH);
      return;
    }

    JTable table = new JTable(assets);
    table.setAutoCreateRowSorter(true);
    add(new JScrollPane(table), BorderLayout.CENTER);
  }
}

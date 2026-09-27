/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.ui;

import burp.models.SpartaFindingModel;
import burp.models.SpartaFindingTableModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.table.TableCellRenderer;

public class SpartaFindingTable extends JTable {

  /** Researcher-chosen tints, in menu order. Pale so a row still reads as a row. */
  private static final Map<String, Color> PALETTE;

  static {
    Map<String, Color> palette = new LinkedHashMap<>();
    palette.put("Red", new Color(255, 214, 214));
    palette.put("Orange", new Color(255, 235, 214));
    palette.put("Yellow", new Color(255, 248, 214));
    palette.put("Green", new Color(214, 245, 214));
    palette.put("Blue", new Color(214, 232, 255));
    palette.put("Purple", new Color(232, 214, 255));
    PALETTE = Collections.unmodifiableMap(palette);
  }

  /** Tinted rows carry their own foreground so they read on a dark theme too. */
  private static final Color TINTED_TEXT = new Color(33, 33, 33);

  /** Widths the columns share the viewport by; the long text lives in the detail pane. */
  private static final int[] COLUMN_WIDTHS = {70, 150, 240, 240, 150, 180, 260};

  /** Highlight per finding id, so sorting and Refresh keep them. Untinted by default. */
  private final Map<String, Color> rowColors = new HashMap<>();

  private final JPopupMenu popup = new JPopupMenu();

  public SpartaFindingTable(SpartaFindingTableModel data) {
    super(data);
    setAutoCreateRowSorter(true);
    // Fit the viewport: a whole row visible at once, the detail pane below shows the long text.
    setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
    for (int i = 0; i < COLUMN_WIDTHS.length && i < getColumnCount(); i++) {
      getColumnModel().getColumn(i).setPreferredWidth(COLUMN_WIDTHS[i]);
    }

    JMenu colorMenu = new JMenu("Color");
    for (String name : PALETTE.keySet()) {
      JMenuItem item = new JMenuItem(name);
      item.addActionListener(e -> paintSelectedRow(colorForName(name)));
      colorMenu.add(item);
    }
    JMenuItem none = new JMenuItem("None");
    none.addActionListener(e -> paintSelectedRow(null));
    colorMenu.addSeparator();
    colorMenu.add(none);
    popup.add(colorMenu);

    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            // Double click to select the cell
            if (e.getClickCount() == 2) {
              int row = rowAtPoint(e.getPoint());
              int col = columnAtPoint(e.getPoint());
              changeSelection(row, col, false, false);
            }
          }

          @Override
          public void mousePressed(MouseEvent e) {
            maybeShowPopup(e);
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            maybeShowPopup(e);
          }

          private void maybeShowPopup(MouseEvent e) {
            // Press on some platforms, release on others; watching both is harmless.
            if (!e.isPopupTrigger()) {
              return;
            }
            int row = rowAtPoint(e.getPoint());
            if (row < 0) {
              return;
            }
            setRowSelectionInterval(row, row);
            popup.show(e.getComponent(), e.getX(), e.getY());
          }
        });
  }

  /** The view row converted to a model row; they differ once the table is sorted. */
  public int selectedModelRow() {
    int viewRow = getSelectedRow();
    return viewRow < 0 ? -1 : convertRowIndexToModel(viewRow);
  }

  @Override
  public Component prepareRenderer(TableCellRenderer renderer, int viewRow, int viewColumn) {
    Component cell = super.prepareRenderer(renderer, viewRow, viewColumn);
    if (!isRowSelected(viewRow)) {
      // Set on every row, tinted or not: the renderer is shared, so a tint left on it would
      // bleed into every row painted after.
      Color tint = colorAt(viewRow);
      cell.setBackground(tint != null ? tint : getBackground());
      cell.setForeground(tint != null ? TINTED_TEXT : getForeground());
    }
    return cell;
  }

  private void paintSelectedRow(Color color) {
    SpartaFindingModel finding = findingAt(getSelectedRow());
    setRowColor(finding == null ? null : finding.bbFindingId, color);
  }

  /** Null clears. Package-visible so the tint can be tested without the menu. */
  void setRowColor(String bbFindingId, Color color) {
    if (bbFindingId == null || bbFindingId.isEmpty()) {
      return;
    }
    if (color == null) {
      rowColors.remove(bbFindingId);
    } else {
      rowColors.put(bbFindingId, color);
    }
    repaint();
  }

  private Color colorAt(int viewRow) {
    SpartaFindingModel finding = findingAt(viewRow);
    return finding == null ? null : rowColors.get(finding.bbFindingId);
  }

  /** Null when the row is no row, or the model is out of reach. */
  private SpartaFindingModel findingAt(int viewRow) {
    if (viewRow < 0 || !(getModel() instanceof SpartaFindingTableModel findings)) {
      return null;
    }
    return findings.getRow(convertRowIndexToModel(viewRow));
  }

  /** Null for an unknown name, which leaves the row to the theme. */
  static Color colorForName(String name) {
    return name == null ? null : PALETTE.get(name);
  }
}

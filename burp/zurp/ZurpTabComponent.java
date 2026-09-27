/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import burp.api.montoya.core.ToolType;
import burp.fetcher.ZurpDataFetcher;
import burp.models.FbdlRunModel;
import burp.models.FbdlRunTableModel;
import burp.models.SpartaFindingModel;
import burp.models.SpartaFindingTableModel;
import burp.ui.FbdlRunTable;
import burp.ui.SpartaFindingTable;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.Locale;
import java.util.Map;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class ZurpTabComponent extends JPanel {

  private final SpartaFindingTableModel spartaFindings = new SpartaFindingTableModel();
  private final SpartaFindingTable spartaTable = new SpartaFindingTable(spartaFindings);
  private final JTextArea spartaDetail = new JTextArea(8, 80);

  /**
   * Everything that configures Zurp, stacked. The data views get a tab each instead: a findings
   * table wants the height of the window, and sharing a scroll pane with the settings meant
   * scrolling past them to reach it.
   */
  private final JPanel settings = new JPanel();

  private final FbdlRunTableModel fbdlRuns = new FbdlRunTableModel();
  private final FbdlRunTable fbdlRunTable = new FbdlRunTable(fbdlRuns);
  private final JTextArea fbdlDetail = new JTextArea(8, 80);

  public ZurpTabComponent() {
    setLayout(new BorderLayout());
    settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));

    // Create access token component. Shows the resolved value, so a token seeded from the
    // environment is visible here rather than looking unset while requests quietly succeed.
    String accessToken = ZurpUtils.getAccessToken();
    if (accessToken.isEmpty()) {
      ZurpLog.output("Zurp access token not found.");
    }
    buildPrefComponent(ZurpPrefEnum.ACCESS_TOKEN.getValue(), 100, accessToken);

    // Create api base url component
    buildPrefComponent(ZurpPrefEnum.API_BASE_URL.getValue(), 100, ZurpUtils.getApiBaseUrl());

    buildLogLevelPrefComponent();
    buildCsrfPrefComponent();
    buildFetcherPrefComponent();

    JTabbedPane tabs = new JTabbedPane();
    // Scrolled because the CSRF grid grows with the tool list and the fetcher list grows with every
    // fetcher added, so this panel has no fixed height.
    tabs.addTab("Settings", new JScrollPane(settings));
    tabs.addTab("SPARTA", buildSpartaComponent());
    tabs.addTab("FBDL", buildFbdlComponent());
    add(tabs, BorderLayout.CENTER);
  }

  private void buildLogLevelPrefComponent() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
    panel.add(new JLabel(ZurpPrefEnum.LOG_LEVEL.getValue()));

    JComboBox<ZurpLog.Level> levels = new JComboBox<>(ZurpLog.Level.values());
    levels.setSelectedItem(ZurpLog.level());
    levels.addActionListener(
        e -> {
          ZurpLog.Level selected = (ZurpLog.Level) levels.getSelectedItem();
          Zurp.preferences.setString(ZurpPrefEnum.LOG_LEVEL.getValue(), selected.name());
          // Applied as well as stored, so it takes hold without reloading the extension.
          ZurpLog.setLevel(selected);
        });

    panel.add(levels);
    settings.add(panel);
  }

  /**
   * A checkbox per background fetcher. Keyed off each fetcher's own data type name, so a fetcher
   * added later shows up here without this tab being touched.
   */
  private void buildFetcherPrefComponent() {
    JPanel panel = new JPanel(new GridLayout(0, 1));
    panel.setBorder(BorderFactory.createTitledBorder("Background lookups"));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);

    for (ZurpDataFetcher fetcher : Zurp.fetchers) {
      String dataTypeName = fetcher.dataTypeName();
      JCheckBox checkBox =
          buildBoolPrefComponent(
              ZurpPrefEnum.fetchKey(dataTypeName), ZurpUtils.isFetcherEnabled(dataTypeName));
      checkBox.setText(dataTypeName);
      panel.add(checkBox);
    }

    settings.add(panel);
  }

  private JPanel buildSpartaComponent() {
    JPanel panel = new JPanel(new BorderLayout());

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
    controls.add(new JLabel("Findings disclosed to me for the endpoints and doc ids I browse"));

    // The catalog sweep runs on a background tick, so the table needs a way to catch up with it.
    JButton refresh = new JButton("Refresh");
    refresh.addActionListener(e -> reloadSpartaFindings());
    controls.add(refresh);

    JCheckBox organizerPush =
        buildBoolPrefComponent(
            ZurpPrefEnum.ORGANIZER_PUSH.getValue(), ZurpUtils.isOrganizerPushEnabled());
    organizerPush.setText(ZurpPrefEnum.ORGANIZER_PUSH.getValue());
    controls.add(organizerPush);

    spartaDetail.setEditable(false);
    spartaDetail.setLineWrap(true);
    spartaDetail.setWrapStyleWord(true);
    spartaTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              // Selection fires twice, on the press and the release; rendering once is enough.
              if (!e.getValueIsAdjusting()) {
                renderSpartaDetail(spartaFindings.getRow(spartaTable.selectedModelRow()));
              }
            });

    JSplitPane split =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, new JScrollPane(spartaTable), new JScrollPane(spartaDetail));
    // No preferred size: the tab gives the split the whole window. Half each, so a long summary
    // does not squeeze the finding list out.
    split.setResizeWeight(0.5);

    panel.add(controls, BorderLayout.NORTH);
    panel.add(split, BorderLayout.CENTER);

    reloadSpartaFindings();
    return panel;
  }

  private void reloadSpartaFindings() {
    spartaFindings.reset(Zurp.spartaFindingFetcher.getAllFindings());
    // The previous selection refers to a row that may no longer exist, so start from nothing
    // rather than leave the detail pane describing a finding that is not highlighted any more.
    spartaTable.clearSelection();
    renderSpartaDetail(null);
  }

  private void renderSpartaDetail(SpartaFindingModel finding) {
    spartaDetail.setText(spartaDetailText(finding));
    spartaDetail.setCaretPosition(0);
  }

  /** Null-safe: null shows the selection prompt in the detail pane. */
  static String spartaDetailText(SpartaFindingModel finding) {
    if (finding == null) {
      return "Select a finding to see its summary and proof of concept.";
    }
    StringBuilder text = new StringBuilder("SPARTA");
    if (hasText(finding.priority)) {
      text.append(' ').append(finding.priority.toUpperCase(Locale.ROOT));
    }
    if (hasText(finding.title)) {
      text.append(": ").append(finding.title);
    }
    if (hasText(finding.bbFindingId)) {
      text.append(" [").append(finding.bbFindingId).append(']');
    }
    if (hasText(finding.targetId)) {
      text.append("\nTarget: ").append(finding.targetId);
      if (hasText(finding.targetType)) {
        text.append(" (").append(finding.targetType).append(')');
      }
    }
    if (hasText(finding.pocDocId)) {
      text.append("\nPoC: ").append(finding.pocDocId);
    }
    if (hasText(finding.summary)) {
      text.append("\n\n").append(finding.summary);
    }
    if (hasText(finding.pocVariablesJson)) {
      text.append("\n\nPoC variables:\n").append(finding.pocVariablesJson);
    }
    if (hasText(finding.pocPlaceholdersJson)) {
      text.append("\n\nSubstitute before sending:\n").append(finding.pocPlaceholdersJson);
    }
    return text.toString();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isEmpty();
  }

  /**
   * The runs and their results are already in the project file by the time this reads them, so
   * everything here is a local read. Nothing on this panel touches the network: FbdlRunFetcher
   * sweeps the list endpoint on its own tick and Refresh only catches the table up with it.
   */
  private JPanel buildFbdlComponent() {
    JPanel panel = new JPanel(new BorderLayout());

    JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
    controls.add(new JLabel("Test assets minted by my FBDL scripts"));

    JButton refresh = new JButton("Refresh");
    refresh.addActionListener(e -> reloadFbdlRuns());
    controls.add(refresh);

    JButton newRun = new JButton("New run");
    newRun.addActionListener(e -> promptNewRun());
    controls.add(newRun);

    JButton archive = new JButton("Archive");
    archive.addActionListener(e -> archiveSelectedRun());
    controls.add(archive);

    fbdlDetail.setEditable(false);
    fbdlRunTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              // Selection fires twice, on the press and the release; rendering once is enough.
              if (!e.getValueIsAdjusting()) {
                renderFbdlDetail(fbdlRuns.getRow(fbdlRunTable.selectedModelRow()));
              }
            });

    JSplitPane split =
        new JSplitPane(
            JSplitPane.VERTICAL_SPLIT, new JScrollPane(fbdlRunTable), new JScrollPane(fbdlDetail));
    // No preferred size: the tab gives the split the whole window, which is the point of it having
    // a tab. Half each, so a long script does not squeeze the run list out.
    split.setResizeWeight(0.5);

    panel.add(controls, BorderLayout.NORTH);
    panel.add(split, BorderLayout.CENTER);

    reloadFbdlRuns();
    return panel;
  }

  private void reloadFbdlRuns() {
    fbdlRuns.reset(Zurp.fbdlRunFetcher.getRuns());
    // The previous selection refers to a row that may no longer exist, so start from nothing
    // rather than leave the detail pane describing a run that is not highlighted any more.
    fbdlRunTable.clearSelection();
    renderFbdlDetail(null);
  }

  private void renderFbdlDetail(FbdlRunModel run) {
    if (run == null) {
      fbdlDetail.setText("Select a run to see its script and results.");
      fbdlDetail.setCaretPosition(0);
      return;
    }

    StringBuilder text = new StringBuilder();
    if (run.results.isEmpty()) {
      // A run only has results once it has settled, and the sweep stores summaries before the
      // detail call lands, so an empty map is the normal state for a moment rather than an error.
      text.append(run.status.isTerminal() ? "No results.\n" : "Run is still executing.\n");
    } else {
      for (Map.Entry<String, String> result : run.results.entrySet()) {
        text.append(result.getKey()).append(" = ").append(result.getValue()).append('\n');
      }
    }

    if (!run.exceptionStack.isEmpty()) {
      text.append('\n').append(run.exceptionStack).append('\n');
    }

    if (!run.runCode.isEmpty()) {
      text.append('\n').append(run.runCode);
    }

    fbdlDetail.setText(text.toString());
    fbdlDetail.setCaretPosition(0);
  }

  /**
   * The only things on this panel that touch the network. Both block, so they run off the event
   * thread: stalling it would freeze all of Burp, not just this tab.
   */
  private void promptNewRun() {
    JTextArea code = new JTextArea(14, 60);
    JTextField note = new JTextField();

    JPanel form = new JPanel(new BorderLayout(4, 4));
    JPanel notePanel = new JPanel(new GridLayout(1, 2, 4, 4));
    notePanel.add(new JLabel("Note (required)"));
    notePanel.add(note);
    form.add(notePanel, BorderLayout.NORTH);
    form.add(new JScrollPane(code), BorderLayout.CENTER);
    form.setPreferredSize(new Dimension(640, 360));

    int choice =
        JOptionPane.showConfirmDialog(
            this, form, "New FBDL run", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return;
    }

    String fbdlCode = code.getText().trim();
    String runNote = note.getText().trim();
    if (fbdlCode.isEmpty() || runNote.isEmpty()) {
      // Both are required server side; saying so here costs a round trip less than finding out.
      JOptionPane.showMessageDialog(this, "A script and a note are both required.");
      return;
    }

    runOffEventThread(
        () -> {
          String runId = Zurp.fbdlRunFetcher.createRun(fbdlCode, runNote);
          SwingUtilities.invokeLater(
              () -> {
                if (runId == null) {
                  JOptionPane.showMessageDialog(
                      this, "The run was refused. See the Zurp log for the status.");
                  return;
                }
                // Only the summary exists so far; the fetcher polls the result in on its own tick.
                reloadFbdlRuns();
              });
        });
  }

  private void archiveSelectedRun() {
    FbdlRunModel run = fbdlRuns.getRow(fbdlRunTable.selectedModelRow());
    if (run == null) {
      JOptionPane.showMessageDialog(this, "Select a run to archive.");
      return;
    }

    int choice =
        JOptionPane.showConfirmDialog(
            this,
            "Archive run " + run.id + "?\nThis deletes the test assets it created.",
            "Archive FBDL run",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return;
    }

    runOffEventThread(
        () -> {
          String archivedId = Zurp.fbdlRunFetcher.archiveRun(run.id);
          SwingUtilities.invokeLater(
              () -> {
                if (archivedId == null) {
                  JOptionPane.showMessageDialog(
                      this, "The archive was refused. See the Zurp log for the status.");
                }
                // Refreshed either way: a refusal may still have been a run that is already gone.
                reloadFbdlRuns();
              });
        });
  }

  private void runOffEventThread(Runnable work) {
    Thread thread =
        new Thread(
            () -> {
              try {
                work.run();
              } catch (Exception e) {
                ZurpLog.caught("[FBDL] action failed", e);
              }
            },
            "zurp-fbdl-action");
    // Daemon so a request still in flight cannot keep Burp from exiting.
    thread.setDaemon(true);
    thread.start();
  }

  private void buildCsrfPrefComponent() {
    JPanel panel = new JPanel(new GridLayout(0, ZurpPrefEnum.CSRF_TOOLS.length + 1, 8, 2));
    panel.setBorder(BorderFactory.createTitledBorder("CSRF token substitution"));
    panel.setAlignmentX(Component.LEFT_ALIGNMENT);

    panel.add(new JLabel());
    for (ToolType tool : ZurpPrefEnum.CSRF_TOOLS) {
      panel.add(new JLabel(tool.toolName()));
    }

    panel.add(new JLabel("Replace {{fb_dtsg}} placeholders"));
    for (ToolType tool : ZurpPrefEnum.CSRF_TOOLS) {
      panel.add(
          buildBoolPrefComponent(
              ZurpPrefEnum.csrfPlaceholderKey(tool), ZurpUtils.isCsrfPlaceholderEnabled(tool)));
    }

    panel.add(new JLabel("Auto-refresh tokens already present"));
    for (ToolType tool : ZurpPrefEnum.CSRF_TOOLS) {
      JCheckBox autoRefresh =
          buildBoolPrefComponent(
              ZurpPrefEnum.csrfAutoRefreshKey(tool), ZurpUtils.isCsrfAutoRefreshEnabled(tool));
      warnOnAutoRefresh(autoRefresh);
      panel.add(autoRefresh);
    }

    settings.add(panel);
  }

  // Once per load rather than per tool: enabling this for all four tools is one decision, and four
  // identical dialogs would train the researcher to dismiss them unread.
  private boolean autoRefreshWarned;

  /**
   * Auto-refresh rewrites any fb_dtsg it finds, which is the point when replaying a captured
   * request whose token went stale, and a trap when the token was altered on purpose. Testing
   * whether an endpoint validates its CSRF token is exactly the kind of thing a researcher does
   * here, and the rewrite happens silently, so the result looks like the endpoint accepted a bad
   * token when it never received one.
   */
  private void warnOnAutoRefresh(JCheckBox checkBox) {
    checkBox.addActionListener(
        e -> {
          if (!checkBox.isSelected() || autoRefreshWarned) {
            return;
          }
          autoRefreshWarned = true;
          JOptionPane.showMessageDialog(
              this,
              "Auto-refresh replaces fb_dtsg in every replayed request, including one you have\n"
                  + "deliberately altered.\n\n"
                  + "A request meant to test whether an endpoint validates its CSRF token will be\n"
                  + "repaired before it is sent, and will appear to succeed. Turn this off while\n"
                  + "testing CSRF validation itself.\n\n"
                  + "Placeholders are unaffected: {{fb_dtsg}} is only filled where you wrote it.",
              "CSRF auto-refresh",
              JOptionPane.WARNING_MESSAGE);
        });
  }

  public JCheckBox buildBoolPrefComponent(String prefName, boolean value) {
    JCheckBox checkBox = new JCheckBox();
    checkBox.setSelected(value);
    checkBox.addActionListener(e -> Zurp.preferences.setBoolean(prefName, checkBox.isSelected()));
    return checkBox;
  }

  public void buildPrefComponent(String prefName, int fieldLength, String value) {
    JPanel panel = new JPanel();
    panel.setLayout(new FlowLayout(FlowLayout.CENTER));
    JLabel label = new JLabel(prefName);
    JTextField textField = new JTextField(fieldLength);

    textField.setText(value);

    textField
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                updateStringPref(prefName, textField.getText());
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                updateStringPref(prefName, textField.getText());
              }

              @Override
              public void changedUpdate(DocumentEvent e) {
                updateStringPref(prefName, textField.getText());
              }
            });

    panel.add(label);
    panel.add(textField);
    settings.add(panel);
  }

  private void updateStringPref(String key, String val) {
    Zurp.preferences.setString(key, val);
  }
}

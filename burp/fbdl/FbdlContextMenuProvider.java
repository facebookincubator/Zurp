/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fbdl;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import burp.models.FbdlRunModel;
import burp.models.FbdlRunTableModel;
import burp.ui.FbdlRunTable;
import burp.zurp.Zurp;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * Adds "Pin FBDL run" to the editor's context menu, which is how a run id gets into a placeholder.
 *
 * <p>Burp exposes no identity for a Repeater tab -- {@code EditorCreationContext} carries only a
 * tool and a mode, and {@code HttpRequestToBeSent} only a per-send message id -- so Zurp cannot
 * remember "this tab uses that run" anywhere of its own. Writing the run id into the request is
 * what makes the choice stick: the request is the tab, it survives a project reload, and it is
 * visible to the researcher rather than hidden in extension state.
 */
public class FbdlContextMenuProvider implements ContextMenuItemsProvider {

  /** Tools whose requests a researcher edits and replays by hand. */
  private static final ToolType[] EDITABLE_TOOLS = {ToolType.REPEATER, ToolType.INTRUDER};

  @Override
  public List<Component> provideMenuItems(ContextMenuEvent event) {
    Optional<MessageEditorHttpRequestResponse> editor = event.messageEditorRequestResponse();
    if (editor.isEmpty() || !event.isFromTool(EDITABLE_TOOLS)) {
      return null;
    }

    HttpRequest request = editor.get().requestResponse().request();
    if (request == null || !FbdlRewriter.hasPlaceholder(request)) {
      // Nothing to repoint, so do not put an item in the researcher's way.
      return null;
    }

    JMenuItem pin = new JMenuItem("Pin FBDL run...");
    pin.addActionListener(e -> promptForRun(editor.get(), request));
    return List.of(pin);
  }

  private void promptForRun(MessageEditorHttpRequestResponse editor, HttpRequest request) {
    List<FbdlRunModel> runs = Zurp.fbdlRunFetcher.getRuns();
    if (runs.isEmpty()) {
      JOptionPane.showMessageDialog(
          null, "No FBDL runs cached yet. Open the Zurp tab and refresh, or create a run.");
      return;
    }

    FbdlRunTableModel model = new FbdlRunTableModel();
    model.reset(runs);
    FbdlRunTable table = new FbdlRunTable(model);

    JPanel form = new JPanel(new BorderLayout());
    form.add(new JScrollPane(table), BorderLayout.CENTER);
    form.setPreferredSize(new Dimension(760, 320));

    int choice =
        JOptionPane.showConfirmDialog(
            null,
            form,
            "Pin this request to an FBDL run",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) {
      return;
    }

    FbdlRunModel selected = model.getRow(table.selectedModelRow());
    if (selected == null) {
      JOptionPane.showMessageDialog(null, "Select a run to pin to.");
      return;
    }

    warnAboutMissingLabels(request, selected);
    editor.setRequest(FbdlRewriter.pin(request, selected.id));
  }

  /**
   * Pinning to a run that never produced one of the labels would leave that placeholder unresolved
   * at send time, and the request would go out with the literal text in it. Cheaper to say so now
   * than to work it out from the response.
   */
  private void warnAboutMissingLabels(HttpRequest request, FbdlRunModel run) {
    List<String> missing = new ArrayList<>();
    for (String label : FbdlRewriter.labelsIn(request)) {
      if (!run.results.containsKey(label) && !missing.contains(label)) {
        missing.add(label);
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    JOptionPane.showMessageDialog(
        null,
        "Run "
            + run.id
            + " has no value for: "
            + String.join(", ", missing)
            + ".\nThose placeholders will be sent as written.");
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.editor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.RawEditor;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedEditor;
import burp.models.MetaContextTableModel;
import burp.models.MetaGraphqlInfoModel;
import burp.models.MetaObjectInfoModel;
import burp.models.MetaObjectInfoTableModel;
import burp.models.MetaUrlInfoModel;
import burp.models.SpartaFindingModel;
import burp.models.SpartaFindingTableModel;
import burp.models.SpartaTarget;
import burp.ui.MetaObjectInfoTable;
import burp.ui.MetaRequestContextInfo;
import burp.ui.SpartaFindingTable;
import burp.zurp.MetaAssetQueries;
import burp.zurp.SpartaTargetExtractor;
import burp.zurp.Zurp;
import burp.zurp.ZurpLog;
import burp.zurp.ZurpUtils;
import java.awt.*;
import java.util.regex.Matcher;
import javax.swing.*;

public abstract class MetaHttpEditor implements ExtensionProvidedEditor {
  public RawEditor editor;
  public HttpRequestResponse requestResponse;
  public ToolType toolType;
  private JTabbedPane metaViewTabs;

  public MetaHttpEditor(MontoyaApi api, EditorCreationContext creationContext) {
    this.editor = api.userInterface().createRawEditor();
    this.toolType = creationContext.toolSource().toolType();
  }

  @Override
  public String caption() {
    return "Meta View";
  }

  @Override
  public Selection selectedData() {
    return editor.selection().isPresent() ? editor.selection().get() : null;
  }

  @Override
  public Component uiComponent() {

    JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    metaViewTabs = new JTabbedPane();
    populateMetaViewTabs();

    splitPane.setLeftComponent(metaViewTabs);
    splitPane.setRightComponent(editor.uiComponent());
    splitPane.setResizeWeight(0.3);
    return splitPane;
  }

  /**
   * Burp builds the component once and then reuses it for every request the user selects, so the
   * tables have to be rebuilt on selection. They are also rebuilt rather than cached because a
   * fetch that lands after the panel is open should show up the next time it is looked at.
   */
  protected void refreshMetaViewTabs() {
    if (metaViewTabs == null) {
      return;
    }
    int selected = metaViewTabs.getSelectedIndex();
    metaViewTabs.removeAll();
    populateMetaViewTabs();
    if (selected >= 0 && selected < metaViewTabs.getTabCount()) {
      metaViewTabs.setSelectedIndex(selected);
    }
    metaViewTabs.revalidate();
    metaViewTabs.repaint();
  }

  private void populateMetaViewTabs() {
    metaViewTabs.add("Meta Objects", new JScrollPane(getMetaObjectInfoTable()));
    metaViewTabs.add("Meta Context", getMetaContextInfoComponent());
    metaViewTabs.add("SPARTA Findings", new JScrollPane(getSpartaFindingTable()));
  }

  @Override
  public boolean isModified() {
    return false;
  }

  @Override
  public boolean isEnabledFor(HttpRequestResponse requestResponse) {
    if (this.toolType == ToolType.EXTENSIONS) {
      return true;
    }
    try {
      String url = requestResponse.request().url();
      if (url != null) {
        return ZurpUtils.isMetaUrl(url);
      }
      return false;
    } catch (Exception e) {
      ZurpLog.caught("[MetaHttpEditor] Could not read the request URL", e);
      return false;
    }
  }

  public Component getMetaObjectInfoTable() {
    MetaObjectInfoTableModel model = new MetaObjectInfoTableModel();
    Matcher matcher = ZurpUtils.FBID_PATTERN.matcher(editor.getContents().toString());
    while (matcher.find()) {
      String objId = matcher.group();
      if (Zurp.metaObjectInfoFetcher.dataFailed.contains(objId)) {
        continue;
      } else if (Zurp.metaObjectInfoFetcher.dataQueued.contains(objId)) {
        model.add(new MetaObjectInfoModel(objId, "Loading ...", "Loading ..."));
      } else if (Zurp.metaObjectInfoFetcher.dataStored.contains(objId)) {
        model.add(Zurp.metaObjectInfoFetcher.getObject(objId));
      }
    }
    return new MetaObjectInfoTable(model);
  }

  public Component getMetaContextInfoComponent() {
    if (requestResponse == null) {
      return new JPanel();
    }

    HttpRequest request = requestResponse.request();
    String url = request.url();
    MetaContextTableModel assets = new MetaContextTableModel();

    MetaUrlInfoModel urlInfo = Zurp.metaUrlInfoFetcher.getObject(url);
    if (urlInfo != null) {
      assets.add("xcontroller", urlInfo.controllerName, url);
      assets.add("graph_edge", urlInfo.graphEdge, url);
    }

    // The same identifiers the fetcher was given, so the panel shows whatever has resolved since.
    // Guarded by the in-memory set: this runs on the UI thread, and getObject reads the project
    // file, so an unguarded lookup per identifier freezes the editor on a large request.
    for (String query :
        MetaAssetQueries.fromTargets(SpartaTargetExtractor.extract(url, request.bodyToString()))) {
      if (!Zurp.metaGraphqlInfoFetcher.dataStored.contains(query)) {
        continue;
      }
      MetaGraphqlInfoModel graphql = Zurp.metaGraphqlInfoFetcher.getObject(query);
      if (graphql != null) {
        assets.add("graphql", graphql.operationName, query);
      }
    }

    // Objects too: this tab is every kind of Meta context the request carries, not only the kinds
    // the other two tabs do not already cover. The Meta Objects tab keeps its id/type/name columns
    // for reading them in detail.
    Matcher objectIds = ZurpUtils.FBID_PATTERN.matcher(editor.getContents().toString());
    while (objectIds.find()) {
      String objId = objectIds.group();
      if (!Zurp.metaObjectInfoFetcher.dataStored.contains(objId)) {
        continue;
      }
      MetaObjectInfoModel object = Zurp.metaObjectInfoFetcher.getObject(objId);
      if (object != null) {
        assets.add("ent_or_node", object.type, objId);
      }
    }

    return new MetaRequestContextInfo(assets);
  }

  /** Findings already cached for anything this request targets: its doc ids, and its controller. */
  public Component getSpartaFindingTable() {
    SpartaFindingTableModel model = new SpartaFindingTableModel();
    if (requestResponse != null) {
      HttpRequest request = requestResponse.request();
      String url = request.url();
      for (SpartaTarget target : SpartaTargetExtractor.extract(url, request.bodyToString())) {
        addFindings(model, target);
      }
      MetaUrlInfoModel urlInfo = Zurp.metaUrlInfoFetcher.getObject(url);
      if (urlInfo != null && urlInfo.controllerName != null && !urlInfo.controllerName.isEmpty()) {
        addFindings(model, SpartaTarget.endpointName(urlInfo.controllerName));
      }
    }
    return new SpartaFindingTable(model);
  }

  private void addFindings(SpartaFindingTableModel model, SpartaTarget target) {
    for (SpartaFindingModel finding : Zurp.spartaFindingFetcher.getFindings(target)) {
      model.add(finding);
    }
  }
}

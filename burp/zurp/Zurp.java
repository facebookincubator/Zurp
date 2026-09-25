/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.organizer.Organizer;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
import burp.api.montoya.ui.menu.BasicMenuItem;
import burp.api.montoya.ui.menu.Menu;
import burp.api.montoya.ui.menu.MenuItem;
import burp.csrf.CsrfTokenStore;
import burp.editor.*;
import burp.fbdl.FbdlContextMenuProvider;
import burp.fetcher.*;
import java.awt.*;
import java.util.List;
import javax.swing.*;

public class Zurp implements BurpExtension {

  private static final String EXTENSION_NAME = "Zurp";
  public static Preferences preferences;
  public static PersistedObject extensionData;

  /** Package private on purpose: everything logs through {@link ZurpLog}, which gates on level. */
  static Logging logger;

  public static Http http;
  public static GraphApiRequester requester;

  /** Where disclosed SPARTA proofs of concept are queued for the researcher. */
  public static Organizer organizer;

  /** One for the whole extension: the rate limit and the allowlist belong to the endpoint. */
  public static AssetResolver assetResolver;

  public static MetaObjectInfoFetcher metaObjectInfoFetcher;
  public static MetaUrlInfoFetcher metaUrlInfoFetcher;
  public static MetaGraphqlInfoFetcher metaGraphqlInfoFetcher;
  public static SpartaFindingFetcher spartaFindingFetcher;
  public static FbdlRunFetcher fbdlRunFetcher;

  /** Every fetcher, so the settings tab and the unload handler need not name them one by one. */
  public static List<ZurpDataFetcher> fetchers;

  public static CsrfTokenStore csrfTokenStore;

  @Override
  public void initialize(MontoyaApi api) {
    // set extension name
    api.extension().setName(EXTENSION_NAME);
    this.preferences = api.persistence().preferences();
    this.extensionData = api.persistence().extensionData();
    this.logger = api.logging();
    // Before anything that logs, so the stored level applies from the first line rather than from
    // the first time the researcher opens the tab.
    ZurpLog.setLevel(ZurpUtils.getLogLevel());
    // Assigned before the fetchers below, which start ticking as soon as they are constructed.
    this.http = api.http();
    this.organizer = api.organizer();
    this.requester = new GraphApiRequester();
    this.assetResolver = new AssetResolver();

    // in-memory only: these are live session credentials and must not reach the project file
    this.csrfTokenStore = new CsrfTokenStore();

    // set various fetchers that are called to get data from external sources. The SPARTA one is
    // built first because MetaUrlInfoFetcher feeds it controller names as soon as it starts.
    this.spartaFindingFetcher = new SpartaFindingFetcher();
    this.metaObjectInfoFetcher = new MetaObjectInfoFetcher();
    this.metaUrlInfoFetcher = new MetaUrlInfoFetcher();
    this.metaGraphqlInfoFetcher = new MetaGraphqlInfoFetcher();
    this.fbdlRunFetcher = new FbdlRunFetcher();
    this.fetchers =
        List.of(
            spartaFindingFetcher,
            metaObjectInfoFetcher,
            metaUrlInfoFetcher,
            metaGraphqlInfoFetcher,
            fbdlRunFetcher);

    // http handler that will process meta request
    api.http().registerHttpHandler(new MetaHttpHandler());

    // Pins a request to an FBDL run. Burp exposes no Repeater tab identity, so the run id has to
    // live in the request itself; this menu is what puts it there.
    api.userInterface().registerContextMenuItemsProvider(new FbdlContextMenuProvider());

    // add response editor tab for meta requests/responses
    api.userInterface().registerHttpResponseEditorProvider(new MetaHttpResponseEditorProvider(api));
    api.userInterface().registerHttpRequestEditorProvider(new MetaHttpRequestEditorProvider(api));

    // menu allowing to unload the extension
    BasicMenuItem basicMenuItem = MenuItem.basicMenuItem("Unload extension");
    MenuItem unloadExtensionItem = basicMenuItem.withAction(() -> api.extension().unload());
    Menu menu = Menu.menu(EXTENSION_NAME).withMenuItems(unloadExtensionItem);
    api.userInterface().menuBar().registerMenu(menu);
    api.extension().registerUnloadingHandler(new ZurpUnloadingHandler());

    // zurp extension settings tab
    ZurpTabComponent zurpTab = new ZurpTabComponent();

    api.userInterface().registerSuiteTab(EXTENSION_NAME, zurpTab);
  }
}

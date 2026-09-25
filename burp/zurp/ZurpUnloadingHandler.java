/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.zurp;

import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.fetcher.ZurpDataFetcher;

public class ZurpUnloadingHandler implements ExtensionUnloadingHandler {

  public ZurpUnloadingHandler() {}

  @Override
  public void extensionUnloaded() {
    for (ZurpDataFetcher fetcher : Zurp.fetchers) {
      fetcher.shutdown();
    }
    Zurp.csrfTokenStore.clear();
    ZurpLog.output("Extension has been unloaded.");
  }
}

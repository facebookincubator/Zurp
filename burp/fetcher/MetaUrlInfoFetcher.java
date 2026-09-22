/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.persistence.PersistedObject;
import burp.models.MetaUrlInfoModel;
import burp.models.SpartaTarget;
import burp.zurp.*;

/** A URL, resolved to the XController that serves it. */
public class MetaUrlInfoFetcher extends ZurpDataFetcher {

  @Override
  protected String getDataTypeName() {
    return "Meta Url Info";
  }

  @Override
  protected boolean isFetchingEnabled() {
    return super.isFetchingEnabled() && Zurp.assetResolver.isAvailable();
  }

  @Override
  protected FetchOutcome fetchData(String dataItem) {
    AssetResolver.Lookup lookup = Zurp.assetResolver.lookup(dataItem);
    if (lookup.assets == null) {
      return lookup.outcome;
    }

    String controllerName = lookup.assets.one(AssetResolver.XCONTROLLER, dataItem);
    if (controllerName.isEmpty()) {
      return FetchOutcome.FAILED;
    }

    PersistedObject objectToSave = PersistedObject.persistedObject();
    objectToSave.setString("url", dataItem);
    objectToSave.setString("controller_name", controllerName);
    fetcherData.setChildObject(dataItem, objectToSave);

    ZurpLog.output("[MetaUrlInfoFetcher] Fetched: " + dataItem + " -> " + controllerName);
    // SPARTA raises findings against the controller, not the URL that reached it.
    Zurp.spartaFindingFetcher.addToQueue(SpartaTarget.endpointName(controllerName));
    return FetchOutcome.STORED;
  }

  /** Not the entry point here: {@link #fetchData} is overridden to report the third outcome. */
  @Override
  protected boolean fetchDataAndStore(String dataItem) {
    return fetchData(dataItem) == FetchOutcome.STORED;
  }

  public MetaUrlInfoModel getObject(String url) {
    PersistedObject persistedObj = fetcherData.getChildObject(url);
    if (persistedObj != null) {
      return new MetaUrlInfoModel(
          persistedObj.getString("url"), persistedObj.getString("controller_name"));
    }
    return null;
  }
}

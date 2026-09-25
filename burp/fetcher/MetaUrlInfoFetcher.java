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

/**
 * A URL, resolved to the XController that serves it, or the Graph edge for a {@code graph.} host.
 */
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
    AssetResolver.Lookup lookup = Zurp.assetResolver.lookup(dataItem, false);
    if (lookup.assets == null) {
      return lookup.outcome;
    }

    String controllerName = lookup.assets.one(AssetResolver.XCONTROLLER, dataItem);
    // A graph. host is served by an edge rather than a controller, so either alone is a result.
    String graphEdge = lookup.assets.one(AssetResolver.GRAPH_EDGE, dataItem);
    if (controllerName.isEmpty() && graphEdge.isEmpty()) {
      return FetchOutcome.FAILED;
    }

    PersistedObject objectToSave = PersistedObject.persistedObject();
    objectToSave.setString("url", dataItem);
    objectToSave.setString("controller_name", controllerName);
    objectToSave.setString("graph_edge", graphEdge);
    fetcherData.setChildObject(dataItem, objectToSave);

    ZurpLog.output(
        "[MetaUrlInfoFetcher] Fetched: "
            + dataItem
            + " -> "
            + (controllerName.isEmpty() ? graphEdge : controllerName));
    if (!controllerName.isEmpty()) {
      // SPARTA raises findings against the controller, not the URL that reached it, and its
      // endpoint_name means an XController specifically -- a Graph edge is not one.
      Zurp.spartaFindingFetcher.addToQueue(SpartaTarget.endpointName(controllerName));
    }
    return FetchOutcome.STORED;
  }

  /** Not the entry point here: {@link #fetchData} is overridden to report the third outcome. */
  @Override
  protected boolean fetchDataAndStore(String dataItem) {
    return fetchData(dataItem) == FetchOutcome.STORED;
  }

  public MetaUrlInfoModel getObject(String url) {
    PersistedObject persistedObj = fetcherData.getChildObject(url);
    if (persistedObj == null) {
      return null;
    }
    return new MetaUrlInfoModel(
        persistedObj.getString("url"),
        persistedObj.getString("controller_name"),
        persistedObj.getString("graph_edge"));
  }

  @Override
  protected void prefetch(java.util.List<String> dataItems) {
    Zurp.assetResolver.prime(dataItems);
  }
}

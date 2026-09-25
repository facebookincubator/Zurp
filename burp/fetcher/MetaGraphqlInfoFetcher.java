/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.persistence.PersistedObject;
import burp.models.MetaGraphqlInfoModel;
import burp.zurp.*;

/**
 * A persisted document id or GraphQL operation name, resolved to the operation behind it.
 *
 * <p>Queued as the asset endpoint spells them: a document id keeps its {@code doc_id=} prefix, and
 * an operation name only reaches the queue when it ends in Query or Mutation. See {@link
 * burp.zurp.MetaAssetQueries}.
 */
public class MetaGraphqlInfoFetcher extends ZurpDataFetcher {

  @Override
  protected String getDataTypeName() {
    return "Meta GraphQL Info";
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

    String operationName = lookup.assets.one(AssetResolver.GRAPHQL, dataItem);
    if (operationName.isEmpty()) {
      return FetchOutcome.FAILED;
    }

    PersistedObject objectToSave = PersistedObject.persistedObject();
    objectToSave.setString("query", dataItem);
    objectToSave.setString("operation_name", operationName);
    fetcherData.setChildObject(dataItem, objectToSave);

    ZurpLog.output("[MetaGraphqlInfoFetcher] Fetched: " + dataItem + " -> " + operationName);
    return FetchOutcome.STORED;
  }

  /** Not the entry point here: {@link #fetchData} is overridden to report the third outcome. */
  @Override
  protected boolean fetchDataAndStore(String dataItem) {
    return fetchData(dataItem) == FetchOutcome.STORED;
  }

  public MetaGraphqlInfoModel getObject(String query) {
    PersistedObject persistedObj = fetcherData.getChildObject(query);
    if (persistedObj == null) {
      return null;
    }
    return new MetaGraphqlInfoModel(
        persistedObj.getString("query"), persistedObj.getString("operation_name"));
  }

  @Override
  protected void prefetch(java.util.List<String> dataItems) {
    Zurp.assetResolver.prime(dataItems);
  }
}

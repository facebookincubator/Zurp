/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.fetcher;

import burp.api.montoya.persistence.PersistedObject;
import burp.models.MetaObjectInfoModel;
import burp.zurp.*;

/** An FBID or Instagram media id, resolved to the Ent or Node type behind it. */
public class MetaObjectInfoFetcher extends ZurpDataFetcher {

  @Override
  protected String getDataTypeName() {
    return "Meta Object Info";
  }

  @Override
  protected boolean isFetchingEnabled() {
    return super.isFetchingEnabled() && Zurp.assetResolver.isAvailable();
  }

  @Override
  protected FetchOutcome fetchData(String dataItem) {
    AssetResolver.Lookup lookup = Zurp.assetResolver.lookup(dataItem, true);
    if (lookup.assets == null) {
      return lookup.outcome;
    }

    String objectType = lookup.assets.one(AssetResolver.ENT_OR_NODE, dataItem);
    if (objectType.isEmpty()) {
      // An id that resolves to no Ent or Node has nothing to show in this table, and asking again
      // will not change that.
      return FetchOutcome.FAILED;
    }

    PersistedObject objectToSave = PersistedObject.persistedObject();
    objectToSave.setString("object_id", dataItem);
    objectToSave.setString("object_type", objectType);
    // Usually "": only an object with a vanity the researcher can already see has one.
    objectToSave.setString("object_name", lookup.assets.objectName());
    fetcherData.setChildObject(dataItem, objectToSave);

    ZurpLog.output("[MetaObjectInfoFetcher] Fetched: " + dataItem + " -> " + objectType);
    return FetchOutcome.STORED;
  }

  /** Not the entry point here: {@link #fetchData} is overridden to report the third outcome. */
  @Override
  protected boolean fetchDataAndStore(String dataItem) {
    return fetchData(dataItem) == FetchOutcome.STORED;
  }

  public MetaObjectInfoModel getObject(String id) {
    PersistedObject persistedObj = fetcherData.getChildObject(id);
    if (persistedObj != null) {
      return new MetaObjectInfoModel(
          persistedObj.getString("object_id"),
          persistedObj.getString("object_name"),
          persistedObj.getString("object_type"));
    }
    return null;
  }

  @Override
  protected void prefetch(java.util.List<String> dataItems) {
    Zurp.assetResolver.prime(dataItems);
  }
}

/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

/** A persisted GraphQL document or operation name, resolved to the operation behind it. */
public class MetaGraphqlInfoModel {

  public final String query;
  public final String operationName;

  public MetaGraphqlInfoModel(String query, String operationName) {
    this.query = query;
    this.operationName = operationName;
  }
}

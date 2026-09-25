/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

/** A URL, resolved to the assets that serve it. */
public class MetaUrlInfoModel {
  public String url;

  /** The XController, or "" for a host that is not served by one. */
  public String controllerName;

  /** The Graph edge, or "" since only a {@code graph.} host resolves to one. */
  public String graphEdge;

  public MetaUrlInfoModel(String url, String controllerName, String graphEdge) {
    this.url = url;
    this.controllerName = controllerName;
    this.graphEdge = graphEdge;
  }
}

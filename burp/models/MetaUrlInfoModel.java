/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.models;

public class MetaUrlInfoModel {
  public String url;
  public String controllerName;

  public MetaUrlInfoModel(String url, String controllerName) {
    this.url = url;
    this.controllerName = controllerName;
  }
}

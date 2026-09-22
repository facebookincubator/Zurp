/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.editor;

import static burp.api.montoya.core.ByteArray.byteArray;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import java.awt.*;
import javax.swing.*;

public class MetaHttpRequestEditor extends MetaHttpEditor
    implements ExtensionProvidedHttpRequestEditor {

  public MetaHttpRequestEditor(MontoyaApi api, EditorCreationContext creationContext) {
    super(api, creationContext);
  }

  @Override
  public void setRequestResponse(HttpRequestResponse requestResponse) {
    this.requestResponse = requestResponse;
    editor.setContents(byteArray(requestResponse.request().toString()));
  }

  @Override
  public HttpRequest getRequest() {
    return this.requestResponse.request();
  }
}

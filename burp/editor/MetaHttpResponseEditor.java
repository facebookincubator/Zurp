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
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import java.awt.*;
import javax.swing.*;

public class MetaHttpResponseEditor extends MetaHttpEditor
    implements ExtensionProvidedHttpResponseEditor {

  public MetaHttpResponseEditor(MontoyaApi api, EditorCreationContext creationContext) {
    super(api, creationContext);
    // http responses are not editable
    this.editor.setEditable(false);
  }

  @Override
  public void setRequestResponse(HttpRequestResponse requestResponse) {
    this.requestResponse = requestResponse;
    editor.setContents(byteArray(requestResponse.response().toString()));
    refreshMetaViewTabs();
  }

  @Override
  public HttpResponse getResponse() {
    return this.requestResponse.response();
  }
}

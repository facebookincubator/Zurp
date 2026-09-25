/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.editor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;

public class MetaHttpResponseEditorProvider implements HttpResponseEditorProvider {
  private final MontoyaApi api;

  public MetaHttpResponseEditorProvider(MontoyaApi api) {
    this.api = api;
  }

  @Override
  public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(
      EditorCreationContext creationContext) {
    return new MetaHttpResponseEditor(api, creationContext);
  }
}

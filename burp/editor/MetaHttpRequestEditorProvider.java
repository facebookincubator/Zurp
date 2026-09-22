/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.editor;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;

public class MetaHttpRequestEditorProvider implements HttpRequestEditorProvider {
  private final MontoyaApi api;

  public MetaHttpRequestEditorProvider(MontoyaApi api) {
    this.api = api;
  }

  @Override
  public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(
      EditorCreationContext creationContext) {
    return new MetaHttpRequestEditor(api, creationContext);
  }
}

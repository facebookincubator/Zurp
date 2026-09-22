/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package burp.ui;

import java.awt.GridLayout;
import javax.swing.*;

public class MetaRequestContextInfo extends JPanel {

  public String url;
  public String controllerName;

  public MetaRequestContextInfo(String url, String controllerName) {
    this.url = url;
    this.controllerName = controllerName;
    setLayout(new GridLayout(2, 2));

    add(new JLabel("URL:"));
    JTextField urlField = new JTextField(url);
    urlField.setEditable(false);
    add(urlField);

    add(new JLabel("Controller Name:"));
    JTextField controllerNameField = new JTextField(controllerName);
    controllerNameField.setEditable(false);
    add(controllerNameField);
  }
}

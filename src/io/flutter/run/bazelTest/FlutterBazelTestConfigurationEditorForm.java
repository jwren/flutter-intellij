/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run.bazelTest;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.SimpleListCellRenderer;
import com.jetbrains.lang.dart.ide.runner.server.ui.DartCommandLineConfigurationEditorForm;
import io.flutter.run.bazelTest.BazelTestFields.Scope;
import io.flutter.settings.FlutterSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.Objects;

import static io.flutter.run.bazelTest.BazelTestFields.Scope.*;

public class FlutterBazelTestConfigurationEditorForm extends SettingsEditor<BazelTestConfig> {
  private JPanel myMainPanel;

  private JComboBox<Scope> scope;
  private JLabel scopeLabel;
  private JLabel scopeLabelHint;

  private TextFieldWithBrowseButton myEntryFile;
  private JLabel myEntryFileLabel;
  private JLabel myEntryFileHintLabel;

  private JTextField myBuildTarget;
  private JLabel myBuildTargetLabel;
  private JLabel myBuildTargetHintLabel;

  private JTextField myTestName;
  private JLabel myTestNameLabel;
  private JLabel myTestNameHintLabel;

  private JTextField myAdditionalArgs;
  private JLabel myAdditionalArgsLabel;
  private JLabel myAdditionalArgsLabelHint;

  private Scope displayedScope;

  public FlutterBazelTestConfigurationEditorForm(final Project project) {
    FlutterSettings.getInstance().addListener(() -> {
      final Scope next = getScope();
      updateFields(next);
      render(getScope());
    });
    scope.setModel(new DefaultComboBoxModel<>(new Scope[]{TARGET_PATTERN, FILE, NAME}));
    scope.addActionListener((ActionEvent e) -> {
      final Scope next = getScope();
      updateFields(next);
      render(next);
    });
    scope.setRenderer(new SimpleListCellRenderer<>() {
      @Override
      public void customize(final JList list,
                            final Scope value,
                            final int index,
                            final boolean selected,
                            final boolean hasFocus) {
        setText(value.getDisplayName());
      }
    });
    scope.setEnabled(true);

    final FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor();

    DartCommandLineConfigurationEditorForm.initDartFileTextWithBrowse(project, myEntryFile);
  }

  @Override
  protected void resetEditorFrom(@NotNull final BazelTestConfig configuration) {
    final BazelTestFields fields = configuration.getFields();
    myTestName.setText(fields.getTestName());
    myEntryFile.setText(FileUtil.toSystemDependentName(StringUtil.notNullize(fields.getEntryFile())));
    myBuildTarget.setText(StringUtil.notNullize(fields.getBazelTarget()));
    myAdditionalArgs.setText(StringUtil.notNullize(fields.getAdditionalArgs()));
    final Scope next = fields.getScope(configuration.getProject());
    scope.setSelectedItem(next);
    render(next);
  }

  @Override
  protected void applyEditorTo(@NotNull final BazelTestConfig configuration) {
    final String testName = getTextValue(myTestName);
    final String entryFile = getFilePathFromTextValue(myEntryFile);
    final String bazelTarget = getTextValue(myBuildTarget);
    final String additionalArgs = getTextValue(myAdditionalArgs);
    final BazelTestFields fields = new BazelTestFields(
      testName,
      entryFile,
      bazelTarget,
      additionalArgs
    );
    configuration.setFields(fields);
  }

  @NotNull
  @Override
  protected JComponent createEditor() {
    return myMainPanel;
  }


  /**
   * When switching between file and directory scope, update the next field to
   * a suitable default.
   */
  private void updateFields(Scope next) {
    // TODO(devoncarew): This if path is empty - due to a refactoring?
    if (next == Scope.TARGET_PATTERN && displayedScope != Scope.TARGET_PATTERN) {
    }
    else if (next != Scope.TARGET_PATTERN) {
      if (getFilePathFromTextValue(myEntryFile) == null) {
        myEntryFile.setText(myBuildTarget.getText().replace("//", ""));
      }
    }
  }

  @NotNull
  private Scope getScope() {
    final Object item = scope.getSelectedItem();
    // Set in resetEditorForm.
    assert (item != null);
    return (Scope)item;
  }


  /**
   * Show and hide fields as appropriate for the next scope.
   */
  private void render(Scope next) {

    scope.setVisible(true);
    scopeLabel.setVisible(true);
    scopeLabelHint.setVisible(true);

    myAdditionalArgs.setVisible(true);
    myAdditionalArgsLabel.setVisible(true);
    myAdditionalArgsLabel.setVisible(true);

    myTestName.setVisible(next == Scope.NAME);
    myTestNameLabel.setVisible(next == Scope.NAME);
    myTestNameHintLabel.setVisible(next == Scope.NAME);

    myEntryFile.setVisible(next == Scope.FILE || next == Scope.NAME);
    myEntryFileLabel.setVisible(next == Scope.FILE || next == Scope.NAME);
    myEntryFileHintLabel.setVisible(next == Scope.FILE || next == Scope.NAME);

    myBuildTarget.setVisible(next == Scope.TARGET_PATTERN);
    myBuildTargetLabel.setVisible(next == Scope.TARGET_PATTERN);
    myBuildTargetHintLabel.setVisible(next == Scope.TARGET_PATTERN);

    // Because the scope of the underlying fields is calculated based on which parameters are assigned,
    // we remove fields that aren't part of the selected scope.
    if (Objects.equals(next, Scope.TARGET_PATTERN)) {
      myTestName.setText("");
      myEntryFile.setText("");
    }
    else if (next.equals(Scope.FILE)) {
      myTestName.setText("");
    }

    displayedScope = next;
  }

  @Nullable
  private String getTextValue(@NotNull String textFieldContents) {
    return StringUtil.nullize(textFieldContents.trim(), true);
  }

  @Nullable
  private String getTextValue(JTextField textField) {
    return getTextValue(textField.getText());
  }

  @Nullable
  private String getFilePathFromTextValue(TextFieldWithBrowseButton textField) {
    return getTextValue(FileUtil.toSystemIndependentName(textField.getText()));
  }
}

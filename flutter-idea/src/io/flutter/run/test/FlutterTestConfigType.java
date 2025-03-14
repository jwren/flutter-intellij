/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run.test;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import icons.FlutterIcons;
import io.flutter.run.FlutterRunConfigurationType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * The type of configs that run tests using "flutter test".
 */
public class FlutterTestConfigType extends ConfigurationTypeBase {
  protected FlutterTestConfigType() {
    super("FlutterTestConfigType", "Flutter Test", "description", FlutterIcons.Flutter_test);

    addFactory(new Factory(this));
  }

  public static FlutterTestConfigType getInstance() {
    return CONFIGURATION_TYPE_EP.findExtensionOrFail(FlutterTestConfigType.class);
  }

  private static class Factory extends ConfigurationFactory {
    public Factory(FlutterTestConfigType type) {
      super(type);
    }

    @NotNull
    @Override
    @NonNls
    public String getId() {
      return "Flutter Test";
    }

    @NotNull
    @Override
    public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
      return new TestConfig(project, this, "Flutter Test");
    }

    @Override
    public boolean isApplicable(@NotNull Project project) {
      return FlutterRunConfigurationType.doShowFlutterRunConfigurationForProject(project);
    }
  }
}

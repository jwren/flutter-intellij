/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run.bazel;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationSingletonPolicy;
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.jetbrains.lang.dart.DartFileType;
import icons.FlutterIcons;
import io.flutter.FlutterBundle;
import io.flutter.utils.FlutterModuleUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class FlutterBazelRunConfigurationType extends ConfigurationTypeBase {
  @VisibleForTesting final Factory factory = new Factory(this);

  public FlutterBazelRunConfigurationType() {
    super("FlutterBazelRunConfigurationType", FlutterBundle.message("runner.flutter.bazel.configuration.name"),
          FlutterBundle.message("runner.flutter.bazel.configuration.description"), FlutterIcons.BazelRun);
    addFactory(factory);
  }

  /**
   * Defined here for all Flutter Bazel run configurations.
   */
  public static boolean doShowBazelRunConfigurationForProject(@NotNull Project project) {
    return FileTypeIndex.containsFileOfType(DartFileType.INSTANCE, GlobalSearchScope.projectScope(project)) &&
           FlutterModuleUtils.isFlutterBazelProject(project);
  }

  @VisibleForTesting
  static class Factory extends ConfigurationFactory {
    public Factory(FlutterBazelRunConfigurationType type) {
      super(type);
    }

    @Override
    @NotNull
    public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
      // This is always called first when loading a run config, even when it's a non-template config.
      // See RunManagerImpl.doCreateConfiguration
      return new BazelRunConfig(project, this, FlutterBundle.message("runner.flutter.bazel.configuration.name"));
    }

    @Override
    @NotNull
    public RunConfiguration createConfiguration(String name, @NotNull RunConfiguration template) {
      // Called in two cases:
      //   - When creating a non-template config from a template.
      //   - whenever the run configuration editor is open (for creating snapshots).
      // In the first case, we want to override the defaults from the template.
      // In the second case, don't change anything.
      if (isNewlyGeneratedName(name) && template instanceof BazelRunConfig) {
        // TODO(jwren) is this really a good name for a new run config? Not sure why we override this.
        // Note that if the user creates more than one run config, they will need to rename it manually.
        name = template.getProject().getName();
        return ((BazelRunConfig)template).copyTemplateToNonTemplate(name);
      }
      else {
        return super.createConfiguration(name, template);
      }
    }

    private boolean isNewlyGeneratedName(String name) {
      // Try to determine if this we are creating a non-template configuration from a template.
      // This is a hack based on what the code does in RunConfigurable.createUniqueName().
      // If it fails to match, the new run config still works, just without any defaults set.
      final String baseName = ExecutionBundle.message("run.configuration.unnamed.name.prefix");
      return Objects.equals(name, baseName) || name.startsWith(baseName + " (");
    }

    @Override
    public boolean isApplicable(@NotNull Project project) {
      return FlutterBazelRunConfigurationType.doShowBazelRunConfigurationForProject(project);
    }

    @Override
    @NotNull
    public String getId() {
      return FlutterBundle.message("runner.flutter.bazel.configuration.name");
    }

    @NotNull
    @Override
    public RunConfigurationSingletonPolicy getSingletonPolicy() {
      return RunConfigurationSingletonPolicy.MULTIPLE_INSTANCE_ONLY;
    }
  }
}

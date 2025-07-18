/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.bazel;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import io.flutter.FlutterUtils;
import io.flutter.utils.OpenApiUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.PatternSyntaxException;

/**
 * An in-memory snapshot of the flutter.json file from a Bazel workspace.
 */
public class PluginConfig {
  private final @NotNull Fields fields;

  private PluginConfig(@NotNull Fields fields) {
    this.fields = fields;
  }

  @Nullable
  String getDaemonScript() {
    return fields.daemonScript;
  }

  @Nullable
  String getDevToolsScript() {
    return fields.devToolsScript;
  }

  @Nullable
  String getDoctorScript() {
    return fields.doctorScript;
  }

  @Nullable
  String getTestScript() {
    return fields.testScript;
  }

  @Nullable
  String getRunScript() {
    return fields.runScript;
  }

  @Nullable
  String getSyncScript() {
    return fields.syncScript;
  }

  @Nullable
  String getToolsScript() {
    return fields.toolsScript;
  }

  @Nullable
  String getSdkHome() {
    return fields.sdkHome;
  }

  @Nullable
  String getRequiredIJPluginID() {
    return fields.requiredIJPluginID;
  }

  @Nullable
  String getRequiredIJPluginMessage() {
    return fields.requiredIJPluginMessage;
  }

  @Nullable
  String getConfigWarningPrefix() {
    return fields.configWarningPrefix;
  }

  @Nullable
  String getUpdatedIosRunMessage() {
    return fields.updatedIosRunMessage;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof PluginConfig other)) return false;
    return Objects.equal(fields, other.fields);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(fields);
  }

  /**
   * Reads plugin configuration from a file, if possible.
   */
  @Nullable
  public static PluginConfig load(@NotNull VirtualFile file) {
    final Computable<PluginConfig> readAction = () -> {
      try (
        // Create the input stream in a try-with-resources statement. This will automatically close the stream
        // in an implicit finally section; this addresses a file handle leak issue we had on macOS.
        final InputStreamReader input = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)
      ) {
        final Fields fields = GSON.fromJson(input, Fields.class);
        assert fields != null;
        return new PluginConfig(fields);
      }
      catch (FileNotFoundException e) {
        LOG.info("Flutter plugin didn't find flutter.json at " + file.getPath());
        return null;
      }
      catch (IOException e) {
        FlutterUtils.warn(LOG, "Flutter plugin failed to load config file at " + file.getPath(), e);
        return null;
      }
      catch (JsonSyntaxException e) {
        FlutterUtils.warn(LOG, "Flutter plugin failed to parse JSON in config file at " + file.getPath());
        return null;
      }
      catch (PatternSyntaxException e) {
        FlutterUtils
          .warn(LOG, "Flutter plugin failed to parse directory pattern (" + e.getPattern() + ") in config file at " + file.getPath());
        return null;
      }
    };

    return OpenApiUtils.safeRunReadAction(readAction);
  }

  @VisibleForTesting
  public static @NotNull PluginConfig forTest(
    @Nullable String daemonScript,
    @Nullable String devToolsScript,
    @Nullable String doctorScript,
    @Nullable String testScript,
    @Nullable String runScript,
    @Nullable String syncScript,
    @Nullable String toolsScript,
    @Nullable String sdkHome,
    @Nullable String requiredIJPluginID,
    @Nullable String requiredIJPluginMessage,
    @Nullable String configWarningPrefix,
    @Nullable String updatedIosRunMessage
  ) {
    final Fields fields = new Fields(
      daemonScript,
      devToolsScript,
      doctorScript,
      testScript,
      runScript,
      syncScript,
      toolsScript,
      sdkHome,
      requiredIJPluginID,
      requiredIJPluginMessage,
      configWarningPrefix,
      updatedIosRunMessage
    );
    return new PluginConfig(fields);
  }

  /**
   * The JSON fields in a PluginConfig, as loaded from disk.
   */
  private static class Fields {
    /**
     * The script to run to start 'flutter daemon'.
     */
    @SerializedName("daemonScript")
    private @Nullable String daemonScript;

    @SerializedName("devToolsScript")
    private @Nullable String devToolsScript;

    /**
     * The script to run to start 'flutter doctor'.
     */
    @SerializedName("doctorScript")
    private @Nullable String doctorScript;

    /**
     * The script to run to start 'flutter test'
     */
    @SerializedName("testScript")
    private @Nullable String testScript;

    /**
     * The script to run to start 'flutter run'
     */
    @SerializedName("runScript")
    private @Nullable String runScript;

    /**
     * The script to run to start 'flutter sync'
     */
    @SerializedName("syncScript")
    private @Nullable String syncScript;

    @SerializedName("toolsScript")
    private @Nullable String toolsScript;

    /**
     * The directory containing the SDK tools.
     */
    @SerializedName("sdkHome")
    private @Nullable String sdkHome;

    /**
     * The file containing the Flutter version.
     */
    @SerializedName("requiredIJPluginID")
    private @Nullable String requiredIJPluginID;

    /**
     * The file containing the message to install the required IJ Plugin.
     */
    @SerializedName("requiredIJPluginMessage")
    private @Nullable String requiredIJPluginMessage;

    /**
     * The prefix that indicates a configuration warning message.
     */
    @SerializedName("configWarningPrefix")
    private @Nullable String configWarningPrefix;

    /**
     * The prefix that indicates a message about iOS run being updated.
     */
    @SerializedName("updatedIosRunMessage")
    private @Nullable String updatedIosRunMessage;

    Fields() {
    }

    /**
     * Convenience constructor that takes all parameters.
     */
    Fields(@Nullable String daemonScript,
           @Nullable String devToolsScript,
           @Nullable String doctorScript,
           @Nullable String testScript,
           @Nullable String runScript,
           @Nullable String syncScript,
           @Nullable String toolsScript,
           @Nullable String sdkHome,
           @Nullable String requiredIJPluginID,
           @Nullable String requiredIJPluginMessage,
           @Nullable String configWarningPrefix,
           @Nullable String updatedIosRunMessage) {
      this.daemonScript = daemonScript;
      this.devToolsScript = devToolsScript;
      this.doctorScript = doctorScript;
      this.testScript = testScript;
      this.runScript = runScript;
      this.syncScript = syncScript;
      this.toolsScript = toolsScript;
      this.sdkHome = sdkHome;
      this.requiredIJPluginID = requiredIJPluginID;
      this.requiredIJPluginMessage = requiredIJPluginMessage;
      this.configWarningPrefix = configWarningPrefix;
      this.updatedIosRunMessage = updatedIosRunMessage;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Fields other)) return false;
      return Objects.equal(daemonScript, other.daemonScript)
             && Objects.equal(devToolsScript, other.devToolsScript)
             && Objects.equal(doctorScript, other.doctorScript)
             && Objects.equal(testScript, other.testScript)
             && Objects.equal(runScript, other.runScript)
             && Objects.equal(syncScript, other.syncScript)
             && Objects.equal(sdkHome, other.sdkHome)
             && Objects.equal(requiredIJPluginID, other.requiredIJPluginID)
             && Objects.equal(requiredIJPluginMessage, other.requiredIJPluginMessage)
             && Objects.equal(configWarningPrefix, other.configWarningPrefix)
             && Objects.equal(updatedIosRunMessage, other.updatedIosRunMessage);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(daemonScript, devToolsScript, doctorScript, testScript, runScript, syncScript, sdkHome, requiredIJPluginID,
                              requiredIJPluginMessage, configWarningPrefix, updatedIosRunMessage);
    }
  }

  private static final @NotNull Gson GSON = new Gson();
  private static final @NotNull Logger LOG = Logger.getInstance(PluginConfig.class);
}

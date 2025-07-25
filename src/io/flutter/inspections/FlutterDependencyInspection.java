/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspections;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInspection.*;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.jetbrains.lang.dart.psi.DartFile;
import io.flutter.FlutterBundle;
import io.flutter.FlutterUtils;
import io.flutter.bazel.WorkspaceCache;
import io.flutter.dart.DartPlugin;
import io.flutter.pub.PubRoot;
import io.flutter.pub.PubRootCache;
import io.flutter.sdk.FlutterSdk;
import io.flutter.utils.FlutterModuleUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * This LocalInspectionTool provides the banner on the top of the editor that
 * <p>
 * TODO(jwren) resolve and document why this is a LocalInspectionTool instead of a EditorNotificationProvider, the UX to the end user seems
 *  to be the same, and not require a PsiFile.
 */
public class FlutterDependencyInspection extends LocalInspectionTool {
  private final Set<String> myIgnoredPubspecPaths = new HashSet<>(); // remember for the current session only, do not serialize

  @Nullable
  @Override
  public ProblemDescriptor[] checkFile(@NotNull final PsiFile psiFile, @NotNull final InspectionManager manager, final boolean isOnTheFly) {
    if (!isOnTheFly) return null;

    if (!(psiFile instanceof DartFile)) return null;

    if (DartPlugin.isPubActionInProgress()) return null;

    final VirtualFile file = FlutterUtils.getRealVirtualFile(psiFile);
    if (file == null || !file.isInLocalFileSystem()) return null;

    final Project project = psiFile.getProject();
    // If the project should use bazel instead of pub, don't surface this warning.
    if (WorkspaceCache.getInstance(project).isBazel()) return null;


    var projectRootManager = ProjectRootManager.getInstance(project);
    if (projectRootManager == null) return null;

    if (!projectRootManager.getFileIndex().isInContent(file)) return null;

    final Module module = ModuleUtilCore.findModuleForFile(file, project);
    if (!FlutterModuleUtils.isFlutterModule(module)) return null;

    final PubRoot root = PubRootCache.getInstance(project).getRoot(psiFile);

    if (root == null || myIgnoredPubspecPaths.contains(root.getPubspec().getPath())) return null;

    if (!root.declaresFlutter()) return null;

    // TODO(pq): consider validating package name here (`get` will fail if it's invalid).

    if (root.getPackagesFile() == null && root.getPackageConfigFile() == null) {
      return createProblemDescriptors(manager, psiFile, root, FlutterBundle.message("pub.get.not.run"));
    }

    if (!root.hasUpToDatePackages()) {
      return createProblemDescriptors(manager, psiFile, root, FlutterBundle.message("pubspec.edited"));
    }

    return null;
  }

  @NotNull
  private ProblemDescriptor[] createProblemDescriptors(@NotNull final InspectionManager manager,
                                                       @NotNull final PsiFile psiFile,
                                                       @NotNull final PubRoot root,
                                                       @NotNull final String errorMessage) {
    //noinspection DataFlowIssue
    final LocalQuickFix[] fixes = new LocalQuickFix[]{
      new PackageUpdateFix(FlutterBundle.message("get.dependencies"), FlutterSdk::startPubGet),
      new PackageUpdateFix(FlutterBundle.message("upgrade.dependencies"), FlutterSdk::startPubUpgrade),
      new IgnoreWarningFix(myIgnoredPubspecPaths, root.getPubspec().getPath())};

    return new ProblemDescriptor[]{
      manager.createProblemDescriptor(psiFile, errorMessage, true, fixes, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)};
  }

  private interface SdkAction {
    Process run(FlutterSdk sdk, @NotNull PubRoot root, @NotNull Project project);
  }

  private static class PackageUpdateFix extends IntentionAndQuickFixAction {
    private final String myFixName;
    private final SdkAction mySdkAction;

    private PackageUpdateFix(@NotNull final String fixName, @NotNull final SdkAction action) {
      myFixName = fixName;
      mySdkAction = action;
    }

    @Override
    @NotNull
    public String getName() {
      return myFixName;
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return getName();
    }

    @Override
    public boolean startInWriteAction() {
      return false;
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull final PsiFile psiFile, @Nullable final Editor editor) {
      final FlutterSdk sdk = FlutterSdk.getFlutterSdk(project);
      if (sdk == null) return;

      final PubRoot root = PubRoot.forPsiFile(psiFile);
      if (root == null) return;

      // TODO(skybrian) analytics?
      mySdkAction.run(sdk, root, project);

      restartCodeAnalyzer(project, psiFile);
    }
  }

  private static class IgnoreWarningFix extends IntentionAndQuickFixAction {
    @NotNull private final Set<String> myIgnoredPubspecPaths;
    @NotNull private final String myPubspecPath;

    public IgnoreWarningFix(@NotNull final Set<String> ignoredPubspecPaths, @NotNull final String pubspecPath) {
      myIgnoredPubspecPaths = ignoredPubspecPaths;
      myPubspecPath = pubspecPath;
    }

    @Override
    @NotNull
    public String getName() {
      return FlutterBundle.message("ignore.warning");
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return getName();
    }

    @Override
    public boolean startInWriteAction() {
      return false;
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull final PsiFile psiFile, @Nullable final Editor editor) {
      myIgnoredPubspecPaths.add(myPubspecPath);

      restartCodeAnalyzer(project, psiFile);
    }
  }

  private static void restartCodeAnalyzer(@NotNull final Project project, @NotNull final PsiFile psiFile) {
    var codeAnalyzer = DaemonCodeAnalyzer.getInstance(project);
    if (codeAnalyzer != null) {
      codeAnalyzer.restart(psiFile);
    }
  }
}



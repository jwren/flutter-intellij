/*
 * Copyright 2020 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.vmService;

import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.util.BitUtil;
import com.intellij.util.TimeoutUtil;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.jetbrains.lang.dart.ide.runner.actions.DartPopFrameAction;
import com.jetbrains.lang.dart.ide.runner.base.DartDebuggerEditorsProvider;
import com.jetbrains.lang.dart.util.DartUrlResolver;
import io.flutter.FlutterBundle;
import io.flutter.FlutterUtils;
import io.flutter.ObservatoryConnector;
import io.flutter.run.FlutterLaunchMode;
import io.flutter.utils.OpenApiUtils;
import io.flutter.vmService.frame.DartVmServiceEvaluator;
import io.flutter.vmService.frame.DartVmServiceStackFrame;
import io.flutter.vmService.frame.DartVmServiceSuspendContext;
import org.dartlang.vm.service.VmService;
import org.dartlang.vm.service.consumer.GetObjectConsumer;
import org.dartlang.vm.service.consumer.VMConsumer;
import org.dartlang.vm.service.element.*;
import org.dartlang.vm.service.element.Event;
import org.dartlang.vm.service.logging.Logging;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public abstract class DartVmServiceDebugProcess extends XDebugProcess {
  private static final @NotNull Logger LOG = Logger.getInstance(DartVmServiceDebugProcess.class.getName());

  @NotNull private final ExecutionResult myExecutionResult;
  @NotNull private final DartUrlResolver myDartUrlResolver;
  @NotNull private final XBreakpointHandler[] myBreakpointHandlers;
  private final IsolatesInfo myIsolatesInfo;
  @NotNull private final Map<String, CompletableFuture<Object>> mySuspendedIsolateIds = Collections.synchronizedMap(new HashMap<>());
  private final Map<String, LightVirtualFile> myScriptIdToContentMap = new HashMap<>();
  @Nullable private final VirtualFile myCurrentWorkingDirectory;
  @NotNull private final ObservatoryConnector myConnector;
  @NotNull private final ExecutionEnvironment executionEnvironment;
  @NotNull private final PositionMapper mapper;
  @Nullable protected String myRemoteProjectRootUri;
  private boolean myVmConnected = false;
  private VmServiceWrapper myVmServiceWrapper;
  private String myLatestCurrentIsolateId;

  public DartVmServiceDebugProcess(@NotNull final ExecutionEnvironment executionEnvironment,
                                   @NotNull final XDebugSession session,
                                   @NotNull final ExecutionResult executionResult,
                                   @NotNull final DartUrlResolver dartUrlResolver,
                                   @NotNull final ObservatoryConnector connector,
                                   @NotNull final PositionMapper mapper) {
    super(session);

    myExecutionResult = executionResult;
    myDartUrlResolver = dartUrlResolver;
    myCurrentWorkingDirectory = null;

    myIsolatesInfo = new IsolatesInfo();

    myBreakpointHandlers = new XBreakpointHandler[]{
      new DartVmServiceBreakpointHandler(this),
      new DartExceptionBreakpointHandler(this)
    };

    session.setPauseActionSupported(true);

    session.addSessionListener(new XDebugSessionListener() {
      @Override
      public void sessionPaused() {
        // This can be removed if XFramesView starts popping the project window to the top of the z-axis stack.
        final Project project = getSession().getProject();
        focusProject(project);
        stackFrameChanged();
      }

      @Override
      public void stackFrameChanged() {
        final XStackFrame stackFrame = getSession().getCurrentStackFrame();
        myLatestCurrentIsolateId =
          stackFrame instanceof DartVmServiceStackFrame ? ((DartVmServiceStackFrame)stackFrame).getIsolateId() : null;
      }
    });

    this.executionEnvironment = executionEnvironment;
    this.mapper = mapper;
    myConnector = connector;

    setLogger();

    final Runnable resumeCallback = () -> {
      if (session.isPaused()) {
        session.resume();
      }
    };

    session.addSessionListener(new XDebugSessionListener() {
      @Override
      public void sessionPaused() {
        stackFrameChanged();
        connector.onDebuggerPaused(resumeCallback);
      }

      @Override
      public void sessionResumed() {
        connector.onDebuggerResumed();
      }

      @Override
      public void stackFrameChanged() {
        final XStackFrame stackFrame = getSession().getCurrentStackFrame();
        myLatestCurrentIsolateId =
          stackFrame instanceof DartVmServiceStackFrame ? ((DartVmServiceStackFrame)stackFrame).getIsolateId() : null;
      }
    });

    scheduleConnect();
  }

  public ExceptionPauseMode getBreakOnExceptionMode() {
    return DartExceptionBreakpointHandler
      .getBreakOnExceptionMode(getSession(),
                               DartExceptionBreakpointHandler.getDefaultExceptionBreakpoint(getSession().getProject()));
  }

  public VmServiceWrapper getVmServiceWrapper() {
    return myVmServiceWrapper;
  }

  public Collection<IsolatesInfo.IsolateInfo> getIsolateInfos() {
    return myIsolatesInfo.getIsolateInfos();
  }

  private void setLogger() {
    Logging.setLogger(new org.dartlang.vm.service.logging.Logger() {
      @Override
      public void logError(final String message) {
        if (message.contains("\"code\":102,")) { // Cannot add breakpoint, already logged in logInformation()
          return;
        }

        if (message.contains("\"method\":\"removeBreakpoint\"")) { // That's expected because we set one breakpoint twice
          return;
        }

        if (message.contains("\"type\":\"Sentinel\"")) { // Ignore unwanted message
          return;
        }

        getSession().getConsoleView().print(message.trim() + "\n", ConsoleViewContentType.ERROR_OUTPUT);
        LOG.warn(message);
      }

      @Override
      public void logError(final String message, final Throwable exception) {
        if (!getVmConnected() || getSession() == null) {
          return;
        }
        if (message != null) {
          getSession().getConsoleView().print(message.trim() + "\n", ConsoleViewContentType.ERROR_OUTPUT);
        }
        LOG.warn(message, exception);
      }

      @Override
      public void logInformation(String message) {
        if (message.length() > 500) {
          message = message.substring(0, 300) + "..." + message.substring(message.length() - 200);
        }
        LOG.debug(message);
      }

      @Override
      public void logInformation(final String message, final Throwable exception) {
        LOG.debug(message, exception);
      }
    });
  }

  public void scheduleConnect() {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      // Poll, waiting for "flutter run" to give us a websocket.
      // Don't use a timeout - the user can cancel manually the operation.
      String url = myConnector.getWebSocketUrl();

      while (url == null) {
        if (getSession().isStopped()) return;

        TimeoutUtil.sleep(100);

        url = myConnector.getWebSocketUrl();
      }

      if (getSession().isStopped()) {
        return;
      }

      // "flutter run" has given us a websocket; we can assume it's ready immediately, because
      // "flutter run" has already connected to it.
      final VmService vmService;
      try {
        vmService = VmService.connect(url);
      }
      catch (IOException | RuntimeException e) {
        onConnectFailed("Failed to connect to the VM observatory service at: " + url + "\n"
                        + e + "\n" + formatStackTraces(e));
        return;
      }
      onConnectSucceeded(vmService);
    });
  }

  private void connect(@NotNull final String url) throws IOException {
    final VmService vmService = VmService.connect(url);
    final DartVmServiceListener vmServiceListener =
      new DartVmServiceListener(this, (DartVmServiceBreakpointHandler)myBreakpointHandlers[0]);

    vmService.addVmServiceListener(vmServiceListener);

    myVmServiceWrapper =
      new VmServiceWrapper(this, vmService, vmServiceListener, myIsolatesInfo, (DartVmServiceBreakpointHandler)myBreakpointHandlers[0]);
    myVmServiceWrapper.handleDebuggerConnected();

    myVmConnected = true;
  }

  @Override
  protected ProcessHandler doGetProcessHandler() {
    return myExecutionResult.getProcessHandler();
  }

  @NotNull
  @Override
  public ExecutionConsole createConsole() {
    return myExecutionResult.getExecutionConsole();
  }

  @NotNull
  @Override
  public XDebuggerEditorsProvider getEditorsProvider() {
    return new DartDebuggerEditorsProvider();
  }

  @Override
  @NotNull
  public XBreakpointHandler<?> @NotNull [] getBreakpointHandlers() {
    return myBreakpointHandlers;
  }

  public void guessRemoteProjectRoot(@NotNull final ElementList<LibraryRef> libraries) {
    // TODO(skybrian) do we need to handle multiple isolates?
    mapper.onLibrariesDownloaded(libraries);
  }

  @Override
  public void startStepOver(@Nullable XSuspendContext context) {
    if (myLatestCurrentIsolateId != null && mySuspendedIsolateIds.containsKey(myLatestCurrentIsolateId)) {
      final DartVmServiceSuspendContext suspendContext = (DartVmServiceSuspendContext)context;
      final StepOption stepOption = suspendContext != null && suspendContext.getAtAsyncSuspension() ? StepOption.OverAsyncSuspension
                                                                                                    : StepOption.Over;
      myVmServiceWrapper.resumeIsolate(myLatestCurrentIsolateId, stepOption);
    }
  }

  @Override
  public void startStepInto(@Nullable XSuspendContext context) {
    if (myLatestCurrentIsolateId != null && mySuspendedIsolateIds.containsKey(myLatestCurrentIsolateId)) {
      myVmServiceWrapper.resumeIsolate(myLatestCurrentIsolateId, StepOption.Into);
    }
  }

  @Override
  public void startStepOut(@Nullable XSuspendContext context) {
    if (myLatestCurrentIsolateId != null && mySuspendedIsolateIds.containsKey(myLatestCurrentIsolateId)) {
      myVmServiceWrapper.resumeIsolate(myLatestCurrentIsolateId, StepOption.Out);
    }
  }


  public void dropFrame(DartVmServiceStackFrame frame) {
    myVmServiceWrapper.dropFrame(frame.getIsolateId(), frame.getFrameIndex() + 1);
  }

  @Override
  public void stop() {
    myVmConnected = false;

    mapper.shutdown();

    if (myVmServiceWrapper != null) {
      Disposer.dispose(myVmServiceWrapper);
    }
  }

  @Override
  public void resume(@Nullable XSuspendContext context) {
    for (String isolateId : new ArrayList<>(mySuspendedIsolateIds.keySet())) {
      myVmServiceWrapper.resumeIsolate(isolateId, null);
    }
  }

  @Override
  public void startPausing() {
    for (IsolatesInfo.IsolateInfo info : getIsolateInfos()) {
      if (!mySuspendedIsolateIds.containsKey(info.getIsolateId())) {
        myVmServiceWrapper.pauseIsolate(info.getIsolateId());
      }
    }
  }

  @Override
  public void runToPosition(@NotNull XSourcePosition position, @Nullable XSuspendContext context) {
    if (myLatestCurrentIsolateId != null && mySuspendedIsolateIds.containsKey(myLatestCurrentIsolateId)) {
      // Set a temporary breakpoint and resume.
      myVmServiceWrapper.addTemporaryBreakpoint(position, myLatestCurrentIsolateId);
      myVmServiceWrapper.resumeIsolate(myLatestCurrentIsolateId, null);
    }
  }

  public void isolateSuspended(@NotNull final IsolateRef isolateRef) {
    final String id = isolateRef.getId();
    assert (!mySuspendedIsolateIds.containsKey(id));
    if (!mySuspendedIsolateIds.containsKey(id)) {
      mySuspendedIsolateIds.put(id, new CompletableFuture<>());
    }
  }

  public boolean isIsolateSuspended(@NotNull final String isolateId) {
    return mySuspendedIsolateIds.containsKey(isolateId);
  }

  public CompletableFuture<?> whenIsolateResumed(String isolateId) {
    final CompletableFuture<?> future = mySuspendedIsolateIds.get(isolateId);
    // If the future is null, then the isolate wasn't actually suspended.
    return Objects.requireNonNullElseGet(future, () -> CompletableFuture.completedFuture(null));
  }

  public boolean isIsolateAlive(@NotNull final String isolateId) {
    for (IsolatesInfo.IsolateInfo isolateInfo : myIsolatesInfo.getIsolateInfos()) {
      if (isolateId.equals(isolateInfo.getIsolateId())) {
        return true;
      }
    }
    return false;
  }

  public void isolateResumed(@NotNull final IsolateRef isolateRef) {
    final CompletableFuture<Object> future = mySuspendedIsolateIds.remove(isolateRef.getId());
    if (future != null) {
      future.complete(null); // Notify listeners that the isolate resumed.
    }
  }

  public void isolateExit(@NotNull final IsolateRef isolateRef) {
    myIsolatesInfo.deleteIsolate(isolateRef);
    mySuspendedIsolateIds.remove(isolateRef.getId());

    if (Objects.equals(isolateRef.getId(), myLatestCurrentIsolateId)) {
      resume(getSession().getSuspendContext()); // otherwise no way no resume them from UI
    }
  }

  public void handleWriteEvent(String base64Data) {
    final String message = new String(Base64.getDecoder().decode(base64Data), StandardCharsets.UTF_8);
    getSession().getConsoleView().print(message, ConsoleViewContentType.NORMAL_OUTPUT);
  }


  @Override
  public String getCurrentStateMessage() {
    return getSession().isStopped()
           ? XDebuggerBundle.message("debugger.state.message.disconnected")
           : myVmConnected
             ? XDebuggerBundle.message("debugger.state.message.connected")
             : FlutterBundle.message("waiting.for.flutter");
  }

  @Override
  public void registerAdditionalActions(@NotNull final DefaultActionGroup leftToolbar,
                                        @NotNull final DefaultActionGroup topToolbar,
                                        @NotNull final DefaultActionGroup settings) {
    // For Run tool window this action is added in DartCommandLineRunningState.createActions()
    topToolbar.addSeparator();
    topToolbar.addAction(new DartPopFrameAction());
  }

  @NotNull
  public Collection<String> getUrisForFile(@NotNull final VirtualFile file) {
    return mapper.getBreakpointUris(file);
  }

  @Nullable
  public XSourcePosition getSourcePosition(@NotNull final String isolateId, @NotNull final ScriptRef scriptRef, int tokenPos) {
    CompletableFuture<String> fileFuture = myVmServiceWrapper.findResolvedFile(isolateId, scriptRef.getUri());
    return mapper.getSourcePosition(isolateId, scriptRef, tokenPos, fileFuture);
  }

  @Nullable
  public String getCurrentIsolateId() {
    if (myLatestCurrentIsolateId != null) {
      return myLatestCurrentIsolateId;
    }
    return getIsolateInfos().isEmpty() ? null : getIsolateInfos().iterator().next().getIsolateId();
  }

  @NotNull
  public ExecutionEnvironment getExecutionEnvironment() {
    return executionEnvironment;
  }

  @Nullable
  public XDebuggerEvaluator getEvaluator() {
    final XStackFrame frame = getSession().getCurrentStackFrame();
    if (frame != null) {
      return frame.getEvaluator();
    }
    return new DartVmServiceEvaluator(this);
  }

  @NotNull
  private String formatStackTraces(Throwable e) {
    final StringBuilder out = new StringBuilder();
    Throwable cause = e.getCause();
    while (cause != null) {
      out.append("Caused by: ").append(cause).append("\n");
      cause = cause.getCause();
    }
    return out.toString();
  }

  private void onConnectFailed(@NotNull String message) {
    if (!message.endsWith("\n")) {
      message = message + "\n";
    }
    getSession().getConsoleView().print(message, ConsoleViewContentType.ERROR_OUTPUT);
    getSession().stop();
  }

  private void onConnectSucceeded(VmService vmService) {
    final DartVmServiceListener vmServiceListener =
      new DartVmServiceListener(this, (DartVmServiceBreakpointHandler)myBreakpointHandlers[0]);
    final DartVmServiceBreakpointHandler breakpointHandler = (DartVmServiceBreakpointHandler)myBreakpointHandlers[0];

    myVmServiceWrapper = new VmServiceWrapper(this, vmService, vmServiceListener, myIsolatesInfo, breakpointHandler);

    final ScriptProvider provider =
      (isolateId, scriptId) -> myVmServiceWrapper.getScriptSync(isolateId, scriptId);

    mapper.onConnect(provider, myConnector.getRemoteBaseUrl());

    final FlutterLaunchMode launchMode = FlutterLaunchMode.fromEnv(executionEnvironment);
    if (launchMode.supportsDebugConnection()) {
      myVmServiceWrapper.handleDebuggerConnected();

      // TODO(jacobr): the following code is a workaround for issues
      // auto-resuming isolates paused at their creation while running in
      // debug mode.
      // The ideal fix would by to fix VMServiceWrapper so that it checks
      // for already running isolates like we do here or to refactor where we
      // create our VmServiceWrapper so we can listen for isolate creation soon
      // enough that we never miss an isolate creation message.
      vmService.getVM(new VMConsumer() {
        @Override
        public void received(VM vm) {
          final ElementList<IsolateRef> isolates = vm.getIsolates();
          // There is a risk the isolate we care about loaded before the call
          // to handleDebuggerConnected was made and so
          for (IsolateRef isolateRef : isolates) {
            vmService.getIsolate(isolateRef.getId(), new VmServiceConsumers.GetIsolateConsumerWrapper() {
              public void received(Isolate isolate) {
                final Event event = isolate.getPauseEvent();
                final EventKind eventKind = event.getKind();
                if (eventKind == EventKind.PauseStart) {
                  OpenApiUtils.safeInvokeLater(() -> {
                    // We are assuming it is safe to call handleIsolate multiple times.
                    myVmServiceWrapper.handleIsolate(isolateRef, true);
                  });
                }
                else if (eventKind == EventKind.Resume) {
                  // Currently true if we got here via 'flutter attach'
                  OpenApiUtils.safeInvokeLater(() -> {
                    myVmServiceWrapper.attachIsolate(isolateRef, isolate);
                  });
                }
              }
            });
          }
        }

        @Override
        public void onError(RPCError error) {
          FlutterUtils.warn(LOG, error.toString());
        }
      });
    }

    vmService.addVmServiceListener(vmServiceListener);

    myVmConnected = true;
    getSession().rebuildViews();
    onVmConnected(vmService);
  }

  private ScriptRef toScriptRef(Script script) {
    final JsonObject elt = new JsonObject();
    elt.addProperty("id", script.getId());
    elt.addProperty("uri", script.getUri());
    return new ScriptRef(elt);
  }

  // TODO(devoncarew): Re-implement this in terms of the generated vm service protocol library.
  private void onOpenSourceLocationRequest(@NotNull String isolateId, @NotNull String scriptId, int tokenPos) {
    myVmServiceWrapper.getObject(isolateId, scriptId, new GetObjectConsumer() {
      @Override
      public void received(Obj response) {
        if (response instanceof Script) {
          ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final XSourcePosition source =
              getSourcePosition(isolateId, toScriptRef((Script)response), tokenPos);
            if (source != null) {
              final Project project = getSession().getProject();
              final OpenFileHyperlinkInfo
                info = new OpenFileHyperlinkInfo(project, source.getFile(), source.getLine());
              OpenApiUtils.safeInvokeLater(() -> OpenApiUtils.safeRunWriteAction(() -> {
                info.navigate(project);

                ProjectUtil.focusProjectWindow(project, true);
              }));
            }
          });
        }
      }

      @Override
      public void received(Sentinel response) {
        // ignore
      }

      @Override
      public void onError(RPCError error) {
        // ignore
      }
    });
  }

  /**
   * Callback for subclass.
   */
  protected void onVmConnected(@NotNull VmService vmService) {
  }

  public boolean getVmConnected() {
    return myVmConnected;
  }

  private static boolean isDartPatchUri(@NotNull final String uri) {
    // dart:_builtin or dart:core-patch/core_patch.dart
    return uri.startsWith("dart:_") || uri.startsWith("dart:") && uri.contains("-patch/");
  }

  private static void focusProject(@NotNull Project project) {
    final WindowManager windowManager = WindowManager.getInstance();
    if (windowManager == null) {
      return;
    }

    final JFrame projectFrame = windowManager.getFrame(project);
    if (projectFrame == null) {
      return;
    }

    final int frameState = projectFrame.getExtendedState();

    if (BitUtil.isSet(frameState, java.awt.Frame.ICONIFIED)) {
      // restore the frame if it is minimized
      projectFrame.setExtendedState(frameState ^ java.awt.Frame.ICONIFIED);
      projectFrame.toFront();
    }
    else {
      final JFrame anchor = new JFrame();
      anchor.setType(Window.Type.UTILITY);
      anchor.setUndecorated(true);
      anchor.setSize(0, 0);
      anchor.addWindowListener(new WindowListener() {
        @Override
        public void windowOpened(WindowEvent e) {
        }

        @Override
        public void windowClosing(WindowEvent e) {
        }

        @Override
        public void windowClosed(WindowEvent e) {
        }

        @Override
        public void windowIconified(WindowEvent e) {
        }

        @Override
        public void windowDeiconified(WindowEvent e) {
        }

        @Override
        public void windowActivated(WindowEvent e) {
          if (projectFrame.isDisplayable()) {
            projectFrame.setVisible(true);
          }
          anchor.dispose();
        }

        @Override
        public void windowDeactivated(WindowEvent e) {
        }
      });
      anchor.pack();
      anchor.setVisible(true);
      anchor.toFront();
    }
  }

  public interface PositionMapper {
    void onConnect(ScriptProvider provider, String remoteBaseUrl);

    // TODO(skybrian) this is called once per isolate. Should we pass in the isolate id?

    /**
     * Just after connecting, the debugger downloads the list of Dart libraries from Observatory and reports it here.
     */
    void onLibrariesDownloaded(Iterable<LibraryRef> libraries);

    /**
     * Returns all possible Observatory URI's corresponding to a local file.
     * <p>
     * (A breakpoint will be set in all of them that exist.)
     */
    Collection<String> getBreakpointUris(VirtualFile file);

    /**
     * Returns the local position (to display to the user) corresponding to a token position in Observatory.
     */
    XSourcePosition getSourcePosition(String isolateId, ScriptRef scriptRef, int tokenPos, CompletableFuture<String> fileFuture);

    /**
     * Returns the local position (to display to the user) corresponding to a token position in Observatory.
     */
    XSourcePosition getSourcePosition(String isolateId, Script script, int tokenPos);

    void shutdown();
  }

  public interface ScriptProvider {
    /**
     * Downloads a script from observatory. Blocks until it's available.
     */
    @Nullable
    Script downloadScript(@NotNull String isolateId, @NotNull String scriptId);
  }
}

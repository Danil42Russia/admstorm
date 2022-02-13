package com.vk.admstorm.playground

import com.intellij.dvcs.ui.CompareBranchesDialog
import com.intellij.execution.OutputListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.projectsDataDir
import com.intellij.openapi.ui.WindowWrapper
import com.intellij.openapi.ui.WindowWrapperBuilder
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.remote.ColoredRemoteProcessHandler
import com.intellij.ssh.process.SshExecProcess
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.OnePixelSplitter
import com.intellij.util.DocumentUtil
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.PhpPsiElementFactory
import com.jgoodies.common.base.Strings.isNotBlank
import com.vk.admstorm.actions.ActionToolbarFastEnableAction
import com.vk.admstorm.configuration.kphp.KphpScriptRunner
import com.vk.admstorm.console.Console
import com.vk.admstorm.transfer.TransferService
import com.vk.admstorm.utils.MyKphpUtils.scriptBinaryPath
import com.vk.admstorm.utils.MyPathUtils.remotePathByLocalPath
import com.vk.admstorm.utils.MySshUtils
import com.vk.admstorm.utils.fixIndent
import org.apache.commons.lang.StringUtils.isNotBlank
import java.io.File
import java.io.IOException
import javax.swing.JLabel
import javax.swing.SwingConstants

class KphpPlaygroundWindow(private val myProject: Project) {
    companion object {
        private val LOG = logger<KphpPlaygroundWindow>()

        private const val DEFAULT_TEXT =  """<?php

#ifndef KPHP
require_once "www/autoload.php";
require_once "vendor/autoload.php";
#endif

"""
    }

    internal enum class State {
        Uploading,
        Compilation,
        Run,
        End
    }

    private var myState: State = State.End

    private val myWrapper: WindowWrapper
    private var myEditor: EditorEx

    private val myMainComponent = JBUI.Panels.simplePanel()

    private val myActionToolbar: ActionToolbar
    private val myActionGroup = DefaultActionGroup()
    private val myLoaderLabel = JLabel("Uploading...", AnimatedIcon.Default(), SwingConstants.LEFT)
    private val myHeaderComponent = JBUI.Panels.simplePanel()

    private val myEditorOutputSplitter = OnePixelSplitter(true, 0.6f)
    private val myDiffViewer = KphpPhpDiffViewer(myProject)

    private val myKphpConsole = Console(myProject)
    private val myPhpConsole = Console(myProject)
    private val myCompilationErrorsConsole = Console(myProject, withFilters = false)

    private val myScriptFile: VirtualFile

    private lateinit var myProcessHandler: ColoredRemoteProcessHandler<SshExecProcess>

    private val myRunCompilationAction = object : ActionToolbarFastEnableAction(
        "Run", "",
        AllIcons.Actions.Execute,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            ApplicationManager.getApplication().invokeLater {
                myEditorOutputSplitter.secondComponent = myCompilationErrorsConsole.component()
                myPhpConsole.clear()
                myKphpConsole.clear()
                myCompilationErrorsConsole.clear()
                myDiffViewer.clear()
            }

            compileScriptBinary()
        }
    }

    private val myStopCompilationAction = object : ActionToolbarFastEnableAction(
        "Stop", "",
        AllIcons.Actions.Suspend,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            myProcessHandler.destroyProcess()
            setEnabled(false)
        }
    }

    init {
        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        val tempFile = File(tmpDir, "kphp_script_dummy.php")

        try {
            tempFile.createNewFile()
        } catch (e: IOException) {
            LOG.warn("Unexpected exception while create temp file '${tempFile.path}'", e)
        }

        val tmpVirtualFile = LocalFileSystem.getInstance().findFileByIoFile(tempFile)!!
        val document = FileDocumentManager.getInstance().getDocument(tmpVirtualFile)!!

        myScriptFile = tmpVirtualFile
        myEditor = EditorFactory.getInstance().createEditor(document, myProject, PhpFileType.INSTANCE, false)
                as EditorEx

        val documentIsEmpty = document.textLength == 0
        if (documentIsEmpty) {
            WriteCommandAction.runWriteCommandAction(myProject) {
                document.setText(DEFAULT_TEXT)

                myEditor.caretModel.moveToOffset(100)

                PsiDocumentManager.getInstance(myProject).doPostponedOperationsAndUnblockDocument(document)
            }
        }

        myEditorOutputSplitter.firstComponent = myEditor.component
        myEditorOutputSplitter.secondComponent = myDiffViewer.component

        myStopCompilationAction.setEnabled(false)

        myActionGroup.add(myRunCompilationAction)
        myActionGroup.add(myStopCompilationAction)

        myActionToolbar = ActionManager.getInstance()
            .createActionToolbar("KphpPlaygroundToolBar", myActionGroup, true)
        myActionToolbar.targetComponent = myEditorOutputSplitter

        myActionToolbar.updateActionsImmediately()

        myHeaderComponent
            .addToLeft(JLabel("Run: "))
            .addToCenter(myActionToolbar.component)
            .addToRight(myLoaderLabel)
            .apply { border = JBUI.Borders.empty(0, 5) }

        myMainComponent
            .addToTop(myHeaderComponent)
            .addToCenter(myEditorOutputSplitter)

        myWrapper = WindowWrapperBuilder(WindowWrapper.Mode.FRAME, myMainComponent)
            .setProject(myProject)
            .setTitle("KPHP Playground")
            .setPreferredFocusedComponent(myEditor.component)
            .setDimensionServiceKey(CompareBranchesDialog::class.java.name)
            .setOnCloseHandler {
                dispose()
                true
            }
            .build()

        setState(State.End)
    }

    private fun setState(s: State) {
        myState = s
        when (s) {
            State.Uploading -> {
                myLoaderLabel.isVisible = true
                myLoaderLabel.text = "Uploading..."

                myRunCompilationAction.setEnabled(false)
                myStopCompilationAction.setEnabled(true)
            }
            State.Compilation -> {
                myLoaderLabel.isVisible = true
                myLoaderLabel.text = "Compilation..."
            }
            State.Run -> {
                myLoaderLabel.isVisible = true
                myLoaderLabel.text = "Running..."
            }
            State.End -> {
                myLoaderLabel.isVisible = false

                myRunCompilationAction.setEnabled(true)
                myStopCompilationAction.setEnabled(false)
            }
        }

        myMainComponent.revalidate()
        myMainComponent.repaint()

        ApplicationManager.getApplication().invokeLater {
            myActionToolbar.updateActionsImmediately()
        }
    }

    private fun scriptPath(project: Project): String {
        val projectDir = project.guessProjectDir()?.path ?: ""
        return "$projectDir/../kphp_script_dummy.php"
    }

    private fun compileScriptBinary() {
        setState(State.Uploading)

        TransferService.getInstance(myProject).uploadFile(myScriptFile, scriptPath(myProject)) {
            setState(State.Compilation)

            val command = KphpScriptRunner.buildCommand(myProject, "../../kphp_script_dummy.php")
            myProcessHandler = MySshUtils.exec(myProject, command) ?: return@uploadFile

            myCompilationErrorsConsole.clear()
            myCompilationErrorsConsole.view().attachToProcess(myProcessHandler)

            val outputListener = object : OutputListener() {
                override fun processTerminated(event: ProcessEvent) {
                    super.processTerminated(event)

                    if (event.exitCode != 0) {
                        setState(State.End)
                        return
                    }

                    setState(State.Run)

                    val phpOutput = executePhpScript()
                    val kphpOutput = executeKphpScriptBinary()

                    myDiffViewer.withPhpOutput(phpOutput)
                    myDiffViewer.withKphpOutput(kphpOutput)

                    ApplicationManager.getApplication().invokeLater {
                        myEditorOutputSplitter.secondComponent = myDiffViewer.component
                    }

                    setState(State.End)
                }
            }

            myProcessHandler.addProcessListener(outputListener)
            myProcessHandler.startNotify()
        }
    }

    private fun executePhpScript(): String {
        val remoteScriptName = remotePathByLocalPath(myProject, scriptPath(myProject))
        val command = "php $remoteScriptName"
        val handler = MySshUtils.exec(
            myProject, command,
            "php"
        ) ?: return ""

        val outputListener = OutputListener()
        handler.addProcessListener(outputListener)

        handler.startNotify()
        handler.waitFor()

        return outputListener.output.stdout.ifEmpty { "<no output>" }
    }

    private fun executeKphpScriptBinary(): String {
        val command = "${scriptBinaryPath(myProject)} --Xkphp-options --disable-sql"
        val handler = MySshUtils.exec(
            myProject, command,
            "kphp"
        ) ?: return ""

        val outputListener = OutputListener()
        handler.addProcessListener(outputListener)

        handler.startNotify()
        handler.waitFor()

        return outputListener.output.stdout.ifEmpty { "<no output>" }
    }

    fun show() {
        myWrapper.show()
    }

    fun withCode(code: String) {
        val template = DEFAULT_TEXT + code.fixIndent().trimIndent() + "\n\n"

        WriteCommandAction.runWriteCommandAction(myProject) {
            val document = FileDocumentManager.getInstance().getDocument(myScriptFile)
            document?.deleteString(0, document.text.length)
            document?.insertString(0, template)
        }

        myWrapper.show()
    }

    private fun dispose() {
        if (!myEditor.isDisposed) {
            EditorFactory.getInstance().releaseEditor(myEditor)
        }

        if (!myDiffViewer.isDisposed) {
            myDiffViewer.dispose()
        }
    }
}

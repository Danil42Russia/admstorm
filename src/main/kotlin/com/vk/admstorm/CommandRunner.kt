package com.vk.admstorm

import com.intellij.execution.Output
import com.intellij.execution.OutputListener
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.vk.admstorm.env.Env
import com.vk.admstorm.utils.MySshUtils
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The main class for running commands both local and on the server.
 */
object CommandRunner {
    private val LOG = Logger.getInstance(CommandRunner::class.java)

    fun runLocally(
        project: Project,
        command: String,
        trimOutput: Boolean = true,
        processListener: ProcessListener? = null
    ): Output {
        return runLocally(project, command.split(" "), trimOutput, processListener)
    }

    fun runLocallyToFile(
        project: Project,
        command: String,
        redirectToFile: File
    ): Output {
        return runLocally(project, command.split(" "), false, null, redirectToFile)
    }

    fun runLocally(
        project: Project,
        commands: List<String>,
        trimOutput: Boolean = true,
        processListener: ProcessListener? = null,
        redirectToFile: File? = null
    ): Output {
        LOG.info("Start a local command execution (command: '${commands.joinToString(" ")}')")
        val startTime = System.currentTimeMillis()

        val projectDir = project.guessProjectDir()?.path ?: ""
        val procBuilder = ProcessBuilder(commands)
            .directory(File(projectDir))
            .redirectError(ProcessBuilder.Redirect.PIPE)

        if (redirectToFile != null) {
            procBuilder.redirectOutput(redirectToFile)
        } else {
            procBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
        }

        val proc = procBuilder.start()

        // Running the command with listeners throws an exception if called in EDT,
        // so we use them only if the command is started with listener, the rest of
        // the times we use proc.waitFor().
        val result = if (processListener != null) {
            val handler = ColoredProcessHandler(proc, "Local command ${commands.joinToString(" ")}")

            val outputListener = OutputListener()
            handler.addProcessListener(outputListener)
            handler.addProcessListener(processListener)

            handler.startNotify()
            handler.waitFor(30_000)

            outputListener.output.apply {
                if (trimOutput) {
                    stdout.trim()
                    stderr.trim()
                }
            }
        } else {
            proc.waitFor(30, TimeUnit.SECONDS)

            Output(
                proc.inputStream.bufferedReader().readText().apply { if (trimOutput) trim() },
                proc.errorStream.bufferedReader().readText().apply { if (trimOutput) trim() },
                proc.exitValue()
            )
        }

        val elapsedTime = System.currentTimeMillis() - startTime
        LOG.info("Elapsed time for local command: $elapsedTime" + "ms")

        return result
    }

    fun runRemotely(
        project: Project,
        command: String,
        timeout: Long = 3_000,
        processListener: ProcessListener? = null
    ): Output {
        LOG.info("Start a synchronous SSH command execution (command: '$command', workingDir: '${Env.data.projectRoot}')")
        val startTime = System.currentTimeMillis()

        val handler = MySshUtils.exec(project, command) ?: return Output("", "", 2)

        LOG.info("Waiting for the end of command execution")

        if (processListener != null) {
            handler.addProcessListener(processListener)
        }

        val outputListener = OutputListener()

        handler.addProcessListener(outputListener)
        handler.startNotify()
        handler.waitFor(timeout)

        val result = outputListener.output

        val elapsedTime = System.currentTimeMillis() - startTime
        LOG.info("Elapsed time for synchronous SSH command: ${elapsedTime}ms")

        return result
    }
}

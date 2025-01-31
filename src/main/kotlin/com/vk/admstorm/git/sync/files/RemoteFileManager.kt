package com.vk.admstorm.git.sync.files

import com.intellij.execution.Output
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.IncorrectOperationException
import com.vk.admstorm.CommandRunner
import com.vk.admstorm.git.GitUtils
import com.vk.admstorm.git.sync.SyncChecker
import com.vk.admstorm.notifications.AdmNotification
import com.vk.admstorm.transfer.TransferService
import com.vk.admstorm.ui.MessageDialog
import com.vk.admstorm.utils.MyPathUtils
import com.vk.admstorm.utils.MyUtils.runBackground
import com.vk.admstorm.utils.MyUtils.runConditionalModal
import com.vk.admstorm.utils.MyUtils.virtualFileByRelativePath
import com.vk.admstorm.utils.ServerNameProvider
import com.vk.admstorm.utils.extensions.normalizeSlashes
import git4idea.util.GitUIUtil
import java.io.File
import java.nio.charset.Charset

class RemoteFileManager(private val myProject: Project) {
    companion object {
        private val LOG = logger<RemoteFileManager>()
    }

    private fun removeRemoteFile(project: Project, path: String): Output {
        return CommandRunner.runRemotely(project, "rm $path")
    }

    private fun renameRemoteFileWithGit(project: Project, old: String, new: String): Output {
        return CommandRunner.runRemotely(project, "git mv $old $new")
    }

    private fun renameLocalFileWithGit(project: Project, old: String, new: String): Output {
        return CommandRunner.runLocally(project, "git mv $old $new")
    }

    fun removeRemoteFile(remoteFile: RemoteFile, onReady: Runnable? = null) {
        runBackground(myProject, "Remove ${remoteFile.path} on ${ServerNameProvider.name()}") {
            val output = removeRemoteFile(myProject, remoteFile.path)
            if (output.exitCode != 0) {
                MessageDialog.showWarning(
                    """
                        Can't remove ${GitUIUtil.code(remoteFile.path)}:
                        
                        ${output.stderr}
                        """.trimIndent(),
                    "Problem with removing file on ${ServerNameProvider.name()}"
                )
                return@runBackground
            }

            onReady?.run()
        }
    }

    fun removeLocalFile(remoteFile: RemoteFile, onReady: Runnable? = null) {
        ApplicationManager.getApplication().runWriteAction {
            val relativeFilePath = remoteFile.path
            val localFile = virtualFileByRelativePath(myProject, relativeFilePath)
            if (localFile == null) {
                LOG.info("Remove file: file '$relativeFilePath' not found")
                onReady?.run()
                return@runWriteAction
            }

            val psiFile = PsiManager.getInstance(myProject).findFile(localFile)
            if (psiFile == null) {
                LOG.info("Remove file: psi file for '$relativeFilePath' not found")
                return@runWriteAction
            }

            try {
                psiFile.delete()
                LocalFileSystem.getInstance().refresh(true)
                onReady?.run()
            } catch (e: IncorrectOperationException) {
                LOG.warn("Cannot delete file '$relativeFilePath' via its psi element.", e)
            }
        }
    }

    fun createLocalFileFromRemote(remoteFile: RemoteFile, onReady: Runnable? = null) {
        runBackground(
            myProject,
            "Create new local file ${remoteFile.path} from ${ServerNameProvider.name()} content"
        ) {
            val filepath = MyPathUtils.absoluteLocalPath(myProject, remoteFile.path)

            val newFile = File(filepath)
            try {
                newFile.parentFile.mkdirs()
                newFile.createNewFile()
                LocalFileSystem.getInstance().refresh(true)
            } catch (e: Exception) {
                MessageDialog.showWarning(
                    """
                        Can't create new file ${GitUIUtil.code(remoteFile.path)}:
                        
                        ${e.message}
                    """.trimIndent(),
                    "Problem with creating new file on ${ServerNameProvider.name()}"
                )
                return@runBackground
            }
            val charset = try {
                Charset.forName(remoteFile.encoding)
            } catch (_: Exception) {
                Charset.forName("windows-1251")
            }

            newFile.writeText(remoteFile.content, charset)

            val output = GitUtils.localAddFileToIndex(myProject, filepath)
            if (output.exitCode != 0) {
                MessageDialog.showWarning(
                    """
                        Can't add file ${GitUIUtil.code(remoteFile.path)} to git:
                        
                        ${output.stderr}
                    """.trimIndent(),
                    "Problem with adding new file on ${ServerNameProvider.name()}"
                )
                return@runBackground
            }

            onReady?.run()
        }
    }

    fun rewriteLocalFileWithRemoteContent(localFile: VirtualFile, remoteContent: String, onReady: Runnable? = null) {
        ApplicationManager.getApplication().runWriteAction {
            FileDocumentManager.getInstance().getDocument(localFile)?.setText(remoteContent)
            val doc = FileDocumentManager.getInstance().getDocument(localFile)
            if (doc != null) {
                FileDocumentManager.getInstance().saveDocument(doc)
                onReady?.run()
            }
        }
    }

    fun rewriteRemoteFileWithLocalContent(localFile: VirtualFile, onReady: Runnable? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val remotePath = MyPathUtils.remotePathByLocalPath(myProject, localFile.path)
            val perm = GitUtils.remoteGetPermission(myProject, remotePath)
            LOG.info("File '$remotePath' permissions is $perm")

            TransferService.getInstance(myProject).uploadFile(localFile) {
                // after upload
                val output = GitUtils.remoteAddFileToIndex(myProject, remotePath)
                if (output.exitCode != 0) {
                    MessageDialog.showWarning(
                        """
                        Can't add file ${GitUIUtil.code(remotePath)} to git on ${ServerNameProvider.name()}:
                        
                        ${output.stderr}
                        """.trimIndent(),
                        "Problem with git add"
                    )
                    return@uploadFile
                }

                if (perm != null) {
                    val setPermOutput = GitUtils.remoteSetPermission(myProject, perm, remotePath)
                    if (setPermOutput.exitCode != 0) {
                        MessageDialog.showWarning(
                            """
                            Can't set permission ${GitUIUtil.code(perm)} for ${GitUIUtil.code(remotePath)}:
                            
                            ${output.stderr}
                            """.trimIndent(),
                            "Problems with setting permission"
                        )
                        return@uploadFile
                    }
                }

                onReady?.run()
            }
        }
    }

    /**
     * Returns the passed [remoteFile] to its original state:
     * - Renamed to original name ([RemoteFile.origPath])
     * - The content changes according to the local file ([RemoteFile.localFile])
     *   that represents this remote file
     *
     * Upon successful completion, calls the [onReady] block.
     */
    fun revertRemoteFileToOriginal(remoteFile: RemoteFile, onReady: Runnable? = null) {
        if (remoteFile.origPath == null) return

        runConditionalModal(
            myProject, "Rename ${remoteFile.path} to ${remoteFile.origPath} on ${ServerNameProvider.name()}",
        ) {
            val renameOutput = renameRemoteFileWithGit(myProject, remoteFile.origPath, remoteFile.path)
            if (renameOutput.exitCode != 0) {
                MessageDialog.showWarning(
                    """
                        Can't rename ${GitUIUtil.code(remoteFile.origPath)} to ${GitUIUtil.code(remoteFile.path)}:
                        
                        ${renameOutput.stderr}
                    """.trimIndent(),
                    "Problems with file rename"
                )
                return@runConditionalModal
            }

            if (remoteFile.localFile.virtualFile != null) {
                rewriteRemoteFileWithLocalContent(remoteFile.localFile.virtualFile, onReady)
            }
        }
    }

    /**
     * Renames the local file for the given [remoteFile] to match
     * the new remote file name.
     *
     * Upon successful completion, calls the [onReady] block.
     */
    fun renameLocalFile(remoteFile: RemoteFile, onReady: Runnable? = null) {
        if (remoteFile.localFile.virtualFile == null || remoteFile.origPath == null) {
            return
        }

        runConditionalModal(
            myProject, "Rename ${remoteFile.origPath} to ${remoteFile.path} locally"
        ) {
            val output = renameLocalFileWithGit(myProject, remoteFile.path, remoteFile.origPath)
            if (output.exitCode != 0) {
                MessageDialog.showWarning(
                    """
                        Can't rename ${GitUIUtil.code(remoteFile.origPath)} to ${GitUIUtil.code(remoteFile.path)}:
                        
                        ${output.stderr}
                    """.trimIndent(),
                    "Problems with file rename"
                )
                return@runConditionalModal
            }

            onReady?.run()
        }
    }

    data class AutogeneratedChangesStat(
        val countAdded: Int = 0,
        val countChanged: Int = 0,
        val countDeleted: Int = 0,
    ) {
        fun isEmpty() = countAdded == 0 && countChanged == 0 && countDeleted == 0
    }

    fun doUpdateAutogeneratedFiles(showIfNoChanges: Boolean = true) {
        RemoteFileManager(myProject).updateAutogeneratedFilesThatChangedOrCreatedOnServer { stat ->
            if (stat.isEmpty()) {
                if (showIfNoChanges) {
                    AdmNotification("No changes")
                        .withTitle("Autogenerated files updated")
                        .show()
                }
                return@updateAutogeneratedFilesThatChangedOrCreatedOnServer
            }

            AdmNotification("Changed: ${stat.countChanged}, Added: ${stat.countAdded}, Deleted: ${stat.countDeleted}")
                .withTitle("Autogenerated files updated")
                .show()
        }
    }

    /**
     * Finds out-of-sync autogenerated files between the server and the local
     * repository and downloads all such files, thereby synchronizing both
     * repositories.
     *
     * Should be used when it is known that after executing some command on
     * the server, new autogenerated files have been changed or new files
     * have appeared, and they need to be synchronized.
     *
     * After a successful update, the [onReady] block is called with statistics
     * of changes.
     */
    fun updateAutogeneratedFilesThatChangedOrCreatedOnServer(onReady: (AutogeneratedChangesStat) -> Unit = {}) {
        runBackground(myProject, "Download all changed autogenerated files from server") {
            val checker = SyncChecker.getInstance(myProject)
            val state = checker.currentState()
            if (state != SyncChecker.State.FilesNotSync) {
                onReady(AutogeneratedChangesStat())
                return@runBackground
            }

            val diffFiles = checker.getDiffFiles()
            val handler = RemoteFileManager(myProject)

            invokeLater {
                var countAdded = 0
                var countChanged = 0
                var countDeleted = 0

                diffFiles.forEach { remoteFile ->
                    val relativeFilePath = remoteFile.path
                    val localFile = virtualFileByRelativePath(myProject, relativeFilePath)
                    if (localFile == null) {
                        if (!isAutogeneratedFile(remoteFile.path) { remoteFile.content }) {
                            return@forEach
                        }

                        handler.createLocalFileFromRemote(remoteFile)
                        countAdded++
                        return@forEach
                    }

                    val isAutogenerated = isAutogeneratedFile(remoteFile.path) {
                        if (remoteFile.isNotFound) {
                            LoadTextUtil.loadText(localFile).toString()
                        } else {
                            remoteFile.content
                        }
                    }

                    if (!isAutogenerated) {
                        return@forEach
                    }

                    if (remoteFile.isNotFound) {
                        handler.removeLocalFile(remoteFile)
                        countDeleted++
                        return@forEach
                    }

                    handler.rewriteLocalFileWithRemoteContent(localFile, remoteFile.content)
                    countChanged++
                }

                LocalFileSystem.getInstance().refresh(true)
                onReady(AutogeneratedChangesStat(countAdded, countChanged, countDeleted))
            }
        }
    }

    private fun isAutogeneratedFile(name: String, content: () -> String): Boolean {
        // fast path
        if (name.normalizeSlashes().lowercase().contains("/autogenerated/")) {
            return true
        }

        var doNotEdit = false
        var autoGenerated = false

        content().lines().take(15).map { it.lowercase() }.forEach {
            val looksLikeComment = it.startsWith("//") ||
                    it.startsWith("/*") ||
                    it.startsWith(" *")
            if (!looksLikeComment) return@forEach

            doNotEdit = doNotEdit ||
                    it.contains("do not edit") ||
                    it.contains("don't edit") ||
                    it.contains("do not modify") ||
                    it.contains("don't modify")
            autoGenerated = autoGenerated ||
                    it.contains("auto-generated") ||
                    it.contains("autogenerated") ||
                    it.contains("generated by")
        }

        return doNotEdit && autoGenerated
    }
}

package io.github.vasyaod.acp.ide

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.vasyaod.acp.core.CommandSpec
import io.github.vasyaod.acp.core.FileSystemHandler
import io.github.vasyaod.acp.core.LocalFileSystemHandler
import io.github.vasyaod.acp.core.PermissionChoice
import io.github.vasyaod.acp.core.PermissionPrompter
import io.github.vasyaod.acp.core.PermissionRequest
import io.github.vasyaod.acp.core.ProcessSink
import io.github.vasyaod.acp.core.ProcessStarter
import io.github.vasyaod.acp.core.RunningProcess
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The fs requests, served through the IDE: reads see an open editor's unsaved text, writes go through
 * WriteCommandAction on the Document so they are undoable and the editor stays in sync.
 */
class IdeFileSystem(private val project: Project) : FileSystemHandler {
    private val disk = LocalFileSystemHandler()

    override fun readTextFile(path: Path): String {
        val vf = LocalFileSystem.getInstance().findFileByNioFile(path)
        if (vf != null && !vf.isDirectory) {
            val doc = ReadAction.compute<com.intellij.openapi.editor.Document?, RuntimeException> {
                FileDocumentManager.getInstance().getCachedDocument(vf)
            }
            if (doc != null) return ReadAction.compute<String, RuntimeException> { doc.text }
            if (!Files.exists(path)) throw java.nio.file.NoSuchFileException(path.toString())
            return String(Files.readAllBytes(path), vf.charset)
        }
        return disk.readTextFile(path)
    }

    override fun writeTextFile(path: Path, content: String) {
        val error = AtomicReference<Throwable?>()
        ApplicationManager.getApplication().invokeAndWait({
            try {
                WriteCommandAction.writeCommandAction(project)
                    .withName("ACP Agent: write ${path.fileName}")
                    .run<Throwable> {
                        val vf = findOrCreate(path)
                        val fdm = FileDocumentManager.getInstance()
                        val doc = fdm.getDocument(vf)
                        if (doc != null) {
                            doc.setText(StringUtil.convertLineSeparators(content))
                            fdm.saveDocument(doc) // so the agent's next command sees it on disk
                        } else {
                            vf.setBinaryContent(content.toByteArray(StandardCharsets.UTF_8))
                        }
                    }
            } catch (t: Throwable) {
                error.set(t)
            }
        }, ModalityState.defaultModalityState())
        error.get()?.let { throw RuntimeException("cannot write $path: ${it.message}", it) }
    }

    private fun findOrCreate(path: Path): VirtualFile {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let { return it }
        val parentPath = path.parent ?: throw IllegalArgumentException("no parent directory for $path")
        val parent = VfsUtil.createDirectoryIfMissing(parentPath.toString())
            ?: throw IllegalStateException("cannot create directory $parentPath")
        return parent.createChildData(this, path.fileName.toString())
    }
}

/** Local processes via GeneralCommandLine + KillableProcessHandler, in the given cwd (the project root). */
class IdeProcessStarter(private val project: Project) : ProcessStarter {
    override fun start(spec: CommandSpec, sink: ProcessSink): RunningProcess {
        val cl = GeneralCommandLine(listOf(spec.command) + spec.args)
            .withWorkDirectory(spec.cwd.toFile())
            .withEnvironment(spec.env)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
            .withRedirectErrorStream(true)
        val handler = KillableProcessHandler(cl)
        val killed = AtomicBoolean(false)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType != ProcessOutputTypes.SYSTEM) sink.onOutput(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                if (killed.get()) sink.onExit(null, "SIGKILL") else sink.onExit(event.exitCode, null)
                // The command may have changed files: let the IDE notice.
                project.basePath?.let { base ->
                    LocalFileSystem.getInstance().findFileByPath(base)?.let { VfsUtil.markDirtyAndRefresh(true, true, true, it) }
                }
            }
        })
        handler.startNotify()
        runCatching { handler.processInput?.close() } // no stdin: a prompt for input must not hang the agent
        return object : RunningProcess {
            override fun kill() {
                if (handler.isProcessTerminated) return
                killed.set(true)
                handler.killProcess()
            }
        }
    }
}

/** A modal Allow / Always allow / Reject dialog. Blocks the calling (background) thread until answered. */
class IdePermissionPrompter(private val project: Project) : PermissionPrompter {
    override fun ask(request: PermissionRequest): PermissionChoice {
        val answer = AtomicReference(PermissionChoice.REJECT)
        ApplicationManager.getApplication().invokeAndWait({
            if (project.isDisposed) return@invokeAndWait
            val what = when (request.kind) {
                "execute" -> "run a command on this machine"
                "edit" -> "change a file"
                "delete" -> "delete a file"
                "move" -> "move a file"
                "fetch" -> "fetch from the network"
                else -> "use a tool (${request.kind})"
            }
            val detail = request.title.ifBlank { request.rawInput?.toString() ?: "" }
            val msg = "The agent wants to $what in ${project.basePath}:\n\n" +
                StringUtil.shortenTextWithEllipsis(detail, 2000, 200)
            val idx = Messages.showDialog(
                project, msg, "ACP Agent: Allow?",
                arrayOf("Allow", "Always Allow \"${request.kind}\" in This Chat", "Reject"),
                0, Messages.getQuestionIcon(),
            )
            answer.set(
                when (idx) {
                    0 -> PermissionChoice.ALLOW_ONCE
                    1 -> PermissionChoice.ALLOW_ALWAYS
                    else -> PermissionChoice.REJECT
                },
            )
        }, ModalityState.defaultModalityState())
        return answer.get()
    }
}

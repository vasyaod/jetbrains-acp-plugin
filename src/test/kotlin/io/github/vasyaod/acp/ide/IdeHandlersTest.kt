package io.github.vasyaod.acp.ide

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.vasyaod.acp.core.CommandSpec
import io.github.vasyaod.acp.core.ProcessSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The IntelliJ side of the fs and terminal requests: unsaved buffers, undoable writes, processes in the project dir. */
class IdeHandlersTest : BasePlatformTestCase() {
    private lateinit var dir: Path

    override fun setUp() {
        super.setUp()
        dir = Files.createTempDirectory("acp-ide-test").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.toString())
    }

    fun testReadSeesUnsavedEditorTextAndWriteIsUndoable() {
        val file = dir.resolve("README.md")
        Files.writeString(file, "on disk\n")
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
        val doc = FileDocumentManager.getInstance().getDocument(vf)!!
        WriteCommandAction.runWriteCommandAction(project) { doc.setText("unsaved edit\n") }

        val fs = IdeFileSystem(project)
        assertEquals("unsaved edit\n", fs.readTextFile(file))
        assertEquals("on disk\n", Files.readString(file))

        fs.writeTextFile(file, "agent wrote\n")
        assertEquals("agent wrote\n", doc.text)
        assertEquals("agent wrote\n", Files.readString(file)) // saved, so the agent's next command sees it

        val undo = UndoManager.getInstance(project)
        // A headless stand-in for the editor tab (a real editor needs fonts this CI box may lack).
        val tab = StubFileEditor(vf)
        assertTrue("the agent's write should be undoable", undo.isUndoAvailable(tab))
        undo.undo(tab)
        assertEquals("unsaved edit\n", doc.text)
        Disposer.dispose(tab)
    }

    fun testWriteCreatesMissingDirectoriesAndFiles() {
        val target = dir.resolve("notes/deep/agent.txt")
        IdeFileSystem(project).writeTextFile(target, "hello\n")
        assertEquals("hello\n", Files.readString(target))
        assertEquals("hello\n", IdeFileSystem(project).readTextFile(target))
    }

    fun testReadOfAMissingFileFails() {
        try {
            IdeFileSystem(project).readTextFile(dir.resolve("nope.txt"))
            fail("expected an exception")
        } catch (_: java.nio.file.NoSuchFileException) {
        }
    }

    fun testProcessRunsLocallyInTheGivenDirectory() {
        val out = StringBuffer()
        val done = CountDownLatch(1)
        var code: Int? = -1
        IdeProcessStarter(project).start(
            CommandSpec("/bin/sh", listOf("-c", "hostname && pwd && echo \$ACP_T"), dir, mapOf("ACP_T" to "env-ok")),
            object : ProcessSink {
                override fun onOutput(text: String) { out.append(text) }
                override fun onExit(exitCode: Int?, signal: String?) { code = exitCode; done.countDown() }
            },
        )
        val deadline = System.currentTimeMillis() + 15_000
        while (done.count > 0 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            done.await(50, TimeUnit.MILLISECONDS)
        }
        assertEquals(0, code)
        val host = ProcessBuilder("hostname").start().inputStream.bufferedReader().readText().trim()
        assertEquals(listOf(host, dir.toString(), "env-ok"), out.toString().trim().lines())
    }

    fun testKillEndsTheProcess() {
        val done = CountDownLatch(1)
        var sig: String? = null
        val p = IdeProcessStarter(project).start(
            CommandSpec("/bin/sh", listOf("-c", "sleep 30"), dir, emptyMap()),
            object : ProcessSink {
                override fun onOutput(text: String) {}
                override fun onExit(exitCode: Int?, signal: String?) { sig = signal; done.countDown() }
            },
        )
        p.kill()
        val deadline = System.currentTimeMillis() + 10_000
        while (done.count > 0 && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            done.await(50, TimeUnit.MILLISECONDS)
        }
        assertEquals("SIGKILL", sig)
    }
}

private class StubFileEditor(private val vf: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val panel = javax.swing.JPanel()
    override fun getComponent(): javax.swing.JComponent = panel
    override fun getPreferredFocusedComponent(): javax.swing.JComponent? = null
    override fun getName() = "stub"
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: java.beans.PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: java.beans.PropertyChangeListener) {}
    override fun getFile(): VirtualFile = vf
    override fun dispose() {}
}

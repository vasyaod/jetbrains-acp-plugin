package io.github.vasyaod.acp.core

import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Serves `fs/read_text_file` and `fs/write_text_file`. The IDE version reads unsaved editors and writes undoably. */
interface FileSystemHandler {
    /** The whole current text of [path] (an open editor's unsaved buffer wins over the disk). */
    fun readTextFile(path: Path): String

    /** Replace [path]'s text with [content], creating the file (and its directories) if needed. */
    fun writeTextFile(path: Path, content: String)
}

/** Plain disk I/O; used by tests and as the fallback outside an IDE. */
class LocalFileSystemHandler : FileSystemHandler {
    override fun readTextFile(path: Path): String {
        val bytes = Files.readAllBytes(path)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    override fun writeTextFile(path: Path, content: String) {
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }
}

object TextSlice {
    /** ACP `line` (1-based) and `limit` (line count) applied to [text]; line endings are kept. */
    fun lines(text: String, line: Int?, limit: Int?): String {
        if ((line == null || line <= 1) && limit == null) return text
        val start = maxOf((line ?: 1) - 1, 0)
        var idx = 0
        var current = 0
        while (current < start && idx < text.length) {
            val nl = text.indexOf('\n', idx)
            if (nl < 0) { idx = text.length; break }
            idx = nl + 1
            current++
        }
        if (current < start) return ""
        if (limit == null) return text.substring(idx)
        if (limit <= 0) return ""
        var end = idx
        var taken = 0
        while (taken < limit && end < text.length) {
            val nl = text.indexOf('\n', end)
            end = if (nl < 0) text.length else nl + 1
            taken++
        }
        return text.substring(idx, end)
    }
}

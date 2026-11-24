package io.github.vasyaod.acp.core

object Headers {
    /** "Name: value" per line; blank lines and lines starting with # are ignored. */
    fun parse(text: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.orEmpty().lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val i = line.indexOf(':')
            if (i <= 0) throw IllegalArgumentException("Extra header line is not \"Name: value\": $line")
            out[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return out
    }

    /** The upgrade headers: extra headers first, then `Authorization: Bearer <token>` if a token is set. */
    fun forConnect(token: String?, extra: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in extra) if (!k.equals("Authorization", true) || token.isNullOrBlank()) out[k] = v
        if (!token.isNullOrBlank()) out["Authorization"] = "Bearer ${token.trim()}"
        return out
    }
}

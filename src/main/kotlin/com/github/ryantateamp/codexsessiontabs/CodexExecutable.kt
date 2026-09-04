package com.github.ryantateamp.codexsessiontabs

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal object CodexExecutable {
    fun resolve(): String {
        val userHome = System.getProperty("user.home").orEmpty()
        val candidates = buildList {
            add("/opt/homebrew/bin/codex")
            add("/usr/local/bin/codex")
            if (userHome.isNotBlank()) add("$userHome/.local/bin/codex")
            System.getenv("PATH").orEmpty()
                .split(File.pathSeparatorChar)
                .filter(String::isNotBlank)
                .mapTo(this) { Path.of(it).resolve("codex").toString() }
        }
        return candidates.firstOrNull { Files.isExecutable(Path.of(it)) } ?: "codex"
    }
}

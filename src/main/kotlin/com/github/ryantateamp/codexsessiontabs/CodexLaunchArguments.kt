package com.github.ryantateamp.codexsessiontabs

internal object CodexLaunchArguments {
    private val reusableBooleanOptions = setOf(
        "--strict-config",
        "--oss",
        "--approve-for-me",
        "--dangerously-bypass-approvals-and-sandbox",
        "--yolo",
        "--dangerously-bypass-hook-trust",
        "--search",
        "--no-alt-screen",
        "--fast",
        "--full-auto",
    )
    private val reusableValueOptions = setOf(
        "-c",
        "--config",
        "--enable",
        "--disable",
        "-m",
        "--model",
        "--local-provider",
        "-p",
        "--profile",
        "-s",
        "--sandbox",
        "--add-dir",
        "-a",
        "--ask-for-approval",
    )
    private val excludedValueOptions = setOf("-C", "--cd", "-i", "--image")
    private val variadicExcludedOptions = setOf("-i", "--image")
    private val shortValueOptions = (reusableValueOptions + excludedValueOptions).filter { it.startsWith('-') && !it.startsWith("--") }

    /**
     * Extracts settings worth applying to a resumed interactive session. Positional prompts,
     * one-shot inputs, an existing resume command, and its old session ID are intentionally
     * discarded; callers append one canonical `resume <session-id>` pair themselves.
     */
    fun forResume(rawArguments: List<String>): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        var afterSessionCommand = false

        while (index < rawArguments.size && result.size < MAX_ARGUMENTS) {
            val token = rawArguments[index]
            if (token == "--") break

            if (token == "resume" || token == "fork") {
                afterSessionCommand = true
                index++
                continue
            }

            val option = optionName(token)
            if (option == null) {
                if (!afterSessionCommand) break
                index++
                continue
            }

            when (option) {
                in reusableBooleanOptions -> addBounded(result, token)
                in reusableValueOptions -> {
                    if (hasAttachedValue(token, option)) {
                        addBounded(result, token)
                    } else {
                        val value = rawArguments.getOrNull(index + 1)
                        if (value != null && value != "--") {
                            addPairBounded(result, token, value)
                            index++
                        }
                    }
                }
                in excludedValueOptions -> {
                    if (!hasAttachedValue(token, option)) {
                        index++
                        if (option in variadicExcludedOptions) {
                            while (index + 1 < rawArguments.size && optionName(rawArguments[index + 1]) == null) index++
                        }
                    }
                }
            }
            index++
        }

        return result
    }

    fun resumeCommand(executable: String, sessionId: String, rawArguments: List<String>): List<String> = buildList {
        add(executable)
        addAll(forResume(rawArguments))
        add("resume")
        add(sessionId)
    }

    fun shellResumeCommand(executable: String, sessionId: String, rawArguments: List<String>): String =
        resumeCommand(executable, sessionId, rawArguments).joinToString(" ", transform = ::shellWord)

    private fun optionName(token: String): String? {
        if (token.startsWith("--") && token.length > 2) return token.substringBefore('=')
        if (!token.startsWith('-') || token == "-") return null
        return shortValueOptions.firstOrNull { token == it || token.startsWith("$it=") || token.startsWith(it) }
    }

    private fun hasAttachedValue(token: String, option: String): Boolean = token.length > option.length

    private fun addBounded(arguments: MutableList<String>, value: String) {
        if (arguments.size < MAX_ARGUMENTS &&
            value.length <= MAX_ARGUMENT_LENGTH &&
            arguments.sumOf(String::length) + value.length <= MAX_TOTAL_LENGTH
        ) {
            arguments.add(value)
        }
    }

    private fun addPairBounded(arguments: MutableList<String>, option: String, value: String) {
        if (arguments.size + 2 <= MAX_ARGUMENTS &&
            option.length <= MAX_ARGUMENT_LENGTH &&
            value.length <= MAX_ARGUMENT_LENGTH &&
            arguments.sumOf(String::length) + option.length + value.length <= MAX_TOTAL_LENGTH
        ) {
            arguments.add(option)
            arguments.add(value)
        }
    }

    private fun shellWord(value: String): String = if (SHELL_SAFE_WORD.matches(value)) {
        value
    } else {
        "'${value.replace("'", "'\\''")}'"
    }

    private val SHELL_SAFE_WORD = Regex("[A-Za-z0-9_@%+=:,./-]+")
    private const val MAX_ARGUMENTS = 64
    private const val MAX_ARGUMENT_LENGTH = 4_096
    private const val MAX_TOTAL_LENGTH = 16_384
}

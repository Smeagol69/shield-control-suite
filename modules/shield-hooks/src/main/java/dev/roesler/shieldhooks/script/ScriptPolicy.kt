package dev.roesler.shieldhooks.script

data class PolicyDecision(
    val accepted: Boolean,
    val reasons: List<String>,
)

object ScriptPolicy {
    const val MAX_SOURCE_LENGTH = 4_096
    private const val MAX_STATEMENTS = 32
    private const val MAX_NESTING = 8

    private val allowedApiMethods = setOf(
        "contains",
        "equalsText",
        "log",
        "startsWith",
        "tag",
        "toast",
    )
    private val allowedIdentifiers = allowedApiMethods + setOf(
        "api",
        "className",
        "eventType",
        "false",
        "if",
        "null",
        "packageName",
        "timestamp",
        "true",
    )

    private val forbiddenKeywords = Regex(
        """\b(import|package|class|interface|enum|new|for|while|do|switch|case|try|catch|finally|throw|throws|synchronized|return|break|continue|this|super|instanceof|void|byte|short|int|long|float|double|char|boolean)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val forbiddenIdentifiers = Regex(
        """\b(runtime|process|system|classloader|thread|file|path|socket|url|uri|intent|context|binder|parcel|android|java|javax|kotlin|bsh|interpreter|namespace|reflect|reflection|getclass|forname|exec|load|source|eval)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val assignment = Regex("""(?<![=!<>])=(?!=)""")
    private val call = Regex("""\b([A-Za-z_$][A-Za-z0-9_$]*(?:\s*\.\s*[A-Za-z_$][A-Za-z0-9_$]*)*)\s*\(""")
    private val memberAccess = Regex("""\.\s*([A-Za-z_$][A-Za-z0-9_$]*)""")
    private val identifier = Regex("""\b[A-Za-z_$][A-Za-z0-9_$]*\b""")
    private val uppercaseIdentifier = Regex("""\b[A-Z][A-Za-z0-9_$]*\b""")

    fun validate(source: String): PolicyDecision {
        val reasons = linkedSetOf<String>()
        if (source.isBlank()) reasons += "Script is empty."
        if (source.length > MAX_SOURCE_LENGTH) {
            reasons += "Script exceeds $MAX_SOURCE_LENGTH characters."
        }

        val scan = scanSource(source)
        val stripped = scan.stripped
        if (!scan.closed) {
            reasons += "String, character literal, or block comment is not closed."
        }
        if (forbiddenKeywords.containsMatchIn(stripped)) {
            reasons += "Declarations, loops, exceptions, and object construction are not allowed."
        }
        if (forbiddenIdentifiers.containsMatchIn(stripped)) {
            reasons += "System, reflection, Android, I/O, networking, and interpreter access are not allowed."
        }
        if (assignment.containsMatchIn(stripped) || "++" in stripped || "--" in stripped) {
            reasons += "Variable assignment and mutation are not allowed."
        }
        if (
            '[' in stripped ||
            ']' in stripped ||
            '@' in stripped ||
            '`' in stripped ||
            '\\' in stripped ||
            '#' in stripped
        ) {
            reasons += "Arrays, annotations, escapes, and dynamic syntax are not allowed."
        }
        if (uppercaseIdentifier.containsMatchIn(stripped)) {
            reasons += "Class references and typed declarations are not allowed."
        }
        val unknownIdentifiers = identifier.findAll(stripped)
            .map { it.value }
            .filterNot { it in allowedIdentifiers }
            .toSortedSet()
        if (unknownIdentifiers.isNotEmpty()) {
            reasons += "Only documented variables, control flow, and api methods are allowed."
        }
        if (stripped.count { it == ';' } > MAX_STATEMENTS) {
            reasons += "Script exceeds $MAX_STATEMENTS statements."
        }
        if (!hasSafeNesting(stripped)) {
            reasons += "Blocks or parentheses are unbalanced or nested too deeply."
        }

        memberAccess.findAll(stripped).forEach { match ->
            if (match.groupValues[1] !in allowedApiMethods) {
                reasons += "Only allowlisted api methods may be accessed."
            }
        }

        call.findAll(stripped).forEach { match ->
            val normalized = match.groupValues[1].replace(Regex("""\s+"""), "")
            val allowed = normalized == "if" ||
                (normalized.startsWith("api.") && normalized.removePrefix("api.") in allowedApiMethods)
            if (!allowed) reasons += "Call '$normalized' is not allowed."
        }

        return PolicyDecision(reasons.isEmpty(), reasons.toList())
    }

    private fun hasSafeNesting(source: String): Boolean {
        var braces = 0
        var parentheses = 0
        for (character in source) {
            when (character) {
                '{' -> if (++braces > MAX_NESTING) return false
                '}' -> if (--braces < 0) return false
                '(' -> if (++parentheses > MAX_NESTING * 2) return false
                ')' -> if (--parentheses < 0) return false
            }
        }
        return braces == 0 && parentheses == 0
    }

    internal fun stripStringsAndComments(source: String): String {
        return scanSource(source).stripped
    }

    private fun scanSource(source: String): ScanResult {
        val output = StringBuilder(source.length)
        var index = 0
        var state = ScanState.CODE
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                ScanState.CODE -> when {
                    current == '"' -> {
                        output.append(' ')
                        state = ScanState.STRING
                    }
                    current == '\'' -> {
                        output.append(' ')
                        state = ScanState.CHAR
                    }
                    current == '/' && next == '/' -> {
                        output.append("  ")
                        index++
                        state = ScanState.LINE_COMMENT
                    }
                    current == '/' && next == '*' -> {
                        output.append("  ")
                        index++
                        state = ScanState.BLOCK_COMMENT
                    }
                    else -> output.append(current)
                }
                ScanState.STRING -> when {
                    current == '\\' && next != null -> {
                        output.append("  ")
                        index++
                    }
                    current == '"' -> {
                        output.append(' ')
                        state = ScanState.CODE
                    }
                    else -> output.append(if (current == '\n') '\n' else ' ')
                }
                ScanState.CHAR -> when {
                    current == '\\' && next != null -> {
                        output.append("  ")
                        index++
                    }
                    current == '\'' -> {
                        output.append(' ')
                        state = ScanState.CODE
                    }
                    else -> output.append(if (current == '\n') '\n' else ' ')
                }
                ScanState.LINE_COMMENT -> {
                    output.append(if (current == '\n') '\n' else ' ')
                    if (current == '\n') state = ScanState.CODE
                }
                ScanState.BLOCK_COMMENT -> when {
                    current == '*' && next == '/' -> {
                        output.append("  ")
                        index++
                        state = ScanState.CODE
                    }
                    else -> output.append(if (current == '\n') '\n' else ' ')
                }
            }
            index++
        }
        return ScanResult(
            stripped = output.toString(),
            closed = state == ScanState.CODE || state == ScanState.LINE_COMMENT,
        )
    }

    private data class ScanResult(val stripped: String, val closed: Boolean)

    private enum class ScanState {
        CODE,
        STRING,
        CHAR,
        LINE_COMMENT,
        BLOCK_COMMENT,
    }
}

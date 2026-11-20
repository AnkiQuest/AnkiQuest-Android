// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.lint.rules

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.google.common.annotations.VisibleForTesting
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Reports regular expressions that compile on the JVM but throw on Android.
 *
 * Android's `java.util.regex` runs on ICU, not on the JDK engine. Robolectric unit tests run on the
 * JDK engine, so a pattern ICU rejects passes the unit tests and fails on a device, often in a static
 * initialiser where the error disables the whole class.
 */
class IcuRegexSyntaxDetector :
    Detector(),
    SourceCodeScanner {
    companion object {
        @VisibleForTesting
        const val ID = "IcuRegexSyntax"

        @VisibleForTesting
        const val DESCRIPTION = "Regular expression that Android's ICU regex engine rejects"

        private const val EXPLANATION =
            "Android implements java.util.regex and kotlin.text.Regex with ICU, which accepts less " +
                "than the JDK engine that unit tests run on. ICU rejects the inline flag (?U) and any " +
                "inline flag other than i, d, m, s, u, w and x, a `}` that does not close a {n,m} " +
                "quantifier, and the UNICODE_CHARACTER_CLASS and CANON_EQ compile flags. On Android " +
                "\\d, \\s and \\w already match Unicode characters, so (?U) is not needed. " +
                "Patterns compiled with LITERAL are not checked, and comments are skipped in COMMENTS mode. " +
                "See https://unicode-org.github.io/icu/userguide/strings/regexp.html"

        /** Crashes on device: the highest priority. */
        private const val PRIORITY = 10

        private const val PATTERN = "java.util.regex.Pattern"
        private const val STRING = "java.lang.String"
        private const val REGEX = "kotlin.text.Regex"
        private const val REGEX_OPTION = "kotlin.text.RegexOption"
        private const val KOTLIN_TEXT = "kotlin.text"

        /** Compile flags Android's Pattern throws on ("This flag is not supported on Android"). */
        private val UNSUPPORTED_FLAGS = setOf("UNICODE_CHARACTER_CLASS", "CANON_EQ")

        private val PATTERN_METHODS = setOf("compile", "matches")
        private val STRING_METHODS = setOf("matches", "replaceAll", "replaceFirst", "split")
        private val KOTLIN_RECEIVER_METHODS = setOf("toRegex", "toPattern")
        private val KOTLIN_ARGUMENT_METHODS = setOf("matches")

        /** `Pattern.LITERAL` and `Pattern.COMMENTS`: java.util.regex.Pattern's documented values. */
        private const val LITERAL_FLAG = 0x10
        private const val COMMENTS_FLAG = 0x04
        private const val LITERAL = "LITERAL"
        private const val COMMENTS = "COMMENTS"

        private val implementation = Implementation(IcuRegexSyntaxDetector::class.java, Scope.JAVA_FILE_SCOPE)

        val ISSUE: Issue =
            Issue.create(
                ID,
                DESCRIPTION,
                EXPLANATION,
                Category.CORRECTNESS,
                PRIORITY,
                Severity.FATAL,
                implementation,
            )
    }

    override fun getApplicableConstructorTypes() = listOf(REGEX)

    /** `Regex("...")` */
    override fun visitConstructor(
        context: JavaContext,
        node: UCallExpression,
        constructor: PsiMethod,
    ) {
        val arguments = node.valueArguments
        val pattern = arguments.firstOrNull() ?: return
        checkPattern(context, RegexCall(pattern, arguments.drop(1)))
    }

    override fun getApplicableMethodNames() =
        (PATTERN_METHODS + STRING_METHODS + KOTLIN_RECEIVER_METHODS + KOTLIN_ARGUMENT_METHODS).toList()

    /** `Pattern.compile("...")`, `"...".toRegex()` and the Java String methods that take a regex. */
    override fun visitMethodCall(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        val call = callOf(context, node, method) ?: return
        checkPattern(context, call)
    }

    /** The pattern and options of a call to a regex API, or null for unrelated methods. */
    private fun callOf(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ): RegexCall? {
        val evaluator = context.evaluator
        val arguments = node.valueArguments
        val first = arguments.firstOrNull()

        // Pattern.compile(regex, flags) and Pattern.matches(regex, input), which takes no flags.
        if (method.name in PATTERN_METHODS && evaluator.isMemberInClass(method, PATTERN)) {
            val flags = if (method.name == "compile") arguments.drop(1) else emptyList()
            return first?.let { RegexCall(it, flags) }
        }

        // input.matches(regex), input.replaceAll(regex, ...), input.split(regex) in Java.
        if (method.name in STRING_METHODS && evaluator.isMemberInClass(method, STRING)) {
            return first?.let { RegexCall(it, emptyList()) }
        }

        // Kotlin's String extensions; Regex.matches(input) takes an input, not a pattern.
        if (evaluator.getPackage(method)?.qualifiedName != KOTLIN_TEXT || evaluator.isMemberInClass(method, REGEX)) {
            return null
        }

        // "regex".toRegex(options) and "regex".toPattern(flags): the receiver is the pattern.
        if (method.name in KOTLIN_RECEIVER_METHODS) {
            return node.receiver?.let { RegexCall(it, arguments) }
        }

        // input.matches("regex"); with a Regex argument the constant check skips it.
        if (method.name in KOTLIN_ARGUMENT_METHODS) {
            return first?.let { RegexCall(it, emptyList()) }
        }
        return null
    }

    /** Reports the pattern of [call] if it is a constant string that ICU fails to compile. */
    private fun checkPattern(
        context: JavaContext,
        call: RegexCall,
    ) {
        val pattern = ConstantEvaluator.evaluate(context, call.pattern) as? String ?: return

        // A literal pattern is plain text, not regex syntax.
        val syntax = syntaxOf(context, call.options)
        if (syntax == Syntax.LITERAL) {
            return
        }
        val problem = IcuRegex.problem(pattern, syntax) ?: return
        report(context, call.pattern, problem)
    }

    /** How the options in [options] make the engine read the pattern: literal, with comments or plain. */
    private fun syntaxOf(
        context: JavaContext,
        options: List<UExpression>,
    ): Syntax {
        val names = options.flatMap { optionNames(context, it) }.toSet()
        return when {
            LITERAL in names -> Syntax.LITERAL
            COMMENTS in names -> Syntax.COMMENTS
            else -> Syntax.REGEX
        }
    }

    /**
     * The `LITERAL` and `COMMENTS` options [option] sets: references to `Pattern` and `RegexOption`
     * fields (`Pattern.LITERAL or Pattern.CASE_INSENSITIVE`, `setOf(RegexOption.LITERAL)`), or the
     * bits of a constant int flag.
     */
    private fun optionNames(
        context: JavaContext,
        option: UExpression,
    ): Set<String> {
        val names = mutableSetOf<String>()

        // Pattern flags held in a named constant.
        val flags = ConstantEvaluator.evaluate(context, option) as? Int
        if (flags != null) {
            if (flags and LITERAL_FLAG != 0) {
                names.add(LITERAL)
            }
            if (flags and COMMENTS_FLAG != 0) {
                names.add(COMMENTS)
            }
        }

        // Pattern and RegexOption fields named anywhere in the expression.
        option.accept(
            object : AbstractUastVisitor() {
                override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                    val field = node.resolve() as? PsiField ?: return false
                    val owner = field.containingClass?.qualifiedName
                    if (owner == PATTERN || owner == REGEX_OPTION) {
                        names.add(field.name)
                    }
                    return false
                }
            },
        )
        return names
    }

    override fun getApplicableReferenceNames() = UNSUPPORTED_FLAGS.toList()

    /** `Pattern.UNICODE_CHARACTER_CLASS`, `Pattern.CANON_EQ` and `RegexOption.CANON_EQ`. */
    override fun visitReference(
        context: JavaContext,
        reference: UReferenceExpression,
        referenced: PsiElement,
    ) {
        val field = referenced as? PsiField ?: return
        val owner = field.containingClass?.qualifiedName
        if (owner != PATTERN && owner != REGEX_OPTION) {
            return
        }
        report(context, reference, "Android's Pattern does not support ${field.name} and throws")
    }

    /** Reports [problem] at [element], adding why it only fails on Android. */
    private fun report(
        context: JavaContext,
        element: UElement,
        problem: String,
    ) {
        context.report(ISSUE, element, context.getLocation(element), "$problem. $ANDROID_USES_ICU")
    }
}

/** A pattern and the options passed with it. */
private class RegexCall(
    val pattern: UExpression,
    val options: List<UExpression>,
)

/** How the engine reads a pattern's text. */
private enum class Syntax {
    /** Regex syntax. */
    REGEX,

    /** Regex syntax where `#` starts a comment that runs to the end of the line (`COMMENTS`, `(?x)`). */
    COMMENTS,

    /** Plain text (`LITERAL`): nothing to check. */
    LITERAL,
}

private const val ANDROID_USES_ICU = "Android compiles regular expressions with ICU, unlike the JVM that unit tests run on"

/** The parts of ICU's regex syntax that the JDK accepts and ICU rejects. */
private object IcuRegex {
    private const val ESCAPE = '\\'
    private const val QUOTE_START = 'Q'
    private const val QUOTE_END = "\\E"
    private const val GROUP_START = '('
    private const val GROUP_END = ')'
    private const val GROUP_EXTENSION = '?'
    private const val CLASS_START = '['
    private const val CLASS_END = ']'
    private const val CLASS_NEGATION = '^'
    private const val INTERVAL_START = '{'
    private const val INTERVAL_END = '}'
    private const val FLAGS_END = ")"
    private const val FLAGS_GROUP_END = ":"
    private const val FLAG_NEGATION = '-'
    private const val COMMENT_START = '#'
    private const val LINE_END = '\n'
    private const val COMMENTS_INLINE_FLAG = 'x'

    /** Inline flags ICU accepts in `(?flags)` and `(?flags:...)`: icu4c/source/i18n/regexcst.txt. */
    private const val ICU_FLAGS = "idmsuwx-"
    private const val ICU_FLAGS_LIST = "i, d, m, s, u, w and x"

    /**
     * Why ICU rejects [pattern], read with [syntax], or null when it does not use any of the
     * constructs ICU rejects.
     */
    fun problem(
        pattern: String,
        syntax: Syntax,
    ): String? {
        var index = 0
        var classDepth = 0
        var mode = syntax
        val groups = ArrayDeque<Syntax>()
        while (index < pattern.length) {
            val char = pattern[index]

            // An escaped character, or a \Q...\E quote, is literal text.
            if (char == ESCAPE) {
                index = afterEscape(pattern, index)
                continue
            }

            // In comments mode `#` outside a class starts a comment that runs to the end of the line.
            if (mode == Syntax.COMMENTS && classDepth == 0 && char == COMMENT_START) {
                val end = pattern.indexOf(LINE_END, index)
                index = if (end < 0) pattern.length else end + 1
                continue
            }

            // Inside a character class only nesting matters: `{`, `}` and `(` are literal.
            if (classDepth > 0) {
                if (char == CLASS_START) {
                    classDepth++
                } else if (char == CLASS_END) {
                    classDepth--
                }
                index++
                continue
            }

            when (char) {
                CLASS_START -> {
                    classDepth = 1
                    index = afterClassStart(pattern, index)
                    continue
                }
                INTERVAL_START -> {
                    // `{n}`, `{n,}` and `{n,m}`; the JDK rejects any other `{` itself.
                    val end = pattern.indexOf(INTERVAL_END, index)
                    if (end < 0) {
                        return null
                    }
                    index = end + 1
                    continue
                }
                INTERVAL_END -> return "Unescaped `}` outside a {n,m} quantifier: ICU rejects it, write `\\}`"
                GROUP_START -> {
                    val group = flagGroup(pattern, index)
                    group?.problem()?.let { return it }

                    // A real group restores the surrounding flags when it closes. A standalone
                    // flag change belongs to its enclosing group, so skip its own closing `)`.
                    if (group == null || group.terminator == FLAGS_GROUP_END) {
                        groups.addLast(mode)
                    }
                    if (group != null) {
                        mode = group.syntaxAfter(mode)
                        index = group.nextIndex
                        continue
                    }
                }
                GROUP_END -> {
                    if (groups.isNotEmpty()) {
                        mode = groups.removeLast()
                    }
                }
            }
            index++
        }
        return null
    }

    /** The index after the escape at [index]: two characters, or a whole \Q...\E quote. */
    private fun afterEscape(
        pattern: String,
        index: Int,
    ): Int {
        if (pattern.getOrNull(index + 1) != QUOTE_START) {
            return index + 2
        }
        val end = pattern.indexOf(QUOTE_END, index + 2)
        return if (end < 0) pattern.length else end + QUOTE_END.length
    }

    /** The index after `[`, `[^`, `[]` or `[^]`: a `]` right after the opening is a literal. */
    private fun afterClassStart(
        pattern: String,
        index: Int,
    ): Int {
        var next = index + 1
        if (pattern.getOrNull(next) == CLASS_NEGATION) {
            next++
        }
        if (pattern.getOrNull(next) == CLASS_END) {
            next++
        }
        return next
    }

    /** The inline flag group `(?flags)` or `(?flags:...)` at [index], or null for any other text. */
    private fun flagGroup(
        pattern: String,
        index: Int,
    ): FlagGroup? {
        if (pattern.getOrNull(index + 1) != GROUP_EXTENSION) {
            return null
        }
        val start = index + 2
        var end = start
        while (end < pattern.length && (pattern[end].isLetter() || pattern[end] == FLAG_NEGATION)) {
            end++
        }

        // `(?<name>`, `(?:`, `(?=` and the other group kinds are not flag groups.
        if (end == start) {
            return null
        }
        val terminator = pattern.substring(end, minOf(end + 1, pattern.length))
        if (terminator != FLAGS_END && terminator != FLAGS_GROUP_END) {
            return null
        }
        return FlagGroup(pattern.substring(start, end), terminator, end + 1)
    }

    /** An inline flag group: [flags] such as `i-x`, ended by `)` or `:` ([terminator]). */
    private class FlagGroup(
        val flags: String,
        val terminator: String,
        val nextIndex: Int,
    ) {
        /** Reports flags ICU lacks, or null when ICU accepts them all. */
        fun problem(): String? {
            val unsupported = flags.filter { it !in ICU_FLAGS }
            if (unsupported.isEmpty()) {
                return null
            }
            return "Inline flag `(?$flags$terminator` uses `$unsupported`, which ICU rejects " +
                "(it accepts only $ICU_FLAGS_LIST)"
        }

        /** The syntax after this group: `x` before a `-` turns comments on, after it off. */
        fun syntaxAfter(syntax: Syntax): Syntax {
            val on = flags.substringBefore(FLAG_NEGATION)
            val off = flags.substringAfter(FLAG_NEGATION, "")
            return when {
                COMMENTS_INLINE_FLAG in off -> Syntax.REGEX
                COMMENTS_INLINE_FLAG in on -> Syntax.COMMENTS
                else -> syntax
            }
        }
    }
}

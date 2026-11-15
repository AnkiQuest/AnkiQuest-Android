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
        node.valueArguments.firstOrNull()?.let { checkPattern(context, it) }
    }

    override fun getApplicableMethodNames() =
        (PATTERN_METHODS + STRING_METHODS + KOTLIN_RECEIVER_METHODS + KOTLIN_ARGUMENT_METHODS).toList()

    /** `Pattern.compile("...")`, `"...".toRegex()` and the Java String methods that take a regex. */
    override fun visitMethodCall(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        val pattern = patternOf(context, node, method) ?: return
        checkPattern(context, pattern)
    }

    /** The expression that holds the pattern in a call to a regex API, or null for unrelated methods. */
    private fun patternOf(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ): UExpression? {
        val evaluator = context.evaluator

        // Pattern.compile(regex, ...) and Pattern.matches(regex, input).
        if (method.name in PATTERN_METHODS && evaluator.isMemberInClass(method, PATTERN)) {
            return node.valueArguments.firstOrNull()
        }

        // input.matches(regex), input.replaceAll(regex, ...), input.split(regex) in Java.
        if (method.name in STRING_METHODS && evaluator.isMemberInClass(method, STRING)) {
            return node.valueArguments.firstOrNull()
        }

        // Kotlin's String extensions; Regex.matches(input) takes an input, not a pattern.
        if (evaluator.getPackage(method)?.qualifiedName != KOTLIN_TEXT || evaluator.isMemberInClass(method, REGEX)) {
            return null
        }

        // "regex".toRegex() and "regex".toPattern(): the receiver is the pattern.
        if (method.name in KOTLIN_RECEIVER_METHODS) {
            return node.receiver
        }

        // input.matches("regex"); with a Regex argument the constant check skips it.
        if (method.name in KOTLIN_ARGUMENT_METHODS) {
            return node.valueArguments.firstOrNull()
        }
        return null
    }

    /** Reports [expression] if it is a constant string that ICU fails to compile. */
    private fun checkPattern(
        context: JavaContext,
        expression: UExpression,
    ) {
        val pattern = ConstantEvaluator.evaluate(context, expression) as? String ?: return
        val problem = IcuRegex.problem(pattern) ?: return
        report(context, expression, problem)
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

private const val ANDROID_USES_ICU = "Android compiles regular expressions with ICU, unlike the JVM that unit tests run on"

/** The parts of ICU's regex syntax that the JDK accepts and ICU rejects. */
private object IcuRegex {
    private const val ESCAPE = '\\'
    private const val QUOTE_START = 'Q'
    private const val QUOTE_END = "\\E"
    private const val GROUP_START = '('
    private const val GROUP_EXTENSION = '?'
    private const val CLASS_START = '['
    private const val CLASS_END = ']'
    private const val CLASS_NEGATION = '^'
    private const val INTERVAL_START = '{'
    private const val INTERVAL_END = '}'
    private const val FLAGS_END = ")"
    private const val FLAGS_GROUP_END = ":"
    private const val FLAG_NEGATION = '-'

    /** Inline flags ICU accepts in `(?flags)` and `(?flags:...)`: icu4c/source/i18n/regexcst.txt. */
    private const val ICU_FLAGS = "idmsuwx-"
    private const val ICU_FLAGS_LIST = "i, d, m, s, u, w and x"

    /** Why ICU rejects [pattern], or null when it does not use any of the constructs ICU rejects. */
    fun problem(pattern: String): String? {
        var index = 0
        var classDepth = 0
        while (index < pattern.length) {
            val char = pattern[index]

            // An escaped character, or a \Q...\E quote, is literal text.
            if (char == ESCAPE) {
                index = afterEscape(pattern, index)
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
                GROUP_START -> inlineFlagProblem(pattern, index)?.let { return it }
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

    /** Reports flags ICU lacks in an inline flag group `(?flags)` or `(?flags:...)` at [index]. */
    private fun inlineFlagProblem(
        pattern: String,
        index: Int,
    ): String? {
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
        val flags = pattern.substring(start, end)
        val unsupported = flags.filter { it !in ICU_FLAGS }
        if (unsupported.isEmpty()) {
            return null
        }
        return "Inline flag `(?$flags$terminator` uses `$unsupported`, which ICU rejects " +
            "(it accepts only $ICU_FLAGS_LIST)"
    }
}

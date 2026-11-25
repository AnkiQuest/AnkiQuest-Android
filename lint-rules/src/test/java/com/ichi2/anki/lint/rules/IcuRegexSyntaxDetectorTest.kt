// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.lint.rules

import com.android.tools.lint.checks.infrastructure.LintDetectorTest.java
import com.android.tools.lint.checks.infrastructure.LintDetectorTest.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Test

class IcuRegexSyntaxDetectorTest {
    @Test
    fun `reports the U flag in deck name patterns`() {
        lintFiles(
            kotlin(
                """
                package test

                object DeckNames {
                    private val PREFIX = Regex(${"\"\"\""}(?U)^(?:#?\d+[\s.)_-]+|\[[A-Za-z0-9]+\]\s*)${"\"\"\""})
                    private val SPACE = Regex(${"\"\"\""}(?U)\s+${"\"\"\""})
                }
                """,
            ).indented(),
        ).expectErrorCount(2)
            .expectContains("Inline flag (?U) uses U, which ICU rejects")
    }

    @Test
    fun `reports unsupported flags in every regex entry point`() {
        lintFiles(
            kotlin(
                """
                package test

                import java.util.regex.Pattern

                const val FLAGGED = "(?U)a"

                fun compile(input: String) {
                    Regex("(?iU)a")
                    Regex("(?U:a)", RegexOption.IGNORE_CASE)
                    Regex(FLAGGED)
                    "(?-U)a".toRegex()
                    "(?U)a".toPattern()
                    Pattern.compile("(?c)a")
                    Pattern.matches("(?U)a", input)
                }
                """,
            ).indented(),
            java(
                """
                package test;

                class Split {
                    String[] split(String input) {
                        input.replaceAll("(?U)\\s", " ");
                        input.replaceFirst("(?U)\\s", " ");
                        input.matches("(?U)a");
                        return input.split("(?U)\\s");
                    }
                }
                """,
            ).indented(),
        ).expectErrorCount(11)
    }

    @Test
    fun `accepts the inline flags ICU supports and other group kinds`() {
        lintFiles(
            kotlin(
                """
                package test

                import java.util.regex.Pattern

                fun compile(input: String) {
                    Regex("(?i)a(?m)b(?s)c(?d)d(?u)e(?x)f g(?w)h(?-i)i(?imsux-d:j)")
                    Regex("(?:a)(?=b)(?!c)(?<=d)(?<!e)(?>f)(?<name>g)")
                    Regex("\\(?U\\)").matches(input)
                    Regex("[(?U)]")
                    Regex("\\Q(?U)}\\E")
                    "(?i)a".toRegex()
                    Pattern.compile("(?m)^a$")
                    Regex("a").matches("(?U)} is input, not a pattern")
                    input.split("(?U)}")
                }
                """,
            ).indented(),
        ).expectClean()
    }

    @Test
    fun `reports an unescaped right brace`() {
        lintFiles(
            kotlin(
                """
                package test

                fun compile() {
                    Regex("\\{\\{c\\d+::(.*?)}}")
                    Regex("a}")
                }
                """,
            ).indented(),
        ).expectErrorCount(2)
            .expectContains("Unescaped } outside a {n,m} quantifier")
    }

    @Test
    fun `accepts braces that are quantifiers, escaped or in a class`() {
        lintFiles(
            kotlin(
                """
                package test

                fun compile() {
                    Regex("[0-9a-f]{64}")
                    Regex("a{1,}b{2,3}")
                    Regex("\\p{L}+\\x{1F600}")
                    Regex("\\{\\{([^{}]*)\\}\\}")
                    Regex("[}]")
                    Regex("[]}]")
                    Regex("[^]}]")
                    Regex("[a[}]]")
                }
                """,
            ).indented(),
        ).expectClean()
    }

    @Test
    fun `reports compile flags Android does not support`() {
        lintFiles(
            kotlin(
                """
                package test

                import java.util.regex.Pattern

                fun compile() {
                    Pattern.compile("a", Pattern.UNICODE_CHARACTER_CLASS)
                    Pattern.compile("a", Pattern.CANON_EQ or Pattern.CASE_INSENSITIVE)
                    Regex("a", RegexOption.CANON_EQ)
                }
                """,
            ).indented(),
        ).expectErrorCount(3)
            .expectContains("Android's Pattern does not support UNICODE_CHARACTER_CLASS")
    }

    @Test
    fun `ignores the text of literal patterns`() {
        lintFiles(
            kotlin(
                """
                package test

                import java.util.regex.Pattern

                fun compile() {
                    Pattern.compile("(?U)}", Pattern.LITERAL)
                    Pattern.compile("(?U)}", Pattern.LITERAL or Pattern.CASE_INSENSITIVE)
                    Regex("(?U)}", RegexOption.LITERAL)
                    Regex("(?U)}", setOf(RegexOption.IGNORE_CASE, RegexOption.LITERAL))
                    "(?U)}".toRegex(RegexOption.LITERAL)
                    "(?U)}".toPattern(Pattern.LITERAL)
                }
                """,
            ).indented(),
            java(
                """
                package test;

                import java.util.regex.Pattern;

                class Literal {
                    Pattern compile() {
                        return Pattern.compile("(?U)}", Pattern.LITERAL);
                    }
                }
                """,
            ).indented(),
        ).expectClean()
    }

    @Test
    fun `ignores comments in comments mode`() {
        lintFiles(
            kotlin(
                """
                package test

                import java.util.regex.Pattern

                fun compile() {
                    Regex("a # (?U) and } are a comment\nb", RegexOption.COMMENTS)
                    Regex("a # (?U)}", setOf(RegexOption.COMMENTS))
                    Pattern.compile("a # (?U)}", Pattern.COMMENTS)
                    "a # (?U)}".toRegex(RegexOption.COMMENTS)
                    Regex("(?x)a # (?U)}")
                    Regex("(?ix)a # (?U)}")
                    Regex("a\\# b{2}", RegexOption.COMMENTS)
                }
                """,
            ).indented(),
        ).expectClean()
    }

    @Test
    fun `restores comments mode after a group closes`() {
        lintFiles(
            kotlin(
                """
                package test

                fun compile() {
                    Regex("(?x)(?-x:a)# (?U)\nb")
                    Regex("(?x)(a(?-x)b)# (?U)\nc")
                    Regex("(?x)(?-x:(?i:a))# (?U)\nb")
                    Regex("(?x)((?-x)a)# (?U)\nb")
                }
                """,
            ).indented(),
        ).expectClean()
    }

    @Test
    fun `reports invalid flags after a comments group closes`() {
        lintFiles(
            kotlin(
                """
                package test

                fun compile() {
                    Regex("(?x:a)# (?U)b")
                    Regex("((?x)a)# (?U)b")
                    Regex("(?x:a(?i))# (?U)b")
                }
                """,
            ).indented(),
        ).expectErrorCount(3)
    }

    @Test
    fun `reports patterns outside comments and literal mode`() {
        lintFiles(
            kotlin(
                """
                package test

                import java.util.regex.Pattern

                fun compile() {
                    Regex("a # comment\n(?U)b", RegexOption.COMMENTS)
                    Regex("a\\# (?U)b", RegexOption.COMMENTS)
                    Regex("(?x)a(?-x) # (?U)}")
                    Regex("a # (?U)}")
                    Regex("(?U)}", RegexOption.IGNORE_CASE)
                    Pattern.compile("(?U)}", Pattern.CASE_INSENSITIVE)
                }
                """,
            ).indented(),
        ).expectErrorCount(6)
    }

    @Test
    fun `ignores patterns that are not constant`() {
        lintFiles(
            kotlin(
                """
                package test

                fun compile(flags: String) {
                    Regex(flags)
                    Regex("(?" + flags + ")a")
                }
                """,
            ).indented(),
        ).expectClean()
    }

    @Test
    fun `allows suppression`() {
        lintFiles(
            kotlin(
                """
                package test

                @Suppress("IcuRegexSyntax")
                fun compile() = Regex("(?U)a")
                """,
            ).indented(),
        ).expectClean()
    }

    /** Runs only this detector on [files]. */
    private fun lintFiles(vararg files: TestFile): TestLintResult =
        lint()
            .testModes(TestMode.DEFAULT)
            .allowMissingSdk()
            .allowCompilationErrors()
            .files(*files)
            .issues(IcuRegexSyntaxDetector.ISSUE)
            .run()
}

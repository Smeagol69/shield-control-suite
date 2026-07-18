package dev.roesler.shieldhooks.script

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptPolicyTest {
    @Test
    fun everyBuiltInRecipePassesPolicyAndHasUniqueId() {
        assertEquals(
            ScriptRecipes.all.size,
            ScriptRecipes.all.map(ScriptRecipe::id).distinct().size,
        )
        ScriptRecipes.all.forEach { recipe ->
            val decision = ScriptPolicy.validate(recipe.source)
            assertTrue(
                "${recipe.title}: ${decision.reasons.joinToString()}",
                decision.accepted,
            )
        }
    }

    @Test
    fun acceptsDocumentedSubset() {
        val source = """
            if (api.equalsText(eventType, "activity.resumed")) {
                api.log("Opened " + packageName);
                api.tag("activity", className);
            }
        """.trimIndent()

        assertTrue(ScriptPolicy.validate(source).reasons.joinToString(), ScriptPolicy.validate(source).accepted)
    }

    @Test
    fun acceptsNestedAllowlistedPredicates() {
        val source = """
            if (api.startsWith(packageName, "dev.roesler")) {
                if (api.contains(className, "MainActivity")) {
                    api.toast("Marquee is ready");
                }
            }
        """.trimIndent()

        assertTrue(ScriptPolicy.validate(source).accepted)
    }

    @Test
    fun rejectsReflectionAndObjectConstruction() {
        val source = """api.getClass().forName("java.lang.Runtime");"""

        assertFalse(ScriptPolicy.validate(source).accepted)
    }

    @Test
    fun rejectsLoopsEvenWhenTheyWouldTimeOut() {
        val source = """while (true) { api.log("loop"); }"""

        assertFalse(ScriptPolicy.validate(source).accepted)
    }

    @Test
    fun rejectsAssignmentsAndUserFunctions() {
        val source = """
            value = packageName;
            run(value);
        """.trimIndent()

        assertFalse(ScriptPolicy.validate(source).accepted)
    }

    @Test
    fun ignoresDangerousWordsInsideStringsAndComments() {
        val source = """
            // Runtime and import are harmless here.
            api.log("java.lang.Runtime is text, not code");
        """.trimIndent()

        assertTrue(ScriptPolicy.validate(source).reasons.joinToString(), ScriptPolicy.validate(source).accepted)
    }

    @Test
    fun rejectsUnicodeEscapeKeywordBypass() {
        val source = """\u0069mport java.lang.Runtime;"""

        assertFalse(ScriptPolicy.validate(source).accepted)
    }

    @Test
    fun rejectsUnclosedLiteral() {
        val source = """api.log("unfinished);"""

        assertFalse(ScriptPolicy.validate(source).accepted)
    }

    @Test
    fun rejectsInheritedObjectMethods() {
        val source = """api.toString();"""

        assertFalse(ScriptPolicy.validate(source).accepted)
    }

    @Test
    fun rejectsBeanShellCommandSyntax() {
        assertFalse(ScriptPolicy.validate("""server 9876;""").accepted)
        assertFalse(ScriptPolicy.validate("""print "unexpected";""").accepted)
    }
}

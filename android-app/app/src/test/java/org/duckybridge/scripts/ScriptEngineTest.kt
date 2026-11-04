package org.duckybridge.scripts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScriptEngineTest {
    private val allowed = setOf("whoami", "hostname")
    @Test fun importedTextNeverExecutesDuringValidation() {
        val plan = ScriptEngine.validate(ScriptEngine.example, allowed)
        assertEquals(3, plan.steps.size)
    }
    @Test fun rejectsUnknownCommandsVersionsAndAmbiguousSteps() {
        for (source in listOf(ScriptEngine.example.replace("whoami", "rm -rf /"), ScriptEngine.example.replace("\"version\": 1", "\"version\": 2"), ScriptEngine.example.replace("\"delay_ms\": 300", "\"delay_ms\": 300, \"command\": \"whoami\""))) {
            assertThrows(IllegalArgumentException::class.java) { ScriptEngine.validate(source, allowed) }
        }
    }
    @Test fun sequentialExecutionStopsOnFailure() = runBlocking {
        val plan = ScriptEngine.validate(ScriptEngine.example, allowed)
        val seen = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) { runBlocking { ScriptEngine.run(plan, { seen.add(it); 1 }, {}) } }
        assertEquals(listOf("hostname"), seen)
    }
    @Test fun previousExitConditionIsRespected() = runBlocking {
        val source = """{"version":1,"name":"Conditions","steps":[{"command":"whoami","when_exit":0},{"command":"hostname"}]}"""
        val seen = mutableListOf<String>()
        ScriptEngine.run(ScriptEngine.validate(source, allowed), { seen.add(it); 0 }, {})
        assertEquals(listOf("hostname"), seen)
    }
}

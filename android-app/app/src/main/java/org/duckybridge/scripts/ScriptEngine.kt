package org.duckybridge.scripts

import org.json.JSONObject
import kotlinx.coroutines.delay

data class ScriptStep(val command: String?, val delayMs: Long, val whenExit: Int?)
data class ScriptPlan(val name: String, val steps: List<ScriptStep>)
object ScriptEngine {
    fun validate(source: String, allowed: Set<String>): ScriptPlan {
        require(source.toByteArray(Charsets.UTF_8).size <= 16384) { "Script limité à 16 Ko" }
        val root = JSONObject(source)
        require(root.keys().asSequence().toSet().all { it in setOf("version", "name", "steps") }) { "Champ inconnu" }
        require(root.get("version") is Int && root.getInt("version") == 1) { "Version de script incompatible" }
        val name = root.getString("name").also { require(it.isNotBlank() && it.length <= 80) }
        val array = root.getJSONArray("steps")
        require(array.length() in 1..30) { "Un script doit contenir 1 à 30 étapes" }
        val steps = (0 until array.length()).map { i ->
            val step = array.getJSONObject(i)
            require(step.keys().asSequence().toSet().all { it in setOf("command", "delay_ms", "when_exit") }) { "Champ d'étape inconnu" }
            require(step.has("command") xor step.has("delay_ms")) { "Choisissez une commande ou un délai" }
            val command = if (step.has("command")) step.getString("command") else null
            require(command == null || command in allowed) { "Commande non autorisée : $command" }
            if (step.has("delay_ms")) require(step.get("delay_ms") is Int || step.get("delay_ms") is Long) { "Délai entier requis" }
            if (step.has("when_exit")) require(step.get("when_exit") is Int) { "Code de retour entier requis" }
            val wait = if (step.has("delay_ms")) step.getLong("delay_ms") else 0L
            require(wait in 0..10000) { "Délai limité à 10 secondes" }
            ScriptStep(command, wait, if (step.has("when_exit")) step.getInt("when_exit") else null)
        }
        return ScriptPlan(name, steps)
    }
    suspend fun run(plan: ScriptPlan, execute: suspend (String) -> Int?, log: (String) -> Unit) {
        var previous: Int? = null
        for ((index, step) in plan.steps.withIndex()) {
            if (step.whenExit != null && previous != step.whenExit) { log("Étape ${index+1} ignorée (condition)"); continue }
            log("Étape ${index+1}/${plan.steps.size} : ${step.command ?: "attente ${step.delayMs} ms"}")
            if (step.command != null) {
                previous = execute(step.command)
                check(previous == 0) { "Arrêt du script : code de retour $previous" }
            } else delay(step.delayMs)
        }
    }
    val example = """{
  "version": 1,
  "name": "Diagnostic de connexion",
  "steps": [
    {"command": "hostname"},
    {"delay_ms": 300},
    {"command": "whoami", "when_exit": 0}
  ]
}"""
}

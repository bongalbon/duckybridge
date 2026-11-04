package org.duckybridge

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.duckybridge.data.*
import org.duckybridge.network.*
import org.duckybridge.scripts.ScriptEngine
import org.duckybridge.security.Identity
import org.json.JSONObject
import java.util.UUID

private val android.content.Context.preferences by preferencesDataStore("settings")
data class UiState(val selected: Device? = null, val connected: Boolean = false, val busy: Boolean = false, val commands: List<String> = emptyList(), val output: String = "", val message: String = "Prêt. Ajoutez un agent pour commencer.", val activeRequest: String? = null)
class BridgeViewModel(app: Application) : AndroidViewModel(app) {
    private val database = Room.databaseBuilder(app, BridgeDatabase::class.java, "duckybridge.db").build()
    private val dao = database.dao()
    private val identity = Identity()
    private val client = AgentClient(identity)
    private val mutable = MutableStateFlow(UiState())
    val ui = mutable.asStateFlow()
    val devices = dao.devices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = dao.history().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val scripts = dao.scripts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val discovered = MutableStateFlow<List<DiscoveredAgent>>(emptyList())
    val fingerprint get() = identity.id
    private val darkKey = booleanPreferencesKey("dark")
    private val historyKey = booleanPreferencesKey("history")
    val dark = app.preferences.data.map { it[darkKey] ?: true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val recordHistory = app.preferences.data.map { it[historyKey] ?: true }.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private var discoveryJob: Job? = null
    private var scriptJob: Job? = null
    private var pending: CompletableDeferred<JSONObject>? = null
    private var pendingCommand = ""
    fun theme(value: Boolean) { viewModelScope.launch { getApplication<Application>().preferences.edit { it[darkKey] = value } } }
    fun keepHistory(value: Boolean) { viewModelScope.launch { getApplication<Application>().preferences.edit { it[historyKey] = value } } }
    fun clearHistory() { viewModelScope.launch { dao.clearHistory() } }
    private fun message(text: String) { mutable.update { it.copy(message = text) } }
    fun discover() {
        discoveryJob?.cancel(); discovered.value = emptyList()
        discoveryJob = viewModelScope.launch {
            try { withTimeout(15000) { Discovery(getApplication()).agents().collect { agent -> discovered.update { (it + agent).distinctBy { a -> "${a.host}:${a.port}" } } } } }
            catch (_: TimeoutCancellationException) { message("Découverte terminée. Vérifiez chaque empreinte sur le PC.") }
            catch (e: Exception) { if (e !is CancellationException) message(e.message ?: "Échec de découverte") }
        }
    }
    fun pair(name: String, host: String, port: String, fingerprint: String) {
        viewModelScope.launch {
            try {
                check(!ui.value.busy) { "Opération en cours" }
                val pin = fingerprint.lowercase().replace(" ", "").replace(":", "")
                val d = Device(pin, name.trim().ifEmpty { host }, host.trim(), port.toInt(), pin)
                mutable.update { it.copy(busy = true) }
                val response = withContext(Dispatchers.IO) { client.pair(d) }
                check(JSONObject(response).getString("agent_id") == pin)
                dao.save(d)
                message("Demande envoyée. Comparez l'empreinte Android sur le PC, approuvez-la localement, puis connectez-vous.")
            } catch (e: Exception) { message(e.message ?: "Appairage impossible") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun connect(device: Device) {
        if (ui.value.busy || ui.value.activeRequest != null) { message("Arrêtez l'opération avant de changer d'appareil"); return }
        mutable.update { UiState(selected = device, message = "Connexion à ${device.name}…") }
        runCatching { client.connect(device, { received -> viewModelScope.launch { event(received) } }, { error -> viewModelScope.launch { disconnected(error) } }) }
            .onFailure { disconnected(it.message ?: "Connexion impossible") }
    }
    private fun disconnected(error: String) {
        pending?.completeExceptionally(IllegalStateException(error)); pending = null
        mutable.update { it.copy(connected = false, busy = false, commands = emptyList(), activeRequest = null, message = error) }
    }
    private suspend fun event(event: JSONObject) {
        when (event.optString("type")) {
            "status" -> {
                val array = event.getJSONArray("commands")
                mutable.update { it.copy(connected = true, commands = (0 until array.length()).map(array::getString), message = "Connecté — ${event.optString("os")} — ${event.optString("cwd")}") }
                ui.value.selected?.let { dao.save(it.copy(lastSeen = System.currentTimeMillis(), os = event.optString("os"))) }
            }
            "result", "error" -> {
                val request = event.optString("request_id")
                if (request == ui.value.activeRequest) {
                    val result = event.optJSONObject("result")
                    val text = if (result != null) "${result.optString("stdout")}\n${result.optString("stderr")}\n[${result.optString("status")} · code ${result.opt("exit_code")}]" else event.optString("message")
                    mutable.update { it.copy(output = (it.output + "\n$ $pendingCommand\n$text\n").takeLast(65536), activeRequest = null) }
                    if (recordHistory.value) { dao.add(History(request, ui.value.selected?.id.orEmpty(), pendingCommand, text.take(8192))); dao.trimHistory() }
                    pending?.complete(event)
                } else if (event.optString("type") == "error") disconnected(event.optString("message"))
            }
            "revoked" -> { ui.value.selected?.let { dao.deleteDevice(it.id) }; client.close(); mutable.value = UiState(message = "Appairage révoqué sur l'agent et supprimé du téléphone.") }
        }
    }
    private suspend fun execute(alias: String): JSONObject {
        check(ui.value.connected && alias in ui.value.commands) { "Commande non autorisée ou appareil hors ligne" }
        val request = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JSONObject>(); pending = deferred; pendingCommand = alias
        mutable.update { it.copy(activeRequest = request) }
        check(client.send(JSONObject().put("type", "execute").put("command", alias).put("timeout_secs", 20), request)) { "Connexion fermée" }
        return try { withTimeout(25000) { deferred.await() } } finally { pending = null }
    }
    fun runCommand(alias: String) {
        if (ui.value.busy || ui.value.activeRequest != null) return
        scriptJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try { execute(alias) } catch (e: Exception) { if (e !is CancellationException) message(e.message ?: "Échec") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun cancel() {
        ui.value.activeRequest?.let { id -> runCatching { client.send(JSONObject().put("type", "cancel").put("request_id", id), UUID.randomUUID().toString()) } }
        scriptJob?.cancel(); message("Annulation demandée ; attente du résultat de l'agent.")
    }
    fun validateScript(source: String): String = runCatching {
        val plan = ScriptEngine.validate(source, ui.value.commands.toSet())
        "Simulation : ${plan.steps.size} étapes valides, commandes ${plan.steps.mapNotNull { it.command }.distinct().joinToString()}. Aucun effet exécuté."
    }.getOrElse { "Erreur : ${it.message}" }
    fun saveScript(source: String) {
        viewModelScope.launch { runCatching { val plan = ScriptEngine.validate(source, ui.value.commands.toSet()); dao.saveScript(SavedScript(UUID.randomUUID().toString(), plan.name, source, true)) }.onSuccess { message("Script validé et enregistré") }.onFailure { message(it.message ?: "Script invalide") } }
    }
    fun runScript(source: String) {
        if (ui.value.busy || ui.value.activeRequest != null) return
        scriptJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true) }
            try {
                val plan = ScriptEngine.validate(source, ui.value.commands.toSet())
                ScriptEngine.run(plan, { command -> val event = execute(command); val r = event.optJSONObject("result"); if (r?.optString("status") == "completed" && !r.isNull("exit_code")) r.getInt("exit_code") else null }, { line -> mutable.update { it.copy(output = (it.output + "\n$line").takeLast(65536)) } })
                message("Script terminé")
            } catch (e: Exception) { message(if (e is CancellationException) "Script annulé" else e.message ?: "Échec du script") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun revoke() { runCatching { client.send(JSONObject().put("type", "revoke"), UUID.randomUUID().toString()) }.onFailure { message("Révocation impossible hors ligne. Utilisez revoke sur le PC ; l'entrée locale est conservée.") } }
    override fun onCleared() { client.close(); database.close(); super.onCleared() }
}

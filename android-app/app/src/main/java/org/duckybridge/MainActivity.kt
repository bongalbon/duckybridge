package org.duckybridge

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.duckybridge.scripts.ScriptEngine
import java.text.DateFormat
import java.util.Date

class MainActivity : FragmentActivity() {
    private var authorized: (() -> Unit)? = null
    private val credentialResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = authorized; authorized = null
        if (result.resultCode == RESULT_OK) action?.invoke()
    }
    private fun authenticate(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            android.widget.Toast.makeText(this, "Configurez un code de verrouillage Android pour autoriser les actions.", android.widget.Toast.LENGTH_LONG).show(); return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { action() }
            })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Autoriser DuckyBridge")
                .setSubtitle("Confirmer l'action sur votre appareil appairé")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
        } else {
            @Suppress("DEPRECATION")
            val intent = keyguard.createConfirmDeviceCredentialIntent("DuckyBridge", "Autoriser l'action distante")
            if (intent != null) { authorized = action; credentialResult.launch(intent) }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: BridgeViewModel = viewModel()
            val dark by model.dark.collectAsStateWithLifecycle()
            val colors = if (dark) darkColorScheme(primary = Color(0xFF8DE2CA), secondary = Color(0xFFE8CB8C), background = Color(0xFF101716), surface = Color(0xFF18211F))
                else lightColorScheme(primary = Color(0xFF006B53), secondary = Color(0xFF765B21), background = Color(0xFFF4F8F5))
            MaterialTheme(colorScheme = colors) { Surface(Modifier.fillMaxSize()) { BridgeApp(model, ::authenticate) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeApp(model: BridgeViewModel, authorize: (() -> Unit) -> Unit) {
    val state by model.ui.collectAsStateWithLifecycle()
    val devices by model.devices.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val scripts by model.scripts.collectAsStateWithLifecycle()
    val discovered by model.discovered.collectAsStateWithLifecycle()
    val dark by model.dark.collectAsStateWithLifecycle()
    val record by model.recordHistory.collectAsStateWithLifecycle()
    var tab by rememberSaveableState("Accueil")
    val pages = listOf("Accueil", "Appareils", "Console", "Scripts")
    val icons = listOf("⌂", "▣", ">_", "{ }")
    var name by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9843") }; var pin by remember { mutableStateOf("") }
    var verified by remember { mutableStateOf(false) }
    var script by remember { mutableStateOf(ScriptEngine.example) }
    var simulation by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("hostname") }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Autoriser l'opération") },
        text = { Text("Appareil : ${state.selected?.name ?: "aucun"}. Vérifiez la commande ou les étapes affichées avant de continuer.") },
        confirmButton = { TextButton(onClick = { val action = confirm; confirm = null; action?.let(authorize) }) { Text("Vérifier mon identité") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } })
    Scaffold(topBar = { TopAppBar(title = { Column { Text("DuckyBridge"); Text("VOTRE PONT LOCAL · v0.1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } }, actions = { TextButton(onClick = { tab = "Paramètres" }) { Text("Réglages") } }) },
        bottomBar = { NavigationBar { pages.forEachIndexed { i, page -> NavigationBarItem(selected = tab == page, onClick = { tab = page }, icon = { Text(icons[i], style = MaterialTheme.typography.titleMedium) }, label = { Text(page) }) } } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { tab = "Sécurité" }, label = { Text(if (state.connected) "● Authentifié" else "○ Hors ligne") })
                AssistChip(onClick = { tab = "Historique" }, label = { Text("Historique") })
            }
            Text(tab, style = MaterialTheme.typography.headlineLarge)
            when (tab) {
                "Accueil" -> {
                    InfoCard("Le contrôle commence par la confiance", "Vos appareils, votre réseau et votre accord. Chaque commande est signée ; chaque accès peut être révoqué.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ElevatedCard(Modifier.weight(1f)) { Column(Modifier.padding(18.dp)) { Text("${devices.size}", style = MaterialTheme.typography.headlineLarge); Text("appareils enregistrés") } }
                        ElevatedCard(Modifier.weight(1f)) { Column(Modifier.padding(18.dp)) { Text("${scripts.size}", style = MaterialTheme.typography.headlineLarge); Text("scripts locaux") } }
                    }
                    Button(onClick = { tab = "Appareils" }, Modifier.fillMaxWidth()) { Text("Connecter un appareil") }
                    Text("Opérations récentes", style = MaterialTheme.typography.titleMedium)
                    if (history.isEmpty()) Text("Aucune opération enregistrée.")
                    history.take(4).forEach { InfoCard(it.command, "${DateFormat.getDateTimeInstance().format(Date(it.timestamp))}\n${it.result.take(180)}") }
                    OutlinedButton(onClick = { tab = "Automatisations" }) { Text("Raccourcis d'automatisation") }
                }
                "Appareils" -> {
                    devices.forEach { device -> ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleLarge)
                        Text("${device.host}:${device.port} · ${device.os}")
                        Text(if (device.lastSeen == 0L) "Approbation locale à vérifier" else "Dernière connexion : ${DateFormat.getDateTimeInstance().format(Date(device.lastSeen))}")
                        Button(onClick = { model.connect(device); tab = "Console" }, enabled = !state.busy) { Text("Se connecter") }
                    } } }
                    HorizontalDivider()
                    Text("Ajouter un agent", style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = model::discover) { Text("Rechercher sur le réseau (15 s)") }
                    discovered.forEach { agent -> TextButton(onClick = { name = agent.name; host = agent.host; port = agent.port.toString(); verified = false }) { Text("${agent.name} · ${agent.host}:${agent.port}") } }
                    Text("Sur le PC : lancez l'agent avec --pairing. Recopiez son empreinte affichée localement. La découverte seule ne prouve pas son identité.")
                    OutlinedTextField(name, { name = it }, label = { Text("Nom de l'appareil") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(host, { host = it; verified = false }, label = { Text("Adresse IP ou nom d'hôte") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(port, { port = it }, label = { Text("Port TLS") }, singleLine = true)
                    OutlinedTextField(pin, { pin = it; verified = false }, label = { Text("Empreinte TLS SHA-256 (64 caractères)") }, modifier = Modifier.fillMaxWidth())
                    Row { Checkbox(verified, { verified = it }); Text("J'ai comparé l'empreinte avec celle affichée sur le PC.", Modifier.padding(top = 12.dp)) }
                    Button(onClick = { model.pair(name, host, port, pin) }, enabled = verified && !state.busy && host.isNotBlank()) { Text("Demander l'appairage") }
                    InfoCard("Votre identité Android", model.fingerprint)
                }
                "Console" -> {
                    InfoCard(state.selected?.name ?: "Aucun appareil sélectionné", "Commandes disponibles : ${state.commands.joinToString().ifEmpty { "connectez un appareil approuvé" }}")
                    OutlinedTextField(command, { command = it }, label = { Text("Alias de commande autorisé") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { val requested = command.trim(); confirm = { model.runCommand(requested) } }, enabled = state.connected && !state.busy && state.activeRequest == null && command.trim() in state.commands) { Text("Exécuter") }
                        OutlinedButton(onClick = model::cancel, enabled = state.busy || state.activeRequest != null) { Text("Arrêter") }
                    }
                    Surface(color = Color(0xFF0A100F), shape = MaterialTheme.shapes.medium) { SelectionContainer { Text(state.output.ifEmpty { "$ En attente d'une commande…" }, Modifier.fillMaxWidth().padding(16.dp), color = Color(0xFFB9E6CD), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                    Text("Bibliothèque de diagnostic", style = MaterialTheme.typography.titleMedium)
                    listOf("hostname" to "Réseau · nom de la machine", "whoami" to "Identité · compte d'exécution").forEach { (alias, description) -> OutlinedButton(onClick = { command = alias }, Modifier.fillMaxWidth()) { Text("$alias — $description") } }
                    Text("La console exécute des alias autorisés, sans shell interactif dans cette version.", style = MaterialTheme.typography.bodySmall)
                }
                "Scripts" -> {
                    Text("Format déclaratif v1 · validation et autorisation obligatoires")
                    scripts.forEach { saved -> TextButton(onClick = { script = saved.json; simulation = "" }) { Text("Ouvrir : ${saved.name}") } }
                    OutlinedTextField(script, { script = it; simulation = "" }, Modifier.fillMaxWidth().heightIn(min = 260.dp), label = { Text("Éditeur JSON") }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { simulation = model.validateScript(script) }) { Text("Simuler") }
                        OutlinedButton(onClick = { model.saveScript(script) }, enabled = state.connected) { Text("Enregistrer") }
                    }
                    if (simulation.isNotEmpty()) Text(simulation)
                    Button(onClick = { val source = script; confirm = { model.runScript(source) } }, enabled = state.connected && !state.busy && state.activeRequest == null) { Text("Autoriser et exécuter") }
                    OutlinedButton(onClick = model::cancel, enabled = state.busy) { Text("Annuler le script") }
                }
                "Automatisations" -> {
                    Text("Déclenchement manuel des scripts enregistrés. La planification en arrière-plan reste à implémenter.")
                    scripts.forEach { saved -> Button(onClick = { val source = saved.json; confirm = { model.runScript(source) } }, enabled = state.connected && !state.busy && state.activeRequest == null) { Text(saved.name) } }
                    if (scripts.isEmpty()) Text("Validez et enregistrez un script pour créer un raccourci.")
                }
                "Historique" -> {
                    OutlinedButton(onClick = model::clearHistory) { Text("Effacer l'historique local") }
                    history.forEach { InfoCard("${it.command} · ${DateFormat.getDateTimeInstance().format(Date(it.timestamp))}", it.result) }
                    if (history.isEmpty()) Text("Aucune opération enregistrée.")
                }
                "Sécurité" -> {
                    InfoCard("Identité du téléphone", model.fingerprint)
                    InfoCard("Politique active", "TLS vérifié par empreinte ; signature ES256 avec Android Keystore ; requêtes valides 30 secondes ; session de 10 minutes ; approbation locale de 30 jours. Toutes les exécutions demandent le verrouillage Android.")
                    Button(onClick = { confirm = model::revoke }, enabled = state.connected && !state.busy && state.activeRequest == null) { Text("Révoquer cet appairage") }
                    Text("Hors ligne : révoquez depuis la commande locale de l'agent. Désinstaller Android ne révoque pas automatiquement les accès sur les PC.")
                }
                "Paramètres" -> {
                    Row { Switch(dark, model::theme); Text("Thème sombre", Modifier.padding(12.dp)) }
                    Row { Switch(record, model::keepHistory); Text("Historique local (100 opérations)", Modifier.padding(12.dp)) }
                    Text("L'historique peut contenir des informations privées. Sauvegarde Android désactivée.")
                    OutlinedButton(onClick = { tab = "Sécurité" }) { Text("Gérer la sécurité") }
                    InfoCard("État de cette version", "Premier parcours local. macOS, terminal PTY, planification, imports/exports et transports alternatifs restent à valider ou à développer. Consultez le suivi des fonctionnalités.")
                }
            }
            HorizontalDivider()
            SelectionContainer { Text(state.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
        }
    }
}
@Composable
private fun InfoCard(title: String, text: String) {
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) } } }
}
@Composable
private fun rememberSaveableState(initial: String) = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }

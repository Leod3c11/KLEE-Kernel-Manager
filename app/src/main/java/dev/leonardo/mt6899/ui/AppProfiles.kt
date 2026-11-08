package dev.leonardo.mt6899.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leonardo.mt6899.AppInfo
import dev.leonardo.mt6899.MainViewModel
import dev.leonardo.mt6899.UiState

/** Aba Perfis: liga o perfil por app e lista/edita as regras "app -> perfil". */
@Composable
fun AppProfilesSection(ui: UiState, vm: MainViewModel) {
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadApps() }
    val labels = ui.apps.associate { it.pkg to it.label }
    Section(
        "Perfil por app",
        "Aplica um perfil quando o app abre e volta ao anterior quando você sai. Usa o serviço em segundo plano e root."
    ) {
        SwitchRow(
            "Trocar perfil por app",
            ui.prefs["appprof"] == "1",
            "Confere o app em primeiro plano a cada ~3 s com a tela ligada. Perfis com ⚠ não podem ser usados aqui."
        ) { vm.setPref("appprof", if (it) "1" else "0") }
        ui.appRules.forEach { (pkg, profile) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(labels[pkg] ?: pkg)
                    Hint("$pkg → $profile")
                }
                TextButton(onClick = { vm.removeAppRule(pkg) }) { Text("Remover") }
            }
        }
        if (ui.appRules.isEmpty()) Hint("Nenhum app configurado.")
        OutlinedButton(onClick = { adding = true }) { Text("Adicionar app") }
    }
    if (adding) AddAppDialog(ui, vm) { adding = false }
}

@Composable
private fun AddAppDialog(ui: UiState, vm: MainViewModel, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<AppInfo?>(null) }
    val app = picked
    if (app == null) {
        val q = query.trim().lowercase()
        val list = ui.apps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Escolher app") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text("Buscar") }, modifier = Modifier.fillMaxWidth()
                    )
                    if (ui.apps.isEmpty()) Hint("Carregando a lista de apps…")
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(list, key = { it.pkg }) { a ->
                            Column(Modifier.fillMaxWidth().clickable { picked = a }.padding(vertical = 8.dp)) {
                                Text(a.label)
                                Hint(a.pkg)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } }
        )
    } else {
        val choices = ui.profiles.filter { !it.name.endsWith("⚠") }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Perfil para ${app.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEach { pr ->
                        OutlinedButton(
                            onClick = { vm.addAppRule(app.pkg, pr.name); onClose() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(pr.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Voltar") } }
        )
    }
}

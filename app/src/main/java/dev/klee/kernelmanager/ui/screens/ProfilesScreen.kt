package dev.klee.kernelmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.klee.kernelmanager.BuiltInProfile
import dev.klee.kernelmanager.SavedProfile
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.SectionHeader
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.theme.KleeColors

@Composable
fun ProfilesScreen(
    activeProfile: String,
    savedProfiles: List<SavedProfile>,
    busy: Boolean,
    onApplyBuiltIn: (BuiltInProfile, Boolean) -> Unit,
    onRestoreBaseline: () -> Unit,
    onSaveCurrent: (String, String) -> Unit,
    onApplySaved: (SavedProfile) -> Unit,
    onDeleteSaved: (String) -> Unit
) {
    var includeGpuBoost by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    var profileDesc by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Perfis & Gaming",
            subtitle = "Modos prontos com rollback seguro e perfis personalizados",
            trailing = {
                StatusPill("Ativo: $activeProfile", KleeColors.Gaming)
            }
        )

        // GPU Boost Toggle Card
        KleeCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        "Incluir ajuste de GPU Mali nos perfis",
                        color = KleeColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Aplica apenas quando a tabela OPP devfreq estiver disponível e validada.",
                        color = KleeColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = includeGpuBoost,
                    onCheckedChange = { includeGpuBoost = it }
                )
            }
        }

        // Built-in Profiles
        BuiltInProfile.entries.forEach { profile ->
            val accent = when (profile) {
                BuiltInProfile.ECO -> KleeColors.Power
                BuiltInProfile.BALANCED -> KleeColors.Primary
                BuiltInProfile.PERFORMANCE -> KleeColors.Thermal
                BuiltInProfile.GAMING -> KleeColors.Gaming
            }
            val icon = when (profile) {
                BuiltInProfile.ECO -> Icons.Rounded.Eco
                BuiltInProfile.BALANCED -> Icons.Rounded.Balance
                BuiltInProfile.PERFORMANCE -> Icons.Rounded.Speed
                BuiltInProfile.GAMING -> Icons.Rounded.SportsEsports
            }
            val isActive = activeProfile.equals(profile.title, ignoreCase = true)

            KleeCard(
                modifier = Modifier.fillMaxWidth(),
                accentBorder = if (isActive) accent else accent.copy(alpha = 0.25f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(profile.title, color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                            if (isActive) StatusPill("Ativo", accent)
                        }
                        Text(profile.subtitle, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { onApplyBuiltIn(profile, includeGpuBoost) },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("Aplicar", fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    profile.description,
                    color = KleeColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Restore Baseline Button
        OutlinedButton(
            onClick = onRestoreBaseline,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Restaurar governors e limites originais")
        }

        HorizontalDivider(color = KleeColors.Border)

        // Custom Profiles Section
        SectionHeader(
            title = "Perfis Personalizados",
            subtitle = "Salve o estado atual de CPU/GPU para reaplicar com 1 toque",
            trailing = {
                FilledTonalButton(
                    onClick = {
                        profileName = ""
                        profileDesc = ""
                        showSaveDialog = true
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Salvar atual")
                }
            }
        )

        if (savedProfiles.isEmpty()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Nenhum perfil salvo ainda. Ajuste CPU/GPU como preferir e toque em \"Salvar atual\".",
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            savedProfiles.forEach { profile ->
                val isActive = activeProfile == profile.name
                KleeCard(
                    modifier = Modifier.fillMaxWidth(),
                    accentBorder = if (isActive) KleeColors.Primary else KleeColors.Border
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(profile.description, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${profile.cpuTargets.size} policies CPU" +
                                    if (profile.gpuTarget != null) " · GPU incluída" else "",
                                color = KleeColors.TextMuted,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = { onDeleteSaved(profile.id) },
                                enabled = !busy
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remover", tint = KleeColors.Danger)
                            }
                            FilledTonalIconButton(
                                onClick = { onApplySaved(profile) },
                                enabled = !busy,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = KleeColors.Primary.copy(alpha = 0.2f),
                                    contentColor = KleeColors.Primary
                                )
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Aplicar perfil")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = KleeColors.CardElevated,
            title = { Text("Salvar Perfil Atual", color = KleeColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Captura as frequências e governors atuais de CPU e GPU.",
                        color = KleeColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("Nome do perfil") },
                        placeholder = { Text("Ex: Genshin 60fps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = profileDesc,
                        onValueChange = { profileDesc = it },
                        label = { Text("Descrição (opcional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveDialog = false
                        onSaveCurrent(profileName, profileDesc)
                    },
                    enabled = profileName.isNotBlank()
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

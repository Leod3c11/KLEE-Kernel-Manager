package dev.klee.kernelmanager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.klee.kernelmanager.BuiltInProfile
import dev.klee.kernelmanager.InstalledAppEntry
import dev.klee.kernelmanager.PerAppRule
import dev.klee.kernelmanager.SavedProfile
import dev.klee.kernelmanager.VulkanDriverPackage
import dev.klee.kernelmanager.VulkanVerificationResult
import dev.klee.kernelmanager.service.PerAppEngineService
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.SectionHeader
import dev.klee.kernelmanager.ui.components.SelectorBox
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.theme.KleeColors

@Composable
fun PerAppScreen(
    rules: List<PerAppRule>,
    installedApps: List<InstalledAppEntry>,
    savedProfiles: List<SavedProfile>,
    vulkanDrivers: List<VulkanDriverPackage>,
    patchedPackages: Set<String>,
    patchProgress: String?,
    lastVulkanVerification: VulkanVerificationResult?,
    supportedHz: List<Int>,
    busy: Boolean,
    onLoadApps: () -> Unit,
    onSaveRule: (PerAppRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onLaunchWithRule: (PerAppRule) -> Unit,
    onPatchApp: (PerAppRule) -> Unit,
    onRestorePatchedApp: (String, String) -> Unit,
    onVerifyDriverNow: (PerAppRule) -> Unit,
    onRecheckLastVerification: () -> Unit,
    onOpenVulkanDrivers: () -> Unit
) {
    val context = LocalContext.current
    val engineRunning by PerAppEngineService.running.collectAsState()
    val engineStatus by PerAppEngineService.status.collectAsState()

    var showAppPicker by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<PerAppRule?>(null) }
    var confirmPatchRule by remember { mutableStateOf<PerAppRule?>(null) }
    var confirmRestoreRule by remember { mutableStateOf<PerAppRule?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Perfis & Drivers por App",
            subtitle = "AdrenoTools (PPSSPP), Perfil CPU/GPU, FPSGO, Hz e Driver .zip",
            trailing = {
                FilledTonalButton(
                    onClick = {
                        if (installedApps.isEmpty()) onLoadApps()
                        showAppPicker = true
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("App")
                }
            }
        )

        // Vulkan Drivers .zip Manager Shortcut Card
        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            accentBorder = KleeColors.Gpu.copy(alpha = 0.4f),
            onClick = onOpenVulkanDrivers
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(KleeColors.Gpu.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.FolderZip, contentDescription = null, tint = KleeColors.Gpu)
                    }
                    Column {
                        Text("Drivers Vulkan Customizados (.zip)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (vulkanDrivers.isEmpty()) "Toque para importar e testar um driver PanVK .zip"
                            else "${vulkanDrivers.size} driver(s) .zip · Toque para testar com AdrenoTools",
                            color = KleeColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                StatusPill("${vulkanDrivers.size} .zip", KleeColors.Gpu)
            }
        }

        if (patchProgress != null) {
            KleeCard(
                modifier = Modifier.fillMaxWidth(),
                accentBorder = KleeColors.Primary.copy(alpha = 0.7f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = KleeColors.Primary
                    )
                    Column {
                        Text("Processando AdrenoTools (PPSSPP)...", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleSmall)
                        Text(patchProgress, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (lastVulkanVerification != null) {
            VulkanVerificationCard(
                result = lastVulkanVerification,
                busy = busy,
                onRecheck = onRecheckLastVerification
            )
        }

        // Engine Daemon Control Card
        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            accentBorder = (if (engineRunning) KleeColors.Success else KleeColors.Border).copy(alpha = 0.6f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background((if (engineRunning) KleeColors.Success else KleeColors.Primary).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.AutoMode,
                            contentDescription = null,
                            tint = if (engineRunning) KleeColors.Success else KleeColors.Primary
                        )
                    }
                    Column {
                        Text("Motor Automático Per-App", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (engineRunning) engineStatus else "Desativado · ou use 'Lançar' abaixo",
                            color = if (engineRunning) KleeColors.Success else KleeColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = engineRunning,
                    onCheckedChange = { enable ->
                        if (enable) PerAppEngineService.start(context)
                        else PerAppEngineService.stop(context)
                    }
                )
            }
            Text(
                "Dica: para carregamento 100% real do PanVK (método PPSSPP / libadrenotools, sem Zygisk), toque em 'Injetar AdrenoTools' no card do app e depois em 'Lançar'.",
                color = KleeColors.TextMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Configured Rules List
        Text("Regras Configuradas (${rules.size})", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelLarge)

        if (rules.isEmpty()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, tint = KleeColors.Primary)
                    Text(
                        "Nenhum aplicativo configurado ainda. Toque em \"+ App\" para escolher um app/jogo (ex.: Vulkan Caps Viewer), vincular seu driver PanVK .zip e injetar AdrenoTools.",
                        color = KleeColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            rules.forEach { rule ->
                val profileDisplay = BuiltInProfile.entries.firstOrNull { it.id == rule.profileId }?.title
                    ?: savedProfiles.firstOrNull { it.id == rule.profileId }?.name
                    ?: if (rule.profileId == "NONE") null else rule.profileId
                val driverDisplay = if (rule.vulkanDriverId == "SYSTEM") null
                else vulkanDrivers.firstOrNull { it.id == rule.vulkanDriverId }?.name ?: "Driver Removido"
                val isPatched = patchedPackages.contains(rule.packageName)

                KleeCard(
                    modifier = Modifier.fillMaxWidth(),
                    accentBorder = when {
                        isPatched -> KleeColors.Success.copy(alpha = 0.6f)
                        driverDisplay != null -> KleeColors.Gpu.copy(alpha = 0.35f)
                        else -> KleeColors.Primary.copy(alpha = 0.35f)
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                rule.appLabel,
                                color = KleeColors.TextPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                rule.packageName,
                                color = KleeColors.TextMuted,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row {
                            IconButton(onClick = { editingRule = rule }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Editar", tint = KleeColors.Primary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onDeleteRule(rule.packageName) }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Excluir", tint = KleeColors.Danger, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isPatched) StatusPill("AdrenoTools PPSSPP ✓", KleeColors.Success)
                        driverDisplay?.let { StatusPill("VK: $it", KleeColors.Gpu) }
                        if (rule.forceVulkanAngle) StatusPill("Forçar Vulkan (ANGLE)", KleeColors.Monitor)
                        profileDisplay?.let { StatusPill("Perfil: $it", KleeColors.Cpu) }
                        if (rule.mtkPerfPreset != "NONE") StatusPill("MTK: ${rule.mtkPerfPreset}", KleeColors.Gaming)
                        if (rule.refreshRateHz > 0) StatusPill("${rule.refreshRateHz} Hz", KleeColors.Primary)
                        if (rule.enableBypassCharging) StatusPill("Bypass", KleeColors.Warning)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onLaunchWithRule(rule) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = when {
                                    isPatched && driverDisplay != null -> KleeColors.Success
                                    driverDisplay != null -> KleeColors.Gpu
                                    else -> KleeColors.Primary
                                },
                                contentColor = if (isPatched && driverDisplay != null) Color.Black else if (driverDisplay != null) Color.White else Color.Black
                            ),
                            contentPadding = PaddingValues(vertical = 9.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    isPatched && driverDisplay != null -> "Lançar (AdrenoTools)"
                                    driverDisplay != null -> "Lançar com Driver"
                                    else -> "Lançar com Regra"
                                },
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { onVerifyDriverNow(rule) },
                            enabled = !busy,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                        ) {
                            Icon(Icons.Rounded.Radar, contentDescription = "Verificar driver", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Verificar")
                        }
                    }

                    // AdrenoTools (PPSSPP) Inject / Restore Action Row
                    if (!isPatched) {
                        FilledTonalButton(
                            onClick = { confirmPatchRule = rule },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = KleeColors.Gpu.copy(alpha = 0.16f),
                                contentColor = KleeColors.Gpu
                            ),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Injetar AdrenoTools (PPSSPP · Sem Zygisk)", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { confirmRestoreRule = rule },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Restaurar APK Original (Backup Intacto)")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }

    confirmPatchRule?.let { targetRule ->
        AlertDialog(
            onDismissRequest = { confirmPatchRule = null },
            containerColor = KleeColors.CardElevated,
            title = { Text("Injetar AdrenoTools em ${targetRule.appLabel}?", color = KleeColors.TextPrimary) },
            text = {
                Text(
                    "O KLEE vai:\n" +
                        "1. Salvar um backup intacto do(s) APK(s) originais em /data/adb/klee_drivers/apk_backups/.\n" +
                        "2. Injetar o carregador libadrenotools (mesmo método do PPSSPP) + hooks de Vulkan mantendo OpenGL ES / libMEOW 100% original.\n" +
                        "3. Reassinar e instalar o app via Root (sem Zygisk e sem reiniciar).\n\n" +
                        "Depois de injetado, você troca entre PanVK e Mali instantaneamente ao tocar em 'Lançar'. Como a assinatura muda, os dados locais do app serão resetados nesta primeira vez. Você pode restaurar o APK original a qualquer momento.",
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val r = targetRule
                        confirmPatchRule = null
                        onPatchApp(r)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Gpu, contentColor = Color.White)
                ) {
                    Text("Injetar Agora", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPatchRule = null }) { Text("Cancelar") }
            }
        )
    }

    confirmRestoreRule?.let { targetRule ->
        AlertDialog(
            onDismissRequest = { confirmRestoreRule = null },
            containerColor = KleeColors.CardElevated,
            title = { Text("Restaurar APK Original?", color = KleeColors.TextPrimary) },
            text = {
                Text(
                    "Isso removerá a versão modificada de ${targetRule.appLabel} e reinstalará o(s) APK(s) originais salvos no backup do KLEE.",
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pkg = targetRule.packageName
                        val lbl = targetRule.appLabel
                        confirmRestoreRule = null
                        onRestorePatchedApp(pkg, lbl)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Primary, contentColor = Color.Black)
                ) {
                    Text("Restaurar Original", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestoreRule = null }) { Text("Cancelar") }
            }
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            busy = busy,
            onDismiss = { showAppPicker = false },
            onSelect = { entry ->
                showAppPicker = false
                val existing = rules.firstOrNull { it.packageName == entry.packageName }
                editingRule = existing ?: PerAppRule(
                    packageName = entry.packageName,
                    appLabel = entry.label,
                    profileId = BuiltInProfile.GAMING.id,
                    mtkPerfPreset = "GAMING",
                    refreshRateHz = supportedHz.maxOrNull() ?: 120,
                    enableBypassCharging = false,
                    vulkanDriverId = vulkanDrivers.firstOrNull()?.id ?: "SYSTEM",
                    forceVulkanAngle = false
                )
            }
        )
    }

    editingRule?.let { rule ->
        PerAppRuleEditorDialog(
            initialRule = rule,
            savedProfiles = savedProfiles,
            vulkanDrivers = vulkanDrivers,
            supportedHz = supportedHz,
            onDismiss = { editingRule = null },
            onSave = { updated ->
                editingRule = null
                onSaveRule(updated)
            }
        )
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<InstalledAppEntry>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (InstalledAppEntry) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val q = search.trim().lowercase()
    val filtered = remember(apps, q, showSystem) {
        apps.filter { app ->
            (showSystem || !app.isSystem) &&
                (q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KleeColors.CardElevated,
        title = { Text("Escolher Aplicativo / Jogo", color = KleeColors.TextPrimary) },
        text = {
            Column(
                modifier = Modifier.heightIn(min = 260.dp, max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Buscar jogo ou app...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${filtered.size} apps encontrados", color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = showSystem,
                        onClick = { showSystem = !showSystem },
                        label = { Text("Sistema") }
                    )
                }
                if (busy && apps.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(KleeColors.Surface)
                                    .border(1.dp, KleeColors.Border, RoundedCornerShape(10.dp))
                                    .clickable { onSelect(app) }
                                    .padding(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                Text(app.label, color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(app.packageName, color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
private fun PerAppRuleEditorDialog(
    initialRule: PerAppRule,
    savedProfiles: List<SavedProfile>,
    vulkanDrivers: List<VulkanDriverPackage>,
    supportedHz: List<Int>,
    onDismiss: () -> Unit,
    onSave: (PerAppRule) -> Unit
) {
    val profileOptions = remember(savedProfiles) {
        buildList {
            add("NONE" to "Não alterar")
            BuiltInProfile.entries.forEach { add(it.id to it.title) }
            savedProfiles.forEach { add(it.id to "Salvo: ${it.name}") }
        }
    }
    val driverOptions = remember(vulkanDrivers) {
        buildList {
            add("SYSTEM" to "Padrão do Sistema (Mali)")
            vulkanDrivers.forEach { add(it.id to "${it.name} (${it.primaryLibName})") }
        }
    }
    val mtkOptions = listOf(
        "NONE" to "Não alterar",
        "GAMING" to "Gaming (Anti-Stutter)",
        "STOCK" to "Stock HyperOS",
        "COOL" to "Frio / Estável"
    )
    val hzOptions = remember(supportedHz) {
        listOf(0) + supportedHz.distinct().sorted()
    }

    var profileId by remember { mutableStateOf(initialRule.profileId) }
    var mtkPreset by remember { mutableStateOf(initialRule.mtkPerfPreset) }
    var driverId by remember { mutableStateOf(initialRule.vulkanDriverId) }
    var forceAngle by remember { mutableStateOf(initialRule.forceVulkanAngle) }
    var hz by remember { mutableIntStateOf(initialRule.refreshRateHz) }
    var bypass by remember { mutableStateOf(initialRule.enableBypassCharging) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KleeColors.CardElevated,
        title = {
            Column {
                Text(initialRule.appLabel, color = KleeColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(initialRule.packageName, color = KleeColors.TextMuted, style = MaterialTheme.typography.labelMedium)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SelectorBox(
                    label = "Driver Vulkan Customizado (.zip)",
                    selectedText = driverOptions.firstOrNull { it.first == driverId }?.second ?: "Padrão do Sistema (Mali)",
                    options = driverOptions.map { it.second },
                    enabled = true,
                    accent = KleeColors.Gpu,
                    onSelected = { label ->
                        driverId = driverOptions.firstOrNull { it.second == label }?.first ?: "SYSTEM"
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Forçar Vulkan (ANGLE)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text("Ative para jogos OpenGL ES rodarem sobre Vulkan. Para apps já em Vulkan (ex.: Vulkan Caps Viewer), deixe desligado.", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = forceAngle, onCheckedChange = { forceAngle = it })
                }

                HorizontalDivider(color = KleeColors.Border)

                SelectorBox(
                    label = "Perfil de Desempenho (CPU/GPU)",
                    selectedText = profileOptions.firstOrNull { it.first == profileId }?.second ?: "Não alterar",
                    options = profileOptions.map { it.second },
                    enabled = true,
                    accent = KleeColors.Cpu,
                    onSelected = { label ->
                        profileId = profileOptions.firstOrNull { it.second == label }?.first ?: "NONE"
                    }
                )

                SelectorBox(
                    label = "Motor FPSGO & Xiaomi Metis",
                    selectedText = mtkOptions.firstOrNull { it.first == mtkPreset }?.second ?: "Não alterar",
                    options = mtkOptions.map { it.second },
                    enabled = true,
                    accent = KleeColors.Gaming,
                    onSelected = { label ->
                        mtkPreset = mtkOptions.firstOrNull { it.second == label }?.first ?: "NONE"
                    }
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Taxa de Atualização de Tela", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        hzOptions.forEach { optHz ->
                            FilterChip(
                                selected = hz == optHz,
                                onClick = { hz = optHz },
                                label = { Text(if (optHz == 0) "Auto" else "${optHz}Hz") }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Bypass Charging Automático", color = KleeColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        Text("Pausa carregamento enquanto este app estiver aberto", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = bypass, onCheckedChange = { bypass = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initialRule.copy(
                            profileId = profileId,
                            mtkPerfPreset = mtkPreset,
                            refreshRateHz = hz,
                            enableBypassCharging = bypass,
                            vulkanDriverId = driverId,
                            forceVulkanAngle = forceAngle
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Primary, contentColor = Color.Black)
            ) {
                Text("Salvar Regra", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

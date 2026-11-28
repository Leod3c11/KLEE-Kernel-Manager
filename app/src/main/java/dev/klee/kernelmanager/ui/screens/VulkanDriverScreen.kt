package dev.klee.kernelmanager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.klee.kernelmanager.AdrenoToolsProbeResult
import dev.klee.kernelmanager.SystemVulkanInfo
import dev.klee.kernelmanager.VulkanDriverPackage
import dev.klee.kernelmanager.VulkanLoadStatus
import dev.klee.kernelmanager.VulkanVerificationResult
import dev.klee.kernelmanager.ui.components.KleeCard
import dev.klee.kernelmanager.ui.components.RootWarningCard
import dev.klee.kernelmanager.ui.components.SectionHeader
import dev.klee.kernelmanager.ui.components.StatusPill
import dev.klee.kernelmanager.ui.theme.KleeColors

@Composable
fun VulkanDriverScreen(
    drivers: List<VulkanDriverPackage>,
    systemInfo: SystemVulkanInfo,
    autoUnmountSec: Int,
    lastVerification: VulkanVerificationResult?,
    probeResult: AdrenoToolsProbeResult?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onImportZip: (Uri) -> Unit,
    onProbeDriver: (VulkanDriverPackage) -> Unit,
    onDeleteDriver: (String) -> Unit,
    onRestoreDefault: () -> Unit,
    onSelectAutoUnmount: (Int) -> Unit,
    onRecheckVerification: () -> Unit
) {
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportZip(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(
            title = "Drivers Vulkan (.zip)",
            subtitle = "Motor AdrenoTools (PPSSPP) + Teste Nativo + Monitor",
            trailing = {
                FilledTonalIconButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = KleeColors.Gpu.copy(alpha = 0.18f),
                        contentColor = KleeColors.Gpu
                    )
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar")
                }
            }
        )

        if (!systemInfo.root) {
            RootWarningCard()
        }

        if (probeResult != null) {
            AdrenoToolsProbeCard(probeResult)
        }

        if (lastVerification != null) {
            VulkanVerificationCard(
                result = lastVerification,
                busy = busy,
                onRecheck = onRecheckVerification
            )
        }

        // How PPSSPP libadrenotools Engine works card
        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            accentBorder = KleeColors.Primary.copy(alpha = 0.45f)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = KleeColors.Primary, modifier = Modifier.size(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Motor AdrenoTools (Método PPSSPP · Sem Zygisk)", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Use 'Testar (AdrenoTools)' abaixo para validar seu PanVK .zip diretamente aqui dentro (exibe GPU, Vulkan API 1.4.x e extensões). Depois, na aba Apps, toque em 'Injetar AdrenoTools' no app desejado (ex.: Vulkan Caps Viewer): o KLEE faz backup do APK original, injeta libadrenotools + hooks de Vulkan (mantendo OpenGL ES/MEOW 100% intacto) e permite trocar de driver instantaneamente ou restaurar o APK original com 1 toque.",
                        color = KleeColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // System Vulkan HAL & GraphicsEnvironment Status Card
        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            accentBorder = (if (systemInfo.overlayMounted) KleeColors.Success else KleeColors.Gpu).copy(alpha = 0.45f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.Layers, contentDescription = null, tint = KleeColors.Gpu, modifier = Modifier.size(20.dp))
                    Text(
                        "Vulkan do Sistema & Fallback",
                        color = KleeColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(
                    text = if (systemInfo.overlayMounted) "Carrier Ativo" else "Padrão Mali",
                    color = if (systemInfo.overlayMounted) KleeColors.Warning else KleeColors.Primary
                )
            }

            VkInfoRow("Driver HAL", systemInfo.vulkanFullPath.ifBlank { "Não detectado" })
            VkInfoRow("ro.hardware.vulkan", systemInfo.roHardwareVulkan.ifBlank { "mali (padrão)" })
            VkInfoRow(
                "Carrier (ro.gfx.driver.0)",
                if (systemInfo.carrierPackage.isNotBlank()) {
                    val fqTag = if (systemInfo.carrierForceQueryable) "fq=✓" else "fq=✗"
                    "${systemInfo.carrierPackage} [${systemInfo.carrierMode} · $fqTag]"
                } else "Não detectado"
            )
            VkInfoRow("ANGLE (GLES -> Vulkan)", if (systemInfo.angleAvailable) "Disponível" else "Não detectado")
            if (systemInfo.optInApps.isNotBlank()) {
                VkInfoRow("Apps Opt-In", systemInfo.optInApps)
            }
            if (systemInfo.overlayMounted) {
                val activeDrv = drivers.firstOrNull { it.id == systemInfo.activeDriverId }?.name ?: systemInfo.activeDriverId
                VkInfoRow("Driver Ativo", "$activeDrv (${systemInfo.activePackage})")
            }

            OutlinedButton(
                onClick = onRestoreDefault,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Limpar Staging GraphicsEnvironment (Mali)")
            }
        }

        // Import Button
        Button(
            onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = KleeColors.Gpu, contentColor = Color.White),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Importar Driver Vulkan (.zip)", fontWeight = FontWeight.Bold)
        }

        // Installed Drivers List
        Text("Drivers Importados (${drivers.size})", color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelLarge)

        if (drivers.isEmpty()) {
            KleeCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.FolderZip, contentDescription = null, tint = KleeColors.Gpu)
                    Text(
                        "Nenhum driver .zip importado ainda. Toque em \"Importar Driver Vulkan (.zip)\" para instalar um pacote PanVK / Vulkan ARM64 (compatível com meta.json do AdrenoTools) e teste diretamente com libadrenotools.",
                        color = KleeColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            drivers.forEach { drv ->
                val sizeMb = drv.sizeBytes / (1024.0 * 1024.0)
                val isActive = systemInfo.overlayMounted && systemInfo.activeDriverId == drv.id
                val probedOk = probeResult?.driverId == drv.id && probeResult.success
                KleeCard(
                    modifier = Modifier.fillMaxWidth(),
                    accentBorder = when {
                        probedOk -> KleeColors.Success
                        isActive -> KleeColors.Warning
                        else -> KleeColors.Gpu.copy(alpha = 0.3f)
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    drv.name,
                                    color = KleeColors.TextPrimary,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (probedOk) StatusPill("VK ${probeResult?.apiVersion}", KleeColors.Success)
                                else if (isActive) StatusPill("Ativo", KleeColors.Warning)
                            }
                            Text(
                                "${drv.version} · ${drv.author} · ${"%.2f".format(sizeMb)} MB",
                                color = KleeColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(
                            onClick = { onDeleteDriver(drv.id) },
                            enabled = !busy
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Excluir", tint = KleeColors.Danger)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(KleeColors.Surface)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            drv.primaryLibName,
                            color = KleeColors.Gpu,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace
                        )
                        if (drv.extraLibs.isNotEmpty()) {
                            Text(
                                "+${drv.extraLibs.size} lib(s)",
                                color = KleeColors.TextMuted,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    if (drv.description.isNotBlank()) {
                        Text(drv.description, color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    }

                    FilledTonalButton(
                        onClick = { onProbeDriver(drv) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = KleeColors.Primary.copy(alpha = 0.16f),
                            contentColor = KleeColors.Primary
                        )
                    ) {
                        Icon(Icons.Rounded.Science, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Testar Driver Agora (AdrenoTools PPSSPP)", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AdrenoToolsProbeCard(probe: AdrenoToolsProbeResult) {
    val accent = if (probe.success) KleeColors.Success else KleeColors.Danger
    KleeCard(
        modifier = Modifier.fillMaxWidth(),
        accentBorder = accent.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (probe.success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        "Teste Nativo AdrenoTools (PPSSPP)",
                        color = KleeColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        probe.driverLabel,
                        color = KleeColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            StatusPill(
                text = if (probe.success) "VK ${probe.apiVersion} ✓" else "FALHOU",
                color = accent
            )
        }

        if (probe.success) {
            VkInfoRow("GPU Detectada", probe.deviceName.ifBlank { "Desconhecida" })
            VkInfoRow("Vulkan API", probe.apiVersion)
            if (probe.driverName.isNotBlank()) {
                VkInfoRow("Driver", probe.driverName)
            }
            if (probe.driverInfo.isNotBlank()) {
                VkInfoRow("Versão / Info", probe.driverInfo)
            }
            VkInfoRow("Extensões / Tempo", "${probe.extensionCount} extensões · ${probe.elapsedMs} ms")
        } else {
            Text(
                probe.error.ifBlank { "Falha desconhecida ao carregar driver via libadrenotools." },
                color = KleeColors.Danger,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun VulkanVerificationCard(
    result: VulkanVerificationResult,
    busy: Boolean,
    onRecheck: () -> Unit
) {
    val (accent, pillText, icon) = when (result.status) {
        VulkanLoadStatus.LOADED_CUSTOM -> Triple(KleeColors.Success, "CARREGADO ✓", Icons.Rounded.CheckCircle)
        VulkanLoadStatus.LOADED_STOCK -> Triple(KleeColors.Warning, "MALI PADRÃO", Icons.Rounded.WarningAmber)
        VulkanLoadStatus.ERROR_DETECTED -> Triple(KleeColors.Danger, "ERRO DETECTADO", Icons.Rounded.ErrorOutline)
        VulkanLoadStatus.VULKAN_NOT_MAPPED -> Triple(KleeColors.Primary, "VULKAN INATIVO", Icons.Rounded.Radar)
        VulkanLoadStatus.APP_NOT_RUNNING -> Triple(KleeColors.TextMuted, "APP FECHADO", Icons.Rounded.Radar)
        VulkanLoadStatus.CHECKING -> Triple(KleeColors.Gpu, "VERIFICANDO...", Icons.Rounded.Radar)
    }

    var showMaps by remember(result.timestamp, result.status) {
        mutableStateOf(result.matchedMaps.isNotEmpty() && result.status != VulkanLoadStatus.CHECKING)
    }
    var showLogcat by remember(result.timestamp, result.status) {
        mutableStateOf(
            result.relevantLogcat.isNotEmpty() &&
                (result.status == VulkanLoadStatus.ERROR_DETECTED || result.status == VulkanLoadStatus.LOADED_STOCK)
        )
    }

    KleeCard(
        modifier = Modifier.fillMaxWidth(),
        accentBorder = accent.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (result.status == VulkanLoadStatus.CHECKING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                } else {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(
                        "Monitor Vulkan · ${result.appLabel}",
                        color = KleeColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${result.driverName} · ${result.timestamp}",
                        color = KleeColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            StatusPill(text = pillText, color = accent)
        }

        Text(
            result.summary,
            color = KleeColors.TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusPill(
                text = if (result.pid != null) "PID ${result.pid}" else "Sem PID",
                color = if (result.pid != null) KleeColors.Primary else KleeColors.TextMuted
            )
            StatusPill(
                text = if (result.unloadedBuiltin) "Unload Mali: SIM" else "Unload Mali: NÃO",
                color = if (result.unloadedBuiltin) KleeColors.Success else KleeColors.TextMuted
            )
            StatusPill(
                text = if (result.angleLoaded) "ANGLE: Ativo" else "ANGLE: Inativo",
                color = if (result.angleLoaded) KleeColors.Monitor else KleeColors.TextMuted
            )
            if (result.expectedInode.isNotBlank()) {
                StatusPill(
                    text = "Inode: ${result.expectedInode}",
                    color = KleeColors.Gpu
                )
            }
        }

        if (result.matchedMaps.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Bibliotecas em /proc/${result.pid ?: "?"}/maps (${result.matchedMaps.size})",
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                TextButton(
                    onClick = { showMaps = !showMaps },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(if (showMaps) "Ocultar" else "Mostrar")
                }
            }
            if (showMaps) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(KleeColors.Surface)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    result.matchedMaps.take(8).forEach { line ->
                        val lineColor = when {
                            line.startsWith("[ADRENOTOOLS") || line.startsWith("[CUSTOM") -> KleeColors.Success
                            line.startsWith("[ANGLE]") -> KleeColors.Monitor
                            line.startsWith("[STOCK HAL]") || line.startsWith("[HAL]") -> KleeColors.Warning
                            else -> KleeColors.TextSecondary
                        }
                        Text(
                            text = line,
                            color = lineColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (result.matchedMaps.size > 8) {
                        Text(
                            "+${result.matchedMaps.size - 8} entradas (veja todas no log exportado)",
                            color = KleeColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        if (result.relevantLogcat.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Logcat Vulkan/Linker (${result.relevantLogcat.size})",
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
                TextButton(
                    onClick = { showLogcat = !showLogcat },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(if (showLogcat) "Ocultar" else "Mostrar")
                }
            }
            if (showLogcat) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(KleeColors.Surface)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    result.relevantLogcat.takeLast(8).forEach { line ->
                        val low = line.lowercase()
                        val logColor = when {
                            low.contains("error") || low.contains("failed") || low.contains("denied") || low.contains("fatal") || low.contains("sigsegv") -> KleeColors.Danger
                            low.contains("warn") -> KleeColors.Warning
                            else -> KleeColors.TextSecondary
                        }
                        Text(
                            text = line,
                            color = logColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onRecheck,
            enabled = !busy && result.status != VulkanLoadStatus.CHECKING,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Verificar Novamente Agora")
        }
    }
}

@Composable
private fun VkInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            color = KleeColors.TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

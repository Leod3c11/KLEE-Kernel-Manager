package dev.klee.kernelmanager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.klee.kernelmanager.SystemVulkanInfo
import dev.klee.kernelmanager.VulkanDriverPackage
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
    busy: Boolean,
    onRefresh: () -> Unit,
    onImportZip: (Uri) -> Unit,
    onDeleteDriver: (String) -> Unit,
    onRestoreDefault: () -> Unit,
    onSelectAutoUnmount: (Int) -> Unit
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
            subtitle = "PanVK / AdrenoTools .zip · Injeção isolada por jogo + ANGLE",
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

        // System Vulkan HAL & Overlay Status Card
        KleeCard(
            modifier = Modifier.fillMaxWidth(),
            accentBorder = (if (systemInfo.overlayMounted) KleeColors.Warning else KleeColors.Gpu).copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Layers, contentDescription = null, tint = KleeColors.Gpu, modifier = Modifier.size(20.dp))
                    Text("HAL Vulkan do Sistema", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                }
                StatusPill(
                    text = if (systemInfo.overlayMounted) "Custom Montado" else "Padrão Mali",
                    color = if (systemInfo.overlayMounted) KleeColors.Warning else KleeColors.Success
                )
            }

            VkInfoRow("Driver HAL", systemInfo.vulkanFullPath.ifBlank { "Não detectado" })
            if (systemInfo.isSymlink) {
                VkInfoRow("Alvo Symlink", systemInfo.symlinkTarget.ifBlank { "--" })
            }
            VkInfoRow("ANGLE (GLES -> Vulkan)", if (systemInfo.angleAvailable) "Disponível" else "Não detectado")
            if (systemInfo.overlayMounted) {
                val activeDrv = drivers.firstOrNull { it.id == systemInfo.activeDriverId }?.name ?: systemInfo.activeDriverId
                VkInfoRow("Driver Ativo", "$activeDrv (${systemInfo.activePackage})")
            }

            Text(
                "Segurança: o KLEE monta um overlay isolado em ${systemInfo.vendorHwDir} mantendo libGLES_mali.so e todos os outros HALs intactos, com contexto SELinux same_process_hal_file.",
                color = KleeColors.TextMuted,
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedButton(
                onClick = onRestoreDefault,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Restaurar Driver Padrão do Sistema (Unmount)")
            }
        }

        // Auto-unmount timing safety card
        KleeCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = KleeColors.Primary, modifier = Modifier.size(18.dp))
                Column {
                    Text("Auto-Desmontagem Segura após Lançar Jogo", color = KleeColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Depois que o jogo carrega a .so na RAM, o KLEE desmonta o overlay para o resto do Android continuar usando o driver Mali original.",
                        color = KleeColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(5 to "5s", 8 to "8s", 12 to "12s", 0 to "Ao sair").forEach { (sec, label) ->
                    FilterChip(
                        selected = autoUnmountSec == sec,
                        onClick = { onSelectAutoUnmount(sec) },
                        label = { Text(label) }
                    )
                }
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
                        "Nenhum driver .zip importado ainda. Toque em \"Importar Driver Vulkan (.zip)\" para instalar um pacote PanVK / Vulkan ARM64 (compatível com meta.json do AdrenoTools) e depois vincule ao jogo na aba Apps.",
                        color = KleeColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            drivers.forEach { drv ->
                val sizeMb = drv.sizeBytes / (1024.0 * 1024.0)
                val isActive = systemInfo.overlayMounted && systemInfo.activeDriverId == drv.id
                KleeCard(
                    modifier = Modifier.fillMaxWidth(),
                    accentBorder = if (isActive) KleeColors.Warning else KleeColors.Gpu.copy(alpha = 0.3f)
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
                                if (isActive) StatusPill("Montado", KleeColors.Warning)
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
                }
            }
        }

        Spacer(Modifier.height(12.dp))
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

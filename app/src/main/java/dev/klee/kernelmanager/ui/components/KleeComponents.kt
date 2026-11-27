package dev.klee.kernelmanager.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.klee.kernelmanager.UiFeedback
import dev.klee.kernelmanager.ui.theme.KleeColors

@Composable
fun KleeCard(
    modifier: Modifier = Modifier,
    accentBorder: Color = KleeColors.Border,
    containerColor: Color = KleeColors.Card,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, accentBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, accentBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = KleeColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun RootWarningCard() {
    KleeCard(
        modifier = Modifier.fillMaxWidth(),
        accentBorder = KleeColors.Danger.copy(alpha = 0.5f),
        containerColor = Color(0xFF241016)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = KleeColors.Danger,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Acesso Root Indisponível",
                    color = KleeColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Autorize o KLEE Kernel Manager no KernelSU / Magisk para ler e ajustar parâmetros do kernel.",
                    color = KleeColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun ActionFeedbackBanner(
    feedback: UiFeedback,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (feedback.isError) KleeColors.Danger else KleeColors.Success
    val bg = if (feedback.isError) Color(0xFF281116) else Color(0xFF0E241B)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (feedback.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = feedback.text,
            color = KleeColors.TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Fechar",
                tint = KleeColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun ProgressMeterBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    heightDp: Int = 7
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(RoundedCornerShape(50))
            .background(KleeColors.Border.copy(alpha = 0.65f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        listOf(color.copy(alpha = 0.7f), color)
                    )
                )
        )
    }
}

@Composable
fun SelectorBox(
    label: String,
    selectedText: String,
    options: List<String>,
    enabled: Boolean,
    accent: Color = KleeColors.Primary,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(KleeColors.CardElevated)
                    .border(
                        1.dp,
                        if (enabled) accent.copy(alpha = 0.45f) else KleeColors.Border,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable(enabled = enabled && options.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedText.ifBlank { "Indisponível" },
                    color = if (enabled) KleeColors.TextPrimary else KleeColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    tint = if (enabled) accent else KleeColors.TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = KleeColors.CardElevated
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                color = if (option == selectedText) accent else KleeColors.TextPrimary,
                                fontWeight = if (option == selectedText) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LiveLineChart(
    title: String,
    unit: String,
    values: List<Float>,
    accent: Color,
    minBound: Float = 0f,
    maxBound: Float = 100f,
    modifier: Modifier = Modifier
) {
    val current = values.lastOrNull() ?: 0f
    val peak = values.maxOrNull() ?: 0f
    val avg = if (values.isNotEmpty()) values.average().toFloat() else 0f

    KleeCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(title, color = KleeColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "${"%.1f".format(current)} $unit",
                    color = accent,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Pico: ${"%.1f".format(peak)} $unit", color = KleeColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text("Média: ${"%.1f".format(avg)} $unit", color = KleeColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }

        val effectiveMax = maxOf(maxBound, peak * 1.1f, minBound + 1f)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(KleeColors.Surface)
                .padding(horizontal = 6.dp, vertical = 10.dp)
        ) {
            val w = size.width
            val h = size.height

            // Reference horizontal grid lines
            for (i in 0..3) {
                val y = h * (i / 3f)
                drawLine(
                    color = KleeColors.Border.copy(alpha = 0.55f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }

            if (values.size >= 2) {
                val stepX = w / (values.size - 1).coerceAtLeast(1)
                val linePath = Path()
                val fillPath = Path()

                values.forEachIndexed { idx, value ->
                    val norm = ((value - minBound) / (effectiveMax - minBound)).coerceIn(0f, 1f)
                    val x = idx * stepX
                    val y = h - (norm * h)
                    if (idx == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, h)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                    if (idx == values.lastIndex) {
                        fillPath.lineTo(x, h)
                        fillPath.close()
                    }
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f))
                    )
                )
                drawPath(
                    path = linePath,
                    color = accent,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                val lastNorm = ((values.last() - minBound) / (effectiveMax - minBound)).coerceIn(0f, 1f)
                val lastPoint = Offset(w, h - (lastNorm * h))
                drawCircle(color = accent.copy(alpha = 0.3f), radius = 7.dp.toPx(), center = lastPoint)
                drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = lastPoint)
            }
        }
    }
}

fun formatUptime(seconds: Long): String {
    if (seconds <= 0L) return "--"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val mins = (seconds % 3600) / 60
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        append("${mins}m")
    }
}

fun tempSeverityColor(tempC: Double): Color = when {
    tempC >= 60.0 -> KleeColors.Danger
    tempC >= 45.0 -> KleeColors.Thermal
    tempC >= 36.0 -> KleeColors.Warning
    else -> KleeColors.Power
}

fun governorDescription(gov: String): String = when (gov.lowercase()) {
    "sugov_ext" -> "Governor nativo MediaTek MT6899 com integração EAS/FPSGO (recomendado)."
    "schedutil" -> "Dinâmico padrão integrado ao escalonador Linux."
    "walt" -> "Escalonador avançado de carga em tempo real (baixa latência)."
    "performance" -> "Mantém a frequência travada no limite máximo definido."
    "powersave" -> "Mantém a frequência travada no limite mínimo definido."
    "conservative" -> "Aumenta clocks gradualmente para priorizar economia e menos calor."
    "ondemand" -> "Escala rapidamente para o pico sob demanda de carga."
    "userspace" -> "Permite controle manual de frequência por espaço de usuário."
    "dummy" -> "Modo pass-through MTK: DVFS controlado nativamente pelo GED/gpufreqv2."
    "simple_ondemand" -> "Escalonamento dinâmico padrão de devfreq."
    else -> "Governor disponibilizado pelo driver do kernel."
}

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.leonardo.mt6899.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.mt6899.core.Kind
import dev.leonardo.mt6899.core.NodeSpec
import dev.leonardo.mt6899.core.Risk
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
fun KV(k: String, v: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(v, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun RiskTag(risk: Risk) {
    when (risk) {
        Risk.SAFE -> {}
        Risk.CAUTION -> Text("atenção", color = Amber, fontSize = 12.sp)
        Risk.DANGER -> Text("risco", color = Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Continuar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Slider discreto sobre uma lista de frequências; só aplica ao soltar. */
@Composable
fun FreqSlider(
    label: String,
    freqs: List<Long>,
    value: Long?,
    fmt: (Long) -> String = { mhz(it) },
    enabled: Boolean = true,
    onCommit: (Long) -> Unit
) {
    if (freqs.isEmpty()) return

    fun idxOf(v: Long?): Float {
        if (v == null) return 0f
        var best = 0
        var d = Long.MAX_VALUE
        freqs.forEachIndexed { i, f ->
            val x = abs(f - v)
            if (x < d) {
                d = x
                best = i
            }
        }
        return best.toFloat()
    }

    var pos by remember(freqs) { mutableFloatStateOf(idxOf(value)) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) pos = idxOf(value) }
    val shown = freqs[pos.roundToInt().coerceIn(0, freqs.size - 1)]
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(fmt(shown), fontWeight = FontWeight.Bold)
        }
        Slider(
            value = pos,
            onValueChange = { dragging = true; pos = it },
            onValueChangeFinished = {
                dragging = false
                onCommit(freqs[pos.roundToInt().coerceIn(0, freqs.size - 1)])
            },
            valueRange = 0f..(freqs.size - 1).toFloat().coerceAtLeast(1f),
            steps = (freqs.size - 2).coerceAtLeast(0),
            enabled = enabled
        )
    }
}

@Composable
fun ChipChoice(
    label: String?,
    options: List<String>,
    selected: String?,
    enabled: Boolean = true,
    display: (String) -> String = { it },
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { o ->
                FilterChip(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    label = { Text(display(o)) },
                    enabled = enabled
                )
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, hint: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null) Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun Stepper(label: String, value: Int, unit: String, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
        Text("  $value$unit  ", fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("+") }
    }
}

/** Editor genérico de um nó do kernel (valor lido, campo ou chips, botão Aplicar). */
@Composable
fun NodeRow(spec: NodeSpec, value: String?, onApply: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value ?: "") }
    var pending by remember { mutableStateOf<String?>(null) }
    if (!spec.editable) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(spec.label, fontWeight = FontWeight.SemiBold)
            Hint(value?.takeIf { it.isNotBlank() } ?: "sem leitura")
            Hint(spec.path)
            Hint("Somente leitura · edição ainda não validada")
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(spec.label, Modifier.weight(1f))
            RiskTag(spec.risk)
        }
        if (spec.hint.isNotEmpty()) Hint(spec.hint)
        if (spec.kind == Kind.CHOICE && spec.choices.isNotEmpty()) {
            ChipChoice(
                label = null,
                options = spec.choices,
                selected = value,
                onSelect = { c ->
                    if (spec.group == "Térmica" || spec.risk == Risk.DANGER) pending = c
                    else onApply(c)
                }
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (spec.group == "Térmica" || spec.risk == Risk.DANGER) pending = text.trim()
                        else onApply(text.trim())
                    },
                    enabled = text.isNotBlank() && text != value
                ) { Text("Aplicar") }
            }
        }
    }
    val p = pending
    if (p != null) {
        ConfirmDialog(
            title = "Alterar ${spec.label}?",
            text = buildString {
                append("Valor atual: ${value ?: "indisponível"}\n")
                append("Novo valor: $p\n")
                append("Interface: ${spec.path}\n")
                append(if (spec.group == "Térmica") "Alteração térmica: pode mudar a resposta de desempenho e temperatura. " else "Este ajuste é marcado como de risco. ")
                append(spec.hint.ifEmpty { "Sem detalhes adicionais." })
            },
            onConfirm = { onApply(p) },
            onDismiss = { pending = null }
        )
    }
}

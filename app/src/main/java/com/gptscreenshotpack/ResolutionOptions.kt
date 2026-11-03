package com.gptscreenshotpack

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gptscreenshotpack.core.PackSettings
import com.gptscreenshotpack.core.ResolutionMode

internal fun resolutionLabel(mode: ResolutionMode, scaleA: Int, scaleB: Int): String = when (mode) {
    ResolutionMode.ORIGINAL -> "原始分辨率"
    ResolutionMode.REDUCED -> "按缩放比例 A · $scaleA%"
    ResolutionMode.SCALE_B -> "按缩放比例 B · $scaleB%"
    ResolutionMode.WIDTH -> "按指定宽度（保持比例）"
}

internal fun PackSettings.resolutionSummary(): String = when (resolutionMode) {
    ResolutionMode.WIDTH -> "宽度 $targetWidth px · 高度保持比例"
    else -> resolutionLabel(resolutionMode, scalePercent, scalePercentB)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResolutionOptions(mode: ResolutionMode, reducedScalePercent: Int, scalePercentB: Int,
    onChange: (ResolutionMode) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
            OutlinedTextField(value = resolutionLabel(mode, reducedScalePercent, scalePercentB),
                onValueChange = {}, readOnly = true, enabled = enabled, singleLine = true,
                label = { Text("分辨率处理方式") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled))
            ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                ResolutionMode.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(resolutionLabel(option, reducedScalePercent, scalePercentB)) },
                        onClick = { expanded = false; onChange(option) })
                }
            }
        }
        Text(when (mode) {
            ResolutionMode.ORIGINAL -> "保留更多细节；GPT 可能产生更多图像分割。"
            ResolutionMode.REDUCED -> if (reducedScalePercent == 100) "A 当前为 100%，输出尺寸与原图相同。" else "使用比例 A，提高识别速度，减少图像分割。"
            ResolutionMode.SCALE_B -> if (scalePercentB == 100) "B 当前为 100%，输出尺寸与原图相同。" else "使用比例 B，适合中等压缩需求。"
            ResolutionMode.WIDTH -> "按设定宽度缩放，高度自动保持原图比例。"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TargetWidthField(value: String, onChange: (String) -> Unit, enabled: Boolean = true,
    error: String? = null) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text("指定宽度") },
        suffix = { Text("px") }, singleLine = true, enabled = enabled, isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), modifier = Modifier.fillMaxWidth())
}

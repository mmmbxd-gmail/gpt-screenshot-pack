package com.gptscreenshotpack

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gptscreenshotpack.core.ResolutionMode

@Composable
internal fun ResolutionOptions(mode: ResolutionMode, reducedScalePercent: Int,
    onChange: (ResolutionMode) -> Unit, enabled: Boolean = true) {
    Column(Modifier.selectableGroup()) {
        ResolutionMode.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().selectable(selected = mode == option, enabled = enabled,
                role = Role.RadioButton, onClick = { onChange(option) }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == option, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(if (option == ResolutionMode.ORIGINAL) "原始分辨率" else "降低分辨率")
                    Text(if (option == ResolutionMode.ORIGINAL) "保留更多图像细节，但 GPT 处理时可能产生更多图像分割。"
                        else "提高识别速度，减少图像分割。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("降低分辨率使用主界面比例：$reducedScalePercent%" +
            if (reducedScalePercent == 100) "（当前比例与原尺寸相同）" else "", style = MaterialTheme.typography.bodySmall)
    }
}

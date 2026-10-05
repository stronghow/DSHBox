package com.dshbox.app.ui.files.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R

/**
 * 兜底信息卡：文件元信息 + 出口——安装 / 外部打开 / 外部编辑 / 分享 /
 * 导出 / 按文本打开 / 按 Hex 打开 / 复制路径（[补丁] 后两者为本轮新增）。作为不可内建渲染类型（PDF/压缩包/Office/音视频）
 * 的主视图，也可作为其他类型的信息卡弹层复用。
 *
 * 风险确认由外壳在回调前完成；本组件纯展示。
 */
@Composable
internal fun FallbackPanel(
    name: String,
    logicalPath: String,
    physicalPath: String,
    sizeText: String,
    permissionText: String,
    typeText: String,
    modifiedText: String,
    onExternalOpen: (() -> Unit)?,
    onExternalEdit: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onExport: (() -> Unit)?,
    onOpenAsText: (() -> Unit)?,
    onOpenAsHex: (() -> Unit)?,
    /** [补丁] 该类型没有内建预览时，在卡片最上方明确写「无法预览」，不留白。 */
    previewUnavailable: Boolean = false,
    /** [补丁] 「安装」（仅安装包类文件提供；null 则不出现该出口）。 */
    onInstall: (() -> Unit)? = null,
    /** [补丁] 「复制路径」。 */
    onCopyPath: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        if (previewUnavailable) {
            Text(
                text = stringResource(R.string.files_preview_unavailable),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.files_preview_unavailable_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .padding(14.dp),
        ) {
            Text(
                text = name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            InfoRow(stringResource(R.string.files_info_kind), typeText)
            InfoRow(stringResource(R.string.files_info_size), sizeText)
            InfoRow(stringResource(R.string.files_info_permission), permissionText)
            InfoRow(stringResource(R.string.files_info_modified), modifiedText)
            InfoRow(stringResource(R.string.files_info_logical_path), logicalPath)
            InfoRow(stringResource(R.string.files_info_physical_path), physicalPath)
        }
        Spacer(Modifier.height(12.dp))
        val exits = buildList {
            onInstall?.let { add(stringResource(R.string.files_exit_install) to it) }
            onExternalOpen?.let { add(stringResource(R.string.files_exit_external_open) to it) }
            onExternalEdit?.let { add(stringResource(R.string.files_exit_external_edit) to it) }
            onShare?.let { add(stringResource(R.string.files_exit_share) to it) }
            onExport?.let { add(stringResource(R.string.files_exit_export) to it) }
            onOpenAsText?.let { add(stringResource(R.string.files_exit_open_as_text) to it) }
            onOpenAsHex?.let { add(stringResource(R.string.files_exit_open_as_hex) to it) }
            onCopyPath?.let { add(stringResource(R.string.files_exit_copy_path) to it) }
        }
        // 两列出口按钮
        for (i in exits.indices step 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExitButton(exits[i].first, exits[i].second, Modifier.weight(1f))
                if (i + 1 < exits.size) {
                    ExitButton(exits[i + 1].first, exits[i + 1].second, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExitButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    }
}

package dshbox.adapter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.TierCeiling
import interlock.relay.core.runtime.RelayContainer
import interlock.relay.core.settings.RelaySettings
import interlock.relay.core.settings.SettingsStore
import interlock.relay.core.exec.direct.IntentExtra
import interlock.relay.core.exec.direct.IntentTemplateCatalog
import interlock.relay.core.exec.direct.UserIntentTemplate
import interlock.relay.core.exec.direct.UserSettingsPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 一项配置里三块可展开内容的编号。三块互不牵连：展开「改了会怎样」不会带出例子，反之亦然。 */
enum class RulesSection { DETAIL, EXAMPLE, EDITOR }

/**
 * 规则页的展开记忆：哪些组打开着、每一项的三块内容各自是否展开、危险组的"先看一眼代价"确认过没有。
 *
 * 与 [PilotScreen] 里记住每页滚动位置的 `scrollByPage` 同一个理由：切到别的页时这一页整个从组合里
 * 摘掉，写在页内的 `remember` 活不过一次切页。把记忆交给外层 `remember`，滚动位置与展开状态就一起
 * 活到离开这一页为止。默认：只有常用组打开；每一项三块全收起 —— 第一眼只看到"这项管什么 + 当前值"。
 */
class RulesExpansionMemory {
    private val groups = mutableStateMapOf<RelaySettings.Group, Boolean>()
    private val sections = mutableStateMapOf<String, Boolean>()

    /** 危险组展开前的"先看一眼代价"是否已经确认。 */
    var dangerUnlocked by mutableStateOf(false)

    fun groupOpen(group: RelaySettings.Group): Boolean =
        groups[group] ?: (group == RelaySettings.Group.COMMON)

    fun setGroupOpen(group: RelaySettings.Group, open: Boolean) {
        groups[group] = open
    }

    fun open(specId: String, section: RulesSection): Boolean =
        sections[sectionKey(specId, section)] == true

    fun toggle(specId: String, section: RulesSection) {
        sections[sectionKey(specId, section)] = !open(specId, section)
    }

    private fun sectionKey(specId: String, section: RulesSection): String = "$specId#${section.name}"
}

/**
 * "规则与配置"页：整张页面由 [RelaySettings.all] 那张声明表渲染，不写死任何一项。
 *
 * 三条呈现纪律：
 * - 分组 + 一句话分组说明；高级与危险默认折叠，危险组展开前先看一屏说明。
 * - 每一项折叠态只有"标题 + 风险徽标 + 一句话作用 + 当前值"；长说明分成三块，用三个**明显可点**的
 *   文字开关按需铺开：「改了会怎样」（含 默认为什么这样 / 代价 / 影响范围）、「看例子」、「改这一项」
 *   （控件与恢复默认；危险项的子名单跟随它出现）。三块互不牵连，信息一句不少，只是不再同时占屏。
 * - 危险项要求**长按 3 秒**才写入：比对话框更能让人停一下，而且确认句里复述的是
 *   effect 与 risk 的原文，不是"确定/取消"。
 *
 * 这一页不滚动：外层 [PilotScreen] 的 Column 已经带 verticalScroll，这里再套一层同轴滚动
 * 会在运行时崩。
 */
@Composable
fun RulesPage(
    state: PilotUiState,
    viewModel: PilotViewModel,
    open: (PilotDestination) -> Unit,
    expansion: RulesExpansionMemory,
) {
    // 改动回执：动词短语，人话。任何一次写入都从这里出一条。
    var receipt by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var dangerIntro by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var importOpen by remember { mutableStateOf(false) }

    val query = search.trim()
    // 危险项的确认方式：从状态里读，改完立刻反映到下一次弹框。
    // 取不到时用「长按 3 秒」——那正是 schema 里的默认值。
    val confirmMode = state.settingRows
        .firstOrNull { it.spec.id == RelaySettings.CONFIRM_MODE }
        ?.value ?: RelaySettings.CONFIRM_HOLD

    receipt?.let { line ->
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { receipt = null }) { Text("知道了") }
            }
        }
    }

    // ── 首屏"当前策略" ──
    PilotSection(title = "当前策略", summary = "这一句随你的配置实时变化。") {
        state.settingsOverview.forEach { line ->
            PilotNote(text = line)
        }
        PilotDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { exportText = viewModel.exportSettings() }) { Text("导出配置") }
            TextButton(onClick = {
                importOpen = true
            }) { Text("导入配置") }
        }
        PilotDivider()
        PilotActionRow(
            title = "全部恢复默认",
            onClick = {
                val changed = viewModel.resetAllSettings()
                receipt = if (changed.isEmpty()) "没有需要恢复的项，全部已是默认值" else "已恢复默认：${changed.joinToString("、")}"
            },
        )
    }

    OutlinedTextField(
        value = search,
        onValueChange = { search = it },
        label = { Text("搜索配置项（按中文名或关键词）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    RelaySettings.Group.entries.forEach { group ->
        val rows = state.settingRows
            .filter { it.spec.group == group }
            .filter { row -> matches(row, query) }
        // 搜索时把没有命中的组整组隐掉；不写早期 return，避免在可组合 lambda 里提前返回。
        if (query.isEmpty() || rows.isNotEmpty()) {
            val expanded = expansion.groupOpen(group) || query.isNotEmpty()
            GroupCard(
                group = group,
                rows = rows,
                expanded = expanded,
                expansion = expansion,
                onToggle = {
                    if (expanded) {
                        expansion.setGroupOpen(group, false)
                    } else if (group == RelaySettings.Group.DANGER && !expansion.dangerUnlocked) {
                        dangerIntro = true
                    } else {
                        expansion.setGroupOpen(group, true)
                    }
                },
                onResetGroup = {
                    val changed = viewModel.resetSettingsGroup(group)
                    receipt = if (changed.isEmpty()) "本组已经是默认值" else "已恢复默认：${changed.joinToString("、")}"
                },
                onApply = { id, encoded, phrase ->
                    val error = viewModel.setSetting(id, encoded)
                    receipt = error?.let { "没改成：$it" } ?: phrase
                },
                onReset = { id ->
                    val changed = viewModel.resetSetting(id)
                    receipt = if (changed.isEmpty()) "这一项已经是默认值" else "已恢复默认：${changed.joinToString("、")}"
                },
                onOpenLink = open,
                captionRows = state.rows,
                ceilingOverridesJson = { viewModel.ceilingOverridesJson() },
                effectiveCeiling = { viewModel.effectiveCeiling(it) },
                confirmMode = confirmMode,
            )
        }
    }

    if (dangerIntro) {
        AlertDialog(
            onDismissRequest = { dangerIntro = false },
            title = { Text("危险设置：先看一眼代价") },
            text = {
                Column {
                    Text(RelaySettings.Group.DANGER.summary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "这里的每一项都对应一道保护你的判据。关掉它们不会让助手变坏，" +
                            "但它被提示词注入时，你能依靠的东西就少一道。改任何一项都要长按 3 秒，" +
                            "改动会写进授权记录目录里的配置审计。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    expansion.dangerUnlocked = true
                    dangerIntro = false
                    expansion.setGroupOpen(RelaySettings.Group.DANGER, true)
                }) { Text("我明白，展开危险设置") }
            },
            dismissButton = {
                TextButton(onClick = { dangerIntro = false }) { Text("先不看") }
            },
        )
    }

    exportText?.let { text ->
        AlertDialog(
            onDismissRequest = { exportText = null },
            title = { Text("导出配置") },
            text = {
                Column {
                    Text("下面这段文本可以复制保存，或粘到另一台设备上导入。只包含你改过的项。")
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { exportText = null }) { Text("关闭") } },
        )
    }

    if (importOpen) {
        ImportDialog(
            viewModel = viewModel,
            onClose = { importOpen = false },
            onReceipt = { receipt = it },
        )
    }
}

@Composable
private fun GroupCard(
    group: RelaySettings.Group,
    rows: List<RelayContainer.SettingRow>,
    expanded: Boolean,
    expansion: RulesExpansionMemory,
    onToggle: () -> Unit,
    onResetGroup: () -> Unit,
    onApply: (String, String, String) -> Unit,
    onReset: (String) -> Unit,
    onOpenLink: (PilotDestination) -> Unit,
    captionRows: List<RelayContainer.CapabilityRow>,
    ceilingOverridesJson: () -> String,
    effectiveCeiling: (CapabilityDescriptor) -> String,
    confirmMode: String,
) {
    PilotSection(title = group.label + groupChangedSuffix(rows), summary = group.summary) {
        PilotActionRow(title = if (expanded) "收起这一组" else "展开这一组", onClick = onToggle)
        PilotDivider()
        PilotActionRow(title = "这一组恢复默认", onClick = onResetGroup)
        if (expanded) {
            rows.forEach { row ->
                PilotGroupDivider()
                SettingRowItem(
                    row = row,
                    expansion = expansion,
                    onApply = onApply,
                    onReset = onReset,
                    onOpenLink = onOpenLink,
                    captionRows = captionRows,
                    ceilingOverridesJson = ceilingOverridesJson,
                    effectiveCeiling = effectiveCeiling,
                    confirmMode = confirmMode,
                )
            }
        }
    }
}

private fun matches(row: RelayContainer.SettingRow, query: String): Boolean {
    if (query.isEmpty()) return true
    val s = row.spec
    return s.title.contains(query, ignoreCase = true) ||
        s.summary.contains(query, ignoreCase = true) ||
        s.id.contains(query, ignoreCase = true) ||
        s.scope.contains(query, ignoreCase = true)
}

private fun groupChangedSuffix(rows: List<RelayContainer.SettingRow>): String {
    val changed = rows.count { !it.isDefault }
    return if (changed == 0) "（都是默认值）" else "（已改 $changed 项）"
}

// ─────────────────────────── 一行配置 ───────────────────────────

/**
 * 一项配置：折叠态只有"标题 + 风险徽标 + 一句话作用 + 当前值"，三块长内容各自按需展开。
 *
 * 上一版把六段说明与控件一次性铺在展开区里，而唯一的开关是"点整行"（没有任何可见的展开控件）——
 * 想看一眼例子，代价是整页被撑满。这里把展开拆成三个**明显可点**的文字开关：
 * 「改了会怎样」（effect / whyDefault / 代价 / 影响范围）、「看例子」（preview）、
 * 「改这一项」（控件 + 恢复默认 + 危险项的子名单）。点哪块只铺哪块。
 *
 * [onApply] / [onReset] 收的是**带 id 的原始回调**，本函数在下面按 `spec.id` 绑成
 * [applySelf] / [resetSelf] 供本行自己的控件用；递归渲染子项时继续往下传原始回调，
 * 于是每一层绑的都是**它自己**的 id —— 危险项的子规则因此写进自己的键。
 */
@Composable
private fun SettingRowItem(
    row: RelayContainer.SettingRow,
    expansion: RulesExpansionMemory,
    onApply: (String, String, String) -> Unit,
    onReset: (String) -> Unit,
    onOpenLink: (PilotDestination) -> Unit,
    captionRows: List<RelayContainer.CapabilityRow>,
    ceilingOverridesJson: () -> String,
    effectiveCeiling: (CapabilityDescriptor) -> String,
    confirmMode: String,
) {
    val spec = row.spec
    // 本行的写入目标：永远是这一行的 spec.id。父项与子项各自绑各自，不共用一条。
    val applySelf: (String, String) -> Unit = { encoded, phrase -> onApply(spec.id, encoded, phrase) }
    val resetSelf: () -> Unit = { onReset(spec.id) }
    // 危险开关的待确认值：null = 没有待确认的改动。放在这里而不是控件回调里，
    // 是因为弹窗必须在组合上下文里渲染，不能从一个普通事件回调里调用 @Composable。
    var pendingSwitch by remember { mutableStateOf<Boolean?>(null) }
    // 当前值被折行截断时指个路：完整清单一律在「改这一项」里（集合型配置的编辑器逐条列出全部）。
    var valueClipped by remember(spec.id) { mutableStateOf(false) }

    // 未接线项：值改了不生效。默认根本不会显示（见 RelayContainer.settingsRows），
    // 只有用户在「显示未接线项（开发预览）」里显式打开才会走到这里 —— 那时顶上必须有一句
    // 醒目的黄色说明，不能让用户以为"我改了但程序没反应"。
    if (!spec.wired) UnwiredNotice()

    // ── 折叠态：标题与风险徽标同行（徽标不进正文），下面是一句话作用与当前值 ──
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = spec.title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = riskChip(spec),
            style = MaterialTheme.typography.labelSmall,
            color = riskColor(spec),
        )
    }
    Text(
        text = spec.summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 21.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        text = "当前：${row.display}" + if (row.isDefault) "（默认）" else "（已改）",
        style = currentValueStyle(spec),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 19.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { valueClipped = it.hasVisualOverflow },
        modifier = Modifier.padding(top = 6.dp),
    )
    if (valueClipped) {
        Text(
            text = "（值太长只显示开头，点下面「改这一项」看完整清单）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    // 只读 / 未接线的说明跟在当前值后面：它说的是"这一项现在算不算数"，不该藏在展开区里。
    row.readOnlyNote?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    // ── 三个展开开关：各自独立、默认收起、颜色与正文分得开 ──
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionToggle(
            label = "改了会怎样",
            open = expansion.open(spec.id, RulesSection.DETAIL),
            modifier = Modifier.weight(1f),
        ) { expansion.toggle(spec.id, RulesSection.DETAIL) }
        SectionToggle(
            label = "看例子",
            open = expansion.open(spec.id, RulesSection.EXAMPLE),
            modifier = Modifier.weight(1f),
        ) { expansion.toggle(spec.id, RulesSection.EXAMPLE) }
        val link = spec.type as? RelaySettings.Type.Link
        if (link != null) {
            // 链接型项没有可编辑的值，本身就是一个入口：这一颗直接进那一页，比展开再点一下少一步。
            SectionToggle(
                label = "打开设置",
                open = false,
                modifier = Modifier.weight(1f),
                arrow = "›",
            ) { onOpenLink(linkTarget(link.target)) }
        } else {
            SectionToggle(
                label = "改这一项",
                open = expansion.open(spec.id, RulesSection.EDITOR),
                modifier = Modifier.weight(1f),
            ) { expansion.toggle(spec.id, RulesSection.EDITOR) }
        }
    }

    if (expansion.open(spec.id, RulesSection.DETAIL)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DetailBlock("改了会怎样", spec.effect)
            DetailBlock("为什么默认这样", spec.whyDefault)
            DetailBlock("代价", "${spec.risk.label}风险 —— ${spec.riskText}")
            DetailBlock("影响范围", spec.scope)
        }
    }

    if (expansion.open(spec.id, RulesSection.EXAMPLE)) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            DetailBlock("试一下（不改配置）", spec.preview)
        }
    }

    if (expansion.open(spec.id, RulesSection.EDITOR)) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            when (val type = spec.type) {
                is RelaySettings.Type.Link -> {
                    Button(onClick = { onOpenLink(linkTarget(type.target)) }) { Text("打开这一页") }
                }

                is RelaySettings.Type.Switch -> {
                    val checked = row.value == "true"
                    Switch(
                        checked = checked,
                        enabled = spec.wired && !spec.locked,
                        onCheckedChange = { next ->
                            if (spec.holdToConfirm) {
                                pendingSwitch = next
                            } else {
                                applySelf(
                                    next.toString(),
                                    if (next) "已开启：${spec.title}" else "已关闭：${spec.title}",
                                )
                            }
                        },
                    )
                }

                is RelaySettings.Type.Choice -> {
                    ChoiceControl(
                        spec = spec,
                        current = row.value,
                        onApply = applySelf,
                        confirmMode = confirmMode,
                    )
                }

                is RelaySettings.Type.Number -> {
                    NumberControl(spec = spec, type = type, current = row.value, onApply = applySelf)
                }

                is RelaySettings.Type.Text -> {
                    TextControl(spec = spec, type = type, current = row.value, onApply = applySelf)
                }

                is RelaySettings.Type.TextList -> {
                    ListControl(spec = spec, current = row.value, onApply = applySelf)
                }

                is RelaySettings.Type.Document -> when (type.kind) {
                    RelaySettings.Type.Document.Kind.INTENT_TEMPLATES -> TemplateDocumentEditor(
                        spec = spec,
                        current = row.value,
                        onApply = applySelf,
                    )

                    RelaySettings.Type.Document.Kind.SETTINGS_PAGES -> PageDocumentEditor(
                        spec = spec,
                        current = row.value,
                        onApply = applySelf,
                    )

                    RelaySettings.Type.Document.Kind.CEILING_OVERRIDES -> CeilingEditor(
                        captionRows = captionRows,
                        overridesJson = ceilingOverridesJson,
                        effectiveCeiling = effectiveCeiling,
                        onApply = applySelf,
                        confirmMode = confirmMode,
                    )
                }
            }

            if (!row.isDefault && !spec.locked) {
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(onClick = resetSelf) { Text("恢复默认") }
            }

            // 危险项的子规则挂在父项"改这一项"展开之后（父项是"点系统授权框先问你"，
            // 子项是它的三张名单）。它们属于父项的设置，不跟着两块说明展开。
            // 这里刻意传**原始**回调（而不是 applySelf/resetSelf）：子项各自绑自己的 spec.id。
            row.children.forEach { child ->
                PilotDivider()
                SettingRowItem(
                    row = child,
                    expansion = expansion,
                    onApply = onApply,
                    onReset = onReset,
                    onOpenLink = onOpenLink,
                    captionRows = captionRows,
                    ceilingOverridesJson = ceilingOverridesJson,
                    effectiveCeiling = effectiveCeiling,
                    confirmMode = confirmMode,
                )
            }
        }
    }

    pendingSwitch?.let { next ->
        DangerConfirmDialog(
            spec = spec,
            confirmMode = confirmMode,
            onDismiss = { pendingSwitch = null },
            onConfirmed = {
                applySelf(next.toString(), if (next) "已开启：${spec.title}" else "已关闭：${spec.title}")
                pendingSwitch = null
            },
        )
    }
}

/**
 * 未接线项的醒目黄色说明。原文取自 [RelaySettings.UNWIRED_NOTICE]，界面不另编一句。
 *
 * 用固定的黄底深棕字而不是主题色：主题色在浅色/深色模式下含义不同，
 * 而"这条现在不生效"必须在任何主题下都读成同一件事。
 */
@Composable
private fun UnwiredNotice() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color(0xFFFFF3C4),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚠ ", color = Color(0xFF7A5B00), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = RelaySettings.UNWIRED_NOTICE,
                color = Color(0xFF5C4300),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 一个明显的展开开关：文字 + ▾/▴（或给链接型的 ›）。主色、居中、点击热区撑满所在列。
 *
 * 用 labelMedium 而不是正文字号：三个开关要在一行里放得下，也不该比正文更抢眼。
 */
@Composable
private fun SectionToggle(
    label: String,
    open: Boolean,
    modifier: Modifier = Modifier,
    arrow: String? = null,
    onClick: () -> Unit,
) {
    Text(
        text = label + " " + (arrow ?: if (open) "▴" else "▾"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

/**
 * 一段带小标题的说明：**小标题单独占一行**，正文另起一行、给足行高。
 *
 * 上一版把"标签："与正文塞进同一个 Row，长句一折行就与标签糊成一段 —— 那是"挤"的直接来源。
 */
@Composable
private fun DetailBlock(label: String, text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * 当前值的字形：集合型配置的值是包名 / 动作名这类机器串，用等宽字体呈现（仍然正常折行），
 * 免得一长串在卡片里糊成一片。其余类型保持正文字形。
 */
@Composable
private fun currentValueStyle(spec: RelaySettings.Spec) = when (spec.type) {
    is RelaySettings.Type.TextList ->
        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    else -> MaterialTheme.typography.bodySmall
}

private fun riskChip(spec: RelaySettings.Spec): String =
    if (spec.locked) "只读" else when (spec.risk) {
        RelaySettings.Risk.LOW -> "低风险"
        RelaySettings.Risk.MEDIUM -> "中风险"
        RelaySettings.Risk.HIGH -> "高风险"
        RelaySettings.Risk.CRITICAL -> "严重"
    }

@Composable
private fun riskColor(spec: RelaySettings.Spec) = when (spec.risk) {
    RelaySettings.Risk.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
    RelaySettings.Risk.MEDIUM -> MaterialTheme.colorScheme.onSurfaceVariant
    RelaySettings.Risk.HIGH -> MaterialTheme.colorScheme.error
    RelaySettings.Risk.CRITICAL -> MaterialTheme.colorScheme.error
}

private fun linkTarget(target: String): PilotDestination = when (target) {
    "surface" -> PilotDestination.Surface
    "privileged" -> PilotDestination.Privileged
    else -> PilotDestination.Capabilities
}

/**
 * 危险项的统一入口：先弹一屏复述 effect 与 risk，再要求一次**有意识的动作**才写入。
 * 普通项不经过这里 —— 每一项都弹框会让"尽快改完"变成"一路点确定"。
 *
 * 确认方式有两种（见 [RelaySettings.CONFIRM_MODE]）：
 * - `HOLD`（默认）：按住 3 秒，松开即取消。快，且几乎不可能误触。
 * - `PHRASE`：逐字输入「$CONFIRM_PHRASE」再点确认。给用 TalkBack 或不便长按的人一条路 ——
 *   没有长按能力不应该等于进不了设置。
 * 无论偏好设成哪种，确认框底部都留着**切换到另一种**的入口：偏好是习惯，不是牢笼。
 */
@Composable
private fun DangerConfirmDialog(
    spec: RelaySettings.Spec,
    confirmMode: String,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) {
    var open by remember { mutableStateOf(true) }
    if (open) {
        DangerConfirmDialogBody(
            spec = spec,
            initialTyping = confirmMode == RelaySettings.CONFIRM_TYPE,
            onDismiss = {
                open = false
                onDismiss()
            },
            onConfirmed = {
                open = false
                onConfirmed()
            },
        )
    }
}

@Composable
private fun DangerConfirmDialogBody(
    spec: RelaySettings.Spec,
    initialTyping: Boolean,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit,
) {
    var typing by remember { mutableStateOf(initialTyping) }
    var phrase by remember { mutableStateOf("") }
    val phraseOk = phrase.trim() == RelaySettings.CONFIRM_PHRASE
    AlertDialog(
        onDismissRequest = {
            onDismiss()
        },
        title = { Text("要改：${spec.title}") },
        text = {
            Column {
                Text(spec.effect)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${spec.risk.label}风险：${spec.riskText}",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (typing) {
                    Text(
                        "请逐字输入「${RelaySettings.CONFIRM_PHRASE}」再点确认（标点、空格、简繁都算不对）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = phrase,
                        onValueChange = { phrase = it },
                        label = { Text(RelaySettings.CONFIRM_PHRASE) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "按住下面的按钮 3 秒才会生效，松开即取消。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (typing) {
                Button(onClick = { onConfirmed() }, enabled = phraseOk) { Text("确认修改") }
            } else {
                HoldToConfirmButton(
                    text = "按住 3 秒：我仍要这样改",
                    enabled = true,
                    onConfirmed = { onConfirmed() },
                )
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    typing = !typing
                    phrase = ""
                }) {
                    Text(if (typing) "改回长按确认" else "改用输入确认")
                }
                TextButton(onClick = { onDismiss() }) { Text("取消") }
            }
        },
    )
}

private const val HOLD_MS = 3000L

@Composable
private fun HoldToConfirmButton(text: String, enabled: Boolean, onConfirmed: () -> Unit) {
    var holding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    holding = true
                    val pending = scope.launch {
                        delay(HOLD_MS)
                        holding = false
                        onConfirmed()
                    }
                    tryAwaitRelease()
                    pending.cancel()
                    holding = false
                },
            )
        },
    ) {
        Text(
            text = if (holding) "松开就取消，继续按住…" else text,
            color = if (enabled) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

// ─────────────────────────── 各类型控件 ───────────────────────────

@Composable
private fun ChoiceControl(
    spec: RelaySettings.Spec,
    current: String,
    onApply: (String, String) -> Unit,
    confirmMode: String,
) {
    val type = spec.type as RelaySettings.Type.Choice
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    Box {
        Button(onClick = { expanded = true }, enabled = spec.wired && !spec.locked) {
            Text(type.labels[current] ?: current)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            type.values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(type.labels[value] ?: value) },
                    onClick = {
                        expanded = false
                        val phrase = "已改成：${spec.title} → ${type.labels[value] ?: value}"
                        if (spec.holdToConfirm) pending = value else onApply(value, phrase)
                    },
                )
            }
        }
    }
    pending?.let { value ->
        DangerConfirmDialog(
            spec = spec,
            confirmMode = confirmMode,
            onDismiss = { pending = null },
            onConfirmed = {
                onApply(value, "已改成：${spec.title} → ${type.labels[value] ?: value}")
                pending = null
            },
        )
    }
}

@Composable
private fun NumberControl(
    spec: RelaySettings.Spec,
    type: RelaySettings.Type.Number,
    current: String,
    onApply: (String, String) -> Unit,
) {
    var text by remember(spec.id) { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text("取值 ${type.min}..${type.max}${type.unit}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = {
            val n = text.trim().toIntOrNull()
            error = when {
                n == null -> "要填一个整数，例如 ${spec.default}"
                n < type.min || n > type.max -> "要落在 ${type.min}..${type.max} 之间（你填的是 $n）"
                else -> null
            }
            if (error == null) onApply(text.trim(), "已更新：${spec.title} → ${text.trim()}${type.unit}")
        }) { Text("保存") }
    }
}

@Composable
private fun TextControl(
    spec: RelaySettings.Spec,
    type: RelaySettings.Type.Text,
    current: String,
    onApply: (String, String) -> Unit,
) {
    var text by remember(spec.id) { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            label = { Text("最长 ${type.maxChars} 个字") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = {
            error = if (text.length > type.maxChars) "最长 ${type.maxChars} 个字（当前 ${text.length}）" else null
            if (error == null) onApply(text, "已更新：${spec.title}")
        }) { Text("保存") }
    }
}

@Composable
private fun ListControl(
    spec: RelaySettings.Spec,
    current: String,
    onApply: (String, String) -> Unit,
) {
    var text by remember(spec.id) { mutableStateOf(decodeList(current).joinToString("\n")) }
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        Text(
            "一行一条。留空表示这一项不生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            singleLine = false,
            // 清单里是包名 / 动作名这类机器串：等宽 + 折行，长条目不会撑爆卡片。
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 20.sp,
            ),
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = {
            val items = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            error = when {
                items.size > SettingsStore.MAX_LIST_ITEMS -> "最多 ${SettingsStore.MAX_LIST_ITEMS} 条"
                items.any { it.length > SettingsStore.MAX_LIST_ITEM_CHARS } ->
                    "单条最长 ${SettingsStore.MAX_LIST_ITEM_CHARS} 个字"
                else -> null
            }
            if (error == null) onApply(encodeList(items), "已更新：${spec.title}（${items.size} 条）")
        }) { Text("保存") }
    }
}

// ─────────────────────────── 文档型编辑器 ───────────────────────────

@Composable
private fun TemplateDocumentEditor(
    spec: RelaySettings.Spec,
    current: String,
    onApply: (String, String) -> Unit,
) {
    val templates = IntentTemplateCatalog.decodeTemplates(current)
    var draft by remember { mutableStateOf<TemplateDraft?>(null) }
    Column {
        Text(
            "自定义动作 = 显示名字 + 内部名字（小写英文，助手调用时写它）+ 一条写死的 action（可点名组件）。" +
                "内部名字不能与内置 8 个动作重名，重了会被拒；助手只能用这个名字调用，不能自己填 action。" +
                "「不改状态」这个勾只是标记这条动作只上屏、不新建记录；真正免不免问，还要把内部名字写进「打开页面不询问」那份清单。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        templates.forEachIndexed { index, template ->
            if (index > 0) PilotDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        template.name + if (template.noStateChange) "（已标记不改状态）" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${template.id} → ${template.action}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp,
                    )
                }
                TextButton(onClick = { draft = TemplateDraft.of(template) }) { Text("编辑") }
                TextButton(onClick = {
                    val rest = templates.filter { it.id != template.id }
                    onApply(
                        IntentTemplateCatalog.encodeTemplates(rest),
                        "已删除：${template.name}",
                    )
                }) { Text("删除") }
            }
        }
        if (templates.isEmpty()) {
            Text("还没有自定义动作。", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Button(onClick = { draft = TemplateDraft.blank() }) { Text("添加一条上屏动作") }
    }
    draft?.let { value ->
        TemplateFormDialog(
            draft = value,
            onDismiss = { draft = null },
            onSave = { saved ->
                val next = templates.filter { it.id != saved.id } + saved.toTemplate()
                // 写进去由配置中心校验：不合法会被拒，回执里给的是同一句人话。
                onApply(IntentTemplateCatalog.encodeTemplates(next), "已保存：${saved.name}")
                draft = null
            },
        )
    }
}

@Composable
private fun PageDocumentEditor(
    spec: RelaySettings.Spec,
    current: String,
    onApply: (String, String) -> Unit,
) {
    val pages = IntentTemplateCatalog.decodePages(current)
    var draft by remember { mutableStateOf<PageDraft?>(null) }
    Column {
        Text(
            "给 settings.open 加一个新页面键。页面键是助手调用时写的那一个词（小写英文），" +
                "action 是你从系统里抄到的那个字符串。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        pages.forEachIndexed { index, page ->
            if (index > 0) PilotDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(page.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${page.key} → ${page.action}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp,
                    )
                }
                TextButton(onClick = { draft = PageDraft.of(page) }) { Text("编辑") }
                TextButton(onClick = {
                    val rest = pages.filter { it.key != page.key }
                    onApply(IntentTemplateCatalog.encodePages(rest), "已删除：${page.name}")
                }) { Text("删除") }
            }
        }
        if (pages.isEmpty()) {
            Text("还没有自定义页面。", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Button(onClick = { draft = PageDraft.blank() }) { Text("添加一个设置页") }
    }
    draft?.let { value ->
        PageFormDialog(
            draft = value,
            onDismiss = { draft = null },
            onSave = { saved ->
                val next = pages.filter { it.key != saved.key } + saved.toPage()
                onApply(IntentTemplateCatalog.encodePages(next), "已保存：${saved.name}")
                draft = null
            },
        )
    }
}

@Composable
private fun CeilingEditor(
    captionRows: List<RelayContainer.CapabilityRow>,
    overridesJson: () -> String,
    effectiveCeiling: (CapabilityDescriptor) -> String,
    onApply: (String, String) -> Unit,
    confirmMode: String,
) {
    Column {
        Text(
            "每一条能力的上限决定它的「完全访问」能免问到什么程度。" +
                "默认锁在编译期值；改过的条目在这里单独列出。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        captionRows.forEach { row ->
            val current = effectiveCeiling(row.descriptor)
            CeilingRow(
                title = row.descriptor.id.wire,
                current = current,
                confirmMode = confirmMode,
                onPick = { picked ->
                    val map = runCatching { JSONObject(overridesJson()) }.getOrNull() ?: JSONObject()
                    map.put(row.descriptor.id.wire, picked)
                    onApply(map.toString(), "已更新：${row.descriptor.id.wire} 的上限")
                },
            )
        }
    }
}

@Composable
private fun CeilingRow(title: String, current: String, confirmMode: String, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }) { Text(ceilingLabel(current)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                TierCeiling.entries.forEach { ceiling ->
                    DropdownMenuItem(
                        text = { Text(ceilingLabel(ceiling.name)) },
                        onClick = {
                            expanded = false
                            pending = ceiling.name
                        },
                    )
                }
            }
        }
    }
    pending?.let { picked ->
        val spec = RelaySettings.byId.getValue(RelaySettings.CEILING_OVERRIDES)
        DangerConfirmDialog(
            spec = spec,
            confirmMode = confirmMode,
            onDismiss = { pending = null },
            onConfirmed = {
                onPick(picked)
                pending = null
            },
        )
    }
}

private fun ceilingLabel(value: String): String = when (value) {
    "ANY" -> "完全免问"
    "SESSION_ONLY" -> "仅本会话"
    "ASK_ONLY" -> "每次必问"
    else -> value
}

// ─────────────────────────── 导入 ───────────────────────────

@Composable
private fun ImportDialog(
    viewModel: PilotViewModel,
    onClose: () -> Unit,
    onReceipt: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SettingsStore.ImportResult?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("导入配置") },
        text = {
            Column {
                Text("把导出的那段文本粘进来。导入前会先校验；有一段不合法就整段不生效。")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; result = null },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
                result?.let { r ->
                    Spacer(modifier = Modifier.height(8.dp))
                    if (!r.ok) {
                        Text(r.error ?: "导入失败", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("已导入 ${r.applied.size} 项。")
                        if (r.risky.isNotEmpty()) {
                            Text(
                                "其中高风险项：" + r.risky.joinToString("、") + "。建议逐条回看。",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = viewModel.importSettings(text)
                result = r
                if (r.ok) onReceipt("已导入 ${r.applied.size} 项配置")
            }) { Text("检查并导入") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

// ─────────────────────────── 表单草稿 ───────────────────────────

private data class TemplateDraft(
    val originalId: String?,
    val id: String,
    val name: String,
    val action: String,
    val component: String,
    val data: String,
    val extras: String,
    val noStateChange: Boolean,
    val description: String,
) {
    fun toTemplate(): UserIntentTemplate = UserIntentTemplate(
        id = id.trim(),
        name = name.trim().ifEmpty { id.trim() },
        action = action.trim(),
        component = component.trim().takeIf { it.isNotEmpty() },
        data = data.trim().takeIf { it.isNotEmpty() },
        extras = parseExtras(extras),
        noStateChange = noStateChange,
        description = description.trim(),
    )

    companion object {
        fun blank() = TemplateDraft(null, "", "", "", "", "", "", false, "")
        fun of(t: UserIntentTemplate) = TemplateDraft(
            originalId = t.id,
            id = t.id,
            name = t.name,
            action = t.action,
            component = t.component.orEmpty(),
            data = t.data.orEmpty(),
            extras = t.extras.joinToString("\n") { "${it.key}=${it.value}" },
            noStateChange = t.noStateChange,
            description = t.description,
        )
    }
}

private data class PageDraft(
    val originalKey: String?,
    val key: String,
    val name: String,
    val action: String,
    val extras: String,
) {
    fun toPage(): UserSettingsPage = UserSettingsPage(
        key = key.trim(),
        name = name.trim().ifEmpty { key.trim() },
        action = action.trim(),
        extras = parseExtras(extras),
    )

    companion object {
        fun blank() = PageDraft(null, "", "", "", "")
        fun of(p: UserSettingsPage) = PageDraft(
            originalKey = p.key,
            key = p.key,
            name = p.name,
            action = p.action,
            extras = p.extras.joinToString("\n") { "${it.key}=${it.value}" },
        )
    }
}

@Composable
private fun TemplateFormDialog(
    draft: TemplateDraft,
    onDismiss: () -> Unit,
    onSave: (TemplateDraft) -> Unit,
) {
    var value by remember { mutableStateOf(draft) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.originalId == null) "添加一条上屏动作" else "编辑上屏动作") },
        text = {
            Column {
                FormField("显示名字", value.name, { value = value.copy(name = it) })
                FormField("内部名字（小写英文，助手用它调用）", value.id, { value = value.copy(id = it) })
                FormField("action（写死的动作串）", value.action, { value = value.copy(action = it) })
                FormField("组件（可选，写成 包名/类名）", value.component, { value = value.copy(component = it) })
                FormField("data（可选，例如 https://…）", value.data, { value = value.copy(data = it) })
                Text(
                    "参数（可选，一行一条 key=值；值是 true/false 当开关、整数当数字、其余当文本）",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = value.extras,
                    onValueChange = { value = value.copy(extras = it) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = value.noStateChange,
                        onCheckedChange = { value = value.copy(noStateChange = it) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("不改状态（只上屏；免不免问由「打开页面不询问」决定）")
                }
                FormField("说明（可选）", value.description, { value = value.copy(description = it) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存这一条") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PageFormDialog(
    draft: PageDraft,
    onDismiss: () -> Unit,
    onSave: (PageDraft) -> Unit,
) {
    var value by remember { mutableStateOf(draft) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.originalKey == null) "添加一个设置页" else "编辑设置页") },
        text = {
            Column {
                FormField("页面键（小写英文，助手调用时写它）", value.key, { value = value.copy(key = it) })
                FormField("显示名字", value.name, { value = value.copy(name = it) })
                FormField("action（写死的动作串）", value.action, { value = value.copy(action = it) })
                Text(
                    "参数（可选，一行一条 key=值）",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = value.extras,
                    onValueChange = { value = value.copy(extras = it) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存这一个") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FormField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

// ─────────────────────────── 小工具 ───────────────────────────

private fun encodeList(items: List<String>): String = JSONArray(items).toString()

private fun decodeList(text: String): List<String> = runCatching {
    val array = JSONArray(text)
    (0 until array.length()).map { array.optString(it) }
}.getOrDefault(emptyList())

/** `key=值` 每行一条；类型按值的形状推断（true/false → 开关，整数 → 数字，其余文本）。 */
private fun parseExtras(text: String): List<IntentExtra> = text.split('\n')
    .map { it.trim() }
    .filter { it.isNotEmpty() && it.contains('=') }
    .map { line ->
        val key = line.substringBefore('=').trim()
        val raw = line.substringAfter('=').trim()
        val kind = when {
            raw == "true" || raw == "false" -> IntentExtra.KIND_BOOL
            raw.toIntOrNull() != null -> IntentExtra.KIND_INT
            else -> IntentExtra.KIND_STRING
        }
        IntentExtra(key = key, kind = kind, value = raw)
    }
    .filter { it.key.isNotEmpty() }

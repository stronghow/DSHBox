package dshbox.adapter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/**
 * 一项配置里可展开内容的编号。各块互不牵连：展开哪一块都不会带出另外两块。
 *
 * 说明（原来分「改了会怎样」与「看例子」两颗）合并成 DETAIL 一颗：要求是**严格分层** ——
 * 折叠态只留"标题 + 一句话 + 当前值"。
 *
 * 二期再分一层（用户抱怨二级长文挤占视线、把下方的标签顶下去）：
 * - [DETAIL]   二级：只留**精简版**「改了会怎样」与「代价」（＋只读说明）；
 * - [DEV_NOTE] 三级：在 DETAIL 里再折一次，装完整原文 / 为什么默认这样 / 影响范围 ——
 *   偏开发者的内容退到这一层，**一个字都没删**；
 * - [LIST_HELP] 三级（清单型那一项专用）：标签控件的详细说明，同样是折叠的；
 * - [EDITOR]   编辑控件。
 *
 * 四级状态全部由 [RulesExpansionMemory] 记住：切到子页再回来，手风琴该开着的还开着。
 */
enum class RulesSection { DETAIL, DEV_NOTE, LIST_HELP, EDITOR }

/**
 * 规则页的展开记忆：哪些组打开着、每一项的几块内容各自是否展开、危险组的"先看一眼代价"确认过没有、
 * 免问清单那一项是不是正处在"自定义挑选"状态。
 *
 * 与 [PilotScreen] 里记住每页滚动位置的 `scrollByPage` 同一个理由：切到别的页时这一页整个从组合里
 * 摘掉，写在页内的 `remember` 活不过一次切页。把记忆交给外层 `remember`，滚动位置与展开状态就一起
 * 活到离开这一页为止。默认：只有常用组打开；每一项各块全收起 —— 第一眼只看到"这项管什么 + 当前值"。
 */
class RulesExpansionMemory {
    private val groups = mutableStateMapOf<RelaySettings.Group, Boolean>()
    private val sections = mutableStateMapOf<String, Boolean>()
    private val customPicking = mutableStateMapOf<String, Boolean>()

    /** 危险组展开前的"先看一眼代价"是否已经确认。 */
    var dangerUnlocked by mutableStateOf(false)

    fun groupOpen(group: RelaySettings.Group): Boolean =
        groups[group] ?: (group == RelaySettings.Group.COMMON)

    fun setGroupOpen(group: RelaySettings.Group, open: Boolean) {
        groups[group] = open
    }

    /** 默认收起：没记过就是 false —— 手风琴的默认态只由这一句决定。 */
    fun open(specId: String, section: RulesSection): Boolean =
        sections[sectionKey(specId, section)] == true

    fun toggle(specId: String, section: RulesSection) {
        sections[sectionKey(specId, section)] = !open(specId, section)
    }

    /** 明确地摊开某一块（「再改改」把编辑区直接推到用户面前时用）。 */
    fun setOpen(specId: String, section: RulesSection, open: Boolean) {
        sections[sectionKey(specId, section)] = open
    }

    /**
     * 免问清单那一项是否正处在"自定义挑选"状态。
     *
     * 「自定义允许」不是一个存储值（存储键与写入语义一个都没加），它只是"把标签组露出来"这个
     * 界面状态；一旦用户点了别的模式、或点了「全选 / 全不选」，这个状态就退回"由现值反推"。
     */
    fun customPicking(specId: String): Boolean = customPicking[specId] == true

    fun setCustomPicking(specId: String, on: Boolean) {
        customPicking[specId] = on
    }

    private fun sectionKey(specId: String, section: RulesSection): String = "$specId#${section.name}"
}

/**
 * 规则页"编辑中但还没保存"的草稿：`spec.id → 编辑器里的文本`。
 *
 * 为什么草稿挂在这一层而不是控件自己的 `remember`：
 * 1. 折叠「改这一项」不该把用户写了一半的文本扔掉 —— 页内 `remember` 会随控件离树一起消失；
 * 2. 页面顶部那条「有未保存的更改」要一直算得准（草稿在，条就在），"保存/放弃"两个动作也由它驱动。
 *
 * 只有**保留显式保存**的控件（短文本 / 清单）往里写草稿。开关 / 单选 / 数字是即改即生效，
 * 不经过这里 —— 它们的写入路径与从前逐字一致。
 */
class RulesEditMemory {
    private val drafts = mutableStateMapOf<String, String>()

    fun draft(id: String): String? = drafts[id]

    fun setDraft(id: String, text: String) {
        drafts[id] = text
    }

    fun clear(id: String) {
        drafts.remove(id)
    }

    fun clearAll() {
        drafts.clear()
    }

    fun ids(): List<String> = drafts.keys.toList()
}

/**
 * "规则与配置"页：整张页面由 [RelaySettings.all] 那张声明表渲染，不写死任何一项。
 *
 * 呈现纪律（一期按 UX 评审重排，二期按用户第二轮反馈再收一层）：
 * - **人话标题层**：主标题取 [RulesDisplay.title]（id → 人话的展示映射），源代码 id 不再当标题；
 *   真名与存储键放在长按标题 / 「了解风险与代价 → 高级」里，作为可查信息保留。
 * - **严格分层（二期再收一层）**：折叠态还是"风险胶囊 + 人话标题 + 一句话 + 当前状态"；
 *   「了解风险与代价」展开后只有**精简版**「改了会怎样」＋「代价」；完整原文 / 为什么默认这样 /
 *   影响范围 收在里面的「开发者说明 ▾」二级折叠里。**一个字都没删，只是再深一层。**
 * - **一卡一项**：一个分组下每个配置各成一张卡（`approval.screen_only_templates` 与
 *   `intent.settings_pages` 因此天然是两张独立的大卡），危险组默认折叠、展开前先看一屏代价。
 * - **策略模式**：免问清单那一项顶上先给三选一（全部询问 / 全部允许 / 自定义允许），
 *   写入语义见 [RulesDisplay.ScreenOnlyMode]；只有选"自定义"才露出下面那组短标签。
 * - **即改即生效**：开关 / 单选 / 数字一改就写；短文本与清单保留显式保存，且有"未保存"提示条。
 * - **风险可视化**：低=绿 / 中=黄 / 高=红 / 严重=深红，都是一枚带色块的小胶囊，点旁边的 (i) 看后果原文；
 *   卡片里出现"会写系统的动作"时，整张卡片实时染成淡黄底 + 琥珀边。
 *
 * 这一页不滚动：外层 [PilotScreen] 的 Column 已经带 verticalScroll，这里再套一层同轴滚动会在运行时崩。
 * 二期的「模拟运行一次」遮罩层与「新增自定义动作」底部抽屉走各自的 `Dialog` 窗口，不受这一条约束。
 */
@Composable
fun RulesPage(
    state: PilotUiState,
    viewModel: PilotViewModel,
    open: (PilotDestination) -> Unit,
    expansion: RulesExpansionMemory,
    edit: RulesEditMemory,
) {
    // 改动回执：动词短语，人话。任何一次写入都从这里出一条。
    var receipt by remember { mutableStateOf<String?>(null) }
    // 刚刚落盘的那一项：在它自己的卡片上亮一下"已保存 ✓"（即改即生效的轻量反馈）。
    var flashId by remember { mutableStateOf<String?>(null) }
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

    // 有草稿、且草稿与已存值不同 = 真的有未保存的更改。比较口径与控件里的编辑器逐字一致
    // （清单型按"一行一条"解码后再比，避免把格式差异当成"改过"）。
    val dirtyRows = edit.ids()
        .mapNotNull { id -> findSettingRow(state.settingRows, id) }
        .filter { row -> edit.draft(row.spec.id) != draftBase(row.spec, row.value) }

    LaunchedEffect(flashId) {
        if (flashId != null) {
            delay(FLASH_MS)
            flashId = null
        }
    }

    // ── 未保存提示条：只在真的有草稿时出现，两个动作都直接可用 ──
    if (dirtyRows.isNotEmpty()) {
        UnsavedBar(
            titles = dirtyRows.map { RulesDisplay.title(it.spec) },
            onDiscard = {
                edit.clearAll()
                receipt = "已放弃未保存的改动"
            },
            onSave = {
                val failures = mutableListOf<String>()
                var saved = 0
                dirtyRows.forEach { row ->
                    val draft = edit.draft(row.spec.id) ?: return@forEach
                    val normalized = normalizeDraft(row.spec, draft)
                    val encoded = encodeDraft(row.spec, normalized)
                    val error = viewModel.setSetting(row.spec.id, encoded)
                    if (error == null) {
                        saved++
                        // 保存成功后草稿改写成"归一化"的那一份：写入生效后它与已存值逐字相同，
                        // 提示条会自己消失（不在这里直接 clear，免得字段在刷新前闪回旧值）。
                        edit.setDraft(row.spec.id, normalized)
                    } else {
                        failures += "${RulesDisplay.title(row.spec)}：$error"
                    }
                }
                receipt = if (failures.isEmpty()) {
                    "已保存 $saved 项，立刻生效"
                } else {
                    "有一部分没改成：" + failures.joinToString("；")
                }
            },
        )
    }

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
                // 恢复默认是"把这一项拨回去"的明确动作：连同它的未保存草稿一起丢掉，
                // 免得栏里那条"未保存"把刚恢复的值又盖回去。
                edit.clearAll()
                receipt = if (changed.isEmpty()) "没有需要恢复的项，全部已是默认值" else "已恢复默认：${changed.joinToString("、")}"
            },
        )
    }

    OutlinedTextField(
        value = search,
        onValueChange = { search = it },
        label = { Text("搜索配置项（按人话标题、关键词或原始标识符）") },
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
                edit = edit,
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
                    // 只丢掉本组的草稿：别的组里那份写了一半的清单不受影响。
                    RelaySettings.all.filter { it.group == group }.forEach { edit.clear(it.id) }
                    receipt = if (changed.isEmpty()) "本组已经是默认值" else "已恢复默认：${changed.joinToString("、")}"
                },
                onApply = { id, encoded, phrase ->
                    val error = viewModel.setSetting(id, encoded)
                    receipt = error?.let { "没改成：$it" } ?: phrase
                    if (error == null) flashId = id
                },
                onNotice = { receipt = it },
                onReset = { id ->
                    val changed = viewModel.resetSetting(id)
                    edit.clear(id)
                    receipt = if (changed.isEmpty()) "这一项已经是默认值" else "已恢复默认：${changed.joinToString("、")}"
                },
                onOpenLink = open,
                captionRows = state.rows,
                ceilingOverridesJson = { viewModel.ceilingOverridesJson() },
                effectiveCeiling = { viewModel.effectiveCeiling(it) },
                confirmMode = confirmMode,
                flashId = flashId,
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
                            "但它被提示词注入时，你能依靠的东西就少一道。开关类的改动都要过一次有意识的确认" +
                            "（默认长按 3 秒，可在「危险项怎么确认」里改成输入确认短语），" +
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

/**
 * 顶部「有未保存的更改」条。只有真的存在草稿时才会被调到（调用点已经过滤过），
 * 因此这里不做条件判断，只负责把"哪几项 + 放弃 / 保存"摆出来。
 */
@Composable
private fun UnsavedBar(
    titles: List<String>,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = "有未保存的更改：" + titles.joinToString("、"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "这几项要点了保存才生效。开关 / 单选 / 数字是即改即生效，不会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDiscard) { Text("放弃") }
                TextButton(onClick = onSave) { Text("保存") }
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: RelaySettings.Group,
    rows: List<RelayContainer.SettingRow>,
    expanded: Boolean,
    expansion: RulesExpansionMemory,
    edit: RulesEditMemory,
    onToggle: () -> Unit,
    onResetGroup: () -> Unit,
    onApply: (String, String, String) -> Unit,
    onNotice: (String) -> Unit,
    onReset: (String) -> Unit,
    onOpenLink: (PilotDestination) -> Unit,
    captionRows: List<RelayContainer.CapabilityRow>,
    ceilingOverridesJson: () -> String,
    effectiveCeiling: (CapabilityDescriptor) -> String,
    confirmMode: String,
    flashId: String?,
) {
    // 「这一组恢复默认」一次能改多少项：按钮上直接写出来，不让人猜它动了什么。
    val groupSpecCount = RelaySettings.all.count { it.group == group }
    var moreOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = group.label + groupChangedSuffix(rows),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Text(
            text = group.summary,
            style = MaterialTheme.typography.bodySmall,
            color = LocalPilotExtraColors.current.tertiaryText,
            modifier = Modifier.padding(start = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onToggle) { Text(if (expanded) "收起这一组" else "展开这一组") }
            // ── 二期：两处「恢复默认」不再同时占版面 ──
            // 「这一组恢复默认」是"一次改好几项"的重手动作，收进「更多」；静止时每个作用域
            // 只看得见一处「恢复默认」—— 项内那一处（它只在你已经改过、并且正看着这一项时出现）。
            // 组的作用域恰好只有 1 项时不再给这个入口：那一项自己的「恢复默认」与它逐字等价。
            if (groupSpecCount > 1) {
                Box {
                    TextButton(onClick = { moreOpen = true }) { Text("更多 ⋮") }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("这一组恢复默认（$groupSpecCount 项）") },
                            onClick = {
                                moreOpen = false
                                onResetGroup()
                            },
                        )
                    }
                }
            }
        }
        if (expanded) {
            // 一卡一项：每一项各自一张卡（这也是"卡片化拆分"的落点：
            // `approval.screen_only_templates` 与 `intent.settings_pages` 因此是两张独立的大卡）。
            rows.forEach { row ->
                // 会写系统的动作 → 整张卡片淡黄底 + 琥珀边，随配置实时变化。
                val warn = RulesDisplay.stateWriteWarning(row.spec, row.value)
                WarningAwareCard(warning = warn) {
                    SettingRowItem(
                        row = row,
                        expansion = expansion,
                        edit = edit,
                        onApply = onApply,
                        onNotice = onNotice,
                        onReset = onReset,
                        onOpenLink = onOpenLink,
                        captionRows = captionRows,
                        ceilingOverridesJson = ceilingOverridesJson,
                        effectiveCeiling = effectiveCeiling,
                        confirmMode = confirmMode,
                        flashId = flashId,
                    )
                }
            }
        }
    }
}

/**
 * 一张配置卡：没有警示时就是默认那张卡（与一期逐字一致），有警示时整张染成淡黄底 + 琥珀边，
 * 卡首多一条 ⚠ 说明。只在 [RulesDisplay.stateWriteWarning] 给出说法时换色 —— 判定在显示层，
 * 这里不做任何判断。
 */
@Composable
private fun WarningAwareCard(
    warning: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (warning == null) {
        PilotCard { content() }
    } else {
        PilotCard(container = warningCardContainer(), border = warningCardBorder()) {
            StateWriteWarningNotice(warning)
            content()
        }
    }
}

/** 警示卡的底色：浅色=淡黄，深色=暗琥珀。文字色见 [StateWriteWarningNotice]。 */
@Composable
private fun warningCardContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFF3A2F12) else Color(0xFFFFF8E1)

/** 警示卡的描边：比普通卡粗半档、颜色固定（不跟主色走），四档风险里只有"会写系统"才用它。 */
@Composable
private fun warningCardBorder(): BorderStroke =
    BorderStroke(1.5.dp, if (isSystemInDarkTheme()) Color(0xFFE5B94E) else Color(0xFFC8901A))

/**
 * 卡片级警示条：这张卡里的配置会引入"会写系统的动作"时，卡首出现这一条。
 *
 * 颜色固定：浅色下 #6B4E00 压 #FFF8E1（≈7.8:1），深色下 #F0D9A0 压 #3A2F12（≈9:1），
 * 两套都过 WCAG 正文对比度。**不跟主题色走**：它说的是同一件事 —— 你现在放行的动作会直接改设备状态。
 */
@Composable
private fun StateWriteWarningNotice(text: String) {
    val ink = if (isSystemInDarkTheme()) Color(0xFFF0D9A0) else Color(0xFF6B4E00)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("⚠ ", color = ink, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = text,
            color = ink,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 19.sp,
        )
    }
}

private fun matches(row: RelayContainer.SettingRow, query: String): Boolean {
    if (query.isEmpty()) return true
    val s = row.spec
    return s.title.contains(query, ignoreCase = true) ||
        RulesDisplay.title(s).contains(query, ignoreCase = true) ||
        s.summary.contains(query, ignoreCase = true) ||
        s.id.contains(query, ignoreCase = true) ||
        s.scope.contains(query, ignoreCase = true)
}

private fun groupChangedSuffix(rows: List<RelayContainer.SettingRow>): String {
    val changed = rows.count { !it.isDefault }
    return if (changed == 0) "（都是默认值）" else "（已改 $changed 项）"
}

/** 在（含子项的）配置行里按 id 找一行；找不到回 null。 */
private fun findSettingRow(rows: List<RelayContainer.SettingRow>, id: String): RelayContainer.SettingRow? {
    rows.forEach { row ->
        if (row.spec.id == id) return row
        findSettingRow(row.children, id)?.let { return it }
    }
    return null
}

// ─────────────────────────── 一行配置 ───────────────────────────

/**
 * 一项配置的卡片正文。折叠态只有"风险胶囊 + 人话标题 + 一句话作用 + 当前状态"，长内容按需展开。
 *
 * - 「了解风险与代价」里只留精简版「改了会怎样」与「代价」，还有一颗「开发者说明 ▾」二级折叠
 *   （完整原文 / 为什么默认这样 / 影响范围），外加「模拟运行一次」；
 * - 「改这一项」放控件与「恢复默认」，危险项的子名单跟着它出现。
 *
 * 需要显式保存的控件（短文本 / 清单）把草稿写进 [RulesEditMemory]；开关 / 单选 / 数字直接落盘。
 *
 * [onApply] / [onReset] 收的是**带 id 的原始回调**，本函数在下面按 `spec.id` 绑成
 * [applySelf] / [resetSelf] 供本行自己的控件用；递归渲染子项时继续往下传原始回调，
 * 于是每一层绑的都是**它自己**的 id —— 危险项的子规则因此写进自己的键。
 *
 * [onNotice] 只出一条回执（例如「模拟运行一次」看完点"满意"），**不写任何配置**。
 */
@Composable
private fun SettingRowItem(
    row: RelayContainer.SettingRow,
    expansion: RulesExpansionMemory,
    edit: RulesEditMemory,
    onApply: (String, String, String) -> Unit,
    onNotice: (String) -> Unit,
    onReset: (String) -> Unit,
    onOpenLink: (PilotDestination) -> Unit,
    captionRows: List<RelayContainer.CapabilityRow>,
    ceilingOverridesJson: () -> String,
    effectiveCeiling: (CapabilityDescriptor) -> String,
    confirmMode: String,
    flashId: String?,
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
    // 长按标题 / 点「高级」都会露出真名（id 与存储键）；默认不露，避免机器串抢走注意力。
    var advancedShown by remember(spec.id) { mutableStateOf(false) }
    // "模拟运行一次"与风险 (i) 各自一个弹窗。
    var simulateOpen by remember { mutableStateOf(false) }
    var riskOpen by remember { mutableStateOf(false) }
    val risk = riskColors(spec)

    // 未接线项：值改了不生效。默认根本不会显示（见 RelayContainer.settingsRows），
    // 只有用户在「显示未接线项（开发预览）」里显式打开才会走到这里 —— 那时顶上必须有一句
    // 醒目的黄色说明，不能让用户以为"我改了但程序没反应"。
    if (!spec.wired) UnwiredNotice()

    // ── 折叠态：人话标题 + 风险胶囊（色块 + 文字）同行（徽标不进正文），下面是一句话作用与当前值 ──
    // 二期把"风险点 + 单色文字"换成**一枚小胶囊**：中风险就是黄色的那一枚，四档各一色、
    // 浅色/深色各一套（颜色见 [riskColors]）。具体后果仍在旁边的 (i) 里。
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = RulesDisplay.title(spec),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .pointerInput(spec.id) {
                    // 长按标题＝把这一项的真名摊开（可查信息，不去打扰默认态）。
                    detectTapGestures(onLongPress = { advancedShown = !advancedShown })
                },
        )
        Spacer(modifier = Modifier.width(8.dp))
        RiskPill(spec)
        Text(
            text = " (i)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { riskOpen = true }
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
    Text(
        text = spec.summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 21.sp,
        modifier = Modifier.padding(top = 4.dp),
    )
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "当前：" + valueSummary(row) + if (row.isDefault) "（默认）" else "（已改）",
            style = currentValueStyle(spec),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { valueClipped = it.hasVisualOverflow },
            modifier = Modifier.weight(1f),
        )
        if (flashId == spec.id) {
            Text(
                text = "已保存 ✓",
                style = MaterialTheme.typography.labelMedium,
                color = riskGreenText(),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
    if (valueClipped) {
        Text(
            text = "（值太长只显示开头，点下面「改这一项」看完整清单）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    if (advancedShown) {
        Text(
            text = "标识：" + spec.id + "　存储键：" + RelaySettings.key(spec.id) +
                "　默认值：" + defaultDisplay(spec),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    // ── 两颗展开开关：各自独立、默认收起、颜色与正文分得开 ──
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionToggle(
            label = "了解风险与代价",
            open = expansion.open(spec.id, RulesSection.DETAIL),
            modifier = Modifier.weight(1f),
        ) { expansion.toggle(spec.id, RulesSection.DETAIL) }
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
            // ── 二级：只有精简版的两段（改了会怎样 / 代价）＋只读说明 ──
            // 精简版是原文开头的完整句子，不裁半句；短了才提一句"完整原文在下面"。
            DetailBlock("改了会怎样", RulesDisplay.effectShort(spec))
            if (RulesDisplay.effectShortened(spec)) {
                Text(
                    text = "（完整原文在下面的「开发者说明」里，一个字没删。）",
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalPilotExtraColors.current.tertiaryText,
                )
            }
            DetailBlock("代价", "${spec.risk.label}风险 —— ${spec.riskText}")
            row.readOnlyNote?.let { DetailBlock("说明", it) }

            // ── 三级：偏开发者的三段落再折一层（默认收起）──
            SectionToggle(
                label = "开发者说明",
                open = expansion.open(spec.id, RulesSection.DEV_NOTE),
                modifier = Modifier.fillMaxWidth(),
            ) { expansion.toggle(spec.id, RulesSection.DEV_NOTE) }
            if (expansion.open(spec.id, RulesSection.DEV_NOTE)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DetailBlock("改了会怎样", spec.effect)
                    DetailBlock("为什么默认这样", spec.whyDefault)
                    DetailBlock("影响范围", spec.scope)
                }
            }

            TextButton(onClick = { simulateOpen = true }) { Text("模拟运行一次") }
            TextButton(onClick = { advancedShown = !advancedShown }) {
                Text(if (advancedShown) "收起高级信息" else "高级：标识符与默认值")
            }
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
                                    if (next) "已开启：${RulesDisplay.title(spec)}" else "已关闭：${RulesDisplay.title(spec)}",
                                )
                            }
                        },
                    )
                    Text(
                        text = if (spec.holdToConfirm) {
                            if (confirmMode == RelaySettings.CONFIRM_TYPE) {
                                "拨一下就要确认：按「危险项怎么确认」的设置，要逐字输入确认短语。"
                            } else {
                                "拨一下就要确认：按住按钮 3 秒才会生效。"
                            }
                        } else {
                            "拨一下立刻生效，不用点保存。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    TextControl(spec = spec, type = type, current = row.value, onApply = applySelf, edit = edit)
                }

                is RelaySettings.Type.TextList -> {
                    ListControl(
                        spec = spec,
                        current = row.value,
                        onApply = applySelf,
                        edit = edit,
                        expansion = expansion,
                    )
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

            // 危险项的子规则挂在父项"改这一项"展开之后（父项是"点系统授权框前先问你"，
            // 子项是它的三张名单）。它们属于父项的设置，不跟着那块说明展开。
            // 这里刻意传**原始**回调（而不是 applySelf/resetSelf）：子项各自绑自己的 spec.id。
            row.children.forEach { child ->
                SubRuleBlock {
                    PilotDivider()
                    SettingRowItem(
                        row = child,
                        expansion = expansion,
                        edit = edit,
                        onApply = onApply,
                        onNotice = onNotice,
                        onReset = onReset,
                        onOpenLink = onOpenLink,
                        captionRows = captionRows,
                        ceilingOverridesJson = ceilingOverridesJson,
                        effectiveCeiling = effectiveCeiling,
                        confirmMode = confirmMode,
                        flashId = flashId,
                    )
                }
            }
        }
    }

    pendingSwitch?.let { next ->
        DangerConfirmDialog(
            spec = spec,
            confirmMode = confirmMode,
            onDismiss = { pendingSwitch = null },
            onConfirmed = {
                applySelf(next.toString(), if (next) "已开启：${RulesDisplay.title(spec)}" else "已关闭：${RulesDisplay.title(spec)}")
                pendingSwitch = null
            },
        )
    }

    if (riskOpen) {
        RiskExplainDialog(spec = spec, onClose = { riskOpen = false })
    }

    if (simulateOpen) {
        SimulateDialog(
            spec = spec,
            currentRaw = row.value,
            currentDisplay = valueSummary(row),
            onSatisfied = {
                simulateOpen = false
                onNotice("好，就按这个来。这一项没有任何改动，刚才那一屏也没有执行任何系统动作。")
            },
            onAdjust = {
                simulateOpen = false
                // 「再改改」＝把这一项的控件直接推到面前（手风琴记忆里也记成"开着"）。
                expansion.setOpen(spec.id, RulesSection.EDITOR, true)
            },
        )
    }
}

/**
 * 危险项子规则的缩进块：缩进一档，让人一眼看出"这几条属于上面那一项"。
 * 只是一层视觉容器，不改任何子项的渲染与绑定。
 */
@Composable
private fun SubRuleBlock(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) { content() }
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
 * 用 labelMedium 而不是正文字号：两颗开关要在一行里放得下，也不该比正文更抢眼。
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

/** 风险 (i) 弹窗：只说这一项的具体后果（原文 [RelaySettings.Spec.riskText]），不新编事实。 */
@Composable
private fun RiskExplainDialog(spec: RelaySettings.Spec, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${RulesDisplay.title(spec)}：${riskLevelLabel(spec)}") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RiskPill(spec)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "风险等级：${riskLevelLabel(spec)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(spec.riskText)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "颜色怎么读：绿＝改了只会更啰嗦；黄＝少一道确认或改变行为细节；" +
                        "红＝关掉之后助手可以不经你同意做写操作。每一档的具体后果就是上面这段。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("知道了") } },
    )
}

/**
 * 「模拟运行一次」遮罩层（二期升级：**不是**普通对话框那张小卡）。
 *
 * 三层结构：
 * 1. 一句声明 —— "这不会改变任何设置，也不会去执行任何系统动作"；
 * 2. 演示 —— 第 1 步什么时候会轮到这一项（`spec.scope`）、第 2 步按你**此刻**的配置判据怎么走，
 *    然后用一段带框的正文摆出 `spec.preview` 原文（"今后会这样发生"）；
 * 3. 问一句「这就是它以后的行为，是否满意？」＋「满意 / 再改改」两个出口。
 *
 * **一整屏里没有任何执行通路**：不 startActivity、不发 Intent、不碰 sys.shell、不调设备侧能力。
 * 真正的沙盒演示（在可信虚拟屏上真跑一遍再回放）留到三期，方案与前置条件见随本次改动提交的报告。
 */
@Composable
private fun SimulateDialog(
    spec: RelaySettings.Spec,
    currentRaw: String,
    currentDisplay: String,
    onSatisfied: () -> Unit,
    onAdjust: () -> Unit,
) {
    val steps = RulesDisplay.simulateContext(spec, currentRaw, currentDisplay)
    ModalOverlay(onDismiss = onAdjust) {
        Text(
            text = "模拟运行一次",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = RulesDisplay.title(spec),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = "下面只是把「以后会这样发生」演绎一遍：这不会改变任何设置，也不会去执行任何系统动作，" +
                "更不会碰你的屏幕。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        Text(
            text = "演示",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
        steps.forEachIndexed { index, step ->
            SimStep(
                head = step.first,
                body = step.second,
                last = index == steps.lastIndex,
            )
        }
        // 第 3 步：schema 的 preview 原文（一期那张弹窗的正文），这里摆进一个框里。
        Text(
            text = "第 3 步 · 接下来会发生什么",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 14.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(spec.preview)
            }
        }

        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(
                text = "这就是它以后的行为，是否满意？",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onSatisfied) { Text("满意，就这样") }
            TextButton(onClick = onAdjust) { Text("再改改") }
        }
    }
}

/**
 * 遮罩层容器（二期新增）：`Dialog` + `usePlatformDefaultWidth = false` —— 铺满整屏、由我们自己画
 * 遮罩与内容，与"普通对话框"（系统默认宽度那张小卡）不是一回事。点遮罩 = 关掉（什么都不改）。
 *
 * 内容区用 `verticalScroll` 兜住长内容：遮罩层自己那个窗口不参与页面的滚动。
 */
@Composable
private fun ModalOverlay(
    onDismiss: () -> Unit,
    align: Alignment = Alignment.Center,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(onClick = onDismiss),
            contentAlignment = align,
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    // 点内容不该穿透到遮罩上把这一层关掉：这里把点击吃掉。
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    content = content,
                )
            }
        }
    }
}

/** 演示里的一步：左边一个圆点（末步不画连线），右边小标题 + 正文。纯文字/图形演绎，无执行通路。 */
@Composable
private fun SimStep(head: String, body: String, last: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(9.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            if (!last) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .width(2.dp)
                        .height(30.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = head,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
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

/**
 * 折叠态那一行"当前"的取值：`approval.screen_only_templates` 换成显示词（**只是读法**：
 * 写回去的仍然是清单里的原值），其余类型沿用 [RelayContainer.SettingRow.display]。
 */
private fun valueSummary(row: RelayContainer.SettingRow): String {
    val spec = row.spec
    if (spec.id != RelaySettings.SCREEN_ONLY_TEMPLATES) return row.display
    val items = decodeList(row.value)
    val quiet = items.map { raw -> RulesDisplay.SCREEN_ONLY_TAGS.firstOrNull { it.first == raw }?.second ?: raw }
    val ask = RulesDisplay.SCREEN_ONLY_TAGS.filter { it.first !in items }.map { it.second }
    return "不询问：" + (if (quiet.isEmpty()) "（无）" else quiet.joinToString("、")) +
        "；每次问：" + (if (ask.isEmpty()) "（无）" else ask.joinToString("、"))
}

private fun riskLevelLabel(spec: RelaySettings.Spec): String =
    if (spec.locked) "只读" else when (spec.risk) {
        RelaySettings.Risk.LOW -> "低风险"
        RelaySettings.Risk.MEDIUM -> "中风险"
        RelaySettings.Risk.HIGH -> "高风险"
        RelaySettings.Risk.CRITICAL -> "严重"
    }

/**
 * 风险那一眼的四块颜色：胶囊里的色块（[dot]）、胶囊文字（[text]）、胶囊底色（[pillBg]）、
 * 胶囊描边（[pillBorder]）。浅色与深色各给一套，正文对比度都按 WCAG 挑过：
 * 浅色下四档 ≥5.5:1，深色下四档 ≥6:1（深色底用的是暗色块，不是把浅色底反相）。
 */
private data class RiskColors(
    val dot: Color,
    val text: Color,
    val pillBg: Color,
    val pillBorder: Color,
)

@Composable
private fun riskColors(spec: RelaySettings.Spec): RiskColors {
    val dark = isSystemInDarkTheme()
    if (spec.locked) {
        return RiskColors(
            dot = MaterialTheme.colorScheme.outline,
            text = MaterialTheme.colorScheme.onSurfaceVariant,
            pillBg = MaterialTheme.colorScheme.surfaceVariant,
            pillBorder = MaterialTheme.colorScheme.outline,
        )
    }
    return when (spec.risk) {
        RelaySettings.Risk.LOW ->
            if (dark) RiskColors(Color(0xFF5FD08A), Color(0xFF6FD68F), Color(0xFF14301F), Color(0xFF4FAE74))
            else RiskColors(Color(0xFF1B7F3B), Color(0xFF15652F), Color(0xFFE6F4E9), Color(0xFF6FAF84))

        RelaySettings.Risk.MEDIUM ->
            if (dark) RiskColors(Color(0xFFE5B94E), Color(0xFFE5B94E), Color(0xFF3A2F12), Color(0xFFB08F35))
            else RiskColors(Color(0xFF8A6100), Color(0xFF7A5600), Color(0xFFFFF4CC), Color(0xFFD3A83A))

        RelaySettings.Risk.HIGH ->
            if (dark) RiskColors(Color(0xFFF08A8A), Color(0xFFF08A8A), Color(0xFF3A1A1A), Color(0xFFC96A6A))
            else RiskColors(Color(0xFFC02626), Color(0xFFB01F1F), Color(0xFFFDE7E7), Color(0xFFE0A0A0))

        RelaySettings.Risk.CRITICAL ->
            if (dark) RiskColors(Color(0xFFFF8A8A), Color(0xFFFF9E9E), Color(0xFF3A1414), Color(0xFFD96A6A))
            else RiskColors(Color(0xFF8B1A1A), Color(0xFF8B1A1A), Color(0xFFF6DADA), Color(0xFFCE9A9A))
    }
}

/**
 * 风险小胶囊：**色块 + 文字**两样都在（色盲用户靠文字也读得出来），中风险就是黄色的那一枚。
 *
 * 它只说等级；具体后果在旁边的 (i) 里（`riskText` 原文）。胶囊的底色与文字色见 [riskColors]。
 */
@Composable
private fun RiskPill(spec: RelaySettings.Spec) {
    val risk = riskColors(spec)
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = risk.pillBg,
        border = BorderStroke(1.dp, risk.pillBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PilotStatusDot(color = risk.dot)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = riskLevelLabel(spec),
                style = MaterialTheme.typography.labelMedium,
                color = risk.text,
                maxLines = 1,
            )
        }
    }
}

/** "已保存 ✓" 用的绿色：与低风险同一个色相，两处语义一致（都在说"这一项没问题"）。 */
@Composable
private fun riskGreenText(): Color =
    if (isSystemInDarkTheme()) Color(0xFF6FD68F) else Color(0xFF15652F)

/** 一条配置的默认值（人话）：折叠区"高级"里给的是**原值**，这里给的是能读的那一份。 */
private fun defaultDisplay(spec: RelaySettings.Spec): String = when (val type = spec.type) {
    is RelaySettings.Type.Switch -> if (spec.default == "true") "开" else "关"
    is RelaySettings.Type.Choice -> type.labels[spec.default] ?: spec.default
    is RelaySettings.Type.Number -> spec.default + type.unit
    is RelaySettings.Type.Text -> spec.default.ifEmpty { "（空）" }
    is RelaySettings.Type.TextList -> {
        val items = decodeList(spec.default)
        if (items.isEmpty()) "（空）" else items.joinToString("、")
    }
    is RelaySettings.Type.Document -> if (spec.default == "[]" || spec.default == "{}") "（空）" else spec.default
    is RelaySettings.Type.Link -> "（这一项没有值，点开进对应页面）"
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
        title = { Text("要改：${RulesDisplay.title(spec)}") },
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

/** "已保存 ✓" 在卡片上停留的时间。 */
private const val FLASH_MS = 2000L

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

/**
 * 单选：点开菜单选一个，**选中即写入并生效**（不经过保存按钮）。
 * 高风险项仍走一次有意识的确认（长按 / 输入短语）。
 */
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
    Column {
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
                            val phrase = "已改成：${RulesDisplay.title(spec)} → ${type.labels[value] ?: value}"
                            if (spec.holdToConfirm) pending = value else onApply(value, phrase)
                        },
                    )
                }
            }
        }
        Text(
            text = "选中立刻生效，不用点保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    pending?.let { value ->
        DangerConfirmDialog(
            spec = spec,
            confirmMode = confirmMode,
            onDismiss = { pending = null },
            onConfirmed = {
                onApply(value, "已改成：${RulesDisplay.title(spec)} → ${type.labels[value] ?: value}")
                pending = null
            },
        )
    }
}

/**
 * 数字：**填到合法区间里就当场写入**（不用点保存）。
 *
 * 逐字符判断而不是等失焦：界面上没有"保存"这颗按钮，等失焦就等于"我填完了它却没生效"。
 * 填的不是合法整数时只给一句提示、不写盘 —— 校验口径仍在 [SettingsStore]（这里只挡在前面）。
 */
@Composable
private fun NumberControl(
    spec: RelaySettings.Spec,
    type: RelaySettings.Type.Number,
    current: String,
    onApply: (String, String) -> Unit,
) {
    var text by remember(spec.id) { mutableStateOf(current) }
    var hint by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                val n = raw.trim().toIntOrNull()
                hint = when {
                    n == null -> "要填一个整数，例如 ${spec.default}（合法区间 ${type.min}..${type.max}）"
                    n < type.min || n > type.max -> "要落在 ${type.min}..${type.max} 之间（你填的是 $n）"
                    else -> null
                }
                if (hint == null) {
                    // 合法即写。高风险项（当前没有）仍然要先确认。
                    if (spec.holdToConfirm) pending = n.toString()
                    else onApply(n.toString(), "已保存：${RulesDisplay.title(spec)} → $n${type.unit}")
                }
            },
            label = { Text("取值 ${type.min}..${type.max}${type.unit}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "填到区间里立刻生效，不用点保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    pending?.let { value ->
        DangerConfirmDialog(
            spec = spec,
            confirmMode = RelaySettings.CONFIRM_HOLD,
            onDismiss = { pending = null },
            onConfirmed = {
                onApply(value, "已保存：${RulesDisplay.title(spec)} → $value${type.unit}")
                pending = null
            },
        )
    }
}

/** 草稿的基准文本：清单型按"一行一条"解码后再拼（与控件的编辑器逐字一致），短文本就是原文。 */
private fun draftBase(spec: RelaySettings.Spec, current: String): String = when (spec.type) {
    is RelaySettings.Type.TextList -> decodeList(current).joinToString("\n")
    else -> current
}

private fun normalizeDraft(spec: RelaySettings.Spec, draft: String): String = when (spec.type) {
    is RelaySettings.Type.TextList -> listItems(draft).joinToString("\n")
    else -> draft
}

private fun encodeDraft(spec: RelaySettings.Spec, normalizedDraft: String): String = when (spec.type) {
    is RelaySettings.Type.TextList -> encodeList(listItems(normalizedDraft))
    else -> normalizedDraft
}

/** 编辑器文本里真正落盘的条目：去空白、丢空行（与旧的多行文本框同一口径）。 */
private fun listItems(draft: String): List<String> =
    draft.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/** 短文本：保留显式保存（页面顶部会出现"未保存"提示条）。 */
@Composable
private fun TextControl(
    spec: RelaySettings.Spec,
    type: RelaySettings.Type.Text,
    current: String,
    onApply: (String, String) -> Unit,
    edit: RulesEditMemory,
) {
    val committed = draftBase(spec, current)
    val text = edit.draft(spec.id) ?: committed
    val dirty = text != committed
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { edit.setDraft(spec.id, it); error = null },
            label = { Text("最长 ${type.maxChars} 个字") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(
            text = "这一类要点了保存才生效；没保存时页面顶上会提醒你。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    error = if (text.length > type.maxChars) "最长 ${type.maxChars} 个字（当前 ${text.length}）" else null
                    if (error == null) onApply(text, "已更新：${RulesDisplay.title(spec)}")
                },
                enabled = dirty,
            ) { Text(if (dirty) "保存这一项" else "没有要保存的改动") }
            if (dirty) TextButton(onClick = { edit.clear(spec.id) }) { Text("放弃改动") }
        }
    }
}

/**
 * 清单型控件：**一行一条、每行单独改或删**，不再把一大串塞进一个多行框。
 *
 * 分两种走法：
 * - `approval.screen_only_templates`（6 条内置免审批动作）：二期改成**先选策略再挑标签** ——
 *   顶上三选一（全部询问 / 全部允许 / 自定义允许），只有选"自定义"才露出下面那组短标签；
 *   点标签即切换、**当场写入**（绿色=不询问，灰色=每次问）；清单里不认识的条目原样列出，
 *   可单独改/删；「新增自定义动作」里保留任意文本的入口，所以"自己写一个名字"这条路没被堵掉。
 * - 其余清单（`capability.disabled`、危险项的三张名单）：保留显式保存，草稿进 [RulesEditMemory]，
 *   页面顶部会出现「有未保存的更改」。
 */
@Composable
private fun ListControl(
    spec: RelaySettings.Spec,
    current: String,
    onApply: (String, String) -> Unit,
    edit: RulesEditMemory,
    expansion: RulesExpansionMemory,
) {
    if (spec.id == RelaySettings.SCREEN_ONLY_TEMPLATES) {
        ScreenOnlyTagControl(spec = spec, current = current, onApply = onApply, expansion = expansion)
        return
    }

    val committed = draftBase(spec, current)
    val draft = edit.draft(spec.id) ?: committed
    val dirty = draft != committed
    val lines = draft.split('\n')
    var editing by remember(spec.id) { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val items = listItems(draft)
    val shown = lines.withIndex().filter { it.value.isNotBlank() }

    Column {
        Text(
            text = "一行一条，每条都能单独改或删。清单为空表示这一项不生效。" +
                "这一类要点了保存才生效；没保存时页面顶上会提醒你。" +
                "最多 ${SettingsStore.MAX_LIST_ITEMS} 条，单条最长 ${SettingsStore.MAX_LIST_ITEM_CHARS} 个字。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        shown.forEach { entry ->
            PilotDivider()
            EditableItemRow(
                text = entry.value.trim(),
                onEdit = { editing = entry.index },
                onDelete = {
                    val next = lines.toMutableList()
                    if (entry.index in next.indices) next.removeAt(entry.index)
                    edit.setDraft(spec.id, next.joinToString("\n"))
                },
            )
        }
        if (items.isEmpty()) {
            PilotDivider()
            Text("还没有条目。", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(onClick = { editing = ADD_NEW }) { Text("添加一条") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    error = when {
                        items.size > SettingsStore.MAX_LIST_ITEMS -> "最多 ${SettingsStore.MAX_LIST_ITEMS} 条"
                        items.any { it.length > SettingsStore.MAX_LIST_ITEM_CHARS } ->
                            "单条最长 ${SettingsStore.MAX_LIST_ITEM_CHARS} 个字"
                        else -> null
                    }
                    if (error == null) {
                        edit.setDraft(spec.id, items.joinToString("\n"))
                        onApply(encodeList(items), "已更新：${RulesDisplay.title(spec)}（${items.size} 条）")
                    }
                },
                enabled = dirty,
            ) { Text(if (dirty) "保存这一项" else "没有要保存的改动") }
            if (dirty) TextButton(onClick = { edit.clear(spec.id) }) { Text("放弃改动") }
        }
    }

    editing?.let { index ->
        ItemTextDialog(
            initial = if (index == ADD_NEW) "" else lines.getOrElse(index) { "" },
            title = if (index == ADD_NEW) "添加一条" else "改这一条",
            onDismiss = { editing = null },
            onSave = { value ->
                val next = if (index == ADD_NEW) {
                    if (value.isBlank()) draft else draft.trimEnd('\n') + "\n" + value
                } else {
                    val mutable = lines.toMutableList()
                    if (index in mutable.indices) mutable[index] = value
                    mutable.joinToString("\n")
                }
                edit.setDraft(spec.id, next)
                editing = null
            },
        )
    }
}

/** "添加一条"在编辑对话框里用的哨兵下标（不会与真实的 0..n-1 撞）。 */
private const val ADD_NEW = -1

/**
 * `approval.screen_only_templates` 的**策略 + 标签**控件（二期）。
 *
 * 三档策略写进清单的语义（与 [RulesDisplay.ScreenOnlyMode] 的注释同一份口径）：
 * - **全部询问**：写入**空清单** `[]`；
 * - **全部允许**：写入**全部 6 条**原值；
 * - **自定义允许**：不整体重写，只把下面那组短标签露出来，标签一枚一枚地改、各自立即写入。
 *
 * **点标签（以及全选/全不选）写进清单的永远是原值**：标签上写"闹钟"，`onApply` 里传的是
 * `alarm.show`（见 [RulesDisplay.SCREEN_ONLY_TAGS] 的键）。绿色=不弹卡（在清单里），
 * 灰色=每次问你（不在清单里）；点一下立即保存并生效。
 *
 * 模式本身**不落盘**：它是从"清单此刻长什么样"反推出来的；[RulesExpansionMemory.customPicking]
 * 只记住"用户正打算逐条挑"这个界面状态，一点别的档或全选/全不选就退回反推。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenOnlyTagControl(
    spec: RelaySettings.Spec,
    current: String,
    onApply: (String, String) -> Unit,
    expansion: RulesExpansionMemory,
) {
    val items = decodeList(current)
    val derived = RulesDisplay.screenOnlyMode(items)
    // 「自定义」不是一个存储值：用户点进来才算数；一点别的档就退回"由现值反推"。
    val mode = if (expansion.customPicking(spec.id)) RulesDisplay.ScreenOnlyMode.CUSTOM else derived
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var legendOpen by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val unknown = items.filter { raw -> raw.isNotBlank() && RulesDisplay.SCREEN_ONLY_TAGS.none { it.first == raw } }
    val lines = items

    // 选了「全部询问 / 全部允许」＝整体重写清单（这一档写什么见上面的注释）；
    // 选了「自定义允许」本身**不写任何东西**，只是把标签组露出来。
    // 整体重写会把清单里多出来的名字（alarm.set / 自己加的名字）清掉，所以回执里逐个点名。
    val onPickMode: (RulesDisplay.ScreenOnlyMode) -> Unit = { option ->
        if (option == RulesDisplay.ScreenOnlyMode.CUSTOM) {
            expansion.setCustomPicking(spec.id, true)
        } else {
            expansion.setCustomPicking(spec.id, false)
            val raws = RulesDisplay.screenOnlyPolicyRaws(option) ?: emptyList()
            val dropped = items.filter { it.isNotBlank() && it !in raws }
            val tail = if (dropped.isEmpty()) "" else "（清单里原有的 ${dropped.joinToString("、")} 被清掉了）"
            val head = when (option) {
                RulesDisplay.ScreenOnlyMode.ASK_ALL -> "已改成全部询问：这 6 条以后都会先问你。"
                RulesDisplay.ScreenOnlyMode.ALLOW_ALL -> "已改成全部允许：这 6 条以后都不弹卡。"
                RulesDisplay.ScreenOnlyMode.CUSTOM -> ""
            }
            onApply(encodeList(raws), head + tail)
        }
    }

    Column {
        Text(
            text = "这一项按一个策略来设：",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 2.dp),
        )

        // ── 三选一：全部询问 / 全部允许 / 自定义允许 ──
        RulesDisplay.ScreenOnlyMode.entries.forEach { option ->
            val selected = option == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickMode(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = { onPickMode(option) })
                Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(
                        text = RulesDisplay.modeOptionLabel(option),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = RulesDisplay.modeOptionNote(option),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = RulesDisplay.MODE_DEFAULT_NOTE,
            style = MaterialTheme.typography.labelSmall,
            color = LocalPilotExtraColors.current.tertiaryText,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )

        if (mode == RulesDisplay.ScreenOnlyMode.CUSTOM) {
            // ── 只有"自定义"才露出标签组 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    expansion.setCustomPicking(spec.id, false)
                    onApply(
                        encodeList(RulesDisplay.SCREEN_ONLY_TAGS.map { it.first }),
                        "6 条都选上了 —— 与「全部允许」等价，已切到那个模式。",
                    )
                }) { Text("全选") }
                TextButton(onClick = {
                    expansion.setCustomPicking(spec.id, false)
                    onApply(
                        encodeList(emptyList()),
                        "6 条都改成每次问了 —— 与「全部询问」等价，已切到那个模式。",
                    )
                }) { Text("全不选") }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { legendOpen = true }) { Text("简写对照 (i)") }
            }
            Text(
                text = "点一下切换：绿色 = 助手做这类动作时不弹卡；灰色 = 每次都会先问你。点一下立刻保存生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                RulesDisplay.SCREEN_ONLY_TAGS.forEach { (raw, label) ->
                    val on = raw in items
                    TagChip(
                        label = label,
                        on = on,
                        onClick = {
                            val next = if (on) items - raw else items + raw
                            onApply(
                                encodeList(next),
                                if (on) "已改成每次问你：$label" else "已改成不询问：$label",
                            )
                        },
                        onLongClick = { hint = RulesDisplay.tagHint(raw, label) },
                    )
                }
            }

            // 三级折叠：标签控件的详细说明（二期从控件顶上搬进来的那几句，一个字没删）。
            SectionToggle(
                label = "详细说明",
                open = expansion.open(spec.id, RulesSection.LIST_HELP),
                modifier = Modifier.fillMaxWidth(),
            ) { expansion.toggle(spec.id, RulesSection.LIST_HELP) }
            if (expansion.open(spec.id, RulesSection.LIST_HELP)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "六个全灰 = 这类动作都会先问你（原样等价于这一项为空）。" +
                            "把会写东西的动作加进来，助手就能不经过你确认直接改系统状态，可能打断你当前操作。" +
                            "（下面那些说明里写的「打开页面不询问」就是这一项，是同一份清单。）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp,
                    )
                    Text(
                        text = "完整语义与写入值：长按任意一枚标签，或点上面的「简写对照 (i)」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalPilotExtraColors.current.tertiaryText,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            Text(
                text = "当前是" + RulesDisplay.modeOptionLabel(mode) + "，所以这里不逐条挑。" +
                    "想逐条挑就选「自定义允许」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 清单里不认识的条目：原样显示（不认识就不翻译），每一条都能单独改 / 删。
        if (unknown.isNotEmpty()) {
            Text(
                text = "清单里还有这些名字（不认识，原样显示）：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            unknown.forEach { raw ->
                // 内置 8 条动作里没进那 6 个标签的（alarm.set / timer.set），名字认得出来：
                // 括号里给个说法，但原值照样在最前面 —— 写进清单的还是它。
                val label = RulesDisplay.actionLabel(raw)
                PilotDivider()
                EditableItemRow(
                    text = if (label != raw) "$raw（$label）" else raw,
                    onEdit = { editing = raw },
                    onDelete = { onApply(encodeList(items.filter { it != raw }), "已从清单里删除：$raw") },
                )
            }
        }

        // ── 二期：原来那颗长期占版面的「手动添加一条」按钮收了，改成这颗进底部抽屉 ──
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(onClick = { adding = true }) { Text("新增自定义动作") }
    }

    hint?.let { line ->
        AlertDialog(
            onDismissRequest = { hint = null },
            title = { Text("这枚标签是什么意思") },
            text = { Text(line) },
            confirmButton = { TextButton(onClick = { hint = null }) { Text("知道了") } },
        )
    }

    if (legendOpen) {
        TagLegendDialog(onClose = { legendOpen = false })
    }

    editing?.let { original ->
        ItemTextDialog(
            initial = original,
            title = "改这一条",
            onDismiss = { editing = null },
            onSave = { value ->
                val next = lines.map { if (it == original) value.trim() else it }.filter { it.isNotEmpty() }
                editing = null
                onApply(encodeList(next), "已更新清单条目")
            },
        )
    }

    if (adding) {
        AddActionOverlay(
            onDismiss = { adding = false },
            onAdd = { raw ->
                adding = false
                if (raw.isNotEmpty() && raw !in lines) {
                    onApply(encodeList(lines + raw), "已加入清单：$raw")
                }
            },
        )
    }
}

/**
 * 简写 ↔ 完整语义 ↔ 写入原值 的三列对照（标签组的 (i)）。
 * 与长按标签给的是同一份数据，绝不把显示词写进配置。
 */
@Composable
private fun TagLegendDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("这 6 枚标签分别是什么") },
        text = {
            Column {
                RulesDisplay.tagTable().forEach { (short, full, raw) ->
                    Text(
                        text = "「$short」= $full",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "写进配置的值：$raw",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "屏幕上显示的是简写；写进配置的永远是上面那串原值，一个字节都没变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("知道了") } },
    )
}

/**
 * 「新增自定义动作」的底部抽屉（二期）：任意文本的入口还在，只是不再长期占版面。
 *
 * 写入语义一点没变：这里写的是**原样字符串**，逐字进清单，不做翻译。
 */
@Composable
private fun AddActionOverlay(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalOverlay(onDismiss = onDismiss, align = Alignment.BottomCenter) {
        Text("新增自定义动作", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "这里写的是原样字符串（助手调用时用的名字），会逐字写进清单，不做翻译。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = { value = it; error = null },
            label = { Text("动作名，例如 alarm.set") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = {
                val raw = value.trim()
                error = if (raw.isEmpty()) "写一个名字再点添加" else null
                if (error == null) onAdd(raw)
            }) { Text("加进清单") }
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    }
}

/** 可点标签：开=绿（主色容器），关=灰（surfaceVariant）。文案本身带 ✓，不靠颜色单独表意。
 *
 * 二期标签文字收成 2–3 字简写，于是**长按**这枚标签会给出它的完整语义与写入原值
 * （`onLongClick`）——简写只是屏幕上的说法，进配置的永远是原值。
 */
@Composable
private fun TagChip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.pointerInput(label) {
            detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
        },
    ) {
        Text(
            text = (if (on) "✓ " else "") + label,
            style = MaterialTheme.typography.labelLarge,
            color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** 清单里的一行：正文 + 「编辑」/「删除」，每行各自独立。 */
@Composable
private fun EditableItemRow(text: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 19.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onEdit) { Text("编辑") }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

/** 文档型条目的一行：正文 + 一行等宽机器串 + 「编辑」/「删除」。 */
@Composable
private fun EditableItemRow(text: String, secondary: String?, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            secondary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp,
                )
            }
        }
        TextButton(onClick = onEdit) { Text("编辑") }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

/** 改一条清单条目的弹窗：一个输入框 + 保存/取消，写入仍是由调用方决定的原值。 */
@Composable
private fun ItemTextDialog(
    initial: String,
    title: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存这一条") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
                "「不改状态」这个勾只是标记这条动作只上屏、不新建记录；真正免不免问，还要把内部名字写进「打开页面时不再询问」那份清单。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        templates.forEachIndexed { index, template ->
            if (index > 0) PilotDivider()
            EditableItemRow(
                text = template.name + if (template.noStateChange) "（已标记不改状态）" else "",
                secondary = "${template.id} → ${template.action}",
                onEdit = { draft = TemplateDraft.of(template) },
                onDelete = {
                    val rest = templates.filter { it.id != template.id }
                    onApply(
                        IntentTemplateCatalog.encodeTemplates(rest),
                        "已删除：${template.name}",
                    )
                },
            )
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
            EditableItemRow(
                text = page.name,
                secondary = "${page.key} → ${page.action}",
                onEdit = { draft = PageDraft.of(page) },
                onDelete = {
                    val rest = pages.filter { it.key != page.key }
                    onApply(IntentTemplateCatalog.encodePages(rest), "已删除：${page.name}")
                },
            )
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
            // 主标题用能力自己的中文名（与能力页同源），wire 名缩在下一行当可查标识 ——
            // 与 spec 一样：屏幕上先给人话，机器串只做兜底。
            val label = stringResource(row.descriptor.titleRes)
            CeilingRow(
                title = label,
                wire = row.descriptor.id.wire,
                current = current,
                confirmMode = confirmMode,
                onPick = { picked ->
                    val map = runCatching { JSONObject(overridesJson()) }.getOrNull() ?: JSONObject()
                    map.put(row.descriptor.id.wire, picked)
                    onApply(map.toString(), "已更新：$label（${row.descriptor.id.wire}）的上限")
                },
            )
        }
    }
}

@Composable
private fun CeilingRow(
    title: String,
    wire: String,
    current: String,
    confirmMode: String,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = wire,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                    Text("不改状态（只上屏；免不免问由「打开页面时不再询问」决定）")
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

package dshbox.adapter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import interlock.relay.core.runtime.RelayContainer
import dshbox.adapter.R
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.SystemGrantState
import interlock.relay.core.interlock.InterlockGate

/**
 * 手机助手界面的通用行、分组与状态呈现。
 *
 * 版式一律沿用宿主设置页那套：分组标题 + 描边卡片 + 卡内分隔线，
 * 状态只用主色/错误色/中性色表达，不引入宿主界面没有的色相。
 */
@Composable
fun PilotSection(
    title: String? = null,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = LocalPilotExtraColors.current.tertiaryText,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(horizontal = CARD_PADDING, vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
fun PilotDivider() {
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 分组分隔线：比普通线粗一档、颜色重一档，且**左右顶到卡片边界**。
 *
 * 普通线分的是同一组里的行；分组线分的是同一张卡里不同的分组（例如「交付副本」与
 * 「交付DSH产物」各成一组）。顶边靠绘制外扩实现：Compose 的 padding 不接受负值，
 * 而卡片外壳会按自身形状裁剪，外扩的像素正好被裁在卡片边界上——那就是要的边界。
 */
@Composable
fun PilotGroupDivider() {
    val color = MaterialTheme.colorScheme.outline
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .drawBehind {
                val over = CARD_PADDING.toPx()
                drawRect(
                    color = color,
                    topLeft = Offset(-over, 0f),
                    size = Size(size.width + over * 2, size.height),
                )
            },
    )
}

/** 卡片内容区左右各留的空白；分组线靠它算出要外扩多少才能顶到卡片边界。 */
private val CARD_PADDING = 16.dp

/**
 * 缩进一档的信息行：与它的父行成一组（父行是这一组的读数，本行是它的明细）。
 * 只缩标题，值仍贴右边界，与不缩进的行对齐。
 */
@Composable
fun PilotSubRow(title: String, value: String) {
    PilotRow(title = title, value = value, modifier = Modifier.padding(start = CARD_PADDING))
}

/** 分组标题单独成行时用：一组条目各自成卡，标题不属于其中任何一张卡。 */
@Composable
fun PilotGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/**
 * 无标题的描边卡片：一条能力一张卡，卡内不再画分隔线。
 *
 * [container] 与 [border] 是给"这张卡里有警示"用的（规则页在有会写系统的动作时把整张卡
 * 染成淡黄底 + 琥珀边）。缺省值与从前逐字一致：不传 = 与宿主同款的 surface + outlineVariant。
 */
@Composable
fun PilotCard(
    container: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = border,
    ) {
        Column(modifier = Modifier.padding(horizontal = CARD_PADDING, vertical = 4.dp), content = content)
    }
}

/**
 * 只读信息行：标题不染主色，值靠右单行省略。
 *
 * [valueBelow] 给的是「值本身就长」的那一类行（如一个能力的多个执行模式）：
 * 六种语言里法语与阿拉伯语的三项模式名会超出单行宽度，靠右省略会让用户读到两个
 * 半截模式名，误以为能力只支持部分模式。这类值改为标题下一行完整折行。
 */
@Composable
fun PilotRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueBelow: Boolean = false,
) {
    if (valueBelow) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

/** 可进入下一层的信息行：标题染主色，行尾用字符箭头而不是图标。 */
@Composable
fun PilotActionRow(
    title: String,
    onClick: () -> Unit,
    value: String? = null,
    status: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status != null) {
            PilotStatusDot(color = status)
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
        if (value != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 带说明与开关的行。开关只改本模块自己的档位，不代用户改系统权限。 */
@Composable
fun PilotSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
fun PilotStatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/**
 * 绿白各半的方框开关：放行时轨道左半主色、右半留白，滑块落在留白侧；拦截时整条中性、滑块在起始侧。
 *
 * 本开关**不直接改状态**：运行时权限只能由系统在设置页里授予或撤销，
 * 点击的语义是「把用户送到系统里这一条权限的条目上」，返回后由调用方复查真实授予情况。
 */
@Composable
fun PilotSplitSwitch(on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 26.dp)
            .clip(shape)
            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick),
    ) {
        if (on) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
        Box(
            modifier = Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(3.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(
                    1.dp,
                    if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(4.dp),
                ),
        )
    }
}

/**
 * 一段说明文字。垂直留白只给 6dp：块与块之间的间距由所在容器的 24dp 承担，这里再加
 * 会把连续几条说明撑成互不相关的几段。行高给到 20sp：bodyMedium 的默认行高在长句
 * 折行后过挤。
 */
@Composable
fun PilotNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 20.sp,
        modifier = modifier.padding(vertical = 6.dp),
    )
}

/**
 * 虚线框按钮（三期新增）：整块可点、四周一圈虚线，里面是"图标 + 文字"。
 *
 * 用途是**"试一下 / 加一条"这类软动作**：它不该和卡片里那些真改配置的按钮长成一个样子，
 * 所以刻意用虚线框而不是实心按钮 —— 一眼能看出"这不是写入动作"。
 * 文字色由这里统一给主色（调用方只写 `Text("…")`，不带颜色参数）。
 */
/**
 * 虚线框按钮：**强调"这里还有一件事可以做"**，但语气比实心主按钮弱。
 *
 * 七期起可以换一支色（[tint]）：默认主色（配置类，如「＋ 新增自定义动作」），
 * 传别的颜色就能把另一类动作区分开（如「⚡ 模拟运行一次」用测试蓝）——
 * 虚线框本身表达"轻量动作"，色相 + 图标表达"这是哪一类"。
 */
@Composable
fun DashedActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    pill: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = tint
    val line = MaterialTheme.colorScheme.outline
    val shape = if (pill) RoundedCornerShape(999.dp) else RoundedCornerShape(12.dp)
    val radius = if (pill) 999.dp else 12.dp
    val stroke = 1.dp
    Row(
        modifier = modifier
            .clip(shape)
            .background(accent.copy(alpha = 0.04f))
            .clickable(onClick = onClick)
            .drawBehind {
                // 虚线按当前尺寸现画：框会跟着文字换行自动长高，不需要固定高度。
                val w = stroke.toPx()
                val inset = w / 2f
                drawRoundRect(
                    color = line,
                    topLeft = Offset(inset, inset),
                    size = Size(
                        (size.width - w).coerceAtLeast(0f),
                        (size.height - w).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(
                        radius.toPx().coerceAtMost(size.minDimension / 2f),
                    ),
                    style = Stroke(
                        width = w,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f),
                    ),
                )
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Text(text = icon, style = MaterialTheme.typography.labelLarge, color = accent)
            Spacer(modifier = Modifier.width(6.dp))
        }
        CompositionLocalProvider(LocalContentColor provides accent) { content() }
    }
}

/**
 * 小胶囊按钮（三期新增）：与标签同款形状，一行里排得下好几枚，窄屏可换行。
 * 内容色沿用所在容器（卡片正文色），调用方按需要给 `Text(..., color = …)`。
 */
@Composable
fun PilotSmallChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun PilotPagePadding(content: @Composable (Modifier) -> Unit) {
    content(Modifier.padding(PaddingValues(horizontal = 24.dp, vertical = 24.dp)))
}

/** 下拉列表的最大高度（长列表在这个高度里滚动，不把屏幕撑满）。 */
val PILOT_MENU_MAX_HEIGHT: Dp = 320.dp

/** 列表与窗口边缘至少留这么多，免得贴边或被状态栏/手势区吃掉。 */
private val PILOT_MENU_EDGE: Dp = 8.dp

/**
 * 锚点感知的落点：**优先在锚点下方；下方装不下、上方更宽裕就翻到上方**；
 * 两边都紧就贴着空间大的那一边，并且永远夹在窗口里（不溢出屏幕）。
 *
 * 判定依据（三个数都来自框架，不靠估算）：
 * - `anchorBounds`：锚点在窗口里的位置（Popup 自己会给），`bottom` 以下、`top` 以上就是两个候选空间；
 * - `popupContentSize`：**列表量出来的真实高度**（已被 [PILOT_MENU_MAX_HEIGHT] 夹过），不是估算值；
 * - `windowSize`：窗口像素尺寸。
 *
 * 规则一句话：`下方剩余 < 列表高度 && 上方剩余 > 下方剩余` ⇒ 朝上弹，否则朝下；
 * 最后两个方向都再夹进 `[边距, 窗口 - 内容 - 边距]`。
 */
private class AnchorAwareMenuPosition(private val edgePx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val edge = edgePx
        val spaceBelow = windowSize.height - anchorBounds.bottom - edge
        val spaceAbove = anchorBounds.top - edge
        val flipUp = spaceBelow < popupContentSize.height && spaceAbove > spaceBelow
        val rawY = if (flipUp) anchorBounds.top - popupContentSize.height else anchorBounds.bottom
        val maxY = (windowSize.height - popupContentSize.height - edge).coerceAtLeast(edge)
        val y = rawY.coerceIn(edge, maxY)
        val maxX = (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge)
        val x = anchorBounds.left.coerceIn(edge, maxX)
        return IntOffset(x, y)
    }
}

/**
 * 下拉选择器容器（七期）：**可滚动、有最大高度、靠近屏幕底部时朝上弹**。
 *
 * 为什么不用 `DropdownMenu`：默认那一颗不限制高度，条目一多就会顶出屏幕；
 * 这里用 [Popup] + 自写的 [PopupPositionProvider]，把"翻不翻"这件事握在自己手里，
 * 而且列表高度是**量出来**的（`popupContentSize`），不需要估算。
 *
 * 点选后由调用方自己 `onDismiss()`（本组件不替它关）。
 */
@Composable
fun PilotPickerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = PILOT_MENU_MAX_HEIGHT,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    // 边距换算成像素交给落点计算（那一步不是 @Composable，拿不到 LocalDensity）。
    val edgePx = with(LocalDensity.current) { PILOT_MENU_EDGE.roundToPx() }
    val position = remember(edgePx) { AnchorAwareMenuPosition(edgePx) }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = modifier.widthIn(min = 220.dp, max = 340.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
        }
    }
}

/** 下拉列表里的分组标题：轻一行字，不出现在写入值里（分类只是展示）。 */
@Composable
fun PilotPickerGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp),
    )
}

/**
 * 能力列表每一条前面的圆点：三档里「禁止」是中性点，「询问审批 / 完全访问」是主色点，
 * 而档位已开、系统那一侧还缺东西的是红点。
 *
 * 系统六态的颜色不在这里表达：六态说的是系统条件的细分，圆点说的是用户此刻需不需要
 * 处理这条能力。设为禁止的能力不着告警色（决定已由用户做完），只有缺系统授权才标红。
 * 六态的文字标签在能力详情页逐项给出。
 */
@Composable
fun capabilityDotColor(row: RelayContainer.CapabilityRow): Color = when {
    row.state.tier == AccessTier.DENIED -> MaterialTheme.colorScheme.outline
    InterlockGate.systemBlocks(row.state.system) -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun systemStateLabel(state: SystemGrantState): String = stringResource(
    when (state) {
        SystemGrantState.GRANTED -> R.string.pilot_sys_granted
        SystemGrantState.RUNTIME_ASKABLE -> R.string.pilot_sys_askable
        SystemGrantState.MANUAL_ONLY -> R.string.pilot_sys_manual
        SystemGrantState.SESSION_CONSENT -> R.string.pilot_sys_session_consent
        SystemGrantState.FOREGROUND_FOCUS -> R.string.pilot_sys_foreground_focus
        SystemGrantState.UNAVAILABLE_ON_DEVICE -> R.string.pilot_sys_unavailable
    },
)

@Composable
fun tierLabel(tier: AccessTier): String = stringResource(
    when (tier) {
        AccessTier.DENIED -> R.string.pilot_tier_denied
        AccessTier.ASK -> R.string.pilot_tier_ask
        AccessTier.ALWAYS -> R.string.pilot_tier_always
    },
)

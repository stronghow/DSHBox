package dshbox.adapter.ui

import interlock.relay.core.settings.RelaySettings
import org.json.JSONArray

/**
 * 规则与配置页的「显示词」层：**只决定怎么读，不决定写什么**。
 *
 * 四条纪律（改这个文件之前先读这四条）：
 *
 * 1. 这里只有字符串映射与"由现值算给人看的说法"，没有判定、没有默认值、没有写入路径。
 *    页面上的一切行为仍然由 [RelaySettings] 那张声明表与
 *    [interlock.relay.core.settings.SettingsStore] 决定。
 * 2. **屏幕上的译文 ≠ 落盘的值。** 标签上写「闹钟」，写进 `cfg_approval.screen_only_templates`
 *    的仍然是 `alarm.show`；页面标题写「打开页面时不再询问」，存储键仍然是
 *    `cfg_approval.screen_only_templates`。写入一律走 schema 的原值，本文件不参与写入，
 *    也不会把显示词拼进任何要保存的字符串 —— [screenOnlyPolicyRaws] 交出去的是**原值清单**，
 *    编码与写入由页面走 `encodeList(...)` 与 `viewModel.setSetting(...)`。
 * 3. **查不到就回落原文，绝不回落成空串。** 新增一条 spec、或用户往清单里加了一个我们不认识的
 *    名字（新模板名、新包名、厂商自定义页面键）时，标题与标签直接显示原值 —— 宁可露出机器串，
 *    也不能出现一个没有标题的卡片。
 * 4. 这里算出来的每句话都必须能追到 schema 的原文（`summary` / `effect` / `riskText` / `scope` /
 *    `preview` 或 `default`）。**本文件不新增任何关于系统行为的事实**：精简版只是原文的一个前缀，
 *    全文一定还在更深一层里读得到。
 */
internal object RulesDisplay {

    /**
     * 24 条配置的用户语言标题（id → 人话）。页面的主标题一律取这里；源代码 id 只出现在
     * 长按标题、「了解风险与代价 → 开发者说明」与详情区里，作为可查信息保留。
     *
     * schema 里的 `Spec.title` 仍是这一层的**兜底**：表里查不到（新增/未知 id）就用它，
     * 它也是空白时再退到 id 本身。
     */
    private val TITLES: Map<String, String> = mapOf(
        // 常用
        "approval.screen_only_templates" to "打开页面时不再询问",
        "intent.settings_pages" to "助手能打开哪些设置页",
        "intent.user_templates" to "自定义的上屏动作",
        "tier.per_capability" to "每条能力要不要先问你",
        "memory.session_grant_minutes" to "「本会话内允许」管多久",
        "surface.preference" to "助手在哪块屏上干活",
        // 高级
        "approval.host_bring_to_front" to "收尾时把你带回来",
        "diagnostic.verbose_log" to "记录详细日志",
        "shell.verb_optins" to "系统命令的逐条开关",
        "runtime.intent_timeout_ms" to "一次调用最多等多久",
        "runtime.wait_for_max_ms" to "等界面变化的上限",
        "runtime.response_ttl_minutes" to "调用结果保留多久",
        "capture.foreground_route" to "截图走哪条路",
        "capability.disabled" to "彻底停用某些能力",
        "ui.show_unwired" to "显示未接线项",
        "ui.danger_confirm_mode" to "危险项怎么确认",
        // 危险
        "danger.consent_screen_enabled" to "点系统授权框前先问你",
        "danger.consent_extra_packages" to "还要防哪些授权宿主",
        "danger.consent_package_prefixes" to "厂商权限管理器前缀",
        "danger.consent_view_id_keywords" to "授权按钮的特征词",
        "danger.consent_relative_is_consent" to "相对定位也算授权框",
        "danger.ceiling_overrides" to "能力上限（能免问到哪档）",
        "danger.allow_once_remembers_session" to "「允许一次」也记住一段",
        "danger.dangerous_tier_defaults" to "出厂就不问你的能力",
    )

    /** 主标题：人话标题 → schema 标题 → id，三级兜底，任何一级都不会是空串。 */
    fun title(spec: RelaySettings.Spec): String =
        TITLES[spec.id] ?: spec.title.ifBlank { spec.id }

    /**
     * `approval.screen_only_templates` 里那 6 条内置动作的**短标签**（二期：2–3 字简写），
     * 顺序即界面上的顺序。
     *
     * **键是写进配置的原值**（`alarm.show` 等，与 `IntentTemplates.noStateChange` 同源同值），
     * 值只是屏幕上的说法（一期是整句「打开闹钟页面」，二期收成「闹钟」；完整语义在
     * [tagFullMeaning] 里，长按标签或点标签组旁边的 (i) 都能读到）。
     * 点标签时写进清单的永远是键 —— **二期只换了显示词，写入值一个字节没动**。
     */
    val SCREEN_ONLY_TAGS: List<Pair<String, String>> = listOf(
        "alarm.show" to "闹钟",
        "timer.show" to "倒计时",
        "settings.open" to "设置",
        "app.info" to "App信息",
        "dial" to "拨号",
        "web.open" to "网页",
    )

    /**
     * 内置 8 条动作模板的**完整语义**。表外的名字（自定义模板 / 未知项）回落原值。
     *
     * 两个用处：① 短标签旁边的说法（长按 / 简写对照弹层）；② 清单里"不在那 6 个标签上"的
     * 条目（例如用户把 `alarm.set` 加进免问清单）在屏幕上给个说法 —— 写进配置的仍是原值。
     */
    private val ACTION_LABELS: Map<String, String> = mapOf(
        "alarm.set" to "新建闹钟",
        "timer.set" to "新建倒计时",
        "alarm.show" to "打开闹钟页面",
        "timer.show" to "打开倒计时页面",
        "settings.open" to "打开设置页",
        "app.info" to "查看应用信息",
        "dial" to "打开拨号盘",
        "web.open" to "打开网页",
    )

    fun actionLabel(raw: String): String = ACTION_LABELS[raw] ?: raw

    /** 一枚短标签的完整语义（长按提示与「简写对照」弹层用）。查不到就回落原值。 */
    fun tagFullMeaning(raw: String): String = ACTION_LABELS[raw] ?: raw

    /**
     * 长按一枚标签时给的一句话：**简写 ↔ 完整语义 ↔ 写进配置的原值**三样都在。
     * 这是"标签文字收短"之后完整语义的去处之一（另一处是标签组旁边的 (i) 弹层）。
     */
    fun tagHint(raw: String, short: String): String =
        "「$short」= ${tagFullMeaning(raw)}。写进配置的值是 $raw（助手调用时用的名字，不会写成中文）。"

    /**
     * 未在这 6 枚标签上的清单条目在界面上的说法：认得出来的给中文（`alarm.set（新建闹钟）`），
     * 认不出来的一律原样显示 —— **写进配置的仍是原值**。
     */
    fun rawItemLabel(raw: String): String {
        val full = ACTION_LABELS[raw]
        return if (full != null && full != raw) "$raw（$full）" else raw
    }

    /** 简写 ↔ 完整语义 ↔ 写入原值 的三列对照（长按提示与 (i) 弹层共用同一份数据）。 */
    fun tagTable(): List<Triple<String, String, String>> =
        SCREEN_ONLY_TAGS.map { (raw, short) -> Triple(short, tagFullMeaning(raw), raw) }

    // ───────────────────────── 策略模式（针对清单型这一项） ─────────────────────────

    /**
     * 免问清单的三档策略。**模式不是一个存储项** —— 它完全由"清单此刻长什么样"反推出来，
     * 所以读回时永远和值一致（见 [screenOnlyMode]）。
     *
     * 写入语义（二期要求，写进代码与报告）：
     * - [ASK_ALL]   全部询问：写入**空清单** `[]`（每一项都会先问你）。
     * - [ALLOW_ALL] 全部允许：写入**全部 6 条**原值（不弹卡，直接执行）。
     * - [CUSTOM]    自定义允许：显示下面那组标签，逐条挑；**选它本身不写任何东西**，
     *   之后的每一次标签切换各自立即写入。
     */
    enum class ScreenOnlyMode { ASK_ALL, ALLOW_ALL, CUSTOM }

    /**
     * 由现值反推当前模式：**空 = 全部询问**；**6 条全在 = 全部允许**；**其它 = 自定义**。
     *
     * 反推只看"那 6 条在不在"，多出来的名字（`alarm.set` 之类）不影响判定：6 条全在时仍是
     * 「全部允许」，因为那正是它的定义（不弹卡直接执行）；只要缺一条就不是全部允许。
     */
    fun screenOnlyMode(current: List<String>): ScreenOnlyMode {
        val present = current.filter { it.isNotBlank() }.toSet()
        val tags = SCREEN_ONLY_TAGS.map { it.first }
        return when {
            present.isEmpty() -> ScreenOnlyMode.ASK_ALL
            tags.all { it in present } -> ScreenOnlyMode.ALLOW_ALL
            else -> ScreenOnlyMode.CUSTOM
        }
    }

    /**
     * 一个模式整体重写清单时要写进去的原值；[ScreenOnlyMode.CUSTOM] 回 `null`
     * （自定义模式不整体重写，由标签一枚一枚地改）。页面负责 `encodeList(...)` 与写入。
     */
    fun screenOnlyPolicyRaws(mode: ScreenOnlyMode): List<String>? = when (mode) {
        ScreenOnlyMode.ASK_ALL -> emptyList()
        ScreenOnlyMode.ALLOW_ALL -> SCREEN_ONLY_TAGS.map { it.first }
        ScreenOnlyMode.CUSTOM -> null
    }

    /** 模式在单选里的说法（二期：三选一，直接写在页面上）。 */
    fun modeOptionLabel(mode: ScreenOnlyMode): String = when (mode) {
        ScreenOnlyMode.ASK_ALL -> "全部询问（最安全）"
        ScreenOnlyMode.ALLOW_ALL -> "全部允许（不弹卡，直接执行）"
        ScreenOnlyMode.CUSTOM -> "自定义允许（按下面选中的）"
    }

    /** 模式下面那一行解释：把"选了它会写什么"说清楚，不改任何事实。 */
    fun modeOptionNote(mode: ScreenOnlyMode): String = when (mode) {
        ScreenOnlyMode.ASK_ALL -> "写入空清单：这 6 条以后都先问你。"
        ScreenOnlyMode.ALLOW_ALL -> "写入全部 6 条：这 6 条以后都不弹卡。"
        ScreenOnlyMode.CUSTOM -> "下面打勾的才不弹卡，其余每次都问你。"
    }

    /**
     * 模式底下那句"出厂默认是哪一档"的说明。**必须留着**：schema 的默认值是 6 条全在
     * （= 全部允许），把「全部询问」读成"出厂默认"会与卡片上那句「（默认）」自相矛盾。
     */
    const val MODE_DEFAULT_NOTE: String =
        "出厂默认是「全部允许」（这 6 条都在清单里）；「全部询问」是你自己能选的最安全一档，" +
            "它写的是空清单，和「恢复默认」不是一回事。"

    // ───────────────────────── 卡片警示：会写系统的动作 ─────────────────────────

    /**
     * 这张卡片里的配置是不是**引入了会写系统的动作**；是的话回一句警示，页面据此给整张卡片
     * 加淡黄底色 + 琥珀描边。判定只看两处，都是 schema 自查得到的事实：
     *
     * 1. `approval.screen_only_templates` 的清单里出现了**不在那 6 条「只上屏」名单上**的名字
     *    （`alarm.set` / `timer.set` / 用户自己加的名字）。schema 的 riskText 原话就是
     *    "把会写东西的动作（比如设闹钟）加进来，助手就能不问你直接改系统"。
     * 2. `danger.consent_screen_enabled` 关着：助手操作系统的权限授予界面时不再先问，
     *    可能替你点掉别的应用的权限请求（schema 的 effect 原文）。
     *
     * 刻意**不**拿 `intent.user_templates` 的「不改状态」勾当判据：schema 明确写着那个勾
     * "只是标记这条动作只上屏、不新建记录；真正免不免问由清单决定"，拿它当"会写系统"的判据
     * 会把一个标记读成一条判据 —— 那是本页最不该犯的错。
     *
     * 返回值是给人看的一句话（不是判定结果），null = 这张卡片不需要警示。
     */
    fun stateWriteWarning(spec: RelaySettings.Spec, current: String): String? = when (spec.id) {
        RelaySettings.SCREEN_ONLY_TEMPLATES -> {
            val writes = decodeList(current).filter { raw ->
                raw.isNotBlank() && SCREEN_ONLY_TAGS.none { it.first == raw }
            }
            if (writes.isEmpty()) {
                null
            } else {
                "清单里有会写系统的动作：" +
                    writes.joinToString("、") { rawItemLabel(it) } +
                    "。这几条不再问你 —— 助手可以直接改设备状态。"
            }
        }

        RelaySettings.CONSENT_ENABLED ->
            if (current == "false") {
                "这一项关着：助手操作系统的权限授予界面时不再先问你，可能替你点掉别的应用的权限请求。"
            } else {
                null
            }

        else -> null
    }

    // ───────────────────────── 正文瘦身：精简版「改了会怎样」 ─────────────────────────

    /**
     * 精简版只取原文开头的完整句子，**不裁剪句子内部**（截半句会读成另一件事）。
     * 原文一个字都没删：全文永远在「开发者说明 ▾」的第一段里。
     */
    private const val EFFECT_SHORT_MAX = 62

    private fun shortEffect(full: String): String {
        val sentences = full.split('。').map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return full
        var acc = sentences.first()
        for (i in 1 until sentences.size) {
            if (acc.length + 1 >= EFFECT_SHORT_MAX) break
            acc = acc + "。" + sentences[i]
        }
        return acc + "。"
    }

    /** 「改了会怎样」的精简版（永远非空）。 */
    fun effectShort(spec: RelaySettings.Spec): String = shortEffect(spec.effect)

    /** 精简版是不是真的比原文短 —— 短了才显示"完整原文在开发者说明里"那句指路。 */
    fun effectShortened(spec: RelaySettings.Spec): Boolean = shortEffect(spec.effect) != spec.effect

    // ───────────────────────── 演示脚本（模拟运行一次） ─────────────────────────

    /**
     * 「模拟运行一次」遮罩层里的**前两步**演绎。**全都来自 schema 原文与此刻的值**，
     * 不执行任何系统动作，也不预演任何本页读不到的东西：
     *
     * 第 1 步＝什么时候会轮到这一项（`spec.scope`）；第 2 步＝按你现在的配置判据怎么走
     * （清单型由现值当场算，其余类型给"当前：<值>（默认/已改）"）。
     * 第 3 步「接下来会发生什么」由页面直接渲染 `spec.preview` 原文 —— 那正是"试一下"的正文。
     *
     * @param currentRaw 这一项的原始存储值（用于 `isDefault` 与清单解码）
     * @param currentDisplay 页面折叠态那句「当前：…」已经算好的给人看的说法
     */
    fun simulateContext(
        spec: RelaySettings.Spec,
        currentRaw: String,
        currentDisplay: String,
    ): List<Pair<String, String>> = listOf(
        "第 1 步 · 什么时候会轮到这里" to "当助手做这些事的时候：${spec.scope}",
        "第 2 步 · 按你现在的配置" to if (spec.id == RelaySettings.SCREEN_ONLY_TEMPLATES) {
            liveScreenOnlyLine(currentRaw)
        } else {
            "当前：$currentDisplay（" + (if (spec.isDefault(currentRaw)) "默认" else "已改") + "）"
        },
    )

    /** 免问清单此刻的走法（用完整语义，不用简写 —— 演示里要说清是哪件事）。 */
    fun liveScreenOnlyLine(current: String): String {
        val quiet = decodeList(current).filter { it.isNotBlank() }
        val quietText = if (quiet.isEmpty()) {
            "（无）"
        } else {
            quiet.joinToString("、") { tagFullMeaning(it) }
        }
        val ask = SCREEN_ONLY_TAGS.map { it.first }.filter { it !in quiet }
        val askText = if (ask.isEmpty()) "（无）" else ask.joinToString("、") { tagFullMeaning(it) }
        return "不弹卡直接做：$quietText；会先弹卡问你：$askText"
    }

    /** 清单的 JSON 解码（只读；解不开就回空表，界面不因一段坏文本崩掉）。 */
    private fun decodeList(text: String): List<String> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).map { array.optString(it) }
    }.getOrDefault(emptyList())
}

package dshbox.adapter.ui

import interlock.relay.core.exec.direct.IntentTemplates
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
        "tier.per_capability" to "逐项权限控制",
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
     * 折叠态那句副标题（一句话说作用）的人话覆盖表 —— 与 [TITLES] 同一套做法：**只换屏幕上怎么说，
     * 不改 schema 里的任何一个字**（原文仍在开发者说明里可读，见页面里的 `summaryOverridden` 分支）。
     *
     * 只收"用户读不懂或会误解"的那几条：schema 原文把底层枚举直接当句子
     * （例：`逐条设置：禁止 / 每次询问 / 完全访问。`）—— 那三个词是**能力页里的档位名**，
     * 摆在这一行只会让人以为这里能选。没有登记在表里的仍逐字显示 `spec.summary`。
     */
    private val SUMMARIES: Map<String, String> = mapOf(
        // 这一项是个入口（Link 型），本页读不到逐条状态，所以只说"能干什么"，不摆档位枚举。
        "tier.per_capability" to "可单独配置每项能力的访问权限。",
    )

    /** 折叠态副标题：有覆盖用覆盖，没有就逐字用 schema 原文。 */
    fun summary(spec: RelaySettings.Spec): String = SUMMARIES[spec.id] ?: spec.summary

    /**
     * 这一项的副标题是否被换过说法 —— 换过才在开发者说明里补一份 schema 原文（零丢失），
     * 没换过就不重复渲染，免得"同一句话在一张卡里出现两遍"。
     */
    fun summaryOverridden(spec: RelaySettings.Spec): Boolean = spec.id in SUMMARIES

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

    /**
     * 模式下面那一行解释：只留"选了它以后会怎样"这一句人话（三期）。
     *
     * 二期这里写的是"写入空清单：…"／"写入全部 6 条：…"——那半句是**写入语义**（技术说法），
     * 三期把它移进策略 (i) 弹层（见 [modeWriteNote] 与 [MODE_DEFAULT_NOTE]），正文只留结果。
     */
    fun modeOptionNote(mode: ScreenOnlyMode): String = when (mode) {
        ScreenOnlyMode.ASK_ALL -> "这 6 条以后都先问你。"
        ScreenOnlyMode.ALLOW_ALL -> "这 6 条以后都不弹卡。"
        ScreenOnlyMode.CUSTOM -> "下面打勾的才不弹卡，其余每次都问你。"
    }

    /**
     * 「选这一档到底写进去什么」——写入语义的技术说明。**页面正文里不再显示**（三期），
     * 只在策略 (i) 弹层里逐条读出。写入路径本身一个字节没动：这里只是把
     * [screenOnlyPolicyRaws] 的行为用中文复述一遍，供弹层渲染，不参与任何写入。
     */
    fun modeWriteNote(mode: ScreenOnlyMode): String = when (mode) {
        ScreenOnlyMode.ASK_ALL ->
            "全部询问：写进配置的是一个空清单 []（这 6 条都不在清单里），每一类动作都会先问你。"
        ScreenOnlyMode.ALLOW_ALL ->
            "全部允许：写进配置的是那 6 条原值（与出厂默认逐字相同），这 6 条都不再弹卡。"
        ScreenOnlyMode.CUSTOM ->
            "自定义允许：不整体重写清单，下面每一枚标签各自写入自己那一条原值。"
    }

    /**
     * 正文上只留的这一句出厂默认说明（三期）。
     *
     * 二期的正文是一整段（[MODE_DEFAULT_NOTE]），用户反馈那一段"太技术"。三期正文只留这一句，
     * 完整那段原封不动进策略 (i) 弹层 —— **信息一个字没删，只是换了读的地方**。
     */
    const val MODE_DEFAULT_SHORT: String = "默认就是「全部允许」这一档。"

    /**
     * 模式底下那句"出厂默认是哪一档"的完整说明（二期原文，三期移进策略 (i) 弹层）。
     * **必须留着**：schema 的默认值是 6 条全在（= 全部允许），把「全部询问」读成"出厂默认"
     * 会与卡片上那句「（默认）」自相矛盾。
     */
    const val MODE_DEFAULT_NOTE: String =
        "出厂默认是「全部允许」（这 6 条都在清单里）；「全部询问」是你自己能选的最安全一档，" +
            "它写的是空清单，和「恢复默认」不是一回事。"

    /**
     * 标签组上方那一行引导（三期：**只留一行**）。
     *
     * 二期这里是两句话的长段落；三期把绿色/灰色怎么读压成一句，其余解释（为什么、代价、
     * 与「打开页面不询问」是不是同一份清单）整段收进策略 (i)（见 [TAG_LIST_HELP]）。
     */
    const val CUSTOM_GUIDE: String =
        "点一下切换：绿色 = 免问，灰色 = 每次先问（点一下立刻保存生效）。"

    /**
     * 没露出标签组时的那一行指路（三期）。只指路、不重复"这一档会写什么"——
     * 那已经写在各档自己的小字里了（见 [modeOptionNote]）。
     */
    const val NO_TAG_PICKING_HINT: String = "想逐条挑就选上面的「自定义允许」。"

    /**
     * 标签控件那段长解释（**二期原文，三期整段移进策略 (i) 弹层，一个字没删**）。
     *
     * 它解释的是"六个全灰"的含义、把会写东西的动作加进来的代价，以及它与「打开页面不询问」
     * 是同一份清单 —— 这三条事实一条都不能丢，只是不再占卡片正文。
     */
    const val TAG_LIST_HELP: String =
        "六个全灰 = 这类动作都会先问你（原样等价于这一项为空）。" +
            "把会写东西的动作加进来，助手就能不经过你确认直接改系统状态，可能打断你当前操作。" +
            "（下面那些说明里写的「打开页面不询问」就是这一项，是同一份清单。）"

    /** 长解释末尾那句指路（跟着 [TAG_LIST_HELP] 一起进 (i) 弹层）。 */
    const val TAG_HINT_POINTER: String =
        "完整语义与写入值：长按任意一枚标签，或点「简写对照 (i)」看三列对照。"

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
            // 八期：触发条件就是这一行（清单里出现了不在那 6 条「只上屏」名单上的名字）。
            // 具体怎么说那句话由 stateWriteWarnings 按"出身"分派：内置会写系统的说"会写系统"，
            // 未内置的说"无法确认它是否只上屏"。
            val extras = decodeList(current).filter { raw ->
                raw.isNotBlank() && SCREEN_ONLY_TAGS.none { it.first == raw }
            }
            if (extras.isEmpty()) {
                null
            } else {
                stateWriteWarnings(spec, current).joinToString(" ") { it.plain() }
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


    // ─────────────────── 八期：清单里的动作属于哪一类（话必须是真的）───────────────────

    /**
     * 清单里一条动作的**出身**。这决定界面怎么描述它 —— 三种措辞各自对应一种事实，
     * 不许拿"会写系统"去说一条我们根本判断不了的动作。
     *
     * - [BUILTIN_SCREEN_ONLY]：内置、且只用上屏（就是那 6 枚标签的动作，`IntentTemplates.noStateChange`）；
     * - [BUILTIN_STATE_WRITE]：内置、但会改设备状态（`alarm.set` / `timer.set`）—— 说它"会写系统"是有依据的；
     * - [CUSTOM_UNKNOWN]：**不在内置名单里**（用户自己加的名字）—— 我们**无法判断**它会不会写系统，
     *   只能说"未内置、无法确认"，然后**照有风险处理**（保守，但不撒谎）。
     */
    enum class ActionKind { BUILTIN_SCREEN_ONLY, BUILTIN_STATE_WRITE, CUSTOM_UNKNOWN }

    /** 一条动作的出身。认得出来靠的是两张内置名单，不猜。 */
    fun actionKind(raw: String): ActionKind = when {
        SCREEN_ONLY_TAGS.any { it.first == raw } -> ActionKind.BUILTIN_SCREEN_ONLY
        raw in IntentTemplates.names -> ActionKind.BUILTIN_STATE_WRITE
        else -> ActionKind.CUSTOM_UNKNOWN
    }

    /**
     * 卡片警示的一句话：被动作名切成 [lead] + 名字 + [tail] 三段，
     * 名字单独拿出来是为了在界面上**用等宽高亮**（`alarm.get` 一眼看得见是哪个）。
     */
    data class StateWriteWarning(
        val kind: ActionKind,
        val names: List<String>,
        val lead: String,
        val tail: String,
    ) {
        /** 拼回一句话（给读屏 / 纯文本回退用，也保证与老调用点逐字兼容）。 */
        fun plain(): String = lead + names.joinToString("、") + tail
    }

    /**
     * 卡片级警示的**结构化**版本：按出身分成几条，每条各自说自己的实话。
     *
     * 内置但会写系统的：说"会写系统"（schema 的 riskText 原话就是"把会写东西的动作加进来…"）。
     * 未内置（用户自己加的）：**不说"会写系统"** —— 那是没有依据的断言；
     * 只说"这是你自定义的动作（未内置），我们无法确认它是否只上屏，因此按有风险处理"。
     */
    fun stateWriteWarnings(spec: RelaySettings.Spec, current: String): List<StateWriteWarning> {
        if (spec.id != RelaySettings.SCREEN_ONLY_TEMPLATES) return emptyList()
        val extras = decodeList(current).filter { raw ->
            raw.isNotBlank() && SCREEN_ONLY_TAGS.none { it.first == raw }
        }
        if (extras.isEmpty()) return emptyList()
        val writes = extras.filter { actionKind(it) == ActionKind.BUILTIN_STATE_WRITE }
        val customs = extras.filter { actionKind(it) == ActionKind.CUSTOM_UNKNOWN }
        return buildList {
            if (writes.isNotEmpty()) {
                add(
                    StateWriteWarning(
                        kind = ActionKind.BUILTIN_STATE_WRITE,
                        names = writes,
                        lead = "清单里有会写系统的动作：",
                        tail = "。这几条不再问你 —— 助手可以直接改设备状态。",
                    ),
                )
            }
            if (customs.isNotEmpty()) {
                add(
                    StateWriteWarning(
                        kind = ActionKind.CUSTOM_UNKNOWN,
                        names = customs,
                        lead = "这是你自定义添加的动作（未内置）：",
                        tail = "。我们无法确认它是否只上屏，所以按有风险处理 —— 这几条也不再问你，" +
                            "助手会按这个名字原样执行。",
                    ),
                )
            }
        }
    }

    /** 卡片上要等宽高亮那几个名字（未内置的自定义动作）。 */
    fun customActionNames(spec: RelaySettings.Spec, current: String): List<String> =
        stateWriteWarnings(spec, current)
            .filter { it.kind == ActionKind.CUSTOM_UNKNOWN }
            .flatMap { it.names }

    /**
     * 清单里那些**未内置**的动作在界面上的分组标题（八期）。
     *
     * 老文案是"不认识，原样显示" —— 那像在推卸责任（是用户主动加的东西）；
     * 现在中立、肯定用户的行为，同时把事实说清：不在内置名单里，助手按原样执行。
     */
    const val CUSTOM_ACTIONS_LABEL: String = "自定义动作（未内置，按原样执行）"

    /** 混排进标签组之后，橙色标签旁边那一行说明（八期）。 */
    const val CUSTOM_MIX_NOTE: String =
        "橙色的是你自定义的动作：助手按名字原样执行；我们无法确认它只上屏，所以按有风险处理。"

    /** 橙色标签怎么操作（八期：点=改，长按或 ✕=删）。 */
    const val CUSTOM_CHIP_HINT: String =
        "点橙色标签改名字；长按它、或点标签上的 ✕ 删掉（删之前会再问你一次）。"

    /**
     * 「按策略运行」状态行（八期）：把模式与清单里的自定义动作放在一句话里说清，
     * 不再把自定义项单独摘出来当成"异常"。没有自定义动作时返回 null。
     */
    fun policyLine(mode: ScreenOnlyMode, customNames: List<String>): String? {
        if (customNames.isEmpty()) return null
        val (head, tail) = when (mode) {
            ScreenOnlyMode.ALLOW_ALL -> "当前按策略运行：全部允许。" to "。这几条也免问。"
            ScreenOnlyMode.ASK_ALL -> "当前按策略运行：全部询问。" to "。这几条也会先问你。"
            ScreenOnlyMode.CUSTOM ->
                "当前按策略运行：自定义允许。" to "。这几条按清单走（在清单里就免问、不在就先问你）。"
        }
        return head + "其中包含自定义动作：" + customNames.joinToString("、") + tail
    }


    // ─────────────────── 九期：设置页弹窗（文案 / 校验 / 参数录入）───────────────────

    /** 弹窗正文只留这两句（九期）；技术细节整段移进旁边的 (i)，一句话没删。 */
    const val PAGE_PRESET_SHORT: String =
        "点上面按钮可快速填入内置页面。自定义页面要自己从系统里抄指令，助手不会替你猜。"

    /** 上面那两句话背后的技术细节（九期从正文移进 (i)）：来源、写入形状、上限都在这里。 */
    const val PAGE_PRESET_DETAIL: String =
        "上面按钮里的动作串只有两处来源，都指得出源码：" +
            "① 内置 8 个设置页（与实现同一批系统常量，`DirectBackend.settingsAction(page)`）；" +
            "② 内置模板在用的那几个动作串（标了「不是设置页」，来自 `DirectBackend.buildTemplateIntent`）。" +
            "附加参数每行一条 `键=值`：键名 `[A-Za-z_][A-Za-z0-9_.]{0,63}`、最多 16 条、" +
            "值按形状推断（`true`/`false` → 开关，整数 → 数字，其余 → 文本）、单个值最长 200 字。" +
            "调用代号（页面键）`[a-z][a-z0-9_]{1,30}`，不能与内置 8 页重名 —— 与配置中心同一套校验。"

    /**
     * 调用代号撞上内置页时的提示（九期：从"保存时才失败"改成**输入时就红**）。
     *
     * 依据是后端自己的话：`IntentTemplateCatalog.validatePages` 里
     * `key in IntentTemplates.settingsPages -> return "页面键「$key」与内置页面重名"` ——
     * 我们只是把它提前到输入框上显示，规则一个字没改。
     */
    const val PAGE_KEY_BUILTIN_TAKEN: String = "该代号已被内置页面使用，请修改。"

    /** 调用代号与别的自定义页重复。 */
    const val PAGE_KEY_DUPLICATE: String = "已经有一个自定义页用这个代号了，请换一个。"

    /**
     * 这一页会直接控制系统动作 —— 安全提示（九期换成人话，含义不变、不夸大也不隐瞒）。
     * 注意它说的只是"可能出错"，**不**断言具体会改什么（那是我们判断不了的）。
     */
    const val PAGE_DANGER_NOTE: String =
        "⚠️ 谨慎操作：自定义系统指令可能让助手运行出错，甚至影响手机系统。请确认你熟悉所填指令。"

    /** 附加参数里有一行没有「=」（那行不会生效，原样保留不丢）。 */
    const val PAGE_EXTRA_NO_EQUALS: String =
        "有一行没有「=」：助手只读「键=值」的行，这一行不会生效（已原样保留）。"

    /** 附加参数最多几条 —— 与 `IntentTemplateCatalog.MAX_EXTRAS` 同一个数（那边是 private，所以这页另立一份）。 */
    const val PAGE_EXTRA_LIMIT: Int = 16

    /** 单个参数值最长多少字（同上，对齐 `MAX_EXTRA_VALUE_CHARS`）。 */
    const val PAGE_EXTRA_VALUE_LIMIT: Int = 200

    /**
     * 附加参数的一行。九期的结构化录入用它当内存模型：
     *
     * - 正常行 = `键=值`（[key] / [value]）；
     * - 没有「=」的行用 [raw] 原样存着（助手不读它，但**不丢**——用户在弹窗里删掉之前它一直都在）。
     *
     * [text] 拼出来的就是**老路径逐字同一个形状**：`键=值`，每行一条。
     */
    data class ParamLine(val key: String, val value: String, val raw: String? = null) {
        fun text(): String = raw ?: "$key=$value"
    }

    /** `键=值` 多行文本 → 结构化行（只读文本，不做任何改写）。 */
    fun parseParamLines(text: String): List<ParamLine> = text.split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            if (line.contains('=')) {
                ParamLine(key = line.substringBefore('=').trim(), value = line.substringAfter('=').trim())
            } else {
                ParamLine(key = "", value = "", raw = line)
            }
        }

    /** 结构化行 → `键=值` 多行文本（写进配置的仍是这一串，形状与老路径一字不差）。 */
    fun buildParamLines(rows: List<ParamLine>): String =
        rows.filter { it.raw != null || it.key.isNotBlank() }.joinToString("\n") { it.text() }

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

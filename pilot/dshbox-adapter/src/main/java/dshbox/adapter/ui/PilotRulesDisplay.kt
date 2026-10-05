package dshbox.adapter.ui

import interlock.relay.core.settings.RelaySettings

/**
 * 规则与配置页的「显示词」层：**只决定怎么读，不决定写什么**。
 *
 * 三条纪律（改这个文件之前先读这三条）：
 *
 * 1. 这里只有字符串映射，没有判定、没有默认值、没有写入路径。页面上的一切行为仍然由
 *    [RelaySettings] 那张声明表与 [interlock.relay.core.settings.SettingsStore] 决定。
 * 2. **屏幕上的译文 ≠ 落盘的值。** 标签上写「打开闹钟页面」，写进 `cfg_approval.screen_only_templates`
 *    的仍然是 `alarm.show`；页面标题写「打开页面时不再询问」，存储键仍然是
 *    `cfg_approval.screen_only_templates`。写入一律走 schema 的原值，本文件不参与写入，
 *    也不会把显示词拼进任何要保存的字符串。
 * 3. **查不到就回落原文，绝不回落成空串。** 新增一条 spec、或用户往清单里加了一个我们不认识的
 *    名字（新模板名、新包名、厂商自定义页面键）时，标题与标签直接显示原值 —— 宁可露出机器串，
 *    也不能出现一个没有标题的卡片。
 */
internal object RulesDisplay {

    /**
     * 24 条配置的用户语言标题（id → 人话）。页面的主标题一律取这里；源代码 id 只出现在
     * 长按标题、「了解风险与代价 → 高级」与详情区里，作为可查信息保留。
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
     * `approval.screen_only_templates` 里那 6 条内置动作的显示词，顺序即界面上的顺序。
     *
     * **键是写进配置的原值**（`alarm.show` 等，与 `IntentTemplates.noStateChange` 同源同值），
     * 值只是屏幕上的说法。点标签时写进清单的永远是键。
     */
    val SCREEN_ONLY_TAGS: List<Pair<String, String>> = listOf(
        "alarm.show" to "打开闹钟页面",
        "timer.show" to "打开倒计时页面",
        "settings.open" to "打开设置页",
        "app.info" to "查看应用信息",
        "dial" to "打开拨号盘",
        "web.open" to "打开网页",
    )

    /**
     * 内置 8 条动作模板的显示词。表外的名字（自定义模板 / 未知项）回落原值。
     *
     * 用在清单里"不在那 6 个标签上"的条目上（例如用户把 `alarm.set` 加进免问清单）：
     * 屏幕上给个说法，写进配置的仍是原值。
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
}

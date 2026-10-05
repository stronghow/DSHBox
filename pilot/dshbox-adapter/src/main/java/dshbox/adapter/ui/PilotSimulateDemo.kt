package dshbox.adapter.ui

import interlock.relay.core.exec.direct.IntentTemplates
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「模拟运行一次」的 **C 方案判据层**：把"这一发现在会不会弹卡"按**真闸门同一条判据**逐条复算。
 *
 * 这一层刻意做成**纯函数、零 Android 依赖**，所以能在 JVM 单测里对钉（见
 * `PilotSimulateDemoTest`）。判据链一个字都不抄：
 *
 * 1. 闸门那一条捷径 —— `InterlockGate.kt:135`
 *    `if (noStateChangeIntent && !consentScreen) return GateResult(true, false, null, …, "no_state_change")`；
 * 2. 形状判据 —— `RelayCoordinator.kt:1180-1183` 的 `noStateChangeIntentShape(...)`：
 *    `sys.intent` 且 `IntentTemplates.isNoStateChange(template)`；
 * 3. 取值 —— `IntentTemplates.kt:96-97` = `template.isNotEmpty() && IntentTemplateCatalog.isScreenOnly(template)`，
 *    而 `IntentTemplateCatalog.kt:96` 就是 `template in snapshot.screenOnlyTemplates`；
 * 4. 快照从哪来 —— `RelayContainer.kt:538-556` 的 `applyIntentCatalog()`：把设置项
 *    `approval.screen_only_templates` 解成集装进目录。
 *
 * 界面手里的 `row.value` 就是**同一个设置项的原始值**（`RelayContainer.settingRow` →
 * `settings.raw(spec.id)`），所以 [screenOnlyNow] 的集合成员判定与闸门读的那个集合同值；
 * 单测把同一份清单装进快照，逐条对钉 `IntentTemplates.isNoStateChange`。
 *
 * **判据边界（演示里必须说清的三件事，来自立项文档 §3）**：
 * 1. `consentScreen` 优先级最高：目标是系统授权界面时一律当场问，与免问清单无关；
 * 2. 档位为「禁止」时仍然挡下（`verdict` → `tier_denied` → `E_GATE_HOST_DENIED`，
 *    `InterlockGate.kt:316-321`）；
 * 3. 清单是**用户可以编辑的集合**，可能含 `alarm.set` 或自造名字 ⇒ 结论照实算，
 *    但「真的开一次」的**白名单必须硬编码这 6 条**（[TEMPLATES]），多出来的只给一行说明、绝不试跑。
 */
object RulesDemo {

    /**
     * 演示里逐条复算的 6 条：**硬编码**在这里，且逐个引用 core 常量（不另抄字符串）。
     *
     * 为什么不能拿"清单里有什么"当演示范围：清单是用户可编辑的集合，可能含 `alarm.set`
     * 或自造名字（schema 的风险原文就写着"把会写东西的动作（比如设闹钟）加进来，助手就能
     * 不问你直接改系统"，`RelaySettingsSchema.kt:173-184`）。白名单与清单必须分开。
     */
    val TEMPLATES: List<String> = listOf(
        IntentTemplates.ALARM_SHOW,
        IntentTemplates.TIMER_SHOW,
        IntentTemplates.SETTINGS_OPEN,
        IntentTemplates.APP_INFO,
        IntentTemplates.DIAL,
        IntentTemplates.WEB_OPEN,
    )

    /** 演示里给"示例参数"用的固定取值：值本身无意义，只为让卡面/回执看得出是"对谁做"。 */
    const val SAMPLE_PAGE: String = "wifi"
    const val SAMPLE_NUMBER: String = "10086"
    const val SAMPLE_URL: String = "https://example.com"

    /**
     * 一条动作在演示里的结论。三个布尔各说一件事，不许互相顶替：
     * - [inList]：界面上那份清单里有没有它（呈现"依据"用）；
     * - [recomputed]：**按那份清单复算**的结论（`template in list`，与快照同一语义）；
     * - [screenOnly]：**闸门此刻真的会怎么说**（`IntentTemplates.isNoStateChange`，同一条代码路径）。
     *
     * 正常情况 [recomputed] == [screenOnly]（同一份现值）；两值不等只可能是"页面读到的值
     * 比闸门手里的快照旧"，那时以闸门为准（[screenOnly]），并在界面上如实点出不一致。
     */
    data class Item(
        val template: String,
        val label: String,
        val inList: Boolean,
        val recomputed: Boolean,
        val screenOnly: Boolean,
        val inDemoScope: Boolean,
    ) {
        /** 结论是不是"不会弹卡"。 */
        val quiet: Boolean get() = screenOnly

        /** 清单里多出来的条目（不在那 6 条硬编码白名单里）。 */
        val extra: Boolean get() = !inDemoScope

        /** 页面读到的清单与闸门快照不一致（只可能是页面值滞后）。 */
        val disagrees: Boolean get() = recomputed != screenOnly

        /**
         * 这一条允许出现「真的开一次试试」吗？
         *
         * 两道门都要过：① 在硬编码白名单里；② 复算结论为"不会弹卡"。两边不一致时
         * 一律不开放（保守）——宁可不给按钮，也不给一个真会弹卡的按钮。
         */
        val runsForReal: Boolean get() = inDemoScope && screenOnly && recomputed
    }

    /** 演示列表：那 6 条按固定顺序在前，清单里多出来的条目另列在后（去重、保持出现顺序）。 */
    fun items(currentRaw: String): List<Item> {
        val list = decodeList(currentRaw)
        val head = TEMPLATES.map { build(it, list) }
        val extras = list.filter { it.isNotBlank() && it !in TEMPLATES }.distinct().map { build(it, list) }
        return head + extras
    }

    private fun build(template: String, list: List<String>): Item = Item(
        template = template,
        label = RulesDisplay.actionLabel(template),
        inList = template in list,
        recomputed = screenOnlyNow(template, list),
        screenOnly = IntentTemplates.isNoStateChange(template),
        inDemoScope = template in TEMPLATES,
    )

    /**
     * 「这一发现在会不会弹卡」的复算：清单里有它 ⇒ 命中的是 `no_state_change` 那条捷径。
     *
     * 语义与 `IntentTemplateCatalog.isScreenOnly`（`template in snapshot.screenOnlyTemplates`）
     * 逐字相同；单测把同一份清单装进目录快照，与 `IntentTemplates.isNoStateChange` 逐条对钉。
     */
    fun screenOnlyNow(template: String, current: List<String>): Boolean =
        template.isNotEmpty() && template in current

    /**
     * 「真的开一次」的许可：**只认这 6 条硬编码白名单**，并且闸门此刻确实会免弹
     * （同一份清单里确实有它）——两道都要过，任一不成立就**根本不发**。
     *
     * 危险项与自定义模板一律 false，哪怕它们真在清单里（那时闸门也免弹，但演示不试跑）：
     * `alarm.set` / `timer.set` 会真建闹钟与计时器；`danger.*` / `sys.shell` /
     * `sys.settings.write` / `appops.set` / `app.install|stop` 是别的能力、根本不在 `sys.intent`
     * 的模板表里；自定义模板的 action 由用户写死，语义不可预判。
     *
     * 第二道（清单成员）不是重复：[Items.runsForReal] 已经在渲染层拦过一次，这里是发信前的
     * 最后一道 —— 清单为空时真闸门会弹卡，那时绝不能发出去（零 `E_AWAITING_CONSENT` 靠的是
     * "不发"，不是靠回执处理）。
     */
    fun canReallyOpen(template: String, currentRaw: String): Boolean =
        template in TEMPLATES &&
            IntentTemplates.isNoStateChange(template) &&
            screenOnlyNow(template, decodeList(currentRaw))

    /** 这一条的"示例参数"；表外名字回 null —— 没有参数也就无从试跑。 */
    fun sampleArgs(template: String, hostPackage: String): JSONObject? = when (template) {
        IntentTemplates.ALARM_SHOW, IntentTemplates.TIMER_SHOW ->
            JSONObject().put(IntentTemplates.KEY_TEMPLATE, template)

        IntentTemplates.SETTINGS_OPEN ->
            JSONObject().put(IntentTemplates.KEY_TEMPLATE, template).put(IntentTemplates.KEY_PAGE, SAMPLE_PAGE)

        IntentTemplates.APP_INFO ->
            JSONObject().put(IntentTemplates.KEY_TEMPLATE, template).put(IntentTemplates.KEY_PACKAGE, hostPackage)

        IntentTemplates.DIAL ->
            JSONObject().put(IntentTemplates.KEY_TEMPLATE, template).put(IntentTemplates.KEY_NUMBER, SAMPLE_NUMBER)

        IntentTemplates.WEB_OPEN ->
            JSONObject().put(IntentTemplates.KEY_TEMPLATE, template).put(IntentTemplates.KEY_URL, SAMPLE_URL)

        else -> null
    }

    /**
     * 卡面上那行"参数"的取值：与 `InterlockTargets.of` 的 SYS_INTENT 分支同一形状
     * （`alarm.show` / `timer.show` 没有参数 ⇒ 卡上没有那一行）。
     */
    fun sampleDetail(template: String, hostPackage: String): String? = when (template) {
        IntentTemplates.SETTINGS_OPEN -> SAMPLE_PAGE
        IntentTemplates.APP_INFO -> hostPackage
        IntentTemplates.DIAL -> SAMPLE_NUMBER
        IntentTemplates.WEB_OPEN -> SAMPLE_URL
        else -> null
    }

    /** 清单的 JSON 解码（只读；解不开回空表，界面不因一段坏文本崩掉）。 */
    fun decodeList(text: String): List<String> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).map { array.optString(it) }
    }.getOrDefault(emptyList())

    /**
     * 从授权记录里取出这一条请求的**判据名**（`decision` 字段，例如 `no_state_change`）。
     *
     * 真闸门的判据名走的是授权记录与运行日志，不在回包正文里（成功回包的 `note` 是后端那句
     * handoff 说明）。所以"回执里带判据名"这一条由这里读出来，读的是同一份记录
     * （`RelayContainer.diagnostics().auditLines`，与诊断页同源）。
     */
    fun decisionFrom(auditLines: List<String>, requestId: String): String? = auditLines.asSequence()
        .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
        .firstOrNull { it.optString("req") == requestId }
        ?.optString("decision")
        ?.takeIf { it.isNotEmpty() }
}

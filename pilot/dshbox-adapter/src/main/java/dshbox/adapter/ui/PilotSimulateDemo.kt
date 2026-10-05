package dshbox.adapter.ui

import interlock.relay.core.exec.direct.IntentTemplateCatalog
import interlock.relay.core.exec.direct.IntentTemplates
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.settings.RelaySettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「模拟运行一次」的 **C 方案判据层**：把"这一发会不会弹卡"按**真闸门同一条判据**逐条复算。
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
 * **判据边界（演示里必须说清的事，来自立项文档 §3 与闸门裁决顺序）**：
 * 1. `consentScreen` 优先级最高：目标是系统授权界面时一律当场问，与免问清单无关；
 * 2. 档位为「禁止」时仍然挡下，且**排在免问清单那条捷径之前**（`verdict` → `tier_denied`
 *    → `E_GATE_HOST_DENIED`，`InterlockGate.kt:133` 与 `:316-321`）⇒ 档位=禁止时，
 *    哪怕清单里有它也不会执行；
 * 3. 反过来，档位是「完全访问」（`ALWAYS`）时，清单里**没有**它也不会弹
 *    （`requiresApproval` 的 `tier == ASK || ceiling == SESSION_ONLY` 为假，`InterlockGate.kt:338-352`，
 *    那条路径的判据名是 `auto`）⇒ 这时"不弹卡"靠的是档位，不是免问清单。
 *    档位从界面自己已经读到的能力行里取（`CapabilityRow.state.tier`，与闸门读的是同一个
 *    `TierStore`，`RelayContainer.kt:1077`），不另开一条读法。
 *    同一处还有两个输入本页读不到，所以**不替它们猜**：
 *    `ceiling == SESSION_ONLY`（上限被收紧）与 `sessionGranted`（你刚按过「本会话内允许」）；
 * 4. 清单是**用户可以编辑的集合**，可能含 `alarm.set` 或自造名字 ⇒ 结论照实算，
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
     * 一条动作在演示里的一条结论。四支互斥，各有各的判据来源 —— 不许合并成一句"不会弹"：
     * - [QUIET_BY_LIST]：免问清单里有它 ⇒ 命中 `no_state_change` 捷径（靠清单）；
     * - [QUIET_BY_TIER]：清单纯粹没参与 —— 档位是「完全访问」，闸门那条路走的是 `auto`（靠档位）；
     * - [PROMPTS]：两条都不成立 ⇒ 会先弹一张确认卡问你；
     * - [BLOCKED]：档位是「禁止」⇒ 当场挡下（`tier_denied`），弹卡都轮不到。
     */
    enum class Conclusion { QUIET_BY_LIST, QUIET_BY_TIER, PROMPTS, BLOCKED }

    /**
     * 一条动作在演示里的复算结果。三个布尔各说一件事，不许互相顶替：
     * - [inList]：界面上那份清单里有没有它（呈现"依据"用）；
     * - [recomputed]：**按那份清单复算**的结论（`template in list`，与快照同一语义）；
     * - [screenOnly]：**闸门此刻真的会怎么说**（`IntentTemplates.isNoStateChange`，同一条代码路径）。
     *
     * 正常情况 [recomputed] == [screenOnly]（同一份现值）；两值不等只可能是"页面读到的值
     * 比闸门手里的快照旧"，那时以闸门为准（[screenOnly]），并在界面上如实点出不一致。
     *
     * [key] 是这一行的身份（内置/自定义模板就是模板名；**用户自填的设置页是它的页面键**），
     * [template] 是这一发真正过闸门用的模板名（用户设置页恒为 `settings.open`）。
     */
    data class Item(
        val key: String,
        val template: String,
        val label: String,
        val inList: Boolean,
        val recomputed: Boolean,
        val screenOnly: Boolean,
        /** 在内置那 6 条白名单里（真开的第一道门；用户自造的名字永远不在）。 */
        val whitelisted: Boolean,
        /** 这一行是不是用户自填的（页面 / 动作）——界面据此换一句说明。 */
        val custom: Boolean,
        val tier: AccessTier,
        val conclusion: Conclusion,
        /**
         * 这一行**有没有资格**真开（第二道门，按种类定）。
         *
         * 内置模板与用户自填页面为 true；**用户自填动作恒为 false** —— 它的 action 是用户写死的
         * 任意字符串，我们无法确认它只上屏，所以永不真开（与"临时清单里有没有它"无关）。
         */
        val realRunEligible: Boolean,
    ) {
        /** 结论是不是"这一发不会弹卡"（两条路都算）。 */
        val quiet: Boolean
            get() = conclusion == Conclusion.QUIET_BY_LIST || conclusion == Conclusion.QUIET_BY_TIER

        /** 免问清单这一条判据放行了吗（与 `isNoStateChange` 同值）。 */
        val quietByList: Boolean get() = conclusion == Conclusion.QUIET_BY_LIST

        /** 清单里多出来的条目（不在那 6 条硬编码白名单里）。 */
        val extra: Boolean get() = !whitelisted

        /** 页面读到的清单与闸门快照不一致（只可能是页面值滞后）。 */
        val disagrees: Boolean get() = recomputed != screenOnly

        /**
         * 这一行允许出现「真的开一次」吗？四道都要过：
         * ① 这一行的种类有资格（[realRunEligible]）；② 在内置白名单里（[whitelisted]）；
         * ③ 这一页按同一份清单算出放行（[recomputed]）；④ 放行的原因是那条捷径本身
         * （[Conclusion.QUIET_BY_LIST]）—— 不是档位给的。
         *
         * 于是「靠档位不弹」（[Conclusion.QUIET_BY_TIER]）、「档位是禁止」（[Conclusion.BLOCKED]）、
         * 以及一切用户自造动作，都不开放真开：前者一旦档位被改就会真弹卡，中者发出去只会被挡下，
         * 后者连"只上屏"都无法确认。宁可不给按钮，也不给一个真会弹卡或注定被挡的按钮。
         */
        val runsForReal: Boolean
            get() = realRunEligible && whitelisted && recomputed && conclusion == Conclusion.QUIET_BY_LIST
    }

    /**
     * 演示列表：那 6 条按固定顺序在前，清单里多出来的条目另列在后（去重、保持出现顺序）。
     *
     * [tier] 是这条能力此刻的档位（界面从 `CapabilityRow.state.tier` 取）。缺省 `ASK`
     * 只在读不到能力行时兜底，那时结论按"会弹卡"这一支算 —— 宁可保守，不替读不到的状态说话。
     */
    fun items(currentRaw: String, tier: AccessTier = AccessTier.ASK): List<Item> {
        val list = decodeList(currentRaw)
        val head = TEMPLATES.map { build(it, list, tier) }
        val extras = list.filter { it.isNotBlank() && it !in TEMPLATES }.distinct().map { build(it, list, tier) }
        return head + extras
    }

    /**
     * **用户自填设置页**的一行。它底下那一发就是 `settings.open` + `page=<这个键>`
     * （`IntentTemplates.validateSettings` 认的自定义页面键来自同一份目录快照），
     * 所以结论就是 `settings.open` 的结论 —— 闸门看不到"页"这一层，只看模板名。
     *
     * 真开的资格与内置模板相同（`settings.open` 在白名单里）：靠的还是"清单放行 ⇒ 本来就不会弹卡"。
     */
    fun pageRow(
        pageKey: String,
        pageName: String,
        currentRaw: String,
        tier: AccessTier = AccessTier.ASK,
    ): Item = buildGeneric(
        key = pageKey,
        template = IntentTemplates.SETTINGS_OPEN,
        label = pageName + "（打开 $pageKey）",
        currentRaw = currentRaw,
        tier = tier,
        custom = true,
        realRunEligible = true,
    )

    /**
     * **用户自填动作**的一行。结论按同一条闸门判据算（自定义名字写进免问清单时，闸门也认，
     * 这正是这一屏要让人看见的），但 [Item.runsForReal] 恒为假：action 是用户写死的任意字符串。
     */
    fun userTemplateRow(
        id: String,
        name: String,
        currentRaw: String,
        tier: AccessTier = AccessTier.ASK,
    ): Item = buildGeneric(
        key = id,
        template = id,
        label = name,
        currentRaw = currentRaw,
        tier = tier,
        custom = true,
        realRunEligible = false,
    )

    /**
     * 一条动作的结论，按**闸门里那几步的真实顺序**定：
     * 档位「禁止」最先挡（`verdict` 在捷径之前）⇒ 再看免问清单那条捷径 ⇒ 再落到档位判定。
     */
    fun conclude(screenOnly: Boolean, tier: AccessTier): Conclusion = when {
        tier == AccessTier.DENIED -> Conclusion.BLOCKED
        screenOnly -> Conclusion.QUIET_BY_LIST
        tier == AccessTier.ALWAYS -> Conclusion.QUIET_BY_TIER
        else -> Conclusion.PROMPTS
    }

    private fun build(template: String, list: List<String>, tier: AccessTier): Item = buildGeneric(
        key = template,
        template = template,
        label = RulesDisplay.actionLabel(template),
        currentRaw = null,
        list = list,
        tier = tier,
        custom = false,
        realRunEligible = true,
    )

    private fun buildGeneric(
        key: String,
        template: String,
        label: String,
        currentRaw: String? = null,
        list: List<String>? = null,
        tier: AccessTier,
        custom: Boolean,
        realRunEligible: Boolean,
    ): Item {
        val decoded = list ?: decodeList(currentRaw.orEmpty())
        val screenOnly = IntentTemplates.isNoStateChange(template)
        return Item(
            key = key,
            template = template,
            label = label,
            inList = template in decoded,
            recomputed = screenOnlyNow(template, decoded),
            screenOnly = screenOnly,
            whitelisted = template in TEMPLATES,
            custom = custom,
            tier = tier,
            conclusion = conclude(screenOnly, tier),
            realRunEligible = realRunEligible,
        )
    }

    /**
     * 「这一发会不会弹卡」的复算：清单里有它 ⇒ 命中的是 `no_state_change` 那条捷径。
     *
     * 语义与 `IntentTemplateCatalog.isScreenOnly`（`template in snapshot.screenOnlyTemplates`）
     * 逐字相同；单测把同一份清单装进目录快照，与 `IntentTemplates.isNoStateChange` 逐条对钉。
     */
    fun screenOnlyNow(template: String, current: List<String>): Boolean =
        template.isNotEmpty() && template in current

    /**
     * 一项配置在演示里的落点。**由规则算出来**（值类型 + 值本身），不是手写的一张
     * "哪张卡显示按钮"的表：新增一条配置时它自动落进某一支，不需要有人记得来改这里。
     */
    sealed interface DemoEntry {
        val specId: String

        /** 卡片上那句说明。能演的说"演什么"，不能演的说清"为什么没有可模拟的运行" —— 两种情况都必须给。 */
        val note: String

        /** 卡片正文末尾那颗 ⚡「模拟运行一次」（整项一个逐条浮层）。 */
        data class CardButton(override val specId: String, override val note: String) : DemoEntry

        /** 用户自填的设置页：入口在卡片内部**每一行页面**上（结论 + 放行时给真开）。 */
        data class UserPages(
            override val specId: String,
            override val note: String,
            val pages: List<String>,
        ) : DemoEntry

        /** 用户自填的动作：入口在卡片内部**每一行动作**上（只给结论与卡示意，永不真开）。 */
        data class UserTemplates(
            override val specId: String,
            override val note: String,
            val templates: List<String>,
        ) : DemoEntry

        /** 这一项没有可模拟的运行。 */
        data class Unavailable(override val specId: String, override val note: String) : DemoEntry
    }

    /**
     * 算出某一项配置的演示落点。三条结构化规则，按顺序判：
     *
     * 1. **值是"一组 `sys.intent` 动作"**：`IntentTemplateCatalog` 消费的那两种文档
     *    （`SETTINGS_PAGES` / `INTENT_TEMPLATES`）—— 用户自填页面与动作都在这里；
     * 2. 值类型是字符串集合、且它的**默认值逐元素等于** [IntentTemplates.noStateChange]
     *    ⇒ 这就是"内置只上屏模板那一组"（schema 自己也写着与 core 同源同值）；
     * 3. 其余一律 [DemoEntry.Unavailable]，并给一句按类别的准确说明（[unavailableNote]）。
     *
     * 第 1、2 条都依赖"值与 core 同源"这个事实：一旦 schema 的默认值和 core 的内置名单漂移，
     * 单测会当场红 —— 这正是"不许手写表"的落点。
     */
    fun demoEntry(spec: RelaySettings.Spec, currentRaw: String): DemoEntry {
        val type = spec.type
        if (type is RelaySettings.Type.Document) {
            when (type.kind) {
                RelaySettings.Type.Document.Kind.SETTINGS_PAGES -> {
                    val pages = IntentTemplateCatalog.decodePages(currentRaw)
                    return if (pages.isEmpty()) {
                        DemoEntry.Unavailable(
                            spec.id,
                            "这一项还没有你自己填的页面，所以现在没有可模拟的对象。" +
                                "加上一条之后（在「改这一项」里加），每一行页面就能算「会不会弹卡」，" +
                                "放行的那些还能真的打开一次。",
                        )
                    } else {
                        DemoEntry.UserPages(
                            specId = spec.id,
                            note = "你填的 ${pages.size} 个页面逐条按真闸门算：会不会弹卡；" +
                                "放行的那些能真的打开一次（会先问你一次）。展开上面「改这一项」逐行看。",
                            pages = pages.map { it.key },
                        )
                    }
                }

                RelaySettings.Type.Document.Kind.INTENT_TEMPLATES -> {
                    val templates = IntentTemplateCatalog.decodeTemplates(currentRaw)
                    return if (templates.isEmpty()) {
                        DemoEntry.Unavailable(
                            spec.id,
                            "这一项还没有你自己加的动作，所以现在没有可模拟的对象。" +
                                "加上一条之后（在「改这一项」里加），每一行就能算结论、看卡示意 —— " +
                                "自定义动作不真开。",
                        )
                    } else {
                        DemoEntry.UserTemplates(
                            specId = spec.id,
                            note = "你加的 ${templates.size} 条动作逐条算结论、给卡示意：" +
                                "action 是你自己写的任意字符串，所以不真开。展开上面「改这一项」逐行看。",
                            templates = templates.map { it.id },
                        )
                    }
                }

                else -> Unit // 上限覆盖表是一组别的设置，落到下面的通用说明
            }
        }
        if (type is RelaySettings.Type.TextList &&
            decodeList(spec.default).toSet() == IntentTemplates.noStateChange
        ) {
            return DemoEntry.CardButton(
                specId = spec.id,
                note = "逐条算：这一发会不会弹卡；那 6 条内置只上屏动作还能真的开一次。",
            )
        }
        return DemoEntry.Unavailable(spec.id, unavailableNote(spec))
    }

    /**
     * 不能演的那句准确说明。按"这一项为什么没有单独的一发动作"分类给，不用含糊说法：
     * 显示偏好那条另外点明"演它要真建虚拟屏，我们不建"。
     *
     * 每一条都**自成一句**（界面原样渲染，不再另加前缀）：句子开头就把"没有可模拟的运行"说了。
     */
    fun unavailableNote(spec: RelaySettings.Spec): String {
        val type = spec.type
        val reason = when {
            spec.id == RelaySettings.SURFACE_PREFERENCE ->
                "这一项没有可模拟的运行 —— 要真演它就得先建一块虚拟屏，我们不建虚拟屏，所以这里只说明。"

            type is RelaySettings.Type.Link ->
                "这一项没有可模拟的运行 —— 它不产生单独的一发动作，控制点在「" +
                    RulesDisplay.linkPageLabel(type.target) + "」那一页，要改就去那里。"

            type is RelaySettings.Type.Document ->
                "这一项没有可模拟的运行 —— 它是一组别的设置，不是一次动作。"

            spec.group == RelaySettings.Group.DANGER ->
                "这一项没有可模拟的运行 —— 危险项改的是保护你的那道判据本身，不产生单独的一发动作。"

            type is RelaySettings.Type.Number ->
                "这一项没有可模拟的运行 —— 它是个数量 / 时长，不产生单独的一发动作。"

            else ->
                "这一项没有可模拟的运行 —— 它是个开关 / 取值，不产生单独的一发动作。"
        }
        // 还没接线的项另有实话要说：改了不生效，界面自己已经标了「暂不可改」。
        return if (spec.wired) reason else reason + "（这一项目前还没接线，改了不生效。）"
    }

    /**
     * 一次「真的开一次」的请求：内置模板，或用户自填的设置页（`settings.open` + `page`）。
     */
    data class RunRequest(
        val template: String,
        val pageKey: String? = null,
        /** 回执里用来称呼这一发（人话）。 */
        val label: String = template,
    )

    /** 用户自填页面的那一发参数：与 `settings.open` 既有校验同一条路（`IntentTemplates.validate`）。 */
    fun pageArgs(pageKey: String): JSONObject =
        JSONObject().put(IntentTemplates.KEY_TEMPLATE, IntentTemplates.SETTINGS_OPEN)
            .put(IntentTemplates.KEY_PAGE, pageKey)

    /** 卡面上"参数"那一行的取值（示例参数或用户页面的建）。 */
    fun detailFor(template: String, pageKey: String?, hostPackage: String): String? =
        if (pageKey != null) pageKey else sampleDetail(template, hostPackage)

    /** 用户自填页面的键是否还在此刻的页面表里（页面被删掉之后不允许再用旧行发信）。 */
    fun pageKeys(pagesRaw: String): List<String> = IntentTemplateCatalog.decodePages(pagesRaw).map { it.key }

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

    /**
     * 一次 [RunRequest] 的许可：内置模板走 [canReallyOpen]；用户自填页面还要**此刻真的还在页面表里**
     * （行是上一帧画出来的，页面可能刚被删）。
     */
    fun canRun(request: RunRequest, screenOnlyRaw: String, pagesRaw: String): Boolean {
        if (!canReallyOpen(request.template, screenOnlyRaw)) return false
        val key = request.pageKey ?: return true
        return key.isNotEmpty() && key in pageKeys(pagesRaw)
    }

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

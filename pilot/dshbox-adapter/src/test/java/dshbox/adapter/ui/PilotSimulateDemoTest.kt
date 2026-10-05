package dshbox.adapter.ui

import interlock.relay.core.exec.direct.IntentTemplateCatalog
import interlock.relay.core.exec.direct.IntentTemplates
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.settings.RelaySettings
import java.io.File
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 「模拟运行一次」C+D 方案的判据单测。
 *
 * 三条要钉住的事：
 * 1. **界面结论 == 真闸门的结论**：把同一份清单装进 `IntentTemplateCatalog` 快照，
 *    逐条对钉 `IntentTemplates.isNoStateChange`（那条判据就是 `InterlockGate.kt:135` 的
 *    `no_state_change` 捷径用的那一条）；
 * 2. **白名单硬编码且只有那 6 条**：与 `IntentTemplates.noStateChange` 逐元素相等，
 *    `alarm.set` / `timer.set` / 危险能力名 / 自定义名字即便真在清单里也**不可试跑**；
 * 3. **演示路径零建屏、零写设置**：源码扫描两条新文件与页面上那一块演示区
 *    （不扫全文件：`PilotRulesScreen.kt` 里别的地方本来就有 `viewModel.setSetting(...)` 这条合法写入路径）。
 *
 * 快照是全局的，每条改过它的用例都在 [restoreCatalog] 里装回内置默认值。
 */
class PilotSimulateDemoTest {

    @After
    fun restoreCatalog() {
        IntentTemplateCatalog.install(IntentTemplateCatalog.Config.BUILTIN)
    }

    private fun install(list: Collection<String>) {
        IntentTemplateCatalog.install(
            IntentTemplateCatalog.Config(
                screenOnlyTemplates = list.toSet(),
                pages = emptyList(),
                templates = emptyList(),
            ),
        )
    }

    private fun rawOf(list: Collection<String>): String = JSONArray(list.toList()).toString()

    @Test
    fun `the hardcoded whitelist is exactly the six built-in screen-only templates`() {
        assertEquals(6, RulesDemo.TEMPLATES.size)
        assertEquals(IntentTemplates.noStateChange, RulesDemo.TEMPLATES.toSet())
        // 逐条点名：这六条与闸门里那份内置名单同源（引用常量，不另抄字符串）。
        listOf(
            IntentTemplates.ALARM_SHOW,
            IntentTemplates.TIMER_SHOW,
            IntentTemplates.SETTINGS_OPEN,
            IntentTemplates.APP_INFO,
            IntentTemplates.DIAL,
            IntentTemplates.WEB_OPEN,
        ).forEach { assertTrue(it, it in RulesDemo.TEMPLATES) }
    }

    @Test
    fun `the recomputation equals the real gate for the same snapshot`() {
        val matrix = listOf(
            emptyList(),
            listOf("settings.open"),
            IntentTemplates.noStateChange.toList(),
            listOf("settings.open", "alarm.set"),
            listOf("alarm.set", "timer.set"),
            IntentTemplates.noStateChange.toList() + listOf("my_custom", "alarm.set"),
            listOf("my_custom"),
            listOf("", "dial"),
        )
        val names = IntentTemplates.names + listOf("my_custom", "danger.foo", "")
        matrix.forEach { list ->
            install(list)
            names.forEach { template ->
                assertEquals(
                    "清单=$list 模板=$template 时，界面复算必须与真闸门同结论",
                    IntentTemplates.isNoStateChange(template),
                    RulesDemo.screenOnlyNow(template, list),
                )
            }
        }
    }

    @Test
    fun `a listed name is quiet and an unlisted one prompts`() {
        val list = listOf("settings.open", "alarm.set")
        install(list)
        val items = RulesDemo.items(rawOf(list))

        val settings = items.first { it.template == "settings.open" }
        assertTrue("在清单里 ⇒ 不会弹卡", settings.quiet)
        assertTrue("在清单里 ⇒ 可以真开", settings.runsForReal)

        val alarmShow = items.first { it.template == "alarm.show" }
        assertFalse("不在清单里 ⇒ 会弹卡", alarmShow.quiet)
        assertFalse("会弹卡 ⇒ 不给真开按钮", alarmShow.runsForReal)
        assertTrue("会弹卡才给卡示意", !alarmShow.quiet)

        // 清单里多出来的 alarm.set：真判定确实是"不弹卡"（闸门也认），但演示不试跑它。
        val alarmSet = items.first { it.template == "alarm.set" }
        assertTrue("闸门口径：清单里有它 ⇒ 不弹卡", alarmSet.quiet)
        assertFalse("会真建闹钟 ⇒ 演示不试跑", alarmSet.runsForReal)
        assertTrue(alarmSet.extra)

        // 6 条固定在前，多出来的条目跟在后。
        assertEquals(7, items.size)
        assertEquals(RulesDemo.TEMPLATES, items.take(6).map { it.template })
        assertEquals("alarm.set", items.last().template)
    }

    @Test
    fun `only the six whitelisted names can ever be opened for real`() {
        val everything = IntentTemplates.names + listOf("danger.consent_screen_enabled", "sys.shell", "my_custom")
        install(everything)
        val raw = rawOf(everything)

        RulesDemo.TEMPLATES.forEach { assertTrue(it, RulesDemo.canReallyOpen(it, raw)) }
        listOf(
            IntentTemplates.ALARM_SET,
            IntentTemplates.TIMER_SET,
            "danger.consent_screen_enabled",
            "sys.shell",
            "sys.settings.write",
            "appops.set",
            "app.install",
            "app.stop",
            "screen.record",
            "audio.capture",
            "my_custom",
        ).forEach { assertFalse("$it 绝不可试跑", RulesDemo.canReallyOpen(it, raw)) }

        // 清单为空（全部询问）时，那 6 条也不开放真开：结论是"会弹卡"。
        RulesDemo.TEMPLATES.forEach { assertFalse(it, RulesDemo.canReallyOpen(it, "[]")) }
    }

    @Test
    fun `items marks a page-side snapshot that disagrees with the gate`() {
        // 造一次"页面值比闸门快照旧"：闸门快照里有 settings.open，页面读到的是空清单。
        install(listOf("settings.open"))
        val item = RulesDemo.items("[]").first { it.template == "settings.open" }
        assertTrue("结论以闸门为准", item.quiet)
        assertTrue("并且如实标出不一致", item.disagrees)
        assertFalse("不一致时保守：不给真开", item.runsForReal)
    }

    @Test
    fun `recomputing ten times reads the same value and changes nothing`() {
        // 无设备环境下无法真跑十次界面；这里用等价证明：判据层是纯读的，
        // 十次复算结果逐次相同，且它依赖的那份全局快照一个字节没动。
        val list = listOf("settings.open", "dial")
        install(list)
        val before = IntentTemplateCatalog.current()
        val raw = rawOf(list)
        val first = RulesDemo.items(raw)
        repeat(10) {
            assertEquals(first, RulesDemo.items(raw))
            assertEquals(IntentTemplateCatalog.current(), before)
        }
    }

    @Test
    fun `the tier takes part in the same order the gate checks it`() {
        val list = listOf("settings.open")
        install(list)
        val raw = rawOf(list)
        fun at(template: String, tier: AccessTier) =
            RulesDemo.items(raw, tier).first { it.template == template }

        // 档位=每次询问（默认）：清单里的走捷径，清单外的弹卡。
        assertEquals(RulesDemo.Conclusion.QUIET_BY_LIST, at("settings.open", AccessTier.ASK).conclusion)
        assertEquals(RulesDemo.Conclusion.PROMPTS, at("dial", AccessTier.ASK).conclusion)

        // 档位=完全访问：清单外的也不弹 —— 但那是档位给的，不是免问清单，且不开放试跑。
        val always = at("dial", AccessTier.ALWAYS)
        assertEquals(RulesDemo.Conclusion.QUIET_BY_TIER, always.conclusion)
        assertTrue("靠档位也确实是「不弹卡」", always.quiet)
        assertFalse("但不是清单放行 ⇒ 不给真开按钮", always.runsForReal)
        assertFalse(always.quietByList)

        // 档位=禁止：清单里那条也挡下（闸门里 verdict 排在捷径之前）⇒ 一样没有按钮。
        val denied = at("settings.open", AccessTier.DENIED)
        assertEquals(RulesDemo.Conclusion.BLOCKED, denied.conclusion)
        assertFalse(denied.quiet)
        assertFalse(denied.runsForReal)
    }

    @Test
    fun `the gate label is read back out of the audit record`() {
        val line = """{"ts":"2026-10-05 10:00:00","req":"demo12ab-1","cap":"sys.intent",""" +
            """"target":"settings.open","sys":"GRANTED","tier":"ASK","decision":"no_state_change",""" +
            """"source":"system_granted","uid":"app","result":"success","surface":"foreground",""" +
            """"from":null,"reason":null,"ms":42,"bytes":0,"argsDigest":"deadbeef"}"""
        val other = """{"ts":"2026-10-05 09:59:59","req":"demo12ab-0","decision":"tier_denied"}"""
        assertEquals("no_state_change", RulesDemo.decisionFrom(listOf(other, line), "demo12ab-1"))
        assertNull(RulesDemo.decisionFrom(listOf(other, line), "nope"))
        assertNull(RulesDemo.decisionFrom(listOf("not json"), "demo12ab-1"))
    }

    // ── 七期（任务 A）：渲染规则 ──────────────────────────────────────────────
    //
    // 三条新纪律：① 给不给 ⚡ 由**规则**算（不是手写的表）；② 不给 ⚡ 的项必须有一句
    // 准确说明，且全页没有第二处邀请"试一下"的文案；③ 用户自填设置页能真开、自填动作永不真开。

    /** 页面 / 动作两类文档的"有内容"取值，用来把规则逼到"能演"那一支。 */
    private val pagesJson = """[{"key":"wifi_custom","name":"我的无线页面",""" +
        """"action":"android.settings.WIFI_SETTINGS"}]"""
    private val templatesJson = """[{"id":"my_action","name":"我的动作",""" +
        """"action":"android.intent.action.VIEW","noStateChange":true}]"""

    /** 某一项在单测里的"当前值"：两类文档喂有内容的样例，其余用 schema 自己的默认值。 */
    private fun valueFor(spec: RelaySettings.Spec): String = when (spec.id) {
        RelaySettings.SETTINGS_PAGES -> pagesJson
        RelaySettings.USER_TEMPLATES -> templatesJson
        else -> spec.default
    }

    private fun entryOf(spec: RelaySettings.Spec) = RulesDemo.demoEntry(spec, valueFor(spec))

    @Test
    fun `the demo entry of all specs is decided by one rule and lands on three of them`() {
        // 这张表是 schema 给的，不是这里抄的：加一条配置就自动多一行，规则不用改。
        assertEquals("配置项总数变了就该有人看一眼这张渲染清单", 24, RelaySettings.all.size)

        val entries = RelaySettings.all.associateWith { entryOf(it) }
        val decided = entries.filterValues { it !is RulesDemo.DemoEntry.Unavailable }

        // ③ 只有这三项能演：内置免问模板（整项浮层）、用户自填页面、用户自填动作。
        assertEquals(
            setOf(
                RelaySettings.SCREEN_ONLY_TEMPLATES,
                RelaySettings.SETTINGS_PAGES,
                RelaySettings.USER_TEMPLATES,
            ),
            decided.keys.map { it.id }.toSet(),
        )
        assertTrue(decided.getValue(RelaySettings.all.first { it.id == RelaySettings.SCREEN_ONLY_TEMPLATES })
            is RulesDemo.DemoEntry.CardButton)
        assertTrue(decided.getValue(RelaySettings.all.first { it.id == RelaySettings.SETTINGS_PAGES })
            is RulesDemo.DemoEntry.UserPages)
        assertTrue(decided.getValue(RelaySettings.all.first { it.id == RelaySettings.USER_TEMPLATES })
            is RulesDemo.DemoEntry.UserTemplates)

        // 规则是按**值**判的，不是按 id 名单：两份文档为空时它们本身也没有可模拟的对象。
        val empty = RelaySettings.all.map { RulesDemo.demoEntry(it, it.default) }
        assertEquals("默认值下只有内置免问模板那一项能演", 1, empty.count { it !is RulesDemo.DemoEntry.Unavailable })
        assertTrue(empty.first { it.specId == RelaySettings.SETTINGS_PAGES } is RulesDemo.DemoEntry.Unavailable)
        assertTrue(empty.first { it.specId == RelaySettings.USER_TEMPLATES } is RulesDemo.DemoEntry.Unavailable)

        // 卡片底部那颗 ⚡ 全页只有一处，且长在 CardButton 这一支里（其余两支都不渲染按钮）。
        val source = rulesScreenSource()
        assumeTrue("定位不到界面源码，跳过按钮落点检查", source != null)
        val gate = requireNotNull(source)
            .substringAfter("when (val entry = RulesDemo.demoEntry(spec, row.value))")
            .substringBefore("pendingSwitch?.let")
        assertTrue("渲染规则必须由 demoEntry 算出来", gate.contains("is RulesDemo.DemoEntry.Unavailable"))
        assertTrue(
            "⚡ 只许长在 CardButton 那一支里",
            gate.substringBefore("is RulesDemo.DemoEntry.UserPages").contains("""Text("模拟运行一次")"""),
        )
        assertEquals(
            "全页只有这一颗「模拟运行一次」按钮",
            1,
            Regex("""Text\("模拟运行一次"\)""").findAll(requireNotNull(source)).count(),
        )
    }

    @Test
    fun `a spec without a runnable action gets an accurate sentence instead of an invitation`() {
        val entries = RelaySettings.all.map { it to RulesDemo.demoEntry(it, valueFor(it)) }
        val unavailable = entries.filter { it.second is RulesDemo.DemoEntry.Unavailable }
        assertTrue("不能演的项应当是多数（24 减能演的 3 项）", unavailable.size == 21)

        unavailable.forEach { (spec, entry) ->
            val note = (entry as RulesDemo.DemoEntry.Unavailable).note
            assertTrue("${spec.id} 必须给一句说明", note.isNotEmpty())
            assertTrue(
                "${spec.id} 的说明要把「没有可模拟的运行」说清楚：$note",
                note.contains("没有可模拟") || note.contains("不模拟") || note.contains("不真开"),
            )
            // ③ 不许出现任何**邀请**去模拟的文案（负向说明本身不算邀请）。
            listOf("模拟运行一次", "点这里", "试试", "真的开一次试试").forEach { invite ->
                assertFalse("${spec.id} 的说明里不许出现邀请：$invite", note.contains(invite))
            }
            // 说明是一行注释的体量，不是一段正文。
            assertTrue("${spec.id} 的说明太长：${note.length}", note.length < 120)
        }
    }

    @Test
    fun `the inaccurate-simulation cases each get their own honest reason`() {
        fun note(id: String): String {
            val spec = RelaySettings.all.first { it.id == id }
            return (RulesDemo.demoEntry(spec, valueFor(spec)) as RulesDemo.DemoEntry.Unavailable).note
        }
        // ① Link 型：说清控制点在别的页面，并且页面名与那一页的标题逐字相同。
        assertTrue(note(RelaySettings.TIER_PER_CAPABILITY).contains("控制点"))
        assertTrue(note(RelaySettings.TIER_PER_CAPABILITY).contains(RulesDisplay.linkPageLabel("capabilities")))
        // ② 显示偏好：说清"演它要真建虚拟屏，我们不建"。
        assertTrue(note(RelaySettings.SURFACE_PREFERENCE).contains("虚拟屏"))
        // ③ 数量型：数量 / 时长不产生单独的一发。
        assertTrue(note(RelaySettings.SESSION_GRANT_MINUTES).contains("数量"))
        // ④ 危险项：它改的是判据本身。
        assertTrue(note(RelaySettings.CONSENT_ENABLED).contains("判据本身"))
        // ⑤ 还没接线的项另有实话：改了不生效。
        assertTrue(note(RelaySettings.INTENT_TIMEOUT_MS).contains("还没接线"))
    }

    @Test
    fun `the link page labels are the real page titles`() {
        assertEquals("执行模式", RulesDisplay.linkPageLabel("surface"))
        assertEquals("特权模式", RulesDisplay.linkPageLabel("privileged"))
        assertEquals("能力", RulesDisplay.linkPageLabel("capabilities"))
        val strings = findZhStrings()
        assumeTrue("定位不到中文资源，跳过对钉", strings != null)
        val text = requireNotNull(strings)
        fun stringOf(name: String): String =
            Regex("""<string name="$name">([^<]*)</string>""").find(text)?.groupValues?.get(1).orEmpty()
        assertEquals(stringOf("pilot_page_surface"), RulesDisplay.linkPageLabel("surface"))
        assertEquals(stringOf("pilot_page_privileged"), RulesDisplay.linkPageLabel("privileged"))
        assertEquals(stringOf("pilot_page_capabilities"), RulesDisplay.linkPageLabel("capabilities"))
    }

    // ── 七期（任务 B）：用户自填设置页 ────────────────────────────────────────

    @Test
    fun `a user page is judged with the same gate as settings open`() {
        val withPage = """[{"key":"wifi_custom","name":"我的无线页面","action":"android.settings.WIFI_SETTINGS"}]"""

        // 清单放行 settings.open ⇒ 这个页面也"不会弹卡"，并因此拿到真开资格。
        install(listOf("settings.open"))
        val quiet = RulesDemo.pageRow("wifi_custom", "我的无线页面", rawOf(listOf("settings.open")))
        assertEquals(IntentTemplates.SETTINGS_OPEN, quiet.template)
        assertEquals("wifi_custom", quiet.key)
        assertTrue(quiet.runsForReal)
        assertFalse("结论按模板算，说明里带上页面键", quiet.extra)

        // 清单里没有 settings.open（把快照也清掉）⇒ 会弹卡：只给卡示意，不给真开。
        install(emptyList())
        val prompts = RulesDemo.pageRow("wifi_custom", "我的无线页面", "[]")
        assertEquals(RulesDemo.Conclusion.PROMPTS, prompts.conclusion)
        assertFalse(prompts.runsForReal)

        // 档位禁止 ⇒ 会被挡下，一样没有按钮（先把清单装回去，档位与清单是两件独立的事）。
        install(listOf("settings.open"))
        val denied = RulesDemo.pageRow("wifi_custom", "我的无线页面", rawOf(listOf("settings.open")), AccessTier.DENIED)
        assertEquals(RulesDemo.Conclusion.BLOCKED, denied.conclusion)
        assertFalse(denied.runsForReal)

        // 发信前的许可：参数形状与 settings.open 既有校验同路（page 换成自定义键）。
        val request = RulesDemo.RunRequest(IntentTemplates.SETTINGS_OPEN, pageKey = "wifi_custom", label = "我的无线页面")
        assertEquals("settings.open", RulesDemo.pageArgs("wifi_custom").optString(IntentTemplates.KEY_TEMPLATE))
        assertEquals("wifi_custom", RulesDemo.pageArgs("wifi_custom").optString(IntentTemplates.KEY_PAGE))
        assertTrue(RulesDemo.canRun(request, rawOf(listOf("settings.open")), withPage))
        // 页面刚被删掉（这一行是上一帧画出来的）：不发。
        assertFalse(RulesDemo.canRun(request, rawOf(listOf("settings.open")), "[]"))
        // 清单又被拨回"每次询问"（快照也跟着回到"每次询问"）：不发（真闸门会弹卡）。
        install(emptyList())
        assertFalse(RulesDemo.canRun(request, "[]", withPage))
    }

    @Test
    fun `a user template never gets a real run even when it is listed`() {
        // 把自定义名字写进清单：闸门这时**确实**会免弹（结论是"不会弹卡"）……
        val list = listOf("my_action")
        install(list)
        val row = RulesDemo.userTemplateRow("my_action", "我的动作", rawOf(list))
        assertEquals(RulesDemo.Conclusion.QUIET_BY_LIST, row.conclusion)
        assertTrue(row.quiet)
        // ……但演示永不真开：action 是用户写死的任意字符串。
        assertFalse("用户自填动作永不真开", row.runsForReal)
        assertFalse(RulesDemo.canRun(RulesDemo.RunRequest("my_action"), rawOf(list), "[]"))

        // 连名字撞上白名单那 6 条也一样（界面会拒重名，这里把这条纪律也钉住）。
        install(RulesDemo.TEMPLATES + "dial")
        RulesDemo.TEMPLATES.forEach { name ->
            assertFalse(name, RulesDemo.userTemplateRow(name, name, rawOf(list)).runsForReal)
        }
    }

    @Test
    fun `the user page row and template row carry their per-row content`() {
        val pages = RulesDemo.demoEntry(
            RelaySettings.all.first { it.id == RelaySettings.SETTINGS_PAGES },
            pagesJson,
        ) as RulesDemo.DemoEntry.UserPages
        assertEquals(listOf("wifi_custom"), pages.pages)
        assertTrue(pages.note.contains("1"))

        val templates = RulesDemo.demoEntry(
            RelaySettings.all.first { it.id == RelaySettings.USER_TEMPLATES },
            templatesJson,
        ) as RulesDemo.DemoEntry.UserTemplates
        assertEquals(listOf("my_action"), templates.templates)
        assertTrue("自填动作那一项必须明说不真开", templates.note.contains("不真开"))
    }

    @Test
    fun `the demo path never creates a display and never writes settings`() {
        val root = findUiSourceRoot()
        assumeTrue("定位不到 ui 源码树，跳过源码扫描", root != null)
        val sourceRoot = requireNotNull(root)

        val demo = File(sourceRoot, "PilotSimulateDemo.kt").readText()
        val run = File(sourceRoot, "PilotSimulateRun.kt").readText()
        val screen = File(sourceRoot, "PilotRulesScreen.kt").readText()
        // 只扫页面上那两块演示区：整页别处本来就有合法的 setSetting 写入路径。
        //   ① 演示浮层里那一块（逐条真判定 + 卡示意 + 真开）；
        //   ② 两个文档编辑器（用户自填页面 / 动作）—— 七期新增的入口在这两处。
        val block = screen
            .substringAfter("private fun ScreenOnlyDemoBlock(")
            .substringBefore("private fun ModalOverlay(")
        // 两个编辑器各截一段：中间夹着的「上限编辑器」本来就有合法的导入通路（`importSettings`），
        // 那不是演示路径，不能算进来。
        val templateEditor = screen
            .substringAfter("private fun TemplateDocumentEditor(")
            .substringBefore("private fun PageDocumentEditor(")
        val pageEditor = screen
            .substringAfter("private fun PageDocumentEditor(")
            .substringBefore("private fun CeilingEditor(")
        val editors = templateEditor + "\n" + pageEditor
        assertTrue("演示区应当找得到", block.isNotEmpty())
        assertTrue("两个文档编辑器应当找得到", editors.isNotEmpty())
        // 七期新增的两行也在这一块里：用户页面行的**二次确认**、自定义动作行的"永不真开"。
        assertTrue("用户页面行必须带二次确认那句话", block.contains("你的当前界面会被切走"))
        assertTrue("自定义动作行必须明说不真开", block.contains("自定义动作一律不真开"))
        assertTrue("页面行与动作行共用同一处结论", block.contains("DemoConclusionLines("))

        // ⑥ 自定义动作那一行里**不存在**任何"真开"通路：不许有那颗按钮，也不许有那句话。
        // 到下一个 KDoc 为止：再往后是「回执」那一块的注释，不属这一行。
        val templateRow = block
            .substringAfter("private fun UserTemplateDemoRow(")
            .substringBefore("\n/**")
        assertTrue("自定义动作行应当找得到", templateRow.isNotEmpty())
        assertFalse("自定义动作行不许有真开按钮", templateRow.contains("DashedActionButton"))
        assertFalse("自定义动作行不许出现「真的开一次」", templateRow.contains("真的开一次"))
        assertFalse("自定义动作行不许出现「真的打开一次」", templateRow.contains("真的打开一次"))

        // ④ 用户页面行的真开必须**先过一次二次确认**：确认框与那句"界面会被切走"都在这一行里。
        val pageRow = block
            .substringAfter("private fun UserPageDemoRow(")
            .substringBefore("private fun UserTemplateDemoRow(")
        assertTrue("页面行应当找得到", pageRow.isNotEmpty())
        assertTrue("页面行的真开必须先过确认框", pageRow.contains("AlertDialog("))
        assertTrue("确认框要说清界面会被切走", pageRow.contains("你的当前界面会被切走"))
        assertTrue("按钮只挂在 runsForReal 那一条分支上", pageRow.contains("if (item.runsForReal)"))

        // 建屏通路、绕过闸门的直发、以及任何一次设置写入：这三类都不许出现在演示路径里。
        val forbidden = listOf(
            "SURFACE_VIRTUAL",
            "createVirtualDisplay",
            "TrustedDisplay",
            "displayId",
            "startActivity(",
            "setSetting(",
            "settings.put(",
            "importSettings(",
            "resetSetting",
        )
        forbidden.forEach { token ->
            assertFalse("PilotSimulateDemo.kt 不许出现 $token", demo.contains(token))
            assertFalse("PilotSimulateRun.kt 不许出现 $token", run.contains(token))
            assertFalse("页面的演示区不许出现 $token", block.contains(token))
            assertFalse("两个文档编辑器不许出现 $token", editors.contains(token))
        }

        // 名单与参数键必须引用 IntentTemplates 常量，不能另抄一份字符串。
        val literals = listOf(
            "alarm.set", "timer.set", "alarm.show", "timer.show",
            "settings.open", "app.info", "dial", "web.open",
        )
        literals.forEach { name ->
            assertFalse("白名单不许另抄字符串 \"$name\"", demo.contains("\"$name\""))
        }
    }

    /** 界面源码：找不到返回 null（测试按假设跳过）。 */
    private fun rulesScreenSource(): String? =
        findUiSourceRoot()?.let { File(it, "PilotRulesScreen.kt").takeIf(File::isFile)?.readText() }

    /** 中文资源文件：找不到返回 null（测试按假设跳过）。 */
    private fun findZhStrings(): String? {
        var dir: File? = findUiSourceRoot()
        while (dir != null) {
            val candidate = File(dir, "res/values-zh/strings.xml")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        return null
    }

    /** 从工作目录向上找 ui 源码树；找不到返回 null，测试按假设跳过。 */
    private fun findUiSourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (relative in listOf(
                "src/main/java/dshbox/adapter/ui",
                "dshbox-adapter/src/main/java/dshbox/adapter/ui",
                "pilot/dshbox-adapter/src/main/java/dshbox/adapter/ui",
            )) {
                val candidate = File(dir, relative)
                if (File(candidate, "PilotSimulateDemo.kt").isFile) return candidate
            }
            dir = dir.parentFile
        }
        return null
    }
}

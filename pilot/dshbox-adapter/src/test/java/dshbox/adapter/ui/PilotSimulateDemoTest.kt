package dshbox.adapter.ui

import interlock.relay.core.exec.direct.IntentTemplateCatalog
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.exec.direct.IntentTemplates
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

    @Test
    fun `the demo path never creates a display and never writes settings`() {
        val root = findUiSourceRoot()
        assumeTrue("定位不到 ui 源码树，跳过源码扫描", root != null)
        val sourceRoot = requireNotNull(root)

        val demo = File(sourceRoot, "PilotSimulateDemo.kt").readText()
        val run = File(sourceRoot, "PilotSimulateRun.kt").readText()
        val screen = File(sourceRoot, "PilotRulesScreen.kt").readText()
        // 只扫页面上那一块演示区：整页别处本来就有合法的 setSetting 写入路径。
        val block = screen
            .substringAfter("private fun ScreenOnlyDemoBlock(")
            .substringBefore("private fun ModalOverlay(")
        assertTrue("演示区应当找得到", block.isNotEmpty())

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

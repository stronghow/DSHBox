package interlock.relay.core.exec.direct

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板表的判据在 JVM 侧钉住：这张表决定"助手能让系统做哪几件事"，
 * 越界与未知模板必须在落到 Intent 之前就被拒掉。
 */
class IntentTemplatesTest {

    private fun outcome(args: JSONObject) = IntentTemplates.validate(args)

    private fun ok(args: JSONObject): IntentTemplates.Params {
        val result = outcome(args)
        assertTrue("expected Ok, got $result", result is IntentTemplates.Outcome.Ok)
        return (result as IntentTemplates.Outcome.Ok).params
    }

    private fun reason(args: JSONObject): String {
        val result = outcome(args)
        assertTrue("expected Bad, got $result", result is IntentTemplates.Outcome.Bad)
        return (result as IntentTemplates.Outcome.Bad).reason
    }

    @Test
    fun `alarm takes hour and minute as given`() {
        val params = ok(JSONObject().put("template", "alarm.set").put("hour", 23).put("minute", 40))
        assertEquals(IntentTemplates.ALARM_SET, params.template)
        assertEquals(23, params.hour)
        assertEquals(40, params.minute)
    }

    @Test
    fun `alarm bounds are refused rather than wrapped`() {
        // 25 点被折成 1 点之后，用户批的是卡片上那个 25:00，落进系统的是另一天的 1 点。
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("hour", 25).put("minute", 0)).contains("0..23"))
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("hour", 7).put("minute", 60)).contains("0..59"))
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("hour", -1).put("minute", 0)).isNotBlank())
    }

    @Test
    fun `alarm needs both hour and minute`() {
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("minute", 5)).contains("hour"))
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("hour", 5)).contains("minute"))
    }

    @Test
    fun `timer length is bounded`() {
        assertEquals(90, ok(JSONObject().put("template", "timer.set").put("length", 90)).lengthSeconds)
        assertTrue(reason(JSONObject().put("template", "timer.set").put("length", 0)).contains("seconds"))
        assertTrue(reason(JSONObject().put("template", "timer.set").put("length", 90000)).contains("seconds"))
        assertTrue(reason(JSONObject().put("template", "timer.set")).contains("length"))
    }

    @Test
    fun `unknown template lists what the table accepts`() {
        val reason = reason(JSONObject().put("template", "alarm.delete").put("hour", 1).put("minute", 1))
        assertTrue(reason.startsWith("unknown template: alarm.delete"))
        assertTrue(reason.contains("alarm.set"))
        // 表里没有"删除"这类入口，也不给任何路径把它加进来。
        assertTrue(!IntentTemplates.names.any { it.contains("delete") || it.contains("remove") })
    }

    @Test
    fun `missing template is a shape error`() {
        assertTrue(reason(JSONObject().put("hour", 7)).contains("template"))
    }

    /**
     * 免审批那一类必须**恰好**是那六条只上屏的模板。
     *
     * 这张表是 `InterlockGate` 那条 `no_state_change` 捷径的唯一判据，所以两个方向都要钉：
     * 六条都在里面（少一条就有一条只上屏的路径被 ASK 档位卡死），两条写操作都不在里面
     * （多一条就是一次免弹的闹钟/计时器创建）。两边加起来必须正好等于全表 —— 这条等式
     * 是"新加模板时必须二选一表态"的强制点。
     */
    @Test
    fun `only the six screen-only templates are exempt from approval`() {
        assertEquals(
            setOf("alarm.show", "timer.show", "settings.open", "app.info", "dial", "web.open"),
            IntentTemplates.noStateChange,
        )
        assertTrue(!IntentTemplates.isNoStateChange(IntentTemplates.ALARM_SET))
        assertTrue(!IntentTemplates.isNoStateChange(IntentTemplates.TIMER_SET))
        // 未知模板不算这一类：它照旧走档位判定，不会被免弹放行到后端才判"未知模板"。
        assertTrue(!IntentTemplates.isNoStateChange("alarm.delete"))
        assertTrue(!IntentTemplates.isNoStateChange(""))
        assertEquals(
            IntentTemplates.names.toSet(),
            IntentTemplates.noStateChange + setOf(IntentTemplates.ALARM_SET, IntentTemplates.TIMER_SET),
        )
        // 判据与校验共用同一个模板名字面量，两处不能各写一份拼写。
        IntentTemplates.noStateChange.forEach { template ->
            assertTrue("$template 不在表内", template in IntentTemplates.names)
        }
    }

    @Test
    fun `message length is capped`() {
        val long = "a".repeat(101)
        assertTrue(reason(JSONObject().put("template", "alarm.set").put("hour", 7).put("minute", 0).put("message", long))
            .contains("100"))
        assertEquals("起床", ok(JSONObject().put("template", "alarm.set").put("hour", 7).put("minute", 0).put("message", "起床")).message)
    }

    @Test
    fun `the two show templates need no arguments`() {
        assertEquals(IntentTemplates.ALARM_SHOW, ok(JSONObject().put("template", "alarm.show")).template)
        assertEquals(IntentTemplates.TIMER_SHOW, ok(JSONObject().put("template", "timer.show")).template)
    }

    @Test
    fun `settings page must be one of the named panels`() {
        assertEquals("wifi", ok(JSONObject().put("template", "settings.open").put("page", "wifi")).page)
        assertEquals("nfc", ok(JSONObject().put("template", "settings.open").put("page", "NFC")).page)
        assertTrue(reason(JSONObject().put("template", "settings.open")).contains("page"))
        // 表外的页面名不能靠"猜一个 action 常量"兜底：那等于把自由填写放回来。
        assertTrue(reason(JSONObject().put("template", "settings.open").put("page", "airplane"))
            .contains("airplane"))
    }

    @Test
    fun `app info needs a well formed package`() {
        assertEquals("com.x.y", ok(JSONObject().put("template", "app.info").put("package", "com.x.y")).packageName)
        assertTrue(reason(JSONObject().put("template", "app.info")).contains("package"))
        assertTrue(reason(JSONObject().put("template", "app.info").put("package", "has spaces")).isNotBlank())
        assertTrue(reason(JSONObject().put("template", "app.info").put("package", "com.x/y")).isNotBlank())
    }

    @Test
    fun `dial accepts dial-pad characters only`() {
        assertEquals("+86 138-0000", ok(JSONObject().put("template", "dial").put("number", "+86 138-0000")).number)
        // 其余字符会改 `tel:` 之外的东西：问号开查询串、with 分号开别的 scheme 参数。
        assertTrue(reason(JSONObject().put("template", "dial").put("number", "138;rm -rf")).isNotBlank())
        assertTrue(reason(JSONObject().put("template", "dial").put("number", "http://x")).isNotBlank())
        assertTrue(reason(JSONObject().put("template", "dial").put("number", "1".repeat(33))).isNotBlank())
        assertTrue(reason(JSONObject().put("template", "dial")).contains("number"))
    }

    @Test
    fun `web open takes only http and https`() {
        assertEquals(
            "https://example.com/a?b=1",
            ok(JSONObject().put("template", "web.open").put("url", "https://example.com/a?b=1")).url,
        )
        // 这四个 scheme 能把"打开网页"变成读私有文件或调起任意组件。
        listOf("file:///sdcard/x", "content://mms", "intent:#Intent", "javascript:alert(1)").forEach { bad ->
            assertTrue("$bad must be refused", reason(JSONObject().put("template", "web.open").put("url", bad)).isNotBlank())
        }
        assertTrue(reason(JSONObject().put("template", "web.open").put("url", "https://a b")).isNotBlank())
        assertTrue(reason(JSONObject().put("template", "web.open").put("url", "https://" + "a".repeat(512))).contains("512"))
    }

    @Test
    fun `the table holds no destructive or alarm-cancelling entry`() {
        // 删除类与"停掉正在响的闹钟"都不进表：后者对单次闹钟等于永久关闭。
        val banned = listOf("delete", "remove", "dismiss", "snooze", "clear", "uninstall", "stop")
        assertTrue(IntentTemplates.names.none { name -> banned.any { name.contains(it) } })
    }

    @Test
    fun `handler names the receiver and is carried on every template`() {
        // 「这一发由谁接」与模板无关：八条都可能有一个以上接收方，所以每条都该带着它。
        val perTemplate = listOf(
            JSONObject().put("template", "alarm.set").put("hour", 7).put("minute", 30),
            JSONObject().put("template", "timer.set").put("length", 60),
            JSONObject().put("template", "alarm.show"),
            JSONObject().put("template", "timer.show"),
            JSONObject().put("template", "settings.open").put("page", "display"),
            JSONObject().put("template", "app.info").put("package", "com.example.app"),
            JSONObject().put("template", "dial").put("number", "10086"),
            JSONObject().put("template", "web.open").put("url", "https://example.com"),
        )
        perTemplate.forEach { args ->
            assertEquals(
                "${args.optString("template")} 没带上 handler",
                "com.example.clock",
                ok(JSONObject(args.toString()).put("handler", "com.example.clock")).handler,
            )
            assertEquals(
                "${args.optString("template")} 没给 handler 时不该凭空造一个",
                null,
                ok(args).handler,
            )
        }
    }

    @Test
    fun `handler and package are two different things`() {
        // app.info 的 package 是"展示哪个应用"，handler 是"谁接这一发"：两者可以同时在，且不必相同。
        val params = ok(
            JSONObject()
                .put("template", "app.info")
                .put("package", "com.example.target")
                .put("handler", "com.example.settings"),
        )
        assertEquals("com.example.target", params.packageName)
        assertEquals("com.example.settings", params.handler)
    }

    @Test
    fun `handler must be a package name before it reaches the system`() {
        // 形状不合法就不往下走：一个不是包名的字符串交给 setPackage 只会静默找不到接收方。
        listOf("", "  ", "not a package", "com", "com.", "1com.example", "com/example").forEach { bad ->
            val args = JSONObject().put("template", "web.open").put("url", "https://example.com").put("handler", bad)
            val outcome = outcome(args)
            if (bad.isBlank()) {
                // 空白等于没给（trim 之后为空），不算错。
                assertTrue("$bad 应该被当成没给", outcome is IntentTemplates.Outcome.Ok)
                assertEquals(null, (outcome as IntentTemplates.Outcome.Ok).params.handler)
            } else {
                assertTrue("$bad 应被拒，实际 $outcome", outcome is IntentTemplates.Outcome.Bad)
                assertTrue(
                    (outcome as IntentTemplates.Outcome.Bad).reason.contains("is not a package name"),
                )
            }
        }
        // 合法形状原样保留（不做大小写或别名处理）。
        assertEquals(
            "com.example.app_2",
            ok(
                JSONObject().put("template", "web.open").put("url", "https://example.com")
                    .put("handler", "com.example.app_2"),
            ).handler,
        )
    }
}

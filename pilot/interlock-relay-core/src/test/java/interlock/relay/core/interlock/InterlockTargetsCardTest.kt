package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 确认卡上那一行字：用户批的从来不是"sys.intent 这个能力"，而是卡上写的那一件事。
 *
 * 这一轮给 `sys.intent` 加了可选接收方 `handler`（见 `IntentTemplates.KEY_HANDLER`），
 * 于是卡面多了一种必须说清的形状：「交给谁」。不点 handler 时卡面必须与从前一字不差 ——
 * 加参数不该顺带把老卡面改了。
 */
class InterlockTargetsCardTest {

    private val descriptor = requireNotNull(CapabilityRegistry.find(CapabilityId.SYS_INTENT))

    private fun card(args: JSONObject): Pair<String?, String?> =
        InterlockTargets.of(descriptor, args) { "$it chars" }

    @Test
    fun `without a handler the card is exactly as before`() {
        assertEquals(
            "alarm.set" to "07:30",
            card(JSONObject().put("template", "alarm.set").put("hour", 7).put("minute", 30)),
        )
        assertEquals(
            "timer.set" to "length=60s",
            card(JSONObject().put("template", "timer.set").put("length", 60)),
        )
        assertEquals(
            "app.info" to "com.example.target",
            card(JSONObject().put("template", "app.info").put("package", "com.example.target")),
        )
        // 打开设置页那几条没有额外参数：第二格是空串，不是 "-01:-01"。
        assertEquals("timer.show" to "", card(JSONObject().put("template", "timer.show")))
    }

    @Test
    fun `a named receiver is shown on the card`() {
        assertEquals(
            "alarm.set" to "07:30 -> com.example.clock",
            card(
                JSONObject().put("template", "alarm.set").put("hour", 7).put("minute", 30)
                    .put("handler", "com.example.clock"),
            ),
        )
        // 对象（package）与接收方（handler）是两件事，卡上两个都要看得见。
        assertEquals(
            "app.info" to "com.example.target -> com.example.settings",
            card(
                JSONObject().put("template", "app.info").put("package", "com.example.target")
                    .put("handler", "com.example.settings"),
            ),
        )
        // 空串等于没点：卡面不该冒出一个空箭头。
        assertEquals(
            "settings.open" to "display",
            card(JSONObject().put("template", "settings.open").put("page", "display").put("handler", "")),
        )
    }

    @Test
    fun `a long receiver still fits the one line`() {
        val long = "com.example." + "a".repeat(120)
        val (first, second) = card(
            JSONObject().put("template", "web.open").put("url", "https://example.com").put("handler", long),
        )
        assertEquals("web.open", first)
        assertEquals(80, second!!.length)
    }
}

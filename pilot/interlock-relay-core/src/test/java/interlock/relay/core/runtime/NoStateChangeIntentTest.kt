package interlock.relay.core.runtime

import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免审批捷径的守门判据：只有「只上屏、不改状态」那一形状能免掉用户那次确认。
 *
 * 这条判据放宽一格就是一次免弹的状态改动（真建出闹钟），收紧一格就是一条只能上屏的
 * 路径在 ASK 档位下被卡死。两个方向各钉一遍，另外钉住"别的能力带上 template 也不算"。
 */
class NoStateChangeIntentTest {

    private val sysIntent = requireNotNull(CapabilityRegistry.find(CapabilityId.SYS_INTENT)) {
        "能力注册表里没有 ${CapabilityId.SYS_INTENT.wire}"
    }

    private val pkgQuery = requireNotNull(CapabilityRegistry.find(CapabilityId.PKG_QUERY))

    private fun args(template: String?) =
        JSONObject().apply { if (template != null) put("template", template) }

    @Test
    fun `the six screen-only templates are exempt`() {
        listOf("settings.open", "alarm.show", "timer.show", "app.info", "dial", "web.open").forEach {
            assertTrue(it, noStateChangeIntentShape(sysIntent, args(it)))
        }
    }

    @Test
    fun `the two writing templates stay behind the tier gate`() {
        assertFalse(noStateChangeIntentShape(sysIntent, args("alarm.set")))
        assertFalse(noStateChangeIntentShape(sysIntent, args("timer.set")))
    }

    @Test
    fun `an unknown template is not exempt`() {
        // 未知模板在后端要被回「unknown template」；若在这里就免弹放行，等于先批准再报错。
        assertFalse(noStateChangeIntentShape(sysIntent, args("alarm.delete")))
        assertFalse(noStateChangeIntentShape(sysIntent, args("")))
        assertFalse(noStateChangeIntentShape(sysIntent, args(null)))
    }

    @Test
    fun `another capability carrying a template key is not exempt`() {
        assertFalse(noStateChangeIntentShape(pkgQuery, args("settings.open")))
    }
}

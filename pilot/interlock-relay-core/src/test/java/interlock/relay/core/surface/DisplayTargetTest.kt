package interlock.relay.core.surface

import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.protocol.SurfaceKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ui.*` 的显式目标屏参数：编号 → 执行面的那一次换算。
 *
 * 这条换算的失效方式都是"看起来成功"：认错了屏，调用落在另一块屏上照常回 ok。
 * 所以三种输入各钉一遍 —— 不传、两块认得的屏、认不出的编号。
 */
class DisplayTargetTest {

    private fun resolve(
        capability: CapabilityId = CapabilityId.UI_SNAPSHOT,
        args: JSONObject = JSONObject(),
        trustedDisplayId: Int = -1,
    ) = DisplayTarget.resolve(capability, args, trustedDisplayId)

    @Test
    fun omittingTheKeyLeavesRoutingToTheSurfacePolicy() {
        assertEquals(
            DisplayTarget.Resolution.Unspecified,
            resolve(args = JSONObject().put("nodeId", 3), trustedDisplayId = 2),
        )
    }

    /** 不收这个键的能力即便递了它也不该在这里被解释：放行/拒绝由那张键表决定。 */
    @Test
    fun capabilitiesOutsideTheTableNeverGetRoutedHere() {
        assertEquals(
            DisplayTarget.Resolution.Unspecified,
            resolve(capability = CapabilityId.SCREEN_CAPTURE, args = JSONObject().put("display", 0)),
        )
        assertEquals(
            DisplayTarget.Resolution.Unspecified,
            resolve(capability = CapabilityId.PKG_QUERY, args = JSONObject().put("display", 0)),
        )
    }

    @Test
    fun zeroIsTheScreenTheUserIsLookingAt() {
        assertEquals(
            DisplayTarget.Resolution.To(SurfaceKind.FOREGROUND),
            resolve(args = JSONObject().put("display", 0), trustedDisplayId = 2),
        )
        // 没有虚拟屏时 0 照样成立：用户眼前那块屏永远在。
        assertEquals(
            DisplayTarget.Resolution.To(SurfaceKind.FOREGROUND),
            resolve(args = JSONObject().put("display", 0), trustedDisplayId = -1),
        )
    }

    @Test
    fun theTrustedDisplayNumberMapsToTheBackendSurface() {
        assertEquals(
            DisplayTarget.Resolution.To(SurfaceKind.BACKEND_TRUSTED),
            resolve(args = JSONObject().put("display", 2), trustedDisplayId = 2),
        )
    }

    @Test
    fun anUnknownDisplayIsRefusedWithWhereToFindTheRightOne() {
        val bad = resolve(args = JSONObject().put("display", 7), trustedDisplayId = 2)
        assertTrue("expected Bad, got $bad", bad is DisplayTarget.Resolution.Bad)
        val reason = (bad as DisplayTarget.Resolution.Bad).reason
        assertTrue(reason, reason.contains("display=7"))
        assertTrue(reason, reason.contains("currently 2"))

        // 没有屏在跑时，编号 2 也认不出来 —— 不能说成"那块屏"。
        val noScreen = resolve(args = JSONObject().put("display", 2), trustedDisplayId = -1)
        assertTrue(noScreen is DisplayTarget.Resolution.Bad)
        assertTrue((noScreen as DisplayTarget.Resolution.Bad).reason.contains("none exists now"))
    }

    @Test
    fun aNonNumericDisplayIsRefusedWithTheNumberWording() {
        val bad = resolve(args = JSONObject().put("display", "0"))
        assertTrue(bad is DisplayTarget.Resolution.Bad)
        assertTrue((bad as DisplayTarget.Resolution.Bad).reason.startsWith("display must be a number"))
    }

    @Test
    fun aFractionalDisplayIsRefusedRatherThanTruncated() {
        val bad = resolve(args = JSONObject().put("display", 1.5))
        assertTrue(bad is DisplayTarget.Resolution.Bad)
        assertTrue((bad as DisplayTarget.Resolution.Bad).reason.startsWith("display must be a whole number"))
    }

    /**
     * 这张名单与能力注册表必须同时成立：一条只声明了 FOREGROUND 的能力出现在这里，
     * 就意味着 `{"display":<trustedId>}` 会被换成一个它自己根本没声明的执行面。
     */
    @Test
    fun everyDisplaySelectableCapabilityDeclaresBothSurfaces() {
        DisplayTarget.CAPABILITIES.forEach { id ->
            val descriptor = requireNotNull(CapabilityRegistry.find(id)) { "${id.wire} 不在注册表里" }
            assertTrue(
                "${id.wire} 没声明 ${SurfaceKind.BACKEND_TRUSTED.wire}，却被 display 换算指向它",
                descriptor.surfaces.contains(SurfaceKind.BACKEND_TRUSTED),
            )
            assertTrue(
                "${id.wire} 没声明 ${SurfaceKind.FOREGROUND.wire}，却接受 display=0",
                descriptor.surfaces.contains(SurfaceKind.FOREGROUND),
            )
        }
    }
}

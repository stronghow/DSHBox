package interlock.relay.core.surface

import interlock.relay.core.protocol.ArgErrors
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.SurfaceKind
import org.json.JSONObject

/**
 * `ui.*` 与 `app.launch` 的可选目标屏参数 `display`。
 *
 * 「这次落在哪块屏上」在本框架里由**执行面**（[SurfaceKind]）表达：物理主屏 = `FOREGROUND`，
 * 可信虚拟屏 = `BACKEND_TRUSTED`。助手手上的 displayId 与执行面是同一件事的两种写法，
 * 于是这条参数只做一次换算，不另开一条注入通路 —— 换算到执行面之后，两条通路用的仍是
 * 各自既有的那套坐标基准与取树规则（前台走 `rootInActiveWindow` /
 * `dispatchGesture`，可信屏走 `getWindowsOnAllDisplays` / `input -d`）。
 *
 * 不传 `display` 时返回 [Resolution.Unspecified]，路由原样交给 [SurfacePolicy]，
 * 现有脚本的行为一个字节都不变；传了才改，且只改这一次。
 *
 * 为什么只认两块屏：能力清单里 `backend.shizuku.trustedDisplay.displayId` 是**唯一**
 * 一块框架自己建出来、又知道怎么在上面读写的那块屏。别的 displayId（厂商分屏、
 * 外部 HDMI）没有对应的执行面，硬塞一条 `input -d` 会得到一次"看起来成功"的空动作，
 * 所以这里如实拒并告诉调用方去哪儿取正确的编号。
 */
object DisplayTarget {

    /** 参数键名。与 `CapabilityArgsSpec` 里那张键表、以及沙盒侧说明书共用同一个字面量。 */
    const val KEY_DISPLAY = "display"

    /** 用户正在看的那块屏。Android 的默认屏编号恒为 0。 */
    const val DEFAULT_DISPLAY = 0

    /**
     * 接受 `display` 的能力。
     *
     * 三组：读树那十一条（含 `ui.snapshot`）、坐标注入那四条、以及 `app.launch` 那一条。
     * 前两组的分界与 `A11yBackend` 的 `TREE_CAPABILITIES` / `COORDINATE_CAPABILITIES`
     * 逐条对齐 —— 两处必须同时改；`app.launch` 单独成组：它的两条通路分别由两个后端提供
     * （前台 = `DirectBackend` 的 `startActivity`，可信屏 = `ShizukuBackend` 的
     * `am start --display`），而"这次把应用投到哪块屏"正是最需要调用方点名的一件事。
     * 名单、键表与各后端那张表必须同时成立，[interlock.relay.core.protocol.CapabilityArgsSpecTest]
     * 与 [DisplayTargetTest] 各钉一半。
     */
    val CAPABILITIES: Set<CapabilityId> = setOf(
        CapabilityId.UI_SNAPSHOT,
        CapabilityId.UI_NODE,
        CapabilityId.UI_WAIT_FOR,
        CapabilityId.UI_CLICK,
        CapabilityId.UI_LONG_CLICK,
        CapabilityId.UI_SELECT,
        CapabilityId.UI_DISMISS,
        CapabilityId.UI_SCROLL,
        CapabilityId.UI_SET_VALUE,
        CapabilityId.UI_SET_PROGRESS,
        CapabilityId.UI_IME_ACTION,
        CapabilityId.UI_TAP,
        CapabilityId.UI_SWIPE,
        CapabilityId.UI_TEXT,
        CapabilityId.UI_KEY,
        // 启动也认这个键：0 = 起在用户眼前那块屏（DirectBackend），可信屏编号 = 投送到虚拟屏
        // （ShizukuBackend 的 `am start --display`）。同一条能力两块屏都能落，所以必须点名。
        CapabilityId.APP_LAUNCH,
    )

    /** 一次调用该落在哪块屏上的换算结果。 */
    sealed interface Resolution {
        /** 没传 `display`（或这条能力不收它）：路由交给 [SurfacePolicy]。 */
        object Unspecified : Resolution

        /** 传了，且这个编号有对应的执行面。 */
        data class To(val surface: SurfaceKind) : Resolution

        /** 传了，但不是一次能兑现的请求。[reason] 与后端参数错误同一句式。 */
        data class Bad(val reason: String) : Resolution
    }

    /**
     * @param trustedDisplayId 可信虚拟屏此刻的编号；没有屏时为负数。与
     *   `A11yBackend` / 能力清单读的是同一个数（装配根那一处），三处不能各问一次。
     */
    fun resolve(
        capability: CapabilityId,
        args: JSONObject,
        trustedDisplayId: Int,
    ): Resolution {
        if (capability !in CAPABILITIES) return Resolution.Unspecified
        if (!args.has(KEY_DISPLAY)) return Resolution.Unspecified
        // 预校验已经在闸门之前按同一句措辞挡过类型与整数值，这里是后端侧的防御性复验：
        // 两条路都判同一件事，才不会出现「预校验放行、后端把它当 0 用」的夹紧。
        val value = (args.opt(KEY_DISPLAY) as? Number)?.toDouble()?.takeIf { it.isFinite() }
            ?: return Resolution.Bad(ArgErrors.notNumber(KEY_DISPLAY, args.opt(KEY_DISPLAY)))
        if (value % 1.0 != 0.0 ||
            value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()
        ) {
            return Resolution.Bad(
                "$KEY_DISPLAY must be a whole number, got ${args.opt(KEY_DISPLAY)} " +
                    "(${args.opt(KEY_DISPLAY)?.javaClass?.simpleName})",
            )
        }
        val id = value.toInt()
        return when {
            id == DEFAULT_DISPLAY -> Resolution.To(SurfaceKind.FOREGROUND)
            trustedDisplayId >= 0 && id == trustedDisplayId -> Resolution.To(SurfaceKind.BACKEND_TRUSTED)
            else -> Resolution.Bad(
                "$KEY_DISPLAY=$id is not a screen this call can target: use 0 for the screen the user " +
                    "is looking at, or the displayId of the running trusted display " +
                    "(capabilities.json backend.shizuku.trustedDisplay.displayId" +
                    (if (trustedDisplayId >= 0) ", currently $trustedDisplayId" else ", none exists now") +
                    "). Other displays have no execution surface to drive.",
            )
        }
    }
}

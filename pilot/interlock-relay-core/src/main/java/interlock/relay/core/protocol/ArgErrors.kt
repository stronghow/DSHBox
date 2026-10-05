package interlock.relay.core.protocol

import org.json.JSONObject

/**
 * 参数错误的措辞集中在这里。助手的错误码只有「用法错」与「用户拒绝」两类，
 * 而它区分两者的依据就是 reason 文本 —— 同一次拼错在三条后端的键表里必须拿到
 * 同一句话，否则它会学到"换个通路再试一次"。
 */
object ArgErrors {

    /**
     * 「指定落点」这一类写法的几个别名。只有 `display` 是**一条真正存在的可选参数**，
     * 且只对 `ui.*` 里那几条接受它的能力生效（见 `DisplayTarget.CAPABILITIES`）；
     * 其余三个别名与「这条能力不收 display」的场合都沿用旧口径：落点由用户的执行模式
     * 偏好决定，调用方改名字改不出这条路。`display` 真的落在 [allowed] 里时本分支不会被
     * 走到 —— 调用点只在键不合法时才来问这里。
     */
    private val DISPLAY_TARGETING_KEYS = setOf("display", "displayId", "surface", "display_id")

    /**
     * 未知键。[allowed] 给出时把这一条能力真正接受的键一并列出：只回
     * `unknown arg: keyword` 分不出"名字写错"与"这条能力没有这个入参"，调用方只能
     * 逐个拼写重试；同一份契约里有的键不合类型时会列出可接受取值，两种严格度不该并存。
     * 落点类键名单独处理——在那条能力上它不是一个可以改对的名字。
     */
    fun unknown(key: String, allowed: Collection<String>? = null): String = if (key in DISPLAY_TARGETING_KEYS) {
        "unknown arg: $key — this capability does not take a display argument: which screen a call " +
            "runs on is decided by the user's surface preference, not by the caller (ui.tap / " +
            "ui.swipe / ui.snapshot / ui.node and the other node-level ui.* calls accept an explicit " +
            "\"display\" when you must name one). Read capabilities.json " +
            "backend.shizuku.trustedDisplay {alive,displayId} to see whether a virtual display " +
            "exists, and each response's surface/degraded fields to see where it actually ran."
    } else {
        val accepted = allowed?.sorted()
        if (accepted.isNullOrEmpty()) "unknown arg: $key"
        else "unknown arg: $key — this call takes: ${accepted.joinToString(", ")}"
    }

    /**
     * 数值键不合类型时的措辞，带上**实际收到的值与类型**（字符串按 40 字符截断）。
     * 只说「要是个数字」分不出是调用方传错还是传输层把数字变成了字符串，
     * 而这两者的下一步完全不同：改脚本，还是去查通道。
     */
    fun notNumber(key: String, raw: Any?): String {
        val got = when {
            raw == null || raw === JSONObject.NULL -> "nothing"
            raw is String -> "\"${raw.take(40)}\" (string)"
            else -> "$raw (${raw.javaClass.simpleName})"
        }
        return "$key must be a number, got $got"
    }
}

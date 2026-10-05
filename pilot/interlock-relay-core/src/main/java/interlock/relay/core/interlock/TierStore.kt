package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.spi.RelayPrefs

/**
 * agent 授权档位的存储。
 *
 * 档位只存宿主私有偏好文件，绝不落任何共享目录：沙盒与宿主同 uid，
 * 放在沙盒可读位置等于让助手改写自己的权限。
 * 「会话内允许」只存内存，助手会话结束即失效，不跨会话残留。
 */
class TierStore(private val prefs: RelayPrefs) {

    private val file = RelayPrefs.FILE_MAIN

    private val sessionGrants = java.util.concurrent.ConcurrentHashMap<CapabilityId, Long>()

    fun tierOf(id: CapabilityId): AccessTier =
        prefs.getString(file, key(id), null)
            ?.let { runCatching { AccessTier.valueOf(it) }.getOrNull() }
            ?: defaultTier(id)

    /**
     * 用户没拨过时的档位。
     *
     * [补丁] 下面这一组默认档位直接给 ALWAYS：三条截屏 + `sys.shell` + 8 条"实用"能力
     * （secure settings 写、剪贴板读写、app 停止、包安装、appops、媒体写、通知读取）。
     * 注意：光改这里不够——"每次必问"还有一层来自上限（见 InterlockGate.requiresApproval），
     * 那些能力的上限已在 CapabilityRegistry 里一并改成 ANY；两处缺一不可。
     */
    private fun defaultTier(id: CapabilityId): AccessTier = when (id) {
        CapabilityId.SYS_SHELL,
        CapabilityId.SECURE_SETTINGS,
        CapabilityId.CLIPBOARD_READ,
        CapabilityId.CLIPBOARD_WRITE,
        CapabilityId.APP_STOP,
        CapabilityId.PKG_INSTALL,
        CapabilityId.APPOPS_SET,
        CapabilityId.MEDIA_WRITE,
        CapabilityId.NOTIFY_READ,
        CapabilityId.SCREEN_CAPTURE,
        CapabilityId.SCREEN_OBSERVE,
        CapabilityId.SCREEN_RECORD,
        -> AccessTier.ALWAYS
        else -> DEFAULT_TIER
    }

    /**
     * 档位按用户选定的原样存储。不可逆能力的「每次必问」由裁决层按上限强制
     * （见 InterlockGate.requiresApproval），不在写入时替用户改主意——
     * 否则界面上点了「完全访问」却回到「询问审批」，等于界面在撒谎。
     */
    fun setTier(id: CapabilityId, tier: AccessTier) {
        prefs.putString(file, key(id), tier.name)
        if (tier != AccessTier.ALWAYS) sessionGrants.remove(id)
    }

    fun grantForSession(id: CapabilityId, expiresAtMs: Long) {
        sessionGrants[id] = expiresAtMs
    }

    fun sessionGranted(id: CapabilityId, now: Long): Boolean {
        val expires = sessionGrants[id] ?: return false
        if (expires <= now) {
            sessionGrants.remove(id)
            return false
        }
        return true
    }

    /** agent 会话边界：换会话或沙盒重启时一律清空。 */
    fun clearSession() = sessionGrants.clear()

    private fun key(id: CapabilityId) = "$KEY_PREFIX${id.wire.replace('.', '_')}"

    private companion object {
        const val KEY_PREFIX = "tier_"
        val DEFAULT_TIER = AccessTier.ASK
    }
}

package dshbox.adapter.ui

import interlock.relay.core.exec.direct.IntentTemplates
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.RELAY_PROTOCOL_VERSION
import interlock.relay.core.storage.RelayPaths
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 「真的开一次试试」的执行桥：**唯一一条会执行真实动作的演示路径**，且只能由用户点击触发。
 *
 * 它走的是**真闸门**，一步都不绕：把一条 v1 请求信封投进通道收件箱（与助手侧入口脚本同一形状，
 * 见 `RelayWire.kt:29` 与 `ControlChannelServer.kt:108-115` 的 `submitEnvelopeV1`），
 * 由宿主自己的工作循环认领 → `RelayCoordinator.handle` → `InterlockGate.authorize` →
 * 后端 `DirectBackend.semanticIntent`。判据名（`no_state_change` 等）落在授权记录里，
 * 由 [RulesDemo.decisionFrom] 从同一份记录读出来。
 *
 * **零建屏**：这里没有任何 `surface.virtual` 调用，也不碰 `display` 键。
 * **零写设置**：全程只往通道目录写请求文件，不调 `setSetting` / `SettingsStore.put`。
 * 白名单与结论由 [RulesDemo] 管：只有"硬编码 6 条 且 复算为不弹卡"才允许走到这里，
 * 调用方（[PilotViewModel.simulateRunOnce]）在发之前还会再查一次。
 */
internal object RulesDemoRunner {

    /** 等回执的上限。真动作本身是毫秒级，这里留的是"通道排队 + 落盘"的余量。 */
    const val DEFAULT_TIMEOUT_MS: Long = 12_000L

    /** 回前台那一发的上限：它只是一次 `app.launch` 自己。 */
    const val BRING_BACK_TIMEOUT_MS: Long = 8_000L

    private const val POLL_MS = 120L

    /**
     * 请求有效期：落在信封解码允许的 `1_000..120_000` 里，取一个"足够等到回执"的值。
     * 它与本层的 [DEFAULT_TIMEOUT_MS] 各管一段：这里是"宿主愿意花多久执行"，
     * 那里是"界面愿意等多久读回执"。
     */
    private const val REQUEST_TTL_MS = 30_000L

    /** 回程：`app.launch` 只带宿主包名这一种形状（闸门里那条 `host_bring_to_front` 豁免）。 */
    private const val BRING_BACK_TTL_MS = 10_000L

    private var counter = 0

    /**
     * 一条演示请求的回执。[requestId] 是查授权记录的钥匙；[errorCode] 是线上错误码
     * （`E_AWAITING_CONSENT` / `E_GATE_NO_FOREGROUND` / `E_TRANSPORT_MALFORMED` …）。
     */
    data class Outcome(
        val requestId: String,
        val ok: Boolean,
        val timedOut: Boolean,
        val errorCode: String? = null,
        val note: String? = null,
        val resolvedPackage: String? = null,
        val viaResolver: Boolean = false,
    ) {
        /**
         * 回执是"弹卡/等待同意"。设计上不该出现（按钮只对免弹条目开放），一旦出现就是
         * 一次真相压过了预告：**立即停止，按「会弹卡」呈现**，并把那张已经挂上的框收掉。
         */
        val awaitingConsent: Boolean get() = errorCode == AWAITING_CONSENT
    }

    /** 通道回执里的等待同意码（与 `RelayError.GATE_AWAITING_CONSENT.code` 同值）。 */
    const val AWAITING_CONSENT: String = "E_AWAITING_CONSENT"

    /**
     * 真开一条 `sys.intent` 模板。参数在发之前先用**后端同一把尺子**（`IntentTemplates.validate`）
     * 过一遍：形状不合就根本不该发出去，也没必要让通道再拒一次。
     */
    suspend fun sendTemplate(
        paths: RelayPaths,
        template: String,
        args: JSONObject,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Outcome {
        val shaped = IntentTemplates.validate(args)
        if (shaped is IntentTemplates.Outcome.Bad) {
            return Outcome(
                requestId = "",
                ok = false,
                timedOut = false,
                errorCode = "E_TRANSPORT_MALFORMED",
                note = shaped.reason,
            )
        }
        return submitAndAwait(paths, CapabilityId.SYS_INTENT.wire, args, REQUEST_TTL_MS, timeoutMs)
    }

    /**
     * 收尾把宿主带回前台：`app.launch` + **只带宿主包名**这一种形状。
     * 闸门里它是 `host_bring_to_front` 豁免（`InterlockGate.kt:104-108`），不问人、与档位无关；
     * 前台那一发设置页把用户带走之后，这是把他带回来的那一步。
     */
    suspend fun bringHostToFront(
        paths: RelayPaths,
        hostPackage: String,
        timeoutMs: Long = BRING_BACK_TIMEOUT_MS,
    ): Outcome = submitAndAwait(
        paths = paths,
        capabilityWire = CapabilityId.APP_LAUNCH.wire,
        args = JSONObject().put("package", hostPackage),
        ttlMs = BRING_BACK_TTL_MS,
        timeoutMs = timeoutMs,
    )

    private suspend fun submitAndAwait(
        paths: RelayPaths,
        capabilityWire: String,
        args: JSONObject,
        ttlMs: Long,
        timeoutMs: Long,
    ): Outcome = withContext(Dispatchers.IO) {
        val id = newId()
        if (!submit(paths, id, capabilityWire, args, ttlMs)) {
            return@withContext Outcome(id, ok = false, timedOut = false, errorCode = "E_STORAGE", note = "请求没能落到通道收件箱")
        }
        val out = File(paths.outboxDir, "$id${EnvelopeSuffix}")
        val deadline = System.currentTimeMillis() + timeoutMs
        var body: String? = null
        while (System.currentTimeMillis() < deadline && currentCoroutineContext().isActive) {
            if (out.isFile) {
                body = runCatching { out.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
                if (body != null) break
            }
            delay(POLL_MS)
        }
        if (body == null) {
            // 收不到回执只说明"这一趟没有结论"：请求有效期到了会被宿主按超时收场。
            // 不重发（重发就是第二次真实动作），把现象如实交回界面。
            return@withContext Outcome(id, ok = false, timedOut = true)
        }
        runCatching { out.delete() }
        parse(id, body)
    }

    /**
     * 与助手侧入口脚本同一形状：先写分片、再原子改名进收件箱（改名到达的件免去 300ms 静默期）。
     * 分片落在 `stagingDir`（**不映射给沙盒**的那一边）：同 uid 的对手预放一个同名符号链接
     * 才可能把正文带偏，而它读不到这个目录 —— 与 `StorageReaper.writeTextAtomic` 同一条纪律。
     */
    private fun submit(paths: RelayPaths, id: String, capabilityWire: String, args: JSONObject, ttlMs: Long): Boolean {
        val envelope = JSONObject().apply {
            put("v", RELAY_PROTOCOL_VERSION)
            put("id", id)
            put("ts", System.currentTimeMillis())
            put("capability", capabilityWire)
            put("args", args)
            put("ttlMs", ttlMs)
        }.toString()
        return runCatching {
            paths.stagingDir.mkdirs()
            paths.inboxDir.mkdirs()
            val part = File(paths.stagingDir, "$id$PartSuffix")
            val target = File(paths.inboxDir, "$id$EnvelopeSuffix")
            part.writeText(envelope)
            if (part.renameTo(target)) {
                true
            } else {
                target.delete()
                part.renameTo(target)
            }
        }.getOrDefault(false)
    }

    private fun parse(id: String, body: String): Outcome {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return Outcome(id, ok = false, timedOut = false, errorCode = "E_TRANSPORT_MALFORMED", note = "回执读不出来")
        val ok = json.optBoolean("ok")
        val data = json.optJSONObject("data")
        return Outcome(
            requestId = json.optString("id").ifEmpty { id },
            ok = ok,
            timedOut = false,
            // 回包对成功/失败用两个键说事：`note` 是成功时的补充说明，`reason` 只说为什么失败。
            errorCode = json.optJSONObject("error")?.optString("code")?.takeIf { it.isNotEmpty() },
            note = json.optString(if (ok) "note" else "reason").takeIf { it.isNotEmpty() },
            resolvedPackage = data?.optString("resolvedPackage")?.takeIf { it.isNotEmpty() },
            viaResolver = data?.optBoolean("viaResolver") == true,
        )
    }

    /** 请求号形状受 `EnvelopeCodec.ID_PATTERN = [A-Za-z0-9_-]{1,64}` 约束。 */
    private fun newId(): String {
        counter = (counter + 1) % 1000
        return "demo" + System.currentTimeMillis().toString(36) + "-" + counter
    }

    private const val EnvelopeSuffix = ".json"
    private const val PartSuffix = ".part"
}

package interlock.relay.core.settings

import interlock.relay.core.spi.RelayPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置中心的读写口。
 *
 * 值统一以字符串编码存进宿主私有偏好文件 [RelayPrefs.FILE_MAIN]，键为
 * [RelaySettings.key]。读的时候**缺省即默认**：prefs 里没有这一项、或存的字符串解不出
 * 合法值，一律回落到 schema 里的默认值 —— 于是"用户没拨过"与"升级前"是同一条代码路径，
 * 行为不可能因为本功能上线而漂移。
 *
 * 每一次写入都过校验并留下审计行（谁在什么时候把哪一项从什么改成什么）。审计落在
 * 宿主私有目录（[interlock.relay.core.storage.RelayPaths.auditDir]），不在沙盒绑定子树内。
 */
class SettingsStore(
    private val prefs: RelayPrefs,
    /** 审计落点。由装配根接上文件；缺省不落盘，便于单测。 */
    private val audit: (String) -> Unit = {},
    /** 文档型值的校验器：kind → (文本) → 错误信息（null=通过）。由装配根接上模板编解码器。 */
    private val documentValidator: (String, String) -> String? = { _, _ -> null },
) {

    private val file = RelayPrefs.FILE_MAIN

    fun specs(): List<RelaySettings.Spec> = RelaySettings.all

    fun spec(id: String): RelaySettings.Spec? = RelaySettings.byId[id]

    /** 原始编码值：prefs 里存了什么就是什么；没存过回默认。 */
    fun raw(id: String): String {
        val spec = RelaySettings.byId[id] ?: return ""
        return prefs.getString(file, RelaySettings.key(id), null) ?: spec.default
    }

    fun isDefault(id: String): Boolean {
        val spec = RelaySettings.byId[id] ?: return true
        return raw(id) == spec.default
    }

    // ── 类型化解码：解不出合法值就回默认（而不是把坏值当生效值） ──

    fun bool(id: String, fallback: Boolean): Boolean {
        val spec = RelaySettings.byId[id]
        val stored = prefs.getString(file, RelaySettings.key(id), null)
        if (stored != null) stored.toBooleanStrictOrNull()?.let { return it }
        return spec?.default?.toBooleanStrictOrNull() ?: fallback
    }

    fun int(id: String, fallback: Int): Int {
        val spec = RelaySettings.byId[id]
        val stored = prefs.getString(file, RelaySettings.key(id), null)
        if (stored != null) stored.trim().toIntOrNull()?.let { return it }
        return spec?.default?.trim()?.toIntOrNull() ?: fallback
    }

    fun string(id: String, fallback: String): String {
        val spec = RelaySettings.byId[id]
        return prefs.getString(file, RelaySettings.key(id), null) ?: spec?.default ?: fallback
    }

    /** 字符串集合（存成 JSON 数组）。解析不了就是空集，不把坏值当成"有一堆条目"。 */
    fun stringSet(id: String, fallback: Set<String> = emptySet()): Set<String> {
        val spec = RelaySettings.byId[id]
        val text = prefs.getString(file, RelaySettings.key(id), null) ?: spec?.default
        if (text == null) return fallback
        return decodeList(text)?.toSet() ?: fallback
    }

    /** 逐条能力的上限覆盖表：能力 wire 名 → TierCeiling 名。缺省空表＝全用编译期上限。 */
    fun ceilingOverrides(): Map<String, String> {
        val text = prefs.getString(file, RelaySettings.key(RelaySettings.CEILING_OVERRIDES), null)
            ?: RelaySettings.byId[RelaySettings.CEILING_OVERRIDES]?.default
            ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(text)
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }.getOrDefault(emptyMap())
    }

    /**
     * 一条能力**实际生效**的上限：用户在设置里覆盖过就用覆盖值，否则就是编译期值。
     *
     * 覆盖表里出现未知/坏值时回落到编译期值，不把一次输入错误变成"这条能力失去上限"。
     */
    fun ceilingOf(id: interlock.relay.core.protocol.CapabilityId, builtin: interlock.relay.core.protocol.TierCeiling):
        interlock.relay.core.protocol.TierCeiling {
        val wire = ceilingOverrides()[id.wire] ?: return builtin
        return runCatching { interlock.relay.core.protocol.TierCeiling.valueOf(wire) }.getOrDefault(builtin)
    }

    // ── 写入 ──

    /**
     * 写一项。返回 null 表示成功，否则是给用户看的失败原因。
     *
     * 校验不认识的值一律拒绝而不是静默截断：静默截断会让界面显示一个值、闸门用另一个值，
     * 那种不一致事后从日志里看不出来。
     */
    fun put(id: String, encoded: String): String? {
        val spec = RelaySettings.byId[id] ?: return "未知配置项"
        if (spec.locked) return "这一项是平台不变式，不能修改"
        validate(spec, encoded)?.let { return it }
        val before = raw(id)
        if (before == encoded) return null
        prefs.putString(file, RelaySettings.key(id), encoded)
        audit(auditLine(spec, before, encoded))
        return null
    }

    /** 恢复默认：按规格写入默认值（等于把这一项拨回去，而不是留一个"已改过"的痕迹）。 */
    fun reset(ids: Collection<String>): List<RelaySettings.Spec> {
        val touched = mutableListOf<RelaySettings.Spec>()
        ids.forEach { id ->
            val spec = RelaySettings.byId[id] ?: return@forEach
            if (spec.locked) return@forEach
            if (isDefault(id)) return@forEach
            val before = raw(id)
            prefs.putString(file, RelaySettings.key(id), spec.default)
            audit(auditLine(spec, before, spec.default) + "（恢复默认）")
            touched += spec
        }
        return touched
    }

    fun resetAll(): List<RelaySettings.Spec> = reset(RelaySettings.all.map { it.id })

    fun resetGroup(group: RelaySettings.Group): List<RelaySettings.Spec> =
        reset(RelaySettings.all.filter { it.group == group }.map { it.id })

    /** 已从默认值改过的项，供"我改过哪些项"与总览使用。 */
    fun changed(): List<RelaySettings.Spec> = RelaySettings.all.filter { !it.locked && !isDefault(it.id) }

    // ── 导入 / 导出 ──

    data class ImportResult(
        val ok: Boolean,
        val applied: List<String>,
        val risky: List<String>,
        val error: String? = null,
    )

    /** 导出成一段文本：只含改过的项，导入到新机器不会把你的默认画像一起搬过去。 */
    fun exportJson(): String {
        val values = JSONObject()
        changed().forEach { values.put(it.id, raw(it.id)) }
        return JSONObject()
            .put("kind", "dshbox-relay-settings")
            .put("version", 1)
            .put("values", values)
            .toString(2)
    }

    /**
     * 导入。先整体校验、再一次性写入 —— 一段文本里有一项不合法就整段不生效，
     * 免得用户以为"导入成功"而实际只进去一半。
     */
    fun importJson(text: String): ImportResult {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ImportResult(false, emptyList(), emptyList(), "这段文本不是有效的配置数据")
        if (obj.optString("kind") != "dshbox-relay-settings") {
            return ImportResult(false, emptyList(), emptyList(), "这段文本不是本应用的配置导出")
        }
        val values = obj.optJSONObject("values")
            ?: return ImportResult(false, emptyList(), emptyList(), "配置数据里没有 values 段")
        val pending = linkedMapOf<String, String>()
        val risky = mutableListOf<String>()
        values.keys().forEach { id ->
            val spec = RelaySettings.byId[id]
                ?: return ImportResult(false, emptyList(), emptyList(), "配置数据里有本版本不认识的项：$id")
            if (spec.locked) return@forEach
            val encoded = values.optString(id)
            validate(spec, encoded)?.let { return ImportResult(false, emptyList(), emptyList(), "${spec.title}：$it") }
            if (spec.risk == RelaySettings.Risk.HIGH || spec.risk == RelaySettings.Risk.CRITICAL) {
                risky += spec.title
            }
            if (raw(id) != encoded) pending[id] = encoded
        }
        pending.forEach { (id, encoded) ->
            val spec = RelaySettings.byId.getValue(id)
            val before = raw(id)
            prefs.putString(file, RelaySettings.key(id), encoded)
            audit(auditLine(spec, before, encoded) + "（导入）")
        }
        return ImportResult(true, pending.keys.toList(), risky)
    }

    /** 界面上的当前值显示。枚举一律给中文对照，不把 ASK_ONLY 这类名字裸露给用户。 */
    fun display(spec: RelaySettings.Spec): String = when (val type = spec.type) {
        is RelaySettings.Type.Switch -> if (bool(spec.id, false)) "已开启" else "已关闭"
        is RelaySettings.Type.Choice -> type.labels[raw(spec.id)] ?: raw(spec.id)
        is RelaySettings.Type.Number -> raw(spec.id) + type.unit
        is RelaySettings.Type.Text -> raw(spec.id).ifEmpty { "（空）" }
        is RelaySettings.Type.TextList -> {
            val items = stringSet(spec.id)
            if (items.isEmpty()) "（空）" else "${items.size} 项：${items.joinToString("、")}"
        }
        is RelaySettings.Type.Document -> documentSummary(spec, type.kind)
        is RelaySettings.Type.Link -> "点开设置"
    }

    private fun documentSummary(spec: RelaySettings.Spec, kind: RelaySettings.Type.Document.Kind): String {
        if (isDefault(spec.id)) return "默认"
        val text = raw(spec.id)
        return when (kind) {
            RelaySettings.Type.Document.Kind.INTENT_TEMPLATES ->
                "${decodeArray(text)?.length() ?: 0} 条自定义动作"
            RelaySettings.Type.Document.Kind.SETTINGS_PAGES ->
                "${decodeArray(text)?.length() ?: 0} 个自定义页面"
            RelaySettings.Type.Document.Kind.CEILING_OVERRIDES ->
                "${RelaySettings.parseObject(text).size} 条已调整"
        }
    }

    // ── 校验 ──

    private fun validate(spec: RelaySettings.Spec, encoded: String): String? = when (val type = spec.type) {
        is RelaySettings.Type.Switch ->
            if (encoded == "true" || encoded == "false") null else "只能是开或关"

        is RelaySettings.Type.Choice ->
            if (encoded in type.values) null else "只能选：${type.values.joinToString("、") { type.labels[it] ?: it }}"

        is RelaySettings.Type.Number -> {
            val n = encoded.trim().toIntOrNull()
            when {
                n == null -> "要填一个整数"
                n < type.min || n > type.max -> "要在 ${type.min}..${type.max} 之间（当前 ${n}）"
                else -> null
            }
        }

        is RelaySettings.Type.Text ->
            if (encoded.length <= type.maxChars) null else "最多 ${type.maxChars} 个字"

        is RelaySettings.Type.TextList -> {
            val items = decodeList(encoded)
            when {
                items == null -> "格式不对：应当是一个文本清单"
                items.size > MAX_LIST_ITEMS -> "最多 $MAX_LIST_ITEMS 条"
                else -> items.firstOrNull { it.length > MAX_LIST_ITEM_CHARS }
                    ?.let { "单条最长 $MAX_LIST_ITEM_CHARS 个字：${it.take(12)}…" }
            }
        }

        is RelaySettings.Type.Document -> {
            val err = documentValidator(type.kind.name, encoded)
            if (err != null) err else if (decodeArray(encoded) == null && type.kind != RelaySettings.Type.Document.Kind.CEILING_OVERRIDES) {
                "格式不对：应当是一段结构化配置"
            } else {
                null
            }
        }

        // 链接项只是入口，没有自己的值。
        is RelaySettings.Type.Link -> null
    }

    private fun auditLine(spec: RelaySettings.Spec, before: String, after: String): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        return "$stamp\t本机用户\t${spec.id}\t${spec.title}\t${before.take(120)} -> ${after.take(120)}"
    }

    private fun decodeList(text: String): List<String>? = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).map { array.optString(it) }
    }.getOrNull()

    private fun decodeArray(text: String): JSONArray? = runCatching { JSONArray(text) }.getOrNull()

    companion object {
        const val MAX_LIST_ITEMS = 64
        const val MAX_LIST_ITEM_CHARS = 200
    }
}

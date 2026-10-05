package interlock.relay.core.exec.direct

import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.TierCeiling
import org.json.JSONArray
import org.json.JSONObject

/** 自定义模板里的一条 extra。只允许 String / Int / Bool 三种类型。 */
data class IntentExtra(val key: String, val kind: String, val value: String) {
    companion object {
        const val KIND_STRING = "string"
        const val KIND_INT = "int"
        const val KIND_BOOL = "bool"
        val KINDS = listOf(KIND_STRING, KIND_INT, KIND_BOOL)
    }
}

/** 用户往 `settings.open` 里加的一个页面键 → 一条写死的 action。 */
data class UserSettingsPage(
    val key: String,
    val name: String,
    val action: String,
    val extras: List<IntentExtra> = emptyList(),
)

/**
 * 用户自定义的一条"上屏动作"。
 *
 * action / component / extras 全部由**用户在设置里**写死并保存，助手只能按名字调用，
 * 不能在调用参数里填 action —— 这条与内置模板同一条纪律：能被自由填写的 Intent 等于把
 * "向任意组件发任意请求"并进一条能力里。
 */
data class UserIntentTemplate(
    val id: String,
    val name: String,
    val action: String,
    /** 可选显式组件 `pkg/cls`。厂商设置页常常没有任何公开 action，只能点名组件。 */
    val component: String? = null,
    val categories: List<String> = emptyList(),
    /** 可选 data uri。scheme 走白名单校验（见 [IntentTemplateCatalog]）。 */
    val data: String? = null,
    val extras: List<IntentExtra> = emptyList(),
    /** true = 这条模板免审批（与内置那六条同一判据）。 */
    val noStateChange: Boolean = false,
    val description: String = "",
)

/**
 * 自定义模板 / 自定义设置页 / 免审批模板集合的目录。
 *
 * 目录自己**不持有**偏好存储：装配根在启动时把一段只读快照交给它（[config]）。
 * 这样 `IntentTemplates` 这个纯函数仍可在 JVM 侧被穷举验证，默认快照就是内置行为。
 *
 * 线程安全：快照整体替换，读方拿到的是一个不可变 data class；不做就地修改。
 */
object IntentTemplateCatalog {

    /** 自定义配置的一段只读快照。 */
    data class Config(
        /** 免审批的模板名集合（内置 ∪ 用户调整）。 */
        val screenOnlyTemplates: Set<String>,
        /** 用户往 `settings.open` 里加的页面。 */
        val pages: List<UserSettingsPage>,
        /** 用户自定义的上屏动作。 */
        val templates: List<UserIntentTemplate>,
    ) {
        companion object {
            /** 缺省快照：与本功能加入之前逐字节一致（6 条内置免审批、无自定义页、无自定义模板）。 */
            val BUILTIN = Config(
                screenOnlyTemplates = setOf(
                    "alarm.show", "timer.show", "settings.open", "app.info", "dial", "web.open",
                ),
                pages = emptyList(),
                templates = emptyList(),
            )
        }
    }

    @Volatile
    private var snapshot: Config = Config.BUILTIN

    fun current(): Config = snapshot

    fun install(config: Config) {
        snapshot = config
    }

    /** 按名字取一条自定义模板；内置名一律回 null（内置走 [IntentTemplates] 自己的分支）。 */
    fun template(id: String): UserIntentTemplate? =
        if (id.isEmpty()) null else snapshot.templates.firstOrNull { it.id == id }

    /** 按页面键取一个自定义页面；内置页面键回 null（内置 action 由后端自己的映射给）。 */
    fun page(key: String?): UserSettingsPage? =
        if (key.isNullOrEmpty()) null else snapshot.pages.firstOrNull { it.key == key }

    fun isScreenOnly(template: String): Boolean = template in snapshot.screenOnlyTemplates

    // ───────────────────────── 编解码与校验 ─────────────────────────

    private val TEMPLATE_ID = Regex("[a-z][a-z0-9._-]{1,40}")
    private val PAGE_KEY = Regex("[a-z][a-z0-9_]{1,30}")
    private val ACTION = Regex("[A-Za-z][A-Za-z0-9_.]{2,127}")
    private val CLASS_NAME = Regex("[A-Za-z0-9_.$]{1,180}")
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){1,}")
    private val EXTRA_KEY = Regex("[A-Za-z_][A-Za-z0-9_.]{0,63}")
    private val TEXT_FREE = Regex("[^\\s].*")

    private const val MAX_ITEMS = 32
    private const val MAX_EXTRAS = 16
    private const val MAX_NAME_CHARS = 60
    private const val MAX_DESC_CHARS = 200
    private const val MAX_EXTRA_VALUE_CHARS = 200
    private const val MAX_DATA_CHARS = 512

    /** data 里不允许出现的 scheme：读私有文件、读私有内容、调起任意组件。 */
    private val FORBIDDEN_DATA_SCHEMES = setOf("file", "content", "intent", "javascript", "data")

    fun encodeTemplates(templates: List<UserIntentTemplate>): String =
        JSONArray().apply {
            templates.forEach { t ->
                put(
                    JSONObject().apply {
                        put("id", t.id)
                        put("name", t.name)
                        put("action", t.action)
                        t.component?.let { put("component", it) }
                        if (t.categories.isNotEmpty()) put("categories", JSONArray(t.categories))
                        t.data?.let { put("data", it) }
                        if (t.extras.isNotEmpty()) put("extras", encodeExtras(t.extras))
                        put("noStateChange", t.noStateChange)
                        if (t.description.isNotEmpty()) put("description", t.description)
                    },
                )
            }
        }.toString()

    fun encodePages(pages: List<UserSettingsPage>): String =
        JSONArray().apply {
            pages.forEach { p ->
                put(
                    JSONObject().apply {
                        put("key", p.key)
                        put("name", p.name)
                        put("action", p.action)
                        if (p.extras.isNotEmpty()) put("extras", encodeExtras(p.extras))
                    },
                )
            }
        }.toString()

    private fun encodeExtras(extras: List<IntentExtra>): JSONArray =
        JSONArray().apply {
            extras.forEach { e ->
                put(JSONObject().put("key", e.key).put("kind", e.kind).put("value", e.value))
            }
        }

    /** 解析模板文档。坏文本回空表：界面只是显示不出来，不会因为一段坏 JSON 崩掉。 */
    fun decodeTemplates(text: String): List<UserIntentTemplate> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id")
            val action = obj.optString("action")
            if (id.isEmpty() || action.isEmpty()) return@mapNotNull null
            UserIntentTemplate(
                id = id,
                name = obj.optString("name").ifEmpty { id },
                action = action,
                component = obj.optString("component").takeIf { it.isNotEmpty() },
                categories = obj.optJSONArray("categories")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList(),
                data = obj.optString("data").takeIf { it.isNotEmpty() },
                extras = decodeExtras(obj.optJSONArray("extras")),
                noStateChange = obj.optBoolean("noStateChange", false),
                description = obj.optString("description"),
            )
        }
    }

    fun decodePages(text: String): List<UserSettingsPage> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val key = obj.optString("key")
            val action = obj.optString("action")
            if (key.isEmpty() || action.isEmpty()) return@mapNotNull null
            UserSettingsPage(
                key = key,
                name = obj.optString("name").ifEmpty { key },
                action = action,
                extras = decodeExtras(obj.optJSONArray("extras")),
            )
        }
    }

    private fun decodeExtras(array: JSONArray?): List<IntentExtra> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val key = obj.optString("key")
            if (key.isEmpty()) return@mapNotNull null
            IntentExtra(
                key = key,
                kind = obj.optString("kind").ifEmpty { IntentExtra.KIND_STRING },
                value = obj.optString("value"),
            )
        }
    }

    /** 校验模板文档。返回 null 表示通过，否则是一句给人看的原因。 */
    fun validateTemplates(text: String): String? {
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: return "自定义动作的格式不对（应当是一段结构化配置）"
        if (array.length() > MAX_ITEMS) return "自定义动作最多 $MAX_ITEMS 条"
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: return "第 ${index + 1} 条不是一条有效记录"
            val id = obj.optString("id").trim()
            when {
                id.isEmpty() -> return "第 ${index + 1} 条缺少内部名字"
                !TEMPLATE_ID.matches(id) -> return "内部名字「$id」不合法：小写字母开头，可含小写字母、数字与 . _ -"
                id in IntentTemplates.names -> return "内部名字「$id」与内置动作重名"
                !seen.add(id) -> return "内部名字「$id」重复了"
            }
            validateCommon(obj, "第 ${index + 1} 条「${obj.optString("name").ifEmpty { id }}」")?.let { return it }
        }
        return null
    }

    /** 校验自定义设置页文档。 */
    fun validatePages(text: String): String? {
        val array = runCatching { JSONArray(text) }.getOrNull()
            ?: return "自定义页面的格式不对（应当是一段结构化配置）"
        if (array.length() > MAX_ITEMS) return "自定义页面最多 $MAX_ITEMS 个"
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: return "第 ${index + 1} 个不是一条有效记录"
            val key = obj.optString("key").trim()
            when {
                key.isEmpty() -> return "第 ${index + 1} 个缺少页面键"
                !PAGE_KEY.matches(key) -> return "页面键「$key」不合法：小写字母开头，可含小写字母、数字与下划线"
                key in IntentTemplates.settingsPages -> return "页面键「$key」与内置页面重名"
                !seen.add(key) -> return "页面键「$key」重复了"
            }
            validateCommon(obj, "第 ${index + 1} 个「${obj.optString("name").ifEmpty { key }}」")?.let { return it }
        }
        return null
    }

    private fun validateCommon(obj: JSONObject, where: String): String? {
        val name = obj.optString("name").trim()
        if (name.length > MAX_NAME_CHARS) return "$where 的名字最长 $MAX_NAME_CHARS 个字"
        if (name.isEmpty()) return "$where 缺少显示名字"
        val action = obj.optString("action").trim()
        if (action.isEmpty()) return "$where 缺少 action"
        if (!ACTION.matches(action)) return "$where 的 action「$action」不合法"
        val component = obj.optString("component").trim()
        if (component.isNotEmpty()) validateComponent(component)?.let { return "$where 的组件：$it" }
        val data = obj.optString("data").trim()
        if (data.isNotEmpty()) {
            if (data.length > MAX_DATA_CHARS) return "$where 的 data 最长 $MAX_DATA_CHARS 个字"
            val scheme = data.substringBefore(':').lowercase()
            if (scheme == data || scheme.isEmpty()) return "$where 的 data 缺少 scheme（例如 https://…）"
            if (scheme in FORBIDDEN_DATA_SCHEMES) return "$where 的 data 不允许用 $scheme: （能读私有内容或调起任意组件）"
        }
        val categories = obj.optJSONArray("categories")
        if (categories != null) {
            if (categories.length() > 8) return "$where 的类别最多 8 个"
            for (i in 0 until categories.length()) {
                val c = categories.optString(i).trim()
                if (!ACTION.matches(c)) return "$where 的类别「$c」不合法"
            }
        }
        val description = obj.optString("description")
        if (description.length > MAX_DESC_CHARS) return "$where 的说明最长 $MAX_DESC_CHARS 个字"
        val extras = obj.optJSONArray("extras")
        if (extras != null) {
            if (extras.length() > MAX_EXTRAS) return "$where 的参数最多 $MAX_EXTRAS 条"
            val keys = mutableSetOf<String>()
            for (i in 0 until extras.length()) {
                val extra = extras.optJSONObject(i) ?: return "$where 的参数第 ${i + 1} 条不是一条有效记录"
                val key = extra.optString("key").trim()
                if (!EXTRA_KEY.matches(key)) return "$where 的参数名「$key」不合法"
                if (!keys.add(key)) return "$where 的参数名「$key」重复了"
                val kind = extra.optString("kind").ifEmpty { IntentExtra.KIND_STRING }
                if (kind !in IntentExtra.KINDS) return "$where 的参数「$key」类型只能是文本/整数/开关"
                val value = extra.optString("value")
                if (value.length > MAX_EXTRA_VALUE_CHARS) return "$where 的参数「$key」值最长 $MAX_EXTRA_VALUE_CHARS 个字"
                when (kind) {
                    IntentExtra.KIND_INT -> if (value.trim().toIntOrNull() == null) return "$where 的参数「$key」要填整数"
                    IntentExtra.KIND_BOOL -> if (value != "true" && value != "false") return "$where 的参数「$key」只能是开或关"
                    else -> if (!TEXT_FREE.containsMatchIn(value) && value.isNotEmpty()) {
                        return "$where 的参数「$key」不能以空白开头"
                    }
                }
            }
        }
        return null
    }

    private fun validateComponent(component: String): String? {
        val slash = component.indexOf('/')
        if (slash <= 0 || slash == component.length - 1) return "要写成「包名/类名」"
        val pkg = component.substring(0, slash)
        val cls = component.substring(slash + 1)
        if (!PACKAGE_NAME.matches(pkg)) return "包名「$pkg」不合法"
        if (!CLASS_NAME.matches(cls)) return "类名「$cls」不合法"
        return null
    }

    /** 能力上限覆盖表的校验：键必须是已知能力，值必须是三档之一。 */
    fun validateCeilingOverrides(text: String): String? {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return "能力上限的格式不对（应当是一段结构化配置）"
        obj.keys().forEach { wire ->
            if (CapabilityId.fromWire(wire) == null) return "不认识的名称：$wire"
            val value = obj.optString(wire).trim()
            if (runCatching { TierCeiling.valueOf(value) }.getOrNull() == null) {
                return "$wire 的上限只能是以下之一：${TierCeiling.entries.joinToString("、") { it.name }}"
            }
        }
        return null
    }
}

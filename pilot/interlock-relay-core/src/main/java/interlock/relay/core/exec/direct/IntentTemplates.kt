package interlock.relay.core.exec.direct

import org.json.JSONObject

/**
 * `sys.intent` 的封闭模板表。
 *
 * 一条模板 = 一个写死的系统 action + 一张只收白名单键与取值范围的参数表。动作串与 extra
 * 一律不许助手直接给：能被自由填写的 Intent 等于把"向任意组件发任意请求"并进一条能力里，
 * 那条路比通用 shell 更宽 —— 它连 shell 身份都不需要，一次提示词注入就够替用户答应
 * 任意应用的请求。
 *
 * 表内只收两类，共同点是**改动看得见、也撤得掉**：
 * - 系统本就为"别的应用替我设个闹钟/计时器"公开了 action 与 extra（`SKIP_UI` 那两条），
 *   接收方是用户在系统里选定的那个时钟应用。
 * - 只是把系统页面摆到用户眼前、不替他改任何东西的入口：打开时钟的闹钟页/计时器页、
 *   打开某个系统设置页、打开拨号盘（不拨出）、打开浏览器、打开某个应用的详情页。
 *   这类连 `SKIP_UI` 都不适用——它一定上屏，用户看得见自己刚被带到了哪儿。
 *
 * 删除一类的入口（卸载应用、清除数据、抹掉账号）**不进这张表**，也不给任何开关把它加进来的路径。
 * 「停止正在响的闹钟 / 延后闹铃」也不进：前者对单次闹钟等于永久关闭，与上面那条前提相悖；
 * 两者都没有 `SKIP_UI` 语义，多条命中时系统还会弹一个"你指哪一条"的选择界面。
 *
 * 校验全在这个纯函数里做完，后端只负责把已经定形的参数交给系统 —— 与 shell 那几条
 * 「参数过白名单再拼命令」是同一条规矩，落点不同而已。校验不吃 `android.net.Uri`：
 * 它在单测里是不实现的平台桩，把形状把关写在它上面就等于这条表的判据从没被测过。
 */
object IntentTemplates {

    const val KEY_TEMPLATE = "template"
    const val KEY_HOUR = "hour"
    const val KEY_MINUTE = "minute"
    const val KEY_MESSAGE = "message"
    const val KEY_LENGTH = "length"
    const val KEY_PAGE = "page"
    const val KEY_PACKAGE = "package"
    const val KEY_NUMBER = "number"
    const val KEY_URL = "url"

    /**
     * 可选键：这一发**该由哪个应用接**（包名）。
     *
     * 与 [KEY_PACKAGE] 不是一回事：`package` 是 `app.info` 这条模板**要展示哪个应用**
     * 的参数（intent 的对象），而 `handler` 说的是**谁来接这一发 intent**（intent 的
     * 接收方）。名字分开，是因为两者可以同时出现且互不相同。
     *
     * 为什么要开这个键：表内八条模板发的都是隐式 intent，系统在有多个候选、又没设过
     * 默认应用时会给一个选择器 —— 那个选择器会顶到最前面，把用户正在做的事打断一次，
     * 而回包里的 `resolvedPackage` 变成 `com.android.intentresolver`（"交给了选择器"，
     * 不等于"交给了某个应用"）。让调用方点名接收方，这一发就直达那一个应用，
     * 既不再打断用户，回包也重新说得清是谁接的。
     *
     * 这里只判**形状**（[PACKAGE_NAME]）："这个包到底接不接这一发"要看这台机器上装了
     * 什么，只有后端手里的 PackageManager 答得出来（见 `DirectBackend.semanticIntent`）。
     * 判据不放在纯函数里，也不假装纯函数能判。
     */
    const val KEY_HANDLER = "handler"

    const val ALARM_SET = "alarm.set"
    const val TIMER_SET = "timer.set"
    const val ALARM_SHOW = "alarm.show"
    const val TIMER_SHOW = "timer.show"
    const val SETTINGS_OPEN = "settings.open"
    const val APP_INFO = "app.info"
    const val DIAL = "dial"
    const val WEB_OPEN = "web.open"

    /** 表内的模板名。界面上的那一行说明与回包里的 `unknown template` 都从这里取。 */
    val names: List<String> = listOf(
        ALARM_SET, TIMER_SET, ALARM_SHOW, TIMER_SHOW, SETTINGS_OPEN, APP_INFO, DIAL, WEB_OPEN,
    )

    /**
     * 只把系统页面摆到用户眼前、**一个字节都不改**的六条模板。
     *
     * 它们与 [ALARM_SET] / [TIMER_SET] 的分别不在参数形状，而在**这一条模板本身会不会写**：
     * 这六条对应的 action 全是 SHOW / 打开页（设置页、闹钟页、计时器页、应用详情、拨号盘、
     * 浏览器），系统侧不新建任何记录，也没有要用户回头撤销的东西。所以它们不进审批 ——
     * 判据按「这一次调用的形状（哪条模板）」给，见 `InterlockGate.authorize` 里那条
     * `no_state_change` 捷径；`alarm.set` / `timer.set` 照旧走档位判定。
     *
     * 单列一张集合而不是把六个模板名散在闸门里：这样它可被穷举验证
     * （`IntentTemplatesTest` 钉住「这六条 + alarm.set + timer.set = 全表」），
     * 而散落的字符串迟早会与 [names] 漂移 —— 漂移的后果是某条写操作悄悄变成免弹。
     */
    val noStateChange: Set<String> = setOf(ALARM_SHOW, TIMER_SHOW, SETTINGS_OPEN, APP_INFO, DIAL, WEB_OPEN)

    /**
     * 这条模板是不是「只上屏、不改状态」那一类。
     * 表外的名字一律 false：未知模板照旧走正常门禁，不能在落到 Intent 之前就被免弹。
     *
     * [noStateChange] 是**内置默认**（也是单元测试钉住的那一份）；实际生效的集合由用户在
     * 设置里调整，存在 [IntentTemplateCatalog] 的快照里。用户没拨过时快照就是
     * [noStateChange]，所以这里是逐字节等价的行为。
     */
    fun isNoStateChange(template: String): Boolean =
        template.isNotEmpty() && IntentTemplateCatalog.isScreenOnly(template)

    /** 能打开的系统设置页。只收公开常量、且都无需任何权限的那几个。 */
    val settingsPages: Set<String> = setOf(
        "wifi", "bluetooth", "display", "location", "sound", "apn", "developer", "nfc",
    )

    /** 闹钟备注与计时器标签共用这一条上限：超长文本会撑掉确认卡上那行对象。 */
    private const val MAX_MESSAGE_CHARS = 100

    /** 计时器时长上下限（秒）。下限排掉 0 与负数，上限排掉"永远走不完"的计时器。 */
    private const val MIN_LENGTH_SECONDS = 1
    private const val MAX_LENGTH_SECONDS = 24 * 60 * 60

    private const val MAX_URL_CHARS = 512
    private const val MAX_NUMBER_CHARS = 32

    /**
     * 拨号盘上打得出的字符：数字与 `+ * # ( ) . ,`，外加一个空格和一个连字符作分隔。
     * 写成字符类而不用反斜杠转义，是为了让这一行在 Kotlin 里就是一个字面字符集。
     * 放开别的字符等于往 `tel:` 里塞别的 scheme。
     */
    private val DIAL_NUMBER = Regex("[0-9+*#()., -]{1,$MAX_NUMBER_CHARS}")

    /** 包名形状与 shell 那张表同源，两处对同一个东西不该有两种说法。 */
    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+){1,}")

    /**
     * 校验通过后的参数。带哪一个由 [template] 决定：`hour`/`minute` 只对 alarm.set，
     * `lengthSeconds` 只对 timer.set，`page` 只对 settings.open，`packageName` 只对 app.info，
     * `number` 只对 dial，`url` 只对 web.open。未用的那些保持 -1 / null。
     *
     * [handler] 是唯一**对八条模板都成立**的那个：它说"这一发该由哪个应用接"，
     * 与模板自己的参数无关（见 [validate] 末尾的说明）。
     */
    data class Params(
        val template: String,
        val hour: Int = -1,
        val minute: Int = -1,
        val lengthSeconds: Int = -1,
        val message: String? = null,
        val page: String? = null,
        val packageName: String? = null,
        val number: String? = null,
        val url: String? = null,
        val handler: String? = null,
        /**
         * 用户自定义模板（设置里配的那一条）。非空时 action / 组件 / extras 一律取自它，
         * 调用参数里除了 [handler] 之外不再有别的可填项 —— 与内置模板同一条纪律。
         */
        val custom: UserIntentTemplate? = null,
    )

    sealed interface Outcome {
        data class Ok(val params: Params) : Outcome
        data class Bad(val reason: String) : Outcome
    }

    fun validate(args: JSONObject): Outcome {
        val template = args.optString(KEY_TEMPLATE).trim()
        if (template.isEmpty()) return Outcome.Bad("missing arg: $KEY_TEMPLATE")
        if (template !in names && IntentTemplateCatalog.template(template) == null) {
            // 报错里列出的名字 = 内置 ∪ 用户自定义：助手照这一行就能改对。
            val accepted = names + IntentTemplateCatalog.current().templates.map { it.id }
            return Outcome.Bad("unknown template: $template, accepted: $accepted")
        }
        val message = args.optString(KEY_MESSAGE).takeIf { it.isNotBlank() }?.trim()
        if (message != null && message.length > MAX_MESSAGE_CHARS) {
            return Outcome.Bad("$KEY_MESSAGE must be at most $MAX_MESSAGE_CHARS characters, got ${message.length}")
        }
        // 指定接收方（可选）在模板各自的判据**之前**定形：它不属于任何一条模板的参数，
        // 而是八条共用的一层。形状不合法就不必再往下走 —— 一个不是包名的字符串
        // 交给 setPackage 只会让系统那边静默找不到接收方。
        val handler = args.optString(KEY_HANDLER).trim()
        if (handler.isNotEmpty() && !PACKAGE_NAME.matches(handler)) {
            return Outcome.Bad("$KEY_HANDLER is not a package name: $handler")
        }
        val shaped = when (template) {
            ALARM_SET -> validateAlarm(args, message)
            TIMER_SET -> validateTimer(args, message)
            ALARM_SHOW, TIMER_SHOW -> Outcome.Ok(Params(template, message = message))
            SETTINGS_OPEN -> validateSettings(args)
            APP_INFO -> validateAppInfo(args)
            DIAL -> validateDial(args)
            // 用户自定义模板：形状由模板自己定死，调用方只能给 handler。
            // 放在 `else` 之前判，未知名字在上面的检查里已经被拒。
            else -> IntentTemplateCatalog.template(template)
                ?.let { Outcome.Ok(Params(template, custom = it)) }
                ?: validateWeb(args)
        }
        // 「这一发由谁接」是**调用这一次**的属性，不是模板的属性：八条模板都可能有一个以上
        // 接收方（实测 SET_ALARM 在本机就有两条候选），所以这个键对八条一律成立，不按模板分。
        return when (shaped) {
            is Outcome.Bad -> shaped
            is Outcome.Ok -> Outcome.Ok(shaped.params.copy(handler = handler.ifEmpty { null }))
        }
    }

    private fun validateAlarm(args: JSONObject, message: String?): Outcome {
        if (!args.has(KEY_HOUR)) return Outcome.Bad("missing arg: $KEY_HOUR")
        if (!args.has(KEY_MINUTE)) return Outcome.Bad("missing arg: $KEY_MINUTE")
        val hour = args.optInt(KEY_HOUR, -1)
        val minute = args.optInt(KEY_MINUTE, -1)
        // 越界一律拒，不做"帮你取模"：25 点被静默折成 1 点之后，用户批准的是卡片上那个
        // 25:00，落进系统里却是另一天的一点，而回包看着像成功。
        if (hour !in 0..23) return Outcome.Bad("$KEY_HOUR must be within 0..23, got $hour")
        if (minute !in 0..59) return Outcome.Bad("$KEY_MINUTE must be within 0..59, got $minute")
        return Outcome.Ok(Params(ALARM_SET, hour = hour, minute = minute, message = message))
    }

    private fun validateTimer(args: JSONObject, message: String?): Outcome {
        if (!args.has(KEY_LENGTH)) return Outcome.Bad("missing arg: $KEY_LENGTH")
        val seconds = args.optInt(KEY_LENGTH, -1)
        if (seconds !in MIN_LENGTH_SECONDS..MAX_LENGTH_SECONDS) {
            return Outcome.Bad(
                "$KEY_LENGTH must be within $MIN_LENGTH_SECONDS..$MAX_LENGTH_SECONDS seconds, got $seconds",
            )
        }
        return Outcome.Ok(Params(TIMER_SET, lengthSeconds = seconds, message = message))
    }

    private fun validateSettings(args: JSONObject): Outcome {
        val page = args.optString(KEY_PAGE).trim().lowercase()
        if (page.isEmpty()) {
            return Outcome.Bad("missing arg: $KEY_PAGE, accepted: [${acceptedSettingsPages().joinToString(", ")}]")
        }
        if (page !in settingsPages && IntentTemplateCatalog.page(page) == null) {
            return Outcome.Bad("$KEY_PAGE '$page' is not one of [${acceptedSettingsPages().joinToString(", ")}]")
        }
        return Outcome.Ok(Params(SETTINGS_OPEN, page = page))
    }

    /** 内置 8 个页面 ∪ 用户在设置里加的自定义页面键。 */
    fun acceptedSettingsPages(): List<String> =
        settingsPages.toList() + IntentTemplateCatalog.current().pages.map { it.key }

    private fun validateAppInfo(args: JSONObject): Outcome {
        val pkg = args.optString(KEY_PACKAGE).trim()
        if (pkg.isEmpty()) return Outcome.Bad("missing arg: $KEY_PACKAGE")
        if (!PACKAGE_NAME.matches(pkg)) return Outcome.Bad("$KEY_PACKAGE is not a package name: $pkg")
        return Outcome.Ok(Params(APP_INFO, packageName = pkg))
    }

    private fun validateDial(args: JSONObject): Outcome {
        val number = args.optString(KEY_NUMBER).trim()
        if (number.isEmpty()) return Outcome.Bad("missing arg: $KEY_NUMBER")
        if (!DIAL_NUMBER.matches(number)) {
            return Outcome.Bad(
                "$KEY_NUMBER may only contain digits and + * # ( ) . - , up to $MAX_NUMBER_CHARS characters",
            )
        }
        return Outcome.Ok(Params(DIAL, number = number))
    }

    private fun validateWeb(args: JSONObject): Outcome {
        val url = args.optString(KEY_URL).trim()
        if (url.isEmpty()) return Outcome.Bad("missing arg: $KEY_URL")
        if (url.length > MAX_URL_CHARS) {
            return Outcome.Bad("$KEY_URL must be at most $MAX_URL_CHARS characters, got ${url.length}")
        }
        // 只认 http/https：`file:`、`content:`、`intent:` 这些 scheme 能把一条"打开网页"
        // 变成打开任意私有内容或调起任意组件。
        val scheme = url.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") {
            return Outcome.Bad("$KEY_URL must start with http:// or https://, got scheme '$scheme'")
        }
        if (url.any { it.isWhitespace() }) return Outcome.Bad("$KEY_URL must not contain whitespace")
        return Outcome.Ok(Params(WEB_OPEN, url = url))
    }
}

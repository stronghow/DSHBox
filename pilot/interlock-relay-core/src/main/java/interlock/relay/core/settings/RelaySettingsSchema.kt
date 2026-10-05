package interlock.relay.core.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行时可配置项的声明表。
 *
 * 这张表是**唯一的事实源**：设置界面完全按它渲染，消费点按 id 取值。新增一项配置 =
 * 在这里加一条记录，不用改界面代码、不用重新编包。
 *
 * 三条纪律：
 * 1. 所有值都以字符串编码存进宿主私有偏好文件 [interlock.relay.core.spi.RelayPrefs.FILE_MAIN]
 *    （key = `cfg_<id>`）。偏好只有 String/Boolean 两个原语，统一走字符串就没有第二种口径。
 * 2. **缺省即默认**：用户没拨过这一项时，[SettingsStore] 返回 [SettingSpec.default]，
 *    而每一条的默认值都取自当前编译期常量，因此"刚装上/刚升级"时行为与本功能加入之前
 *    **逐字节一致**。没有任何一项因为"开放了"而默认放松。
 * 3. 危险项不是禁止开放，而是分级 + 明确后果 + 二次确认（长按）+ 变更审计。用户是设备所有者，
 *    决定权在他；这里只负责让他知道代价。
 *
 * 不开放的东西只有两类，都是平台不变式而不是产品选择：
 * - Android 系统级权限（通讯录、录音、安装未知来源……）：运行时只能由用户在系统里授予，
 *   本模块改不了，也不假装能改。
 * - 配置的存放位置：只存宿主私有 prefs，不落共享目录。放在沙盒可读的位置等于让助手
 *   改写自己的权限，这条是整套闸门的地基，不能成为配置项。
 */
object RelaySettings {

    // 以下 id 被消费点按名字引用，写在这里避免字符串散落各处。
    const val SCREEN_ONLY_TEMPLATES = "approval.screen_only_templates"
    const val SETTINGS_PAGES = "intent.settings_pages"
    const val USER_TEMPLATES = "intent.user_templates"
    const val TIER_PER_CAPABILITY = "tier.per_capability"
    const val SESSION_GRANT_MINUTES = "memory.session_grant_minutes"
    const val SURFACE_PREFERENCE = "surface.preference"
    const val HOST_BRING_TO_FRONT = "approval.host_bring_to_front"
    const val SHELL_VERB_OPTINS = "shell.verb_optins"
    const val VERBOSE_LOG = "diagnostic.verbose_log"
    const val INTENT_TIMEOUT_MS = "runtime.intent_timeout_ms"
    const val WAIT_FOR_MAX_MS = "runtime.wait_for_max_ms"
    const val RESPONSE_TTL_MINUTES = "runtime.response_ttl_minutes"
    const val CAPTURE_ROUTE = "capture.foreground_route"
    const val CAPABILITY_DISABLED = "capability.disabled"
    const val CONSENT_ENABLED = "danger.consent_screen_enabled"
    const val CONSENT_EXTRA_PACKAGES = "danger.consent_extra_packages"
    const val CONSENT_PREFIXES = "danger.consent_package_prefixes"
    const val CONSENT_VIEW_IDS = "danger.consent_view_id_keywords"
    const val CONSENT_RELATIVE = "danger.consent_relative_is_consent"
    const val CEILING_OVERRIDES = "danger.ceiling_overrides"
    const val DANGEROUS_TIER_DEFAULTS = "danger.dangerous_tier_defaults"
    const val ALLOW_ONCE_SESSION = "danger.allow_once_remembers_session"
    const val SHOW_UNWIRED = "ui.show_unwired"
    const val CONFIRM_MODE = "ui.danger_confirm_mode"

    /**
     * 危险项的「输入确认」短语。校验规则**只有一条**：去掉首尾空白后必须逐字等于它。
     * 不做大小写折叠、不接受简繁互换、不接受加标点 —— 这是一道让人停一下的闸，不是拼写题。
     */
    const val CONFIRM_PHRASE = "我了解风险"

    /** 危险项确认方式的取值。 */
    const val CONFIRM_HOLD = "HOLD"
    const val CONFIRM_TYPE = "PHRASE"

    /** 配置项在设置界面里的三个分组。分组顺序即渲染顺序。 */
    enum class Group(val label: String, val summary: String) {
        COMMON("常用", "决定助手在哪些情况下会先问你 —— 第一周就会用到的几项。"),
        ADVANCED("高级", "影响助手的行为细节。默认值已经够用，改之前看一眼说明。"),
        DANGER("危险", "这些是保护你的那道边界的组成部分。关掉不等于助手变坏，但你得知道代价。"),
    }

    enum class Risk(val label: String) {
        LOW("低"),
        MEDIUM("中"),
        HIGH("高"),
        CRITICAL("严重"),
    }

    /** 值类型。解码/校验/渲染都按它分派。 */
    sealed interface Type {
        /** 开关。 */
        data object Switch : Type

        /** 单选。[values] 是存储用的枚举名，[labels] 是界面上的中文对照。 */
        data class Choice(val values: List<String>, val labels: Map<String, String>) : Type

        /** 整数区间。 */
        data class Number(val min: Int, val max: Int, val unit: String = "") : Type

        /** 短文本。 */
        data class Text(val maxChars: Int) : Type

        /** 字符串集合：界面上一行一条地编辑。 */
        data object TextList : Type

        /**
         * 结构化文档：由专用编辑器渲染。[kind] 决定用哪个编辑器，
         * 但要加一项新配置仍然只需要在 schema 里追加一条记录。
         */
        data class Document(val kind: Kind) : Type {
            enum class Kind { INTENT_TEMPLATES, SETTINGS_PAGES, CEILING_OVERRIDES }
        }

        /** 已经存在于别的页面的设置：这里只做入口，避免同一项有两个控制点。 */
        data class Link(val target: String) : Type
    }

    /**
     * 一条配置的声明。
     *
     * 文案字段是硬性要求：设置界面每一行都要能回答"这是什么、改了会怎样、默认为什么这样、
     * 有什么代价"，缺一句用户就只能靠猜，而猜错的代价可能是关掉了保护。
     */
    data class Spec(
        val id: String,
        val group: Group,
        val type: Type,
        val default: String,
        /** 中文短名，人话，不用术语。 */
        val title: String,
        /** 一句话"这是什么"。 */
        val summary: String,
        /** 一句话"改了会怎样"，带具体例子。 */
        val effect: String,
        /** 一句话"默认为什么这样"。 */
        val whyDefault: String,
        val risk: Risk,
        /** 风险一句话。高风险必须直白说后果。 */
        val riskText: String,
        /** "试一下"用的静态预览：不改配置，只说明改完下一次会发生什么。 */
        val preview: String,
        /** 影响范围：哪些动作、哪些能力。 */
        val scope: String,
        /** 危险项的子规则：挂在哪一条下面渲染。 */
        val parent: String? = null,
        /** 已接线=false 时界面标"暂不可改"，避免改了不生效而用户以为改坏了。 */
        val wired: Boolean = true,
        /** 平台不变式，只读展示。 */
        val locked: Boolean = false,
        /** 修改是否需要长按 3 秒确认；缺省按风险等级自动判定。 */
        val holdToConfirm: Boolean = risk == Risk.HIGH || risk == Risk.CRITICAL,
    ) {
        /** 界面判断"当前值是不是默认值"用同一个编码比较，不另写一套。 */
        fun isDefault(encoded: String): Boolean = encoded == default
    }

    /** 默认的"只上屏"免审批模板：与 [interlock.relay.core.exec.direct.IntentTemplates.noStateChange] 同源同值。 */
    private val DEFAULT_SCREEN_ONLY = jsonList(
        "alarm.show", "timer.show", "settings.open", "app.info", "dial", "web.open",
    )

    /** 内置的"点掉系统授权框"判据关键词，与 [interlock.relay.core.interlock.ConsentSurfaces] 同值。 */
    private val DEFAULT_CONSENT_VIEW_IDS = jsonList(
        "permission_allow", "permission_deny", "permission_button", "button_allow",
    )

    /** 内置的厂商权限管理器包名前缀，与 [interlock.relay.core.interlock.ConsentSurfaces] 同值。 */
    private val DEFAULT_CONSENT_PREFIXES = jsonList(
        "com.samsung.android.permissioncontroller",
        "com.huawei.systemmanager",
        "com.hihonor.systemmanager",
        "com.miui.securitycore",
        "com.miui.permcenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
    )

    val all: List<Spec> = listOf(
        // ─────────────────────────── 常用 ───────────────────────────
        Spec(
            id = SCREEN_ONLY_TEMPLATES,
            group = Group.COMMON,
            type = Type.TextList,
            default = DEFAULT_SCREEN_ONLY,
            title = "打开页面不询问",
            summary = "这几类动作只是把系统页面摆到你眼前，助手做的时候不弹卡。",
            effect = "从清单里去掉一条，助手再做那次动作就会先弹卡问你。加一条等于让它以后免问。",
            whyDefault = "这几条只上屏、不新建也不改任何记录，所以默认不打扰你。",
            risk = Risk.MEDIUM,
            riskText = "把会写东西的动作（比如设闹钟）加进来，助手就能不问你直接改系统。",
            preview = "如果保持现在这样：助手打开设置页、闹钟页、拨号盘、网页时，你只会看到屏幕自己切过去，不会被问。",
            scope = "sys.intent 的 alarm.show / timer.show / settings.open / app.info / dial / web.open",
        ),
        Spec(
            id = SETTINGS_PAGES,
            group = Group.COMMON,
            type = Type.Document(Type.Document.Kind.SETTINGS_PAGES),
            default = "[]",
            title = "能打开哪些设置页",
            summary = "在内置的 8 个页面之外，再允许助手直接打开别的系统页面。",
            effect = "加一条之后，助手可以用 settings.open 打开它（免审批的那条是否生效由上一项决定）。",
            whyDefault = "内置 8 个页面是所有机型都公开、且不需要任何权限的入口。",
            risk = Risk.MEDIUM,
            riskText = "自定义页面靠 action 字符串点名，等于让助手直达厂商设置里的内部界面。",
            preview = "例如把「系统导航方式」加进来，助手就能直接打开它，不用一层层点进去。",
            scope = "sys.intent 的 settings.open",
        ),
        Spec(
            id = USER_TEMPLATES,
            group = Group.COMMON,
            type = Type.Document(Type.Document.Kind.INTENT_TEMPLATES),
            default = "[]",
            title = "自定义上屏动作",
            summary = "把某个动作配成一条命名模板，助手用名字调用它。",
            effect = "配好之后助手可以说「打开系统导航方式」，而不是自己去猜 action 或一层层点。",
            whyDefault = "默认没有自定义动作 —— 内置那几条已经覆盖了各机型通用的入口。",
            risk = Risk.HIGH,
            riskText = "自定义动作本身可以指向任意可导出的界面；把它标成免审批就等于助手能不问你就打开它。",
            preview = "例如配一条「系统导航方式」，标成免审批：以后打开它不再弹卡。",
            scope = "sys.intent 的自定义模板名",
        ),
        Spec(
            id = TIER_PER_CAPABILITY,
            group = Group.COMMON,
            type = Type.Link("capabilities"),
            default = "",
            title = "每条能力的访问档",
            summary = "逐条设置：禁止 / 每次询问 / 完全访问。",
            effect = "改完立刻生效。上限（有些能力永远不能完全免问）仍由能力自己的性质决定。",
            whyDefault = "默认每条都先问你 —— 助手是新装的，先看清楚它要做什么再放权。",
            risk = Risk.LOW,
            riskText = "放开一条能力等于让它以后做这类事都不再问你。",
            preview = "点进去可以看到每条能力现在是什么档，以及系统那一侧还缺什么。",
            scope = "全部能力",
        ),
        Spec(
            id = SESSION_GRANT_MINUTES,
            group = Group.COMMON,
            type = Type.Number(min = 1, max = 1440, unit = "分钟"),
            default = "30",
            title = "「本会话内允许」多久",
            summary = "你在确认卡上点「本会话内允许」之后，它管用多长时间。",
            effect = "调大＝一次答话管更久；调小＝更频繁地重新问你。到点后自动失效，不跨会话。",
            whyDefault = "30 分钟大约是一次任务的时长，够用又不会留到第二天。",
            risk = Risk.MEDIUM,
            riskText = "调到很大接近「以后都别问」，但你随时可以在能力页把这条改回「每次询问」清掉它。",
            preview = "如果改成 120 分钟：你答一次「本会话内允许」，接下来两小时同类调用都不再问。",
            scope = "确认卡上的第三颗按钮（上限为「每次必问」的能力没有这颗按钮）",
        ),
        Spec(
            id = SURFACE_PREFERENCE,
            group = Group.COMMON,
            type = Type.Link("surface"),
            default = "FOREGROUND",
            title = "执行面偏好",
            summary = "助手在你看得见的屏上干活，还是在后台那块虚拟屏上干活。",
            effect = "改成后台后，助手操作不会打断你，但有些能力在后台不可用（页面上会说明是哪一条）。",
            whyDefault = "默认在你看得见的那块屏上，做什么都看得见。",
            risk = Risk.LOW,
            riskText = "后台模式下助手可以在你看不到的地方操作，但它能用的能力反而更少。",
            preview = "点进去可以选择「优先后台」，并看到后台模式下哪些能力会降级。",
            scope = "全部能指定执行面的能力",
        ),

        // ─────────────────────────── 高级 ───────────────────────────
        Spec(
            id = HOST_BRING_TO_FRONT,
            group = Group.ADVANCED,
            type = Type.Switch,
            default = "true",
            title = "收尾回本应用不询问",
            summary = "任务做完了，助手把你带回本应用（只切前台，不读不改）。",
            effect = "关掉之后，助手每次要把你带回这里都会弹一张确认卡 —— 而那时你往往正看着别的应用。",
            whyDefault = "这条命令的整个理由就是把你带回来，它不读数据、不改状态。",
            risk = Risk.MEDIUM,
            riskText = "关掉只是更啰嗦，不会更不安全；但任务链会因为你没看到卡片而卡住。",
            preview = "保持默认：助手收尾时直接切回这里，不弹卡。",
            scope = "app.launch（参数只有本应用包名这一种形状）",
        ),
        Spec(
            id = VERBOSE_LOG,
            group = Group.ADVANCED,
            type = Type.Switch,
            default = "false",
            title = "详细日志",
            summary = "把每一次调用的更多细节写进日志，方便排查。",
            effect = "打开后日志文件变大更快，日志页会显示更多行；对助手的行为没有影响。",
            whyDefault = "默认只记必要信息，省空间也不拖慢执行。",
            risk = Risk.LOW,
            riskText = "日志里会出现更多调用细节，注意别把日志分享给不信任的人。",
            preview = "打开后去「日志」页，能看到每一步的完整经过。",
            scope = "运行日志",
        ),
        Spec(
            id = SHELL_VERB_OPTINS,
            group = Group.ADVANCED,
            type = Type.Link("privileged"),
            default = "",
            title = "系统命令逐条开关",
            summary = "sys.shell 里会改系统的那几条动词，一条一个开关，默认全关。",
            effect = "打开哪一条，助手才能用哪一条系统命令；关掉立刻不可用。",
            whyDefault = "默认全关 —— 这几条能改系统状态，应当由你逐条决定。",
            risk = Risk.HIGH,
            riskText = "打开一条等于允许助手用它改系统（比如改音量、模拟按键）。",
            preview = "点进去可以看到每条命令的形状说明与当前开关。",
            scope = "sys.shell 的会改系统动词",
        ),
        Spec(
            id = INTENT_TIMEOUT_MS,
            group = Group.ADVANCED,
            type = Type.Number(min = 1000, max = 120000, unit = "毫秒"),
            default = "90000",
            title = "调用的默认等待",
            summary = "助手等一次调用最多等多久（命令行的 --timeout）。",
            effect = "调大＝慢操作更不容易超时；调小＝卡住的调用更快返回失败。",
            whyDefault = "90 秒覆盖了审批、切屏与一次短录屏。",
            risk = Risk.LOW,
            riskText = "调得太小会让本来能成的调用反复超时，看起来像能力坏了。",
            preview = "当前这一项还没接线，界面只展示默认值。",
            scope = "入口脚本的 --timeout（需重铺助手侧资产）",
            wired = false,
        ),
        Spec(
            id = WAIT_FOR_MAX_MS,
            group = Group.ADVANCED,
            type = Type.Number(min = 1000, max = 60000, unit = "毫秒"),
            default = "15000",
            title = "等待条件成立的上限",
            summary = "ui.waitFor 最多能等多久（超过就按上限截断）。",
            effect = "调小＝等界面变化更容易提前判失败；调大＝单次调用占用通道更久。",
            whyDefault = "15 秒足够一次页面切换与动画结束。",
            risk = Risk.LOW,
            riskText = "调得过大时，一个等不到的界面会让这次调用一直占着执行位。",
            preview = "当前这一项还没接线，界面只展示默认值。",
            scope = "ui.waitFor",
            wired = false,
        ),
        Spec(
            id = RESPONSE_TTL_MINUTES,
            group = Group.ADVANCED,
            type = Type.Number(min = 1, max = 1440, unit = "分钟"),
            default = "30",
            title = "结果保留多久",
            summary = "已经取走或过期的回包在磁盘上留多久，方便你回头重取。",
            effect = "调小＝更快清理、省空间；调大＝更久之后还能重取旧结果。",
            whyDefault = "30 分钟覆盖一次任务链路的重试窗口。",
            risk = Risk.LOW,
            riskText = "留得太久会占存储；结果里有参数摘要，长期保留会增加泄露面。",
            preview = "当前这一项还没接线，界面只展示默认值。",
            scope = "请求状态与回包存储",
            wired = false,
        ),
        Spec(
            id = CAPTURE_ROUTE,
            group = Group.ADVANCED,
            type = Type.Choice(
                values = listOf("A11Y", "MEDIAPROJECTION"),
                labels = mapOf(
                    "A11Y" to "无障碍（不弹系统采集框）",
                    "MEDIAPROJECTION" to "屏幕录制（每次会弹系统采集同意框）",
                ),
            ),
            default = "A11Y",
            title = "前台截图路线",
            summary = "前台截屏走哪条路，直接决定会不会弹系统采集同意框。",
            effect = "选无障碍：不弹系统框，但要求无障碍服务在跑、系统版本够。选屏幕录制：兼容性更好，代价是每次采集会话要你点一次系统同意。",
            whyDefault = "无障碍路线不弹系统框，对你是最少打扰的那条。",
            risk = Risk.MEDIUM,
            riskText = "切到屏幕录制后，每次截图/录屏都会出现系统自己的同意框，且系统那条同意无法由本应用代答。",
            preview = "当前这一项还没接线，界面只展示默认值。",
            scope = "screen.capture / screen.observe / screen.record 的前台路线",
            wired = false,
        ),
        Spec(
            id = CAPABILITY_DISABLED,
            group = Group.ADVANCED,
            type = Type.TextList,
            default = "[]",
            title = "停用的能力",
            summary = "整条关掉某个能力，助手连调用都不允许。",
            effect = "写进清单的能力会从助手看到的能力表里消失，调用直接回「不可用」。",
            whyDefault = "默认一条都不停 —— 每条能力的档位默认已经是「每次询问」。",
            risk = Risk.LOW,
            riskText = "停用只会更严格，不会更松；找不到能力时记得回这里看看。",
            preview = "当前这一项还没接线，界面只展示默认值。",
            scope = "能力表",
            wired = false,
        ),
        Spec(
            id = SHOW_UNWIRED,
            group = Group.ADVANCED,
            type = Type.Switch,
            default = "false",
            title = "显示未接线项（开发预览）",
            summary = "把 6 项「还没接线、改了不生效」的配置显示出来。",
            effect = "打开后，高级组与危险组里会多出几项带黄色警示的配置；它们改了不会有任何变化，只是先把位置留出来。",
            whyDefault = "默认藏起来 —— 一个改了没反应的开关会让人以为程序坏了，这是信任问题。",
            risk = Risk.LOW,
            riskText = "打开它不会改变任何行为，只是把尚不生效的项显出来给你看。",
            preview = "打开后你会看到「调用的默认等待」等 6 项，每项顶上都有黄色说明。",
            scope = "规则与配置页面本身",
        ),
        Spec(
            id = CONFIRM_MODE,
            group = Group.ADVANCED,
            type = Type.Choice(
                values = listOf(CONFIRM_HOLD, CONFIRM_TYPE),
                labels = mapOf(
                    CONFIRM_HOLD to "长按 3 秒",
                    CONFIRM_TYPE to "输入短语",
                ),
            ),
            default = CONFIRM_HOLD,
            title = "危险项的确认方式",
            summary = "改危险设置之前，用哪种方式让你确认。",
            effect = "选「输入短语」后，确认框不再要求长按，改成逐字输入「$CONFIRM_PHRASE」再点确认。两种方式都不影响你取消。",
            whyDefault = "长按 3 秒最快，也最难误触；但对用 TalkBack 或不便长按的人不友好，所以必须能换成输入。",
            risk = Risk.LOW,
            riskText = "两种方式的安全强度相当，都要求你主动做一次有意识的动作。",
            preview = "改完之后，下一次改危险项时确认框底部会多出「改用输入确认」入口。",
            scope = "所有危险项的确认框",
        ),

        // ─────────────────────────── 危险 ───────────────────────────
        Spec(
            id = CONSENT_ENABLED,
            group = Group.DANGER,
            type = Type.Switch,
            default = "true",
            title = "点授权框先问你",
            summary = "助手要操作系统的「权限授予/安装确认」这类界面时，一律先问你。",
            effect = "关掉之后，助手点这些界面不再弹卡、也不给「本会话内允许」的豁免 —— 它可能替你点掉别的 App 的权限请求。",
            whyDefault = "这是唯一一条拦住「AI 替你答应任意应用要权限」的判据，默认必须开着。",
            risk = Risk.CRITICAL,
            riskText = "关掉它，一次提示词注入就可能让助手替你批准通讯录、定位、悬浮窗这类权限。",
            preview = "保持默认：助手每次碰到系统授权界面都会当场问你，你答过的一次也不作数。",
            scope = "全部 ui.* 与无障碍点击的目标归属判定",
        ),
        Spec(
            id = CONSENT_EXTRA_PACKAGES,
            group = Group.DANGER,
            type = Type.TextList,
            default = "[]",
            title = "还要防哪些授权宿主",
            summary = "除了内置名单，再点名一些承载授权弹窗的包。",
            effect = "加进来的包会被当成授权界面：操作它们一律先问你。",
            whyDefault = "内置名单是所有 AOSP 与常见厂商的授权组件族。",
            risk = Risk.HIGH,
            riskText = "这一条只会更严格；但如果内置名单太吵，正确做法是改内置名单那条，而不是关掉总开关。",
            preview = "例如把某个厂商的设置包加进来，点它的控件就会先问你。",
            scope = "ConsentSurfaces.isConsentPackage",
            parent = CONSENT_ENABLED,
        ),
        Spec(
            id = CONSENT_PREFIXES,
            group = Group.DANGER,
            type = Type.TextList,
            default = DEFAULT_CONSENT_PREFIXES,
            title = "厂商权限管理器前缀",
            summary = "这些包名开头的应用，被当成权限弹窗的宿主。",
            effect = "删掉一个前缀，那个厂商的权限管理器弹窗就不再被特殊对待（操作它不再强制问你）。",
            whyDefault = "内置覆盖三星/华为/荣耀/小米/OPPO/vivo 等常见厂商。",
            risk = Risk.HIGH,
            riskText = "删得过宽，厂商 ROM 上的权限弹窗会被当成普通界面，助手点它就不再单独问你。",
            preview = "保持默认：小米的权限管理弹窗（com.miui.*）仍受保护。",
            scope = "ConsentSurfaces.isConsentPackage",
            parent = CONSENT_ENABLED,
        ),
        Spec(
            id = CONSENT_VIEW_IDS,
            group = Group.DANGER,
            type = Type.TextList,
            default = DEFAULT_CONSENT_VIEW_IDS,
            title = "授权按钮特征词",
            summary = "控件 id 里出现这些词，就当成授权框上的按钮。",
            effect = "删掉一个词，对应那种按钮的兜底判据就失效。",
            whyDefault = "这四个词覆盖了各版本授权框架的允许/拒绝按钮 id。",
            risk = Risk.HIGH,
            riskText = "删掉 permission_allow / permission_deny 这两条，等于放弃了「包名认不出时」的最后一道兜底。",
            preview = "保持默认：即使授权弹窗由别的应用代管，按钮 id 也能被认出来。",
            scope = "ConsentSurfaces.looksLikeConsentNode / looksLikeConsentControl",
            parent = CONSENT_ENABLED,
        ),
        Spec(
            id = CONSENT_RELATIVE,
            group = Group.DANGER,
            type = Type.Switch,
            default = "true",
            title = "相对定位也算授权框",
            summary = "助手用「先找标题、再点它下面第 N 颗」这种方式操作时，一律先问你。",
            effect = "关掉之后，这种不看包名的点击不再被当成可能碰授权框 —— 而它恰恰是最容易点错的一类。",
            whyDefault = "带 relative 的调用在参数里看不出真正要按哪一颗，只能按最严的一条走。",
            risk = Risk.HIGH,
            riskText = "关掉等于给「标题作锚点 + 下方第 N 颗」开一条绕过归属判据的路。",
            preview = "保持默认：这类点击会先问你，你答过的一次也不作数（不给会话豁免）。",
            scope = "ConsentSurfaces.looksLikeConsentFromArgs",
            parent = CONSENT_ENABLED,
        ),
        Spec(
            id = CEILING_OVERRIDES,
            group = Group.DANGER,
            type = Type.Document(Type.Document.Kind.CEILING_OVERRIDES),
            default = "{}",
            title = "能力上限",
            summary = "每条能力最高能免问到哪一档：仅本会话 / 完全免问。",
            effect = "把一条能力升到「完全免问」，它的档位就能长期停在完全访问而不再询问。",
            whyDefault = "默认锁在各条能力编译期定的上限 —— 那是按可逆性与数据敏感级逐条定的。",
            risk = Risk.CRITICAL,
            riskText = "放开上限后，界面上的「完全访问」不再是纸面档位：写通讯录、截屏、读通知这类操作会真的永久免问。",
            preview = "例如把「写通讯录」升到完全免问：档位调到完全访问后，助手改联系人不再问你。",
            scope = "InterlockGate 的档位裁决 + 能力清单展示",
        ),
        Spec(
            id = ALLOW_ONCE_SESSION,
            group = Group.DANGER,
            type = Type.Switch,
            default = "false",
            title = "「允许一次」记住一段",
            summary = "点「允许一次」之后，这次答话在有效时长内继续管用。",
            effect = "打开后，你点一次「允许一次」，接下来同类调用在有效时长内不再问 —— 与「本会话内允许」几乎没差别。",
            whyDefault = "默认不记住：「允许一次」就该只管这一次，否则三颗按钮只剩两颗。",
            risk = Risk.HIGH,
            riskText = "打开后，你以为只放行了一次，实际放行了一整段时间。",
            preview = "保持默认：「允许一次」只消掉当前这一张卡，下一次同类调用照问。",
            scope = "确认卡上的第一颗按钮",
        ),
        Spec(
            id = DANGEROUS_TIER_DEFAULTS,
            group = Group.DANGER,
            type = Type.Link("capabilities"),
            default = "",
            title = "危险能力的默认档",
            summary = "sys.shell、安装应用、改安全设置、改应用权限这些能力没被拨过时的档位。",
            effect = "这一项决定「你从没碰过这条能力时它是什么档」。当前编译期值已是放开（完全访问），只读展示。",
            whyDefault = "当前构建把它们默认放在完全访问（这是随包一起发的补丁，不属于本页开放的范围）。",
            risk = Risk.CRITICAL,
            riskText = "把它改回「每次询问」是更安全的做法，但那需要改代码重新出包 —— 本页暂未开放这一项。",
            preview = "去能力页逐条看：把不放心的一条手动改成「每次询问」，这是当下就能做的。",
            scope = "TierStore.defaultTier",
            wired = false,
        ),
    )

    val byId: Map<String, Spec> = all.associateBy { it.id }

    /** 某一组里的条目（危险的子规则不单独进组，跟随父项渲染）。 */
    fun inGroup(group: Group): List<Spec> = all.filter { it.group == group && it.parent == null }

    fun childrenOf(parentId: String): List<Spec> = all.filter { it.parent == parentId }

    /** 存储键。加前缀是为了与既有键（`tier_*`、`verbose_log`）永不撞名。 */
    fun key(id: String): String = "cfg_$id"

    private fun jsonList(vararg values: String): String =
        JSONArray().apply { values.forEach { put(it) } }.toString()

    /** 只读/未接线项在界面上的说明尾注。 */
    fun readOnlyNote(spec: Spec): String? = when {
        spec.locked -> "这一项是平台不变式，只能查看。"
        !spec.wired -> "这一项暂未接线：界面展示的是它的默认值，改了当前不会生效。"
        else -> null
    }

    /**
     * 未接线项默认**不进界面**：一个改了没反应的开关会被读成"程序坏了"。
     * 只有用户在「显示未接线项（开发预览）」里显式打开，它们才露面，并且顶上带黄色说明。
     */
    fun isHidden(spec: Spec, showUnwired: Boolean): Boolean = !spec.wired && !showUnwired

    /** 未接线项在界面顶部的醒目黄色说明，原文固定，不随项变化。 */
    const val UNWIRED_NOTICE =
        "这一项当前不影响行为，接线后才会生效，改了不会有任何变化。"

    /** 把一个 JSON 对象文本解析成 map，解析不了就回空表（界面不因一段坏文本整页崩掉）。 */
    fun parseObject(text: String): Map<String, String> = runCatching {
        val obj = JSONObject(text)
        obj.keys().asSequence().associateWith { obj.optString(it) }
    }.getOrDefault(emptyMap())
}

package dev.autorasqr.xuimanager.ui.subscription

import dev.autorasqr.xuimanager.data.inbound.editor.InboundDraft
import dev.autorasqr.xuimanager.data.inbound.editor.JsonPath
import dev.autorasqr.xuimanager.data.inbound.editor.bool
import dev.autorasqr.xuimanager.data.inbound.editor.json
import dev.autorasqr.xuimanager.data.inbound.editor.str
import dev.autorasqr.xuimanager.data.subscription.SubscriptionDraft
import dev.autorasqr.xuimanager.data.subscription.SubscriptionDraft.RuleKind
import dev.autorasqr.xuimanager.ui.inbounds.editor.Actions
import dev.autorasqr.xuimanager.ui.inbounds.editor.ChoiceField
import dev.autorasqr.xuimanager.ui.inbounds.editor.Field
import dev.autorasqr.xuimanager.ui.inbounds.editor.InfoField
import dev.autorasqr.xuimanager.ui.inbounds.editor.JsonEditorField
import dev.autorasqr.xuimanager.ui.inbounds.editor.LocalAction
import dev.autorasqr.xuimanager.ui.inbounds.editor.NumberField
import dev.autorasqr.xuimanager.ui.inbounds.editor.Opt
import dev.autorasqr.xuimanager.ui.inbounds.editor.Section
import dev.autorasqr.xuimanager.ui.inbounds.editor.SwitchField
import dev.autorasqr.xuimanager.ui.inbounds.editor.TagsField
import dev.autorasqr.xuimanager.ui.inbounds.editor.TextField
import dev.autorasqr.xuimanager.ui.inbounds.editor.Toggle
import dev.autorasqr.xuimanager.ui.inbounds.editor.Txt
import dev.autorasqr.xuimanager.ui.inbounds.editor.Visible

/** Tabs of the screen; the panel's General, Formats and Balancers tabs with their inner tabs flattened. */
enum class SubTab { PANEL, INFO, PROFILE, HAPP, INCY, CLASH, JSON, BALANCERS }

fun tabTitle(tab: SubTab): Txt = when (tab) {
    SubTab.PANEL -> Txt("Subscription", "Подписка")
    SubTab.INFO -> Txt("Information", "Информация")
    SubTab.PROFILE -> Txt("Profile", "Профиль")
    SubTab.HAPP -> Txt("Happ")
    SubTab.INCY -> Txt("Incy")
    SubTab.CLASH -> Txt("Clash / Mihomo")
    SubTab.JSON -> Txt("JSON")
    SubTab.BALANCERS -> Txt("Balancers", "Балансировщики")
}

/**
 * Fields of [tab], copied from 3x-ui v3.10.2 (SubscriptionGeneralTab, HappSettingsContent, IncySettingsContent,
 * SubscriptionFormatsTab, SubscriptionBalancersTab). A setting the panel did not send is left out, so an older or
 * newer v3 panel never gets a key it does not know.
 */
fun fieldsFor(tab: SubTab, d: InboundDraft, known: Set<String>, ru: Boolean): List<Field> = Spec(known, ru).apply {
    when (tab) {
        SubTab.PANEL -> panel()
        SubTab.INFO -> info()
        SubTab.PROFILE -> profile()
        SubTab.HAPP -> happ()
        SubTab.INCY -> incy()
        SubTab.CLASH -> clash()
        SubTab.JSON -> jsonFormat(d)
        SubTab.BALANCERS -> observatory()
    }
}.out

private const val TOKENS_META = "{{EMAIL}}, {{ID}}, {{SHORT_ID}}, {{SUB_ID}}, {{TELEGRAM_ID}}"
private const val TOKENS_REMARK = "{{EMAIL}} {{INBOUND}} {{HOST}} {{ID}} {{SHORT_ID}} {{TELEGRAM_ID}} {{SUB_ID}} {{COMMENT}} " +
    "{{TRAFFIC_USED}} {{TRAFFIC_LEFT}} {{TRAFFIC_TOTAL}} {{UP}} {{DOWN}} {{STATUS}} {{STATUS_EMOJI}} {{DAYS_LEFT}} " +
    "{{TIME_LEFT}} {{USAGE_PERCENTAGE}} {{EXPIRE_DATE}} {{RESET_DAYS}} {{PROTOCOL}} {{TRANSPORT}} {{SECURITY}}"

private fun on(key: String): Visible = { it[JsonPath(key)].bool() == true }

private fun Spec.panel() {
    switch("subEnable", Txt("Subscription service", "Сервис подписки"), Txt("Enable or disable the subscription service.", "Включает или выключает сервис подписки."))
    switch(
        "subJsonEnable", Txt("JSON subscription", "JSON-подписка"),
        Txt("Enable or disable the JSON subscription endpoint independently. Its settings are on the JSON tab.", "Включает JSON-эндпоинт подписки отдельно. Его настройки — на вкладке JSON."),
    )
    switch(
        "subClashEnable", Txt("Clash / Mihomo subscription", "Подписка Clash / Mihomo"),
        Txt("Its settings are on the Clash / Mihomo tab.", "Её настройки — на вкладке Clash / Mihomo."),
    )
    note(
        Txt(
            "Changing the address, port, path, domain or certificates changes every client's subscription link. The app does not open a new port in the server's firewall.",
            "Смена адреса, порта, пути, домена или сертификатов меняет ссылку подписки у всех клиентов. Новый порт в файрволе сервера приложение не открывает.",
        ),
    )
    text("subListen", Txt("Listen IP", "IP для прослушивания"), Txt("The IP address for the subscription service; leave blank to listen on all IPs.", "IP-адрес сервиса подписки; пусто — все IP-адреса."))
    text(
        "subDomain", Txt("Listen domain", "Домен прослушивания"),
        Txt(
            "Leave blank to listen on all domains and IPs. Also used for the shown subscription link when the reverse proxy URI is empty.",
            "Пусто — все домены и IP-адреса. Также используется в отображаемой ссылке подписки, если «URI обратного прокси» пуст.",
        ),
    )
    number(
        "subPort", Txt("Listen port", "Порт подписки"), min = 1, max = 65535,
        hint = Txt("Must be an unused port on the server. Also used in the subscription link when the reverse proxy URI is empty.", "Должен быть свободен на сервере. Также используется в ссылке подписки, если «URI обратного прокси» пуст."),
    )
    text("subPath", Txt("URI path", "URI-путь"), Txt("Begins and ends with “/”.", "Начинается и заканчивается на «/»."), placeholder = "/sub/")
    text(
        "subURI", Txt("Reverse proxy URI", "URI обратного прокси"),
        Txt(
            "The full base URL (scheme://domain[:port]/path/) for the subscription link and QR code, used instead of the listen domain and port. Set it when the subscription is reached through a reverse proxy.",
            "Полный базовый URL (схема://домен[:порт]/путь/) для ссылки подписки и QR-кода вместо домена и порта прослушивания. Заполните, если подписка доступна через reverse-proxy.",
        ),
        placeholder = "(http|https)://domain[:port]/path/",
    )
    section(Txt("Certificates", "Сертификаты"), Txt("The app put its Let's Encrypt certificate here on deploy.", "При развёртывании приложение указало здесь свой сертификат Let's Encrypt.")) {
        text("subCertFile", Txt("Public key path", "Путь к публичному ключу"), Txt("Full path beginning with “/”.", "Полный путь, начинающийся с «/»."), monospace = true)
        text("subKeyFile", Txt("Private key path", "Путь к приватному ключу"), Txt("Full path beginning with “/”.", "Полный путь, начинающийся с «/»."), monospace = true)
    }
}

private fun Spec.info() {
    switch("subEncrypt", Txt("Encode", "Кодировать"), Txt("The subscription content is returned Base64-encoded.", "Содержимое подписки отдаётся в Base64."))
    text(
        "remarkTemplate", Txt("Remark template", "Шаблон примечания"),
        Txt(
            "Replaces the name of every link in the subscription. Leave empty for the default. Tokens: $TOKENS_REMARK",
            "Заменяет название каждой ссылки в подписке. Пусто — как по умолчанию. Токены: $TOKENS_REMARK",
        ),
    )
    switch(
        "subShowIdentityOnAllLinks", Txt("Show identity on every link", "Идентификатор на каждой ссылке"),
        Txt("{{EMAIL}} and {{USERNAME}} stay on every link's remark; usage tokens still appear on the first link only.", "{{EMAIL}} и {{USERNAME}} остаются в названии каждой ссылки; токены использования — только на первой."),
    )
    switch(
        "subInfoNodeEnable", Txt("Separate info config", "Отдельный инфо-конфиг"),
        Txt(
            "The remark and the traffic and days left are shown as a separate SOCKS config on top; an expired or depleted subscription returns only the status.",
            "Название и остаток трафика и дней показываются отдельным SOCKS-конфигом сверху, а при истечении срока или трафика возвращается только статус.",
        ),
    )
    switch(
        "subCalendarExpireInclusive", Txt("Month-end expiry display", "Окончание в конце месяца"),
        Txt(
            "For clients renewing on day 1 with a midnight cutoff, report the previous month's last second as the expiry. The actual cutoff is unchanged.",
            "Для клиентов с продлением 1-го числа в полночь показывать окончанием последнюю секунду прошлого месяца. Фактическое отключение не меняется.",
        ),
    )
    text("subExpiredTemplate", Txt("Expired subscription template", "Шаблон истёкшей подписки"), Txt("Name of the placeholder config once the subscription has expired.", "Название фиктивного конфига, когда подписка истекла."))
    text("subTrafficDepletedTemplate", Txt("Traffic depleted template", "Шаблон исчерпанного трафика"), Txt("Name of the placeholder config once the traffic is used up.", "Название фиктивного конфига, когда трафик исчерпан."))
    number("subUpdates", Txt("Update interval (hours)", "Интервал обновления (часы)"), min = 0, max = 525600, hint = Txt("How often client apps refresh the subscription.", "Как часто клиентские приложения обновляют подписку."))
    text(
        "externalSubUserAgent", Txt("External subscription User-Agent", "User-Agent внешней подписки"),
        Txt("Sent when the panel fetches a client's external subscription URL. Blank uses v2rayNG/1.8.5.", "Отправляется, когда панель загружает внешнюю подписку клиента. Пусто — v2rayNG/1.8.5."),
        placeholder = "v2rayNG/1.8.5",
    )
}

private fun Spec.profile() {
    text("subTitle", Txt("Subscription title", "Заголовок подписки"), Txt("Shown in the VPN client. Tokens: $TOKENS_META", "Название подписки в VPN-клиенте. Токены: $TOKENS_META"))
    text("subSupportUrl", Txt("Support URL", "Ссылка на поддержку"), Txt("Support link shown in the VPN client. Tokens: $TOKENS_META", "Ссылка на поддержку в VPN-клиенте. Токены: $TOKENS_META"), placeholder = "https://example.com")
    choice(
        "subProfileMode", Txt("Profile page", "Страница профиля"),
        listOf(opt("none", "No link", "Без ссылки"), opt("builtin", "Built-in page", "Встроенная страница"), opt("custom", "Custom website", "Свой сайт")),
        Txt("Which website link the VPN client shows.", "Какая ссылка на сайт показывается в VPN-клиенте."),
    )
    note(
        Txt(
            "This page exposes subscription URLs and node configurations, including for Happ encrypted subscriptions.",
            "Эта страница раскрывает адреса подписок и конфигурации узлов, в том числе зашифрованных подписок Happ.",
        ),
        warning = true,
        visible = { "subProfileMode" in known && it[JsonPath("subProfileMode")].str() == "builtin" },
    )
    text(
        "subProfileUrl", Txt("Profile URL", "Адрес профиля"), Txt("Link to your website shown in the VPN client. Tokens: $TOKENS_META", "Ссылка на ваш сайт в VPN-клиенте. Токены: $TOKENS_META"),
        placeholder = "https://example.com", visible = { it[JsonPath("subProfileMode")].str() == "custom" },
    )
    text("subAnnounce", Txt("Announcement", "Объявление"), Txt("Text shown in the VPN client. Tokens: $TOKENS_META", "Текст в VPN-клиенте. Токены: $TOKENS_META"), multiline = true)
    text(
        "subThemeDir", Txt("Subscription page theme folder", "Каталог темы страницы подписки"),
        Txt("Absolute path to a folder with your own index.html/sub.html. Leave empty for the default page.", "Абсолютный путь к папке со своим index.html/sub.html. Пусто — страница по умолчанию."),
        placeholder = "/etc/3x-ui/sub_templates/my-theme/", monospace = true,
    )
}

private val HAPP_COLOR_PROFILES = listOf(
    Txt("Reset", "Сброс") to "resetcolors",
    Txt("Violet") to """{"serverRowBackgroundColor":"#21003D67","cardBackgroundColor":"#120023B3"}""",
    Txt("Turquoise") to """{"serverRowBackgroundColor":"#002B3667","cardBackgroundColor":"#001F27B3"}""",
)

private fun Spec.happ() {
    switch("subHappAutoDetect", Txt("Detect Happ headers automatically", "Автоопределение заголовков Happ"), Txt("Add headers and routing for clients whose User-Agent is Happ.", "Добавлять заголовки и маршрутизацию для клиентов с User-Agent Happ."))
    switch("happLinkEnable", Txt("Encrypted subscription links", "Зашифрованные ссылки подписки"), Txt("Allow encrypted Happ links in the client's QR code window.", "Разрешает зашифрованные ссылки Happ в окне QR-кода клиента."))
    section(Txt("Routing and rules", "Маршрутизация и правила")) {
        note(Txt("Ready RoscomVPN profiles are on the server's Routing screen.", "Готовые профили RoscomVPN — на экране «Маршрутизация» сервера."))
        switch("subEnableRouting", Txt("Enable routing", "Включить маршрутизацию"), Txt("Global switch for routing in the VPN client (Happ only).", "Глобальное включение маршрутизации в VPN-клиенте (только Happ)."))
        text(
            "subRoutingRules", Txt("Routing rules", "Правила маршрутизации"),
            Txt(
                "A ready happ:// deeplink or one permanent HTTPS URL returning a deeplink or JSON. The panel refreshes remote rules in the background (Happ only).",
                "Готовый диплинк happ:// или одна постоянная HTTPS-ссылка на диплинк или JSON. Панель обновляет удалённые правила в фоне (только Happ).",
            ),
            placeholder = "happ://routing/onadd/... or https://.../DEFAULT.DEEPLINK", multiline = true, monospace = true,
        )
        switch("subHappNoLimit", Txt("No-Limit mode", "Режим No-Limit"), Txt("Raises xray-core's memory limit in Happ (beta).", "Повышает лимит памяти xray-core в Happ (бета)."))
        switch("subHideSettings", Txt("Hide server settings", "Скрыть настройки сервера"), Txt("Users can't view or edit server configs in the VPN client (Happ only).", "Пользователь не может смотреть и менять конфигурацию сервера в VPN-клиенте (только Happ)."))
    }
    section(Txt("Appearance", "Внешний вид")) {
        text("subHappSubInfoText", Txt("Info banner text", "Текст инфо-баннера"), Txt("Banner at the top of Happ, up to 200 characters.", "Баннер вверху приложения Happ, до 200 символов."), placeholder = "Welcome to our high-speed network!")
        choice(
            "subHappSubInfoColor", Txt("Banner color", "Цвет баннера"),
            listOf(opt("blue", "Blue", "Синий"), opt("green", "Green", "Зелёный"), opt("red", "Red", "Красный")), default = "blue",
        )
        text("subHappSubInfoButtonText", Txt("Banner button text", "Текст кнопки баннера"), Txt("Up to 25 characters.", "До 25 символов."), placeholder = "Support Channel")
        text("subHappSubInfoButtonLink", Txt("Banner button link", "Ссылка кнопки баннера"), null, placeholder = "https://t.me/your_channel")
        switch("subHappSubExpire", Txt("Expiry banner", "Баннер об окончании"), Txt("Show a banner in Happ once the traffic or the time is used up.", "Показывать в Happ баннер, когда закончился трафик или срок."))
        text("subHappSubExpireButtonLink", Txt("Renewal link", "Ссылка для продления"), Txt("URL of the “Renew” button.", "Адрес кнопки «Продлить»."), placeholder = "https://example.com/renew")
        switch("subHappNotificationExpire", Txt("Expiry notification", "Уведомление об окончании"), Txt("Remind the user 3 days before the subscription ends.", "Напоминать пользователю за 3 дня до окончания подписки."))
        text(
            "subHappColorProfile", Txt("Client color theme", "Цветовая тема клиента"),
            Txt("A JSON string with iOS colors, or resetcolors for the standard ones.", "JSON-строка с цветами для iOS или resetcolors для стандартных."),
            placeholder = """{"serverRowBackgroundColor":"#21003D67"} or resetcolors""", monospace = true,
        )
        if ("subHappColorProfile" in known) {
            out += Actions(HAPP_COLOR_PROFILES.map { (label, value) -> LocalAction(label) { it.set(JsonPath("subHappColorProfile"), json(value)) } })
        }
    }
    section(Txt("Network and TUN", "Сеть и TUN")) {
        choice("subHappTunMode", Txt("TUN mode", "Режим TUN"), listOf(opt("", "Default", "По умолчанию"), opt("system", "System", "Системный"), Opt("gvisor", "gVisor")), Txt("Desktop TUN network stack.", "Сетевой стек TUN на десктопе."))
        choice(
            "subHappTunType", Txt("TUN engine", "Движок TUN"),
            listOf(Opt("singbox", "sing-box"), Opt("tun2proxy", "tun2proxy"), opt("default", "Default (Happ TUN)", "По умолчанию"), Opt("xray", "Xray TUN")),
            Txt("Core for the desktop TUN connection.", "Ядро для TUN-подключения на десктопе."), default = "default",
        )
        text("subHappExcludeRoutes", Txt("Excluded routes (CIDR)", "Исключённые маршруты (CIDR)"), Txt("Comma-separated subnets that bypass the VPN tunnel.", "Подсети через запятую, которые идут мимо VPN-туннеля."), placeholder = "192.168.0.0/16, 10.0.0.0/8")
        switch("subHappExcludeApns", Txt("Exclude Apple push (APNS)", "Исключить push Apple (APNS)"), Txt("Apple notification traffic goes direct for reliable delivery on iOS.", "Уведомления Apple идут напрямую для надёжной доставки на iOS."))
        choice(
            "subHappPingType", Txt("Ping method", "Метод пинга"),
            listOf(opt("proxy", "Proxy GET", "Через прокси (GET)"), opt("proxy-head", "Proxy HEAD", "Через прокси (HEAD)"), Opt("tcp", "TCP"), Opt("icmp", "ICMP")), default = "proxy",
        )
        choice(
            "subHappLocalProxyAuth", Txt("Local proxy authorization", "Авторизация локального прокси"),
            listOf(opt("auto", "Auto (random credentials)", "Авто"), opt("disable", "Disabled", "Отключена"), opt("", "Not sent (client default)", "Не отправлять")),
            Txt(
                "Without authorization any app on the device can reach the tunnel through Happ's local SOCKS5/HTTP proxy and learn the server address.",
                "Без авторизации любое приложение на устройстве может через локальный SOCKS5/HTTP-прокси Happ попасть в туннель и узнать адрес сервера.",
            ),
            default = "auto",
        )
        switch("subHappAutoConnect", Txt("Connect on start", "Автоподключение при запуске"), null)
        choice(
            "subHappAutoConnectType", Txt("Auto-connect criterion", "Критерий автоподключения"),
            listOf(opt("lowestdelay", "Lowest delay", "Минимальная задержка"), opt("lastused", "Last used", "Последний"), opt("random", "Random", "Случайный")), default = "lowestdelay",
        )
        choice(
            "subHappPerAppMode", Txt("Per-app proxy (Android)", "Прокси для приложений (Android)"),
            listOf(opt("off", "Off", "Выкл"), opt("on", "Only listed apps", "Только выбранные"), opt("bypass", "Bypass listed apps", "Выбранные в обход")), default = "off",
        )
        text("subHappPerAppList", Txt("Android app packages", "Пакеты Android-приложений"), Txt("Comma-separated package names.", "Имена пакетов через запятую."), placeholder = "org.telegram.messenger, com.google.android.youtube", multiline = true)
    }
    section(Txt("Migration and management", "Миграция и управление")) {
        text("subHappProviderId", Txt("Provider ID", "Идентификатор провайдера"), Txt("Unique ID for app management, remote config and migration.", "Уникальный ID для управления приложением, удалённой настройки и миграции."), placeholder = "my-happ-provider")
        text("subHappNewUrl", Txt("New subscription URL (migration)", "Новый адрес подписки (миграция)"), Txt("Happ switches to this URL automatically.", "Happ сам переключится на этот адрес."), placeholder = "https://new-domain.com/sub/...")
        text("subHappFallbackUrl", Txt("Fallback subscription URL", "Резервный адрес подписки"), Txt("Used when the main server is unavailable.", "Используется, когда основной сервер недоступен."), placeholder = "https://backup-domain.com/sub/...")
        switch("subHappAlwaysHwid", Txt("Mandatory HWID", "Обязательный HWID"), Txt("Users can't turn off sending the HWID in Happ.", "Пользователь не может отключить отправку HWID в Happ."))
    }
}

private fun Spec.incy() {
    switch("subIncyAppAutoDetect", Txt("Detect Incy headers automatically", "Автоопределение заголовков Incy"), Txt("Send Incy headers when the client's User-Agent is INCY. “Not set” leaves the user's own app setting alone.", "Отправлять заголовки Incy, когда User-Agent клиента — INCY. «Не задано» оставляет настройку самого пользователя."))
    section(Txt("App", "Приложение")) {
        text("subIncyProfileDescription", Txt("Profile description", "Описание профиля"), Txt("Up to 200 characters.", "До 200 символов."))
        choice("subIncySortOrder", Txt("Server order", "Порядок серверов"), listOf(opt("", "Not set", "Не задано"), opt("none", "As in the subscription", "Как в подписке"), opt("ping", "By ping", "По пингу"), opt("name", "By name", "По имени")))
        text("subIncySupportEmail", Txt("Support email", "Email поддержки"), null, placeholder = "support@example.com")
        text("subIncyAnnounceUrl", Txt("Announcement link", "Ссылка объявления"), null, placeholder = "https://t.me/your_channel")
        text("subIncyPremiumUrl", Txt("Premium link", "Ссылка Premium"), null, placeholder = "https://example.com/buy")
    }
    section(Txt("Banners", "Баннеры")) {
        text("subIncyBannerText", Txt("Banner text", "Текст баннера"), Txt("Premium providers only.", "Только для premium-провайдеров."))
        text("subIncyBannerButtonText", Txt("Banner button text", "Текст кнопки баннера"), Txt("Empty hides the button.", "Пусто — кнопки нет."))
        text("subIncyBannerButtonUrl", Txt("Banner button link", "Ссылка кнопки баннера"), null, placeholder = "https://example.com/sale")
        text("subIncyBannerBgColor", Txt("Banner background", "Фон баннера"), null, placeholder = "#E53E3E")
        text("subIncyBannerButtonColor", Txt("Banner button color", "Цвет кнопки баннера"), null, placeholder = "#38A169")
    }
    section(Txt("Privacy", "Приватность")) {
        choice("subIncyHideUrl", Txt("Hide subscription URL", "Скрыть адрес подписки"), incyOnOff, Txt("Hides the URL from sharing, copying, QR and backups.", "Скрывает адрес из «Поделиться», копирования, QR и бэкапов."))
        choice("subIncyHideCheck", Txt("Hide the check button", "Скрыть кнопку проверки"), incyOnOff)
        choice("subIncyNoLimitEnabled", Txt("No-Limit mode (iOS)", "Режим No-Limit (iOS)"), incyOnOff, Txt("Memory-saving mode that keeps the tunnel within 50 MB on iOS.", "Экономный режим, чтобы туннель укладывался в 50 МБ на iOS."))
        choice("subIncyPerAppEnable", Txt("Per-app mode (Android)", "Режим per-app (Android)"), incyOnOff)
        choice("subIncyPerAppMode", Txt("Per-app logic", "Логика per-app"), listOf(opt("", "Not set", "Не задано"), opt("proxy", "Only listed apps", "Только выбранные"), opt("bypass", "Bypass listed apps", "Выбранные в обход")))
        text("subIncyPerAppList", Txt("App list", "Список приложений"), Txt("Packages separated by commas or new lines, or a URL to a text file.", "Пакеты через запятую или с новой строки, либо адрес текстового файла."), placeholder = "com.google.chrome\norg.telegram.messenger", multiline = true)
    }
    section(Txt("Network", "Сеть")) {
        choice("subIncyFragmentationEnable", Txt("Fragmentation", "Фрагментация"), incyOnOff)
        text("subIncyFragmentLength", Txt("Fragment length", "Длина фрагмента"), Txt("Range in bytes.", "Диапазон в байтах."), placeholder = "10-30")
        text("subIncyFragmentInterval", Txt("Fragment interval", "Интервал фрагментации"), Txt("Range in milliseconds.", "Диапазон в миллисекундах."), placeholder = "20-40")
        choice("subIncyFragmentPackets", Txt("Fragmented packets", "Пакеты фрагментации"), listOf(opt("", "Not set", "Не задано"), Opt("tlshello"), Opt("1-3"), Opt("1"), Opt("all")))
        choice("subIncyNoisesEnable", Txt("Noise packets", "Шумовые пакеты"), incyOnOff, Txt("Random UDP noise before the VPN handshake.", "Случайный UDP-шум перед рукопожатием VPN."))
        choice("subIncyNoisesType", Txt("Noise type", "Тип шума"), listOf(opt("", "Not set", "Не задано"), Opt("rand"), Opt("str"), Opt("hex")))
        text("subIncyNoisesPacket", Txt("Noise packet", "Содержимое шума"), Txt("Payload; for rand a length range.", "Полезная нагрузка; для rand — диапазон длины."), placeholder = "10-20")
        text("subIncyNoisesDelay", Txt("Noise delay", "Задержка шума"), Txt("Range in milliseconds.", "Диапазон в миллисекундах."), placeholder = "10-50")
        choice("subIncyResolveEnable", Txt("Resolve the server address (DoH)", "Резолв адреса сервера (DoH)"), incyOnOff)
        text("subIncyResolveDnsDomain", Txt("DoH endpoint", "DoH-эндпоинт"), null, placeholder = "https://common.dot.dns.yandex.net/dns-query")
        text("subIncyResolveDnsIp", Txt("DoH server IP", "IP DoH-сервера"), Txt("Needed for bootstrap.", "Нужен для bootstrap."), placeholder = "77.88.8.8")
    }
    section(Txt("Routing", "Маршрутизация")) {
        switch("subIncyEnableRouting", Txt("Enable routing", "Включить маршрутизацию"), Txt("Put a routing profile into the subscription for Incy.", "Добавлять профиль маршрутизации в подписку для Incy."))
        text(
            "subIncyRoutingRules", Txt("Routing rules", "Правила маршрутизации"),
            Txt("A ready incy:// deeplink or one permanent HTTPS URL returning JSON.", "Готовый диплинк incy:// или одна постоянная HTTPS-ссылка на JSON."),
            placeholder = "incy://routing/onadd/... or https://.../DEFAULT.JSON", multiline = true, monospace = true,
        )
    }
}

private fun Spec.clash() {
    section(Txt("Clash / Mihomo subscription", "Подписка Clash / Mihomo"), visible = on("subClashEnable")) {
        text("subClashPath", Txt("URI path", "URI-путь"), null, placeholder = "/clash/")
        text("subClashURI", Txt("Reverse proxy URI", "URI обратного прокси"), null, placeholder = "(http|https)://domain[:port]/path/")
        switch(
            "subClashAutoDetect", Txt("Detect Clash/Mihomo clients automatically", "Автоопределение клиентов Clash/Mihomo"),
            Txt("Recognized clients get Clash YAML on the standard subscription URL.", "Распознанные клиенты получают Clash YAML по обычной ссылке подписки."),
        )
        text("subClashUserAgentRegex", Txt("User-Agent regex", "Регулярное выражение User-Agent"), Txt("Go RE2; empty uses the default pattern.", "Go RE2; пусто — шаблон по умолчанию."), placeholder = "(?i)(clash|mihomo)", monospace = true)
    }
    if ("subClashEnable" in known) {
        note(
            Txt("Turn on the Clash / Mihomo subscription on the Subscription tab to set its address.", "Адрес подписки Clash / Mihomo настраивается после её включения на вкладке «Подписка»."),
            visible = { !on("subClashEnable")(it) },
        )
    }
    switch("subClashEnableRouting", Txt("Enable routing", "Включить маршрутизацию"), Txt("Include global routing rules in generated YAML subscriptions.", "Добавлять глобальные правила маршрутизации в YAML-подписки."))
    text(
        "subClashRules", Txt("Global routing rules", "Глобальные правила маршрутизации"),
        Txt("Inline rules/YAML or one permanent HTTPS URL; the panel refreshes it in the background.", "Правила/YAML или одна постоянная HTTPS-ссылка; панель обновляет её в фоне."),
        placeholder = "https://.../routing.yaml\nGEOSITE,category-ru,DIRECT", multiline = true, monospace = true,
    )
}

private val GEOIP = listOf("private", "ir", "cn", "ru", "vn", "es", "id", "ua", "tr", "br").map { "geoip:$it" }
private val GEOSITE_DIRECT = listOf("private", "category-ir", "cn", "category-ru", "apple", "meta", "google").map { "geosite:$it" }
private val GEOSITE_BLOCK = listOf("category-ads-all", "category-porn").map { "geosite:$it" }

private fun Spec.jsonFormat(d: InboundDraft) {
    section(Txt("JSON subscription", "JSON-подписка"), visible = on("subJsonEnable")) {
        text("subJsonPath", Txt("URI path", "URI-путь"), null, placeholder = "/json/")
        text("subJsonURI", Txt("Reverse proxy URI", "URI обратного прокси"), null, placeholder = "(http|https)://domain[:port]/path/")
        switch(
            "subJsonAlwaysArray", Txt("Always return a JSON array", "Всегда возвращать массив JSON"),
            Txt("Return an array even with one profile, as the XTLS standard requires.", "Возвращать массив даже при одном профиле, как требует стандарт XTLS."),
        )
        switch(
            "subJsonAutoDetect", Txt("Detect Xray JSON clients automatically", "Автоопределение клиентов Xray JSON"),
            Txt("Compatible clients get an Xray JSON array on the standard subscription URL.", "Совместимые клиенты получают массив Xray JSON по обычной ссылке подписки."),
        )
        text("subJsonUserAgentRegex", Txt("Xray JSON User-Agent regex", "Регулярное выражение User-Agent Xray JSON"), Txt("Go RE2. Empty keeps auto-detection off.", "Go RE2. Пусто — автоопределение выключено."), placeholder = "(?i)^myclient([ /]|$)", monospace = true)
        text(
            "subJsonRoutingRules", Txt("Routing profile", "Профиль маршрутизации"),
            Txt("Routing JSON, a happ:// or incy:// deeplink, or an https:// URL. Replaces the DNS and routing rules of the JSON subscription.", "JSON правил, диплинк happ:// или incy://, либо https://-ссылка. Заменяет DNS и маршрутизацию JSON-подписки."),
            multiline = true, monospace = true,
        )
        text(
            "subJsonDns", Txt("DNS servers", "DNS-серверы"),
            Txt("A full dns object or a servers array baked into every JSON config. Empty keeps the built-in resolver.", "Полный объект dns или массив servers для каждого JSON-конфига. Пусто — встроенный резолвер."),
            placeholder = """{"servers":["1.1.1.1"]}""", multiline = true, monospace = true,
        )
    }
    if ("subJsonEnable" in known) {
        note(
            Txt("Turn on the JSON subscription on the Subscription tab to set its address.", "Адрес JSON-подписки настраивается после её включения на вкладке «Подписка»."),
            visible = { !on("subJsonEnable")(it) },
        )
    }
    if (SubscriptionDraft.FINAL_MASK in known) {
        out += Section(
            Txt("Final Mask"),
            listOf(JsonEditorField(JsonPath(SubscriptionDraft.FINAL_MASK), Txt("finalmask JSON"), Txt("""{"tcp": [...], "udp": [...]}; empty turns it off.""", """{"tcp": [...], "udp": [...]}; пусто — выключено."""))),
            hint = Txt(
                "Xray finalmask TCP/UDP masks and QUIC parameters added to every Xray JSON profile. Needs a client with Xray JSON subscriptions and a recent Xray core.",
                "TCP/UDP-маски finalmask Xray и параметры QUIC в каждом профиле Xray JSON. Нужен клиент с поддержкой Xray JSON и свежее ядро Xray.",
            ),
        )
    }
    if (SubscriptionDraft.MUX in known) {
        val mux = JsonPath(SubscriptionDraft.MUX)
        out += Section(
            Txt("Mux"),
            listOf(
                NumberField(mux + "concurrency", Txt("Concurrency", "Параллелизм"), min = -1, max = 1024),
                NumberField(mux + "xudpConcurrency", Txt("xudp concurrency", "Параллелизм xudp"), min = -1, max = 1024),
                ChoiceField(mux + "xudpProxyUDP443", Txt("xudp UDP 443"), listOf(Opt("reject"), Opt("allow"), Opt("skip")), default = "reject"),
            ),
            hint = Txt("Several independent streams inside one connection.", "Несколько независимых потоков в одном соединении."),
            toggle = Toggle(mux, create = { SubscriptionDraft.DEFAULT_MUX }),
        )
    }
    if (SubscriptionDraft.RULES in known) {
        out += rulesSection(
            d, SubscriptionDraft.DIRECT, Txt("Direct connection", "Прямое подключение"),
            Txt("Domains and IP ranges that go direct, bypassing the VPN.", "Домены и IP-диапазоны, которые идут напрямую, мимо VPN."), GEOSITE_DIRECT,
        )
        out += rulesSection(
            d, SubscriptionDraft.BLOCK, Txt("Block connection", "Блокировка соединений"),
            Txt("Domains and IP ranges blocked with the blackhole outbound.", "Домены и IP-диапазоны, блокируемые через blackhole."), GEOSITE_BLOCK,
        )
    }
}

private fun rulesSection(d: InboundDraft, tag: String, title: Txt, hint: Txt, domains: List<String>): Section {
    val fields = listOfNotNull(
        SubscriptionDraft.rulePath(d, tag, RuleKind.IP)?.let { TagsField(it, Txt("IPs", "IP-адреса"), placeholder = "geoip:ru", suggestions = GEOIP) },
        SubscriptionDraft.rulePath(d, tag, RuleKind.DOMAIN)?.let { TagsField(it, Txt("Domains", "Домены"), placeholder = "geosite:category-ru", suggestions = domains) },
    )
    return Section(
        title, fields, hint = hint,
        toggle = Toggle(
            JsonPath(SubscriptionDraft.RULES),
            onOff = { draft, enable -> SubscriptionDraft.setRules(draft, tag, enable) },
            isOn = { SubscriptionDraft.rulesOn(it, tag) },
        ),
    )
}

private fun Spec.observatory() {
    if (SubscriptionDraft.OBSERVATORY !in known) return
    val o = JsonPath(SubscriptionDraft.OBSERVATORY)
    out += Section(
        Txt("Balancer observatory", "Обсерватория балансировщика"),
        listOf(
            TextField(o + "destination", Txt("Probe URL", "URL проверки"), placeholder = "https://www.google.com/generate_204", hint = Txt("Where the client checks each member.", "Куда клиент обращается для проверки каждого участника.")),
            TextField(o + "connectivity", Txt("Connectivity URL", "URL связности"), placeholder = "http://connectivitycheck.platform.hicloud.com/generate_204", hint = Txt("Optional one-off check; empty skips it.", "Необязательная разовая проверка; пусто — пропустить.")),
            TextField(o + "interval", Txt("Probe interval", "Интервал проверок"), placeholder = "1m"),
            TextField(o + "timeout", Txt("Probe timeout", "Тайм-аут проверки"), placeholder = "5s"),
            NumberField(o + "sampling", Txt("Sampling", "Выборка"), min = 1, hint = Txt("Probes in a row averaged for stability.", "Число проверок подряд для усреднения.")),
            ChoiceField(o + "httpMethod", Txt("HTTP method", "HTTP-метод"), listOf(Opt("HEAD"), Opt("GET")), default = "HEAD"),
        ),
        hint = Txt(
            "leastPing and leastLoad balancers always carry a burst observatory; this sets its probes. Off uses the built-in defaults. Saved with the other settings.",
            "Балансировщики leastPing и leastLoad всегда содержат burst-обсерваторию; здесь задаются её проверки. Выключено — значения по умолчанию. Сохраняется вместе с остальными настройками.",
        ),
        toggle = Toggle(o, create = { SubscriptionDraft.DEFAULT_PING }),
    )
}

/** Collects fields, leaving out settings the panel did not send. */
private class Spec(val known: Set<String>, val ru: Boolean) {
    val out = mutableListOf<Field>()

    fun text(key: String, label: Txt, hint: Txt?, placeholder: String? = null, multiline: Boolean = false, monospace: Boolean = false, visible: Visible = { true }) {
        if (key in known) out += TextField(JsonPath(key), label, placeholder = placeholder, hint = hint, multiline = multiline, monospace = monospace, visible = visible)
    }

    fun number(key: String, label: Txt, min: Long? = null, max: Long? = null, hint: Txt? = null) {
        if (key in known) out += NumberField(JsonPath(key), label, min = min, max = max, hint = hint)
    }

    fun switch(key: String, label: Txt, hint: Txt?) {
        if (key in known) out += SwitchField(JsonPath(key), label, hint = hint)
    }

    fun choice(key: String, label: Txt, options: List<Opt>, hint: Txt? = null, default: String = "") {
        if (key in known) out += ChoiceField(JsonPath(key), label, options, hint = hint, default = default)
    }

    fun opt(value: String, en: String, ru: String) = Opt(value, if (this.ru) ru else en)

    val incyOnOff get() = listOf(opt("", "Not set", "Не задано"), opt("1", "On", "Вкл"), opt("0", "Off", "Выкл"))

    fun note(text: Txt, warning: Boolean = false, visible: Visible = { true }) {
        out += InfoField(null, { if (ru) text.ru else text.en }, warning = warning, visible = visible)
    }

    fun section(title: Txt, hint: Txt? = null, visible: Visible = { true }, block: Spec.() -> Unit) {
        val inner = Spec(known, ru).apply(block).out
        if (inner.any { it !is InfoField }) out += Section(title, inner, hint = hint, visible = visible)
    }
}

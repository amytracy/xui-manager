package dev.autorasqr.xuimanager.data.deploy

object DeployConfig {
    /** Pinned 3x-ui release. Changing it requires updating [INSTALLER_SHA256] from the same tag. */
    const val XUI_VERSION = "v3.10.2"
    const val INSTALLER_URL = "https://raw.githubusercontent.com/MHSanaei/3x-ui/$XUI_VERSION/install.sh"
    const val INSTALLER_SHA256 = "9684bae4f769b2127fcceceb4f200045657381311867529e6ba76c6b58e2825d"

    const val INBOUND_REMARK = "xui-manager-reality"
    const val SELFSTEAL_REMARK = "xui-manager-selfsteal"
    const val DEFAULT_INBOUND_PORT = 443
    const val VISION_FLOW = "xtls-rprx-vision"
    const val JOBS_DIR = ".xui-manager/jobs"

    /** Caddy release for the self-steal site; both archives checked against the release's SHA-512 list. */
    const val CADDY_VERSION = "2.11.7"
    const val CADDY_SHA256_AMD64 = "727b91701a392de6ebc5027509f548bf39979e5216340d0faed8fa5e69c84f8b"
    const val CADDY_SHA256_ARM64 = "d8fc6d179a5d283028a472a5618564f6ad8a86fed513e64f032b3b0b7cc45e42"
    const val SELFSTEAL_TIMEOUT_MS = 15 * 60_000L
    /** The user's own decoy page, uploaded next to selfsteal.sh; without it the script picks a random template. */
    const val SITE_PAGE_FILE = "site.html"

    /** Scripts uploaded next to each other into a job directory. */
    val SCRIPTS = listOf("run.sh", "lib.sh", "selfsteal.sh")
    const val GEODATA_TIMEOUT_MS = 10 * 60_000L
    /** apt may first wait up to 10 min for unattended-upgrades to release its lock. */
    const val SYSUPDATE_TIMEOUT_MS = 40 * 60_000L
    const val UNINSTALL_TIMEOUT_MS = 15 * 60_000L
    const val REBOOT_SCRIPT_TIMEOUT_MS = 3 * 60_000L
    /** How long a rebooting server may stay away before the app gives up following it. */
    const val REBOOT_WAIT_MS = 6 * 60_000L
    const val REBOOT_PANEL_WAIT_MS = 2 * 60_000L

    const val POLL_INTERVAL_MS = 1_500L
    const val MAX_LOG_LINES = 3_000
    const val DEPLOY_TIMEOUT_MS = 45 * 60_000L
}

/**
 * Candidate Reality targets for one-click mode. Each must serve TLS 1.3 with HTTP/2 and X25519; the panel's
 * scanner checks this from the server itself and the first feasible one wins.
 */
object SniPresets {
    val targets: List<String> = listOf(
        "www.microsoft.com",
        "www.apple.com",
        "dl.google.com",
        "www.samsung.com",
        "www.nvidia.com",
        "www.amazon.com",
        "www.asus.com",
        "www.cloudflare.com",
        "github.com",
        "www.oracle.com",
    )
}

/** uTLS fingerprints the panel offers for Reality client links (realitySettings.settings.fingerprint). */
object Fingerprints {
    val all: List<String> = listOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized",
    )
}

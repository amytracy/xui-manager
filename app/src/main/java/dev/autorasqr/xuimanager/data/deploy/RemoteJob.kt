package dev.autorasqr.xuimanager.data.deploy

import dev.autorasqr.xuimanager.data.ssh.shQuote

/** Shell snippets for the remote job directory. All of them run through `sh -c` as the login user. */
object RemoteJob {
    const val RESULT_SEPARATOR = "-----XUI-MANAGER-X25519-----"
    private const val MAX_CHUNK = 262_144
    private val safeValue = Regex("""^[A-Za-z0-9._:/@+-]*$""")

    fun jobDir(home: String, jobId: Long): String = home.trimEnd('/') + "/" + DeployConfig.JOBS_DIR + "/" + jobId

    fun paramsEnv(params: StoredDeployParams, serverHost: String): String = env(
        "XUI_VERSION" to DeployConfig.XUI_VERSION,
        "INSTALLER_URL" to DeployConfig.INSTALLER_URL,
        "INSTALLER_SHA256" to DeployConfig.INSTALLER_SHA256,
        "SERVER_HOST" to serverHost,
        "SSL_MODE" to params.sslMode,
        "PANEL_PORT" to params.panelPort.toString(),
        "SUB_PORT" to params.subPort.toString(),
        "INBOUND_PORT" to params.inboundPort.toString(),
        "INBOUND_PORT_STRICT" to if (params.inboundPortStrict) "1" else "0",
        "EXISTING_ACTION" to (params.existingAction?.name?.lowercase() ?: "none"),
        "OWNER_UID" to params.uid.toString(),
        "OWNER_GID" to params.gid.toString(),
        "SELFSTEAL" to if (params.selfSteal) "1" else "0",
        *caddyEnv(),
    )

    /** params.env for a standalone selfsteal.sh run. */
    fun selfStealEnv(action: String, domain: String, uid: Int, gid: Int): String = env(
        "ACTION" to action,
        "DOMAIN" to domain,
        "OWNER_UID" to uid.toString(),
        "OWNER_GID" to gid.toString(),
        *caddyEnv(),
    )

    /** params.env for geodata.sh. */
    fun geodataEnv(action: String, uid: Int, gid: Int): String = env(
        "ACTION" to action,
        "OWNER_UID" to uid.toString(),
        "OWNER_GID" to gid.toString(),
    )

    /** params.env for sysupdate.sh. */
    fun sysUpdateEnv(uid: Int, gid: Int): String = env(
        "OWNER_UID" to uid.toString(),
        "OWNER_GID" to gid.toString(),
    )

    /** params.env for reboot.sh. */
    fun rebootEnv(uid: Int, gid: Int): String = env(
        "OWNER_UID" to uid.toString(),
        "OWNER_GID" to gid.toString(),
    )

    /** params.env for uninstall.sh: [ports] are closed in the firewall, [sshPort] never is. */
    fun uninstallEnv(uid: Int, gid: Int, sshPort: Int, ports: Collection<Int>): String = env(
        "OWNER_UID" to uid.toString(),
        "OWNER_GID" to gid.toString(),
        "SSH_PORT" to sshPort.toString(),
        "PORTS" to ports.filter { it in 1..65535 }.distinct().sorted().joinToString(":"),
        *caddyEnv(),
    )

    /** params.env for restore.sh: [preferredHost] is tried first when the panel certificate covers several names. */
    fun restoreEnv(uid: Int, gid: Int, preferredHost: String): String = env(
        "OWNER_UID" to uid.toString(),
        "OWNER_GID" to gid.toString(),
        "PREFERRED_HOST" to preferredHost,
    )

    /** secrets.env for restore.sh: the panel keeps its path, only the login and password change. */
    fun restoreSecretsEnv(username: String, password: String): String = env(
        "PANEL_USERNAME" to username,
        "PANEL_PASSWORD" to password,
    )

    private fun caddyEnv() = arrayOf(
        "CADDY_VERSION" to DeployConfig.CADDY_VERSION,
        "CADDY_SHA256_AMD64" to DeployConfig.CADDY_SHA256_AMD64,
        "CADDY_SHA256_ARM64" to DeployConfig.CADDY_SHA256_ARM64,
    )

    fun secretsEnv(username: String, password: String, webBasePath: String): String = env(
        "PANEL_USERNAME" to username,
        "PANEL_PASSWORD" to password,
        "WEB_BASE_PATH" to webBasePath,
    )

    /** Truncates or creates the files the root runner appends to, so they stay owned by the login user. */
    fun prepareCommand(jobDir: String): String =
        "umask 077 && cd ${shQuote(jobDir)} && : > log && : > stage && rm -f exit exit.tmp pid result.env x25519.out"

    /** Root part of the launch: detach [script] from the SSH channel and return immediately. */
    fun startScript(jobDir: String, script: String = "run.sh"): String {
        val dir = shQuote(jobDir)
        val file = shQuote("./$script")
        return "cd $dir && if command -v setsid >/dev/null 2>&1; then " +
            "setsid nohup bash $file >/dev/null 2>&1 </dev/null & " +
            "else nohup bash $file >/dev/null 2>&1 </dev/null & fi"
    }

    /** Succeeds only if a runner for this job directory is alive. */
    fun runnerAliveCommand(jobDir: String): String =
        "cd ${shQuote(jobDir)} 2>/dev/null && [ -f pid ] && { p=\$(cat pid); [ -d \"/proc/\$p\" ] || ps -p \"\$p\" >/dev/null 2>&1; }"

    /** Prints stage, exit code and liveness (1 alive, 0 dead, 2 not started yet), then the log from [offset]. */
    fun pollCommand(jobDir: String, offset: Long): String =
        "cd ${shQuote(jobDir)} 2>/dev/null || { printf 'NOJOB\\n\\n0\\n'; exit 0; }; " +
            "s=\$(cat stage 2>/dev/null); e=\$(cat exit 2>/dev/null); a=2; " +
            "if [ -f pid ]; then p=\$(cat pid); if [ -d \"/proc/\$p\" ] || ps -p \"\$p\" >/dev/null 2>&1; then a=1; else a=0; fi; fi; " +
            "printf '%s\\n%s\\n%s\\n' \"\$s\" \"\$e\" \"\$a\"; " +
            "tail -c +${offset + 1} log 2>/dev/null | head -c $MAX_CHUNK"

    fun readResultCommand(jobDir: String): String =
        "cd ${shQuote(jobDir)} && cat result.env && printf '\\n%s\\n' '$RESULT_SEPARATOR' && (cat x25519.out 2>/dev/null || true)"

    /** Removes everything secret; the masked log is kept for troubleshooting. */
    fun cleanupCommand(jobDir: String): String =
        "cd ${shQuote(jobDir)} && rm -rf result.env result.env.tmp x25519.out secrets.env site.html install.sh caddy.tar.gz caddy"

    /** After uninstall.sh: the app's whole directory in the login user's home, logs included. */
    fun removeJobsCommand(home: String): String =
        "rm -rf ${shQuote(home.trimEnd('/') + "/" + DeployConfig.JOBS_DIR.substringBefore('/'))}"

    private fun env(vararg pairs: Pair<String, String>): String = buildString {
        pairs.forEach { (key, value) ->
            require(safeValue.matches(value)) { "unsafe value for $key" }
            append(key).append('=').append(shQuote(value)).append('\n')
        }
    }
}

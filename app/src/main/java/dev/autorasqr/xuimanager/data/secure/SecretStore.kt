package dev.autorasqr.xuimanager.data.secure

interface SecretStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun removeByPrefix(prefix: String)
}

object SecretKeys {
    fun sshPassword(serverId: Long) = "server.$serverId.ssh.password"
    fun sshPrivateKey(serverId: Long) = "server.$serverId.ssh.key"
    fun sshPassphrase(serverId: Long) = "server.$serverId.ssh.passphrase"
    fun panelPassword(serverId: Long) = "server.$serverId.panel.password"
    fun panelToken(serverId: Long) = "server.$serverId.panel.token"
    fun serverPrefix(serverId: Long) = "server.$serverId."

    fun jobPanelPassword(jobId: Long) = "job.$jobId.panel.password"
    fun jobWebBasePath(jobId: Long) = "job.$jobId.panel.basePath"
    fun jobPrefix(jobId: Long) = "job.$jobId."
}

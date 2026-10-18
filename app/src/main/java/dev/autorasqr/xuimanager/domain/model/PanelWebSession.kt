package dev.autorasqr.xuimanager.domain.model

/** What the in-app browser needs to open a panel already signed in. Holds a session cookie, never the password. */
data class PanelWebSession(
    val serverName: String,
    /** https://host:port/basePath/ — the only origin the WebView may load. */
    val origin: String,
    /** Page to open first. */
    val startUrl: String,
    /** Set-Cookie strings for [origin]. */
    val cookies: List<String>,
    /** Set when automatic sign-in failed (e.g. 2FA is on); the login page is shown instead. */
    val loginError: AppError?,
) {
    override fun toString() = "PanelWebSession(origin=***, cookies=${cookies.size}, loginError=$loginError)"
}

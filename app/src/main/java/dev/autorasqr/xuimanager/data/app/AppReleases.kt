package dev.autorasqr.xuimanager.data.app

/** Where the app's own releases are published. */
object AppReleases {
    const val OWNER = "amytracy"
    const val REPO = "xui-manager"

    /** The newest published release; drafts and pre-releases are never returned here. */
    const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"
}

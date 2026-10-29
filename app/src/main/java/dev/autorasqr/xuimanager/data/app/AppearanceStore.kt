package dev.autorasqr.xuimanager.data.app

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.autorasqr.xuimanager.domain.model.UiStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The chosen [UiStyle]; read once at start so the first frame already has the right look. */
@Singleton
class AppearanceStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _style = MutableStateFlow(UiStyle.parse(prefs.getString(KEY_STYLE, null)))
    val style: StateFlow<UiStyle> = _style.asStateFlow()

    fun setStyle(style: UiStyle) {
        prefs.edit { putString(KEY_STYLE, style.name) }
        _style.value = style
    }

    private companion object {
        const val PREFS_NAME = "appearance"
        const val KEY_STYLE = "style"
    }
}

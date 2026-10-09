package com.engreader.app.data

import android.content.Context
import android.content.SharedPreferences
import com.engreader.app.ui.theme.ReadingTheme

/**
 * User preferences. Stored in SharedPreferences because every field is read on the
 * first frame of the reader and must not require a database round-trip.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("engreader_settings", Context.MODE_PRIVATE)

    /** Reader font size in sp. */
    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 19)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value.coerceIn(14, 32)).apply()

    /** Reader line-height multiplier. */
    var lineHeight: Float
        get() = prefs.getFloat(KEY_LINE_HEIGHT, 1.75f)
        set(value) = prefs.edit().putFloat(KEY_LINE_HEIGHT, value.coerceIn(1.3f, 2.4f)).apply()

    var readingTheme: ReadingTheme
        get() = ReadingTheme.entries.getOrElse(prefs.getInt(KEY_THEME, 0)) { ReadingTheme.Paper }
        set(value) = prefs.edit().putInt(KEY_THEME, value.ordinal).apply()

    /** Speech rate for TTS, 0.5x to 1.5x. */
    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 0.95f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(0.5f, 1.5f)).apply()

    /** Locale tag used for the English voice, e.g. `en-GB`. */
    var speechLocale: String
        get() = prefs.getString(KEY_SPEECH_LOCALE, "en-GB").orEmpty()
        set(value) = prefs.edit().putString(KEY_SPEECH_LOCALE, value).apply()

    /**
     * Which bundled voice to read with, e.g. `en_GB-alan-medium`.
     *
     * Blank means "use the accent's own voice", which is what a reader who has never
     * opened the picker gets. Stored by id rather than by index because the picker's
     * order is presentational and could change between versions.
     */
    var speechVoice: String
        get() = prefs.getString(KEY_SPEECH_VOICE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SPEECH_VOICE, value).apply()

    /** Whether a word is saved to the wordbook automatically on look-up. */
    var autoSaveLookups: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SAVE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SAVE, value).apply()

    /** Highlight words the user has already saved, so progress is visible while reading. */
    var highlightSavedWords: Boolean
        get() = prefs.getBoolean(KEY_HIGHLIGHT, true)
        set(value) = prefs.edit().putBoolean(KEY_HIGHLIGHT, value).apply()

    /** Show the Chinese translation panel under each paragraph. */
    var showTranslation: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TRANSLATION, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_TRANSLATION, value).apply()

    /** Daily goal in minutes, used by the stats screen. */
    var dailyGoalMinutes: Int
        get() = prefs.getInt(KEY_DAILY_GOAL, 15)
        set(value) = prefs.edit().putInt(KEY_DAILY_GOAL, value.coerceIn(5, 120)).apply()

    /** Words looked up on a phone without a configured TTS voice are still readable. */
    var ttsReady: Boolean
        get() = prefs.getBoolean(KEY_TTS_READY, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_READY, value).apply()

    private companion object {
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_LINE_HEIGHT = "line_height"
        const val KEY_THEME = "reading_theme"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_SPEECH_LOCALE = "speech_locale"
        const val KEY_SPEECH_VOICE = "speech_voice"
        const val KEY_AUTO_SAVE = "auto_save"
        const val KEY_HIGHLIGHT = "highlight_saved"
        const val KEY_SHOW_TRANSLATION = "show_translation"
        const val KEY_DAILY_GOAL = "daily_goal"
        const val KEY_TTS_READY = "tts_ready"
    }
}

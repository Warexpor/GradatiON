package io.github.stardomains3.oxproxion

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.core.content.edit
import androidx.core.net.toUri

class SharedPreferencesHelper(context: Context) {

    private val appContext = context.applicationContext
    private val apiKeysPrefs: SharedPreferences =
        TolerantPrefs(appContext.getSharedPreferences(API_KEYS_PREFS_STORE, Context.MODE_PRIVATE))
    val mainPrefs: SharedPreferences =
        TolerantPrefs(appContext.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE))
    private val json = Json { ignoreUnknownKeys = true }
    private val gson = Gson() // Kept temporarily for migration only

    /** Decodes a stored JSON value. A corrupt one falls back instead of crashing every screen that reads it. */
    private inline fun <reified T> decodeOr(key: String, raw: String, fallback: () -> T): T =
        try {
            json.decodeFromString<T>(raw)
        } catch (e: Exception) {
            Log.w("SharedPrefs", "Unreadable $key (${e.javaClass.simpleName}); using the default")
            fallback()
        }

    /** True when [key] is present and this version cannot decode it. Absent is not unreadable. */
    private inline fun <reified T> storedJsonUnreadable(key: String): Boolean {
        val raw = mainPrefs.getString(key, null) ?: return false
        return runCatching { json.decodeFromString<T>(raw) }.isFailure
    }

    /**
     * The model list could not be decoded. Launch work that would rewrite it (seeding, the
     * Maverick scrub) must wait: decoding a failure as "no models" and saving that used to
     * delete the only copy.
     */
    internal fun customModelsUnreadable(): Boolean =
        storedJsonUnreadable<List<LlmModel>>(KEY_CUSTOM_MODELS)

    private fun unreadableArchiveKey(key: String) = "$key.unreadable"

    /**
     * Writes [value]. When the current blob does not decode, that blob is copied to
     * `key.unreadable` in the same edit first, so a save is not what deletes it.
     * The first unreadable copy is kept; a later save does not overwrite the archive.
     */
    private inline fun <reified T> putStoredJson(key: String, value: T) {
        val encoded = json.encodeToString(value)
        val archive = unreadableArchiveKey(key)
        val torn = mainPrefs.getString(key, null)?.takeIf { raw ->
            !mainPrefs.contains(archive) &&
                runCatching { json.decodeFromString<T>(raw) }.isFailure
        }
        mainPrefs.edit {
            torn?.let { putString(archive, it) }
            putString(key, encoded)
        }
    }

    companion object {

        private const val KEY_VOICE_INPUT_MODEL = "voice_input_model"
        private const val KEY_VOICE_INPUT_PROVIDER = "voice_input_provider" // VoiceEngine keys: device, cloud, grok, lan, off
        /** Encrypted prefs alias for an xAI API key (Grok STT / future Grok voice). */
        const val XAI_API_KEY_ALIAS = "xai_api_key"
        private const val KEY_THEME_MODE = "theme_mode"
        /** Ambient background style (AmbientBackgroundView.Style.key): off, grain, drift, flow, adaptive. */
        const val KEY_BACKGROUND_STYLE = "background_style"
        /** Empty-chat app icon: off, plain (flat vector), or liquid (the moving glass, default). */
        const val KEY_CHAT_MARK = "chat_mark_style"
        const val CHAT_MARK_OFF = "off"
        const val CHAT_MARK_PLAIN = "plain"
        const val CHAT_MARK_LIQUID = "liquid"
        /** Roleplay tab on the main screen; off by default (Settings > Modes). */
        const val KEY_ROLEPLAY_ENABLED = "roleplay_enabled"
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        private const val KEY_INFERENCE_TEMP_ENABLED = "inference_temp_enabled"
        private const val KEY_INFERENCE_TEMP_VALUE = "inference_temp_value"
        private const val KEY_INFERENCE_TOP_P_ENABLED = "inference_top_p_enabled"
        private const val KEY_INFERENCE_TOP_P_VALUE = "inference_top_p_value"
        private const val KEY_INFERENCE_TOP_K_ENABLED = "inference_top_k_enabled"
        private const val KEY_INFERENCE_TOP_K_VALUE = "inference_top_k_value"
        private const val KEY_INFERENCE_MIN_P_ENABLED = "inference_min_p_enabled"
        private const val KEY_INFERENCE_MIN_P_VALUE = "inference_min_p_value"
        private const val KEY_INFERENCE_REPETITION_PENALTY_ENABLED = "inference_repetition_penalty_enabled"
        private const val KEY_INFERENCE_REPETITION_PENALTY_VALUE = "inference_repetition_penalty_value"
        private const val KEY_INFERENCE_PRESENCE_PENALTY_ENABLED = "inference_presence_penalty_enabled"
        private const val KEY_INFERENCE_PRESENCE_PENALTY_VALUE = "inference_presence_penalty_value"
        const val LAN_PROVIDER_MLX_LM = "mlx_lm"  // NEW

        const val LAN_PROVIDER_HERMES_AGENT = "hermes_agent"  // NEW - Hermes Agent provider
        private const val KEY_AUTO_BACK = "auto_back_enabled"
        private const val KEY_CHAT_FORK_PREFIX = "chat_fork_"
        private const val KEY_CHAT_FORK_INDEX_PREFIX = "chat_fork_idx_"
        private const val KEY_CHAT_FORK_ANCHOR_PREFIX = "chat_fork_anchor_"
        private const val KEY_VOLUME_SCROLL = "volume_scroll_enabled"
        private const val KEY_ENABLED_TOOLS = "enabled_tools"
        private const val KEY_TOOLS_ENABLED = "tools_enabled_preference"
        const val LAN_PROVIDER_OMLX = "omlx"
        const val LAN_PROVIDER_NATIV = "nativ"

        private const val KEY_CHAT_MEMORY_COUNT = "chat_memory_count"
        private const val KEY_ANIMATE_BAR_ON_ERROR = "animate_bar_on_error"
        private const val SAF_FOLDER_URI = "saffolderuri"
        private const val KEY_USE_COPY_BUTTON2 = "use_copy_button2"

        private const val KEY_SHOW_CITATIONS = "show_citations"
        private const val KEY_EXTENDED_TOP_BAR = "extended_top_bar_enabled"
        private const val KEY_OPENROUTER_TRANSFORMS_ENABLED = "openrouter_transforms_enabled"
        private const val KEY_EXPANDABLE_INPUT = "expandable_input_enabled"
        const val LAN_API_KEY = "lan_api_key"  // NEW
        /** Request timeout in minutes; the chat clients rebuild themselves when this changes. */
        const val KEY_TIMEOUT_MINUTES = "timeout_minutes"
        private const val KEY_DISABLE_WEB_SEARCH_AFTER_SEND = "disable_web_search_after_send"
        private const val KEY_SCROLLERS_ENABLED = "scrollers_enabled"
        private const val KEY_CUSTOM_PROMPTS = "custom_prompts"
        const val RP_LAYOUT_CLASSIC = "classic"
        const val RP_LAYOUT_BUBBLES = "bubbles"
        const val RP_LAYOUT_BOOK = "book"
        private const val KEY_SCROLL_PROGRESS_ENABLED = "scroll_progress_enabled"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_CLEAR_CHAT_DEFAULT = "clear_chat_default"
        private const val KEY_CLEAR_CHAT_DEFAULT2 = "clear_chat_default2"
        const val LAN_PROVIDER_KEY = "lan_provider"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_LAST_AI_RESPONSE_CHANNEL = "last_ai_response_channel_"
        const val LAN_PROVIDER_LLAMA_CPP = "llama_cpp"
        private const val KEY_FONT_SIZEC = "font_sizec"
        private const val KEY_USE_COPY_BUTTON = "use_copy_button"
        const val LAN_PROVIDER_OLLAMA = "ollama"
        private const val KEY_WEB_SEARCH_ENGINE = "web_search_engine"
        private const val KEY_WEB_SEARCH_ENABLED = "web_search_enabled"
        const val LAN_PROVIDER_LM_STUDIO = "lm_studio"
        private const val KEY_LAN_ENDPOINT = "lan_endpoint"
        private const val KEY_PRESETS = "user_presets"
        private const val KEY_SELECTED_FONT = "selected_font"
        private const val KEY_FONT_FACE_MIGRATED = "font_face_migrated_jakarta"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_TRUST_SELF_SIGNED_LAN = "trust_self_signed_lan"
        private const val KEY_LAN_CERT_PIN_PREFIX = "lan_cert_pin:"
        private const val KEY_CHAT_DB_RECOVERED = "chat_db_recovered"
        /** Stamp of a recovery that has moved the database aside and not finished opening a fresh one. */
        private const val KEY_CHAT_DB_RECOVERY_STAMP = "chat_db_recovery_stamp"
        /** File name under the databases directory when the original chat_database could not be moved. */
        private const val KEY_CHAT_DB_FILE = "chat_db_file"
        private const val KEY_ALLOW_DESTRUCTIVE_TOOLS = "allow_destructive_tools"
        private const val KEY_HAPTIC_BUTTONS = "haptic_buttons"
        private const val KEY_HAPTIC_RESPONDING = "haptic_responding"
        private const val KEY_PINNED_SESSION_IDS = "pinned_session_ids"
        private const val KEY_CONVERSATION_MODE_ENABLED = "conversation_mode_enabled"
        private const val LAN_API_KEY_ALIAS = "lan_api_key"
        internal const val CHAT_DB_PASSPHRASE_ALIAS = "chat_db_passphrase"

        /** Prefs prefix for a passphrase archived beside `chat_database.unreadable-<stamp>`. */
        internal fun chatDbPassphraseArchivePrefix(stamp: Long) =
            "${CHAT_DB_PASSPHRASE_ALIAS}_unreadable_$stamp"

        /**
         * Decodes a stored passphrase. A wrong length is refused: opening SQLCipher with a truncated
         * key would look like a corrupt database and recovery would then throw the real key away.
         */
        internal fun decodeChatDbPassphrase(existing: String): ByteArray {
            val first = runCatching { Base64.decode(existing, Base64.NO_WRAP) }.getOrNull()
            if (first != null && first.size == 32) return first
            val second = Base64.decode(existing, Base64.DEFAULT)
            if (second.size != 32) {
                throw IllegalStateException("Chat DB passphrase has the wrong length")
            }
            return second
        }
        private const val KEY_LAN_API_KEY_MIGRATED = "lan_api_key_migrated"
        private const val API_KEYS_PREFS_STORE = "ApiKeysPrefsStore"
        const val MAIN_PREFS = "MainAppPrefs"
        private const val KEY_MODEL_NEW_CHAT = "modelvalenewchat"
        private const val KEY_CUSTOM_MODELS = "custom_models"
        private const val KEY_DEFAULT_MODELS_SEEDED = "default_models_seeded"
        private const val KEY_OLD_DEFAULTS_PRUNED = "old_default_models_pruned"
        private const val KEY_DEMO_CHARACTER_SEEDED = "demo_character_seeded"
        private const val KEY_DEMO_CHARACTER_AVATAR_REV = "demo_character_avatar_rev"
        /** Name and id of each model older installs were seeded with. */
        private val OLD_DEFAULT_MODELS = listOf(
            "OpenAI: ChatGPT-4o" to "openai/chatgpt-4o-latest",
            "MoonshotAI: Kimi K2" to "moonshotai/kimi-k2",
            "xAI: Grok 3" to "x-ai/grok-3",
            "Mistral: Mistral Medium 3" to "mistralai/mistral-medium-3",
            "Deepseek: R1 0528" to "deepseek/deepseek-r1-0528",
            "Deepseek: V3 0324" to "deepseek/deepseek-chat-v3-0324",
            "Qwen: Qwen3 235B A22B Instruct 2507" to "qwen/qwen3-235b-a22b-2507",
            "Baidu: ERNIE 4.5 300B A47B" to "baidu/ernie-4.5-300b-a47b",
            "Google: Gemini 2.5 Flash" to "google/gemini-2.5-flash",
            "Google: Gemini 2.5 Pro" to "google/gemini-2.5-pro",
            "xAI: Grok 4" to "x-ai/grok-4",
            "OpenAI: GPT-4.1" to "openai/gpt-4.1",
            "Anthropic: Claude Sonnet 4" to "anthropic/claude-sonnet-4",
            "Perplexity: Sonar Pro" to "perplexity/sonar-pro",
        )
        private const val KEY_SELECTED_SYSTEM_MESSAGE = "selected_system_message"
        private const val KEY_CUSTOM_SYSTEM_MESSAGES = "custom_system_messages"
        private const val KEY_DEFAULT_SYSTEM_MESSAGES_SEEDED = "default_system_messages_seeded"
        private const val KEY_STREAMING_ENABLED = "streaming_enabled"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_OPEN_ROUTER_MODELS = "open_router_models"
        /** One-shot. Old caches had no reasoning flag, so every model read as not reasoning. */
        private const val KEY_OPEN_ROUTER_REASONING_MIGRATED = "open_router_reasoning_migrated"
        private const val KEY_NOTI_ENABLED = "noti_enabled"
        private const val KEY_EXT_ENABLED = "ext_enabled"
        private const val KEY_EXT_ENABLED2 = "ext_enabled2"
        private const val KEY_REASONING_ENABLED = "reasoning_enabled"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_DEFAULT_SYSTEM_MESSAGE = "default_system_message"
        private const val KEY_MIGRATION_COMPLETE = "has_migrated_to_kotlin_serialization"

        // GradatiON RP
        private const val KEY_CHAT_MODE = "chat_mode"
        private const val KEY_RP_ACTIVE_CHARACTER_ID = "rp_active_character_id"
        private const val KEY_RP_PERSONA = "rp_persona"
        private const val KEY_RP_PERSONA_PRESETS = "rp_persona_presets"
        private const val KEY_RP_PERSONA_NAME = "rp_persona_name"
        private const val KEY_RP_PERSONA_PHOTO = "rp_persona_photo"
        private const val KEY_RP_PERSONA_ENABLED = "rp_persona_enabled"
        private const val KEY_RP_LORE_ENABLED = "rp_lore_enabled"
        private const val KEY_RP_THIRD_PERSON = "rp_third_person"
        private const val KEY_RP_SHOW_THOUGHTS = "rp_show_thoughts"
        private const val KEY_SHOW_THINKING_BLOCKS = "show_thinking_blocks"
        private const val KEY_WEB_SEARCH_RETIRED = "web_search_button_retired"
        private const val KEY_RP_LLM_MODE = "rp_llm_mode"
        private const val KEY_RP_DRAFT_SESSION_ASK = "rp_draft_session_ask"
        private const val KEY_RP_DRAFT_SESSION_RP = "rp_draft_session_rp"
        private const val KEY_COMPOSER_DRAFT_ASK = "composer_draft_ask"
        private const val KEY_COMPOSER_DRAFT_RP = "composer_draft_rp"
        /** Per-thread unsent text for Chat. Survives leaving a thread; the mode drafts do not. */
        private const val KEY_ASK_COMPOSER_DRAFTS = "ask_composer_drafts"
        private const val KEY_RP_SWIPE_PREFIX = "rp_swipe_"
        private const val KEY_RP_PENDING_INSTRUCT = "rp_pending_instruct"
        private const val KEY_RP_DELETED_CHAR_REMAP = "rp_deleted_char_remap"
    }

    init {
        migrateFromGson()
        migrateLanApiKeyToEncrypted()
    }

    private fun migrateLanApiKeyToEncrypted() {
        if (mainPrefs.getBoolean(KEY_LAN_API_KEY_MIGRATED, false)) return

        val plaintext = mainPrefs.getString(LAN_API_KEY, null)
        if (plaintext.isNullOrBlank() || plaintext == "any-non-empty-string") {
            // Nothing to migrate — safe to mark done and drop any leftover placeholder.
            mainPrefs.edit {
                remove(LAN_API_KEY)
                putBoolean(KEY_LAN_API_KEY_MIGRATED, true)
            }
            return
        }

        val ok = saveApiKey(LAN_API_KEY_ALIAS, plaintext)
        if (!ok) {
            // Keep plaintext and retry on next launch — do not mark migrated.
            return
        }
        mainPrefs.edit {
            remove(LAN_API_KEY)
            putBoolean(KEY_LAN_API_KEY_MIGRATED, true)
        }
    }

    private fun migrateFromGson() {
        if (!mainPrefs.getBoolean(KEY_MIGRATION_COMPLETE, false)) {
            //   Log.d("Migration", "Starting migration from Gson to Kotlin Serialization")

            runCatching { migrateCustomModels() }.onFailure {
                //    Log.e("Migration", "Failed to migrate custom models", it)
            }
            runCatching { migrateSystemMessages() }.onFailure {
                //     Log.e("Migration", "Failed to migrate system messages", it)
            }
            runCatching { migrateOpenRouterModels() }.onFailure {
                //   Log.e("Migration", "Failed to migrate OpenRouter models", it)
            }
            runCatching { migrateSelectedSystemMessage() }.onFailure {
                //  Log.e("Migration", "Failed to migrate selected system message", it)
            }
            runCatching { migrateDefaultSystemMessage() }.onFailure {
                //  Log.e("Migration", "Failed to migrate default system message", it)
            }

            mainPrefs.edit { putBoolean(KEY_MIGRATION_COMPLETE, true) }
            //  Log.d("Migration", "Migration to Kotlin Serialization complete")
        }
    }


    private fun migrateCustomModels() {
        val oldJson = mainPrefs.getString(KEY_CUSTOM_MODELS, null)
        if (oldJson != null) {
            val type = object : TypeToken<List<LlmModel>>() {}.type
            val oldModels: List<LlmModel> = gson.fromJson(oldJson, type)
            saveCustomModels(oldModels)
        }
    }

    private fun migrateSystemMessages() {
        val oldJson = mainPrefs.getString(KEY_CUSTOM_SYSTEM_MESSAGES, null)
        if (oldJson != null) {
            val type = object : TypeToken<List<SystemMessage>>() {}.type
            val oldMessages: List<SystemMessage> = gson.fromJson(oldJson, type)
            saveCustomSystemMessages(oldMessages)
        }
    }

    private fun migrateOpenRouterModels() {
        val oldJson = mainPrefs.getString(KEY_OPEN_ROUTER_MODELS, null)
        if (oldJson != null) {
            val type = object : TypeToken<List<LlmModel>>() {}.type
            val oldModels: List<LlmModel>? = try {
                gson.fromJson(oldJson, type)
            } catch (e: Exception) {
                Log.w("SharedPrefs", "Unreadable $KEY_OPEN_ROUTER_MODELS (${e.javaClass.simpleName}); skipping migration")
                null
            }
            if (oldModels != null) saveOpenRouterModels(oldModels)
        }
    }
    fun getCustomPrompts(): List<Prompt> {
        val jsonString = mainPrefs.getString(KEY_CUSTOM_PROMPTS, null)
        return if (jsonString != null) {
            decodeOr(KEY_CUSTOM_PROMPTS, jsonString) { emptyList<Prompt>() }
        } else {
            emptyList()
        }
    }
    // --- Inference Parameter Methods ---

    // Temperature (Default: 1.0)
    fun getInferenceTempEnabled(): Boolean = mainPrefs.getBoolean(KEY_INFERENCE_TEMP_ENABLED, false)
    fun saveInferenceTempEnabled(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_INFERENCE_TEMP_ENABLED, enabled) }
    fun getInferenceTempValue(): String = mainPrefs.getString(KEY_INFERENCE_TEMP_VALUE, "1.0") ?: "1.0"
    fun saveInferenceTempValue(value: String) = mainPrefs.edit { putString(KEY_INFERENCE_TEMP_VALUE, value) }

    // Top P (Default: 1.0)
    fun getInferenceTopPEnabled(): Boolean = mainPrefs.getBoolean(KEY_INFERENCE_TOP_P_ENABLED, false)
    fun saveInferenceTopPEnabled(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_INFERENCE_TOP_P_ENABLED, enabled) }
    fun getInferenceTopPValue(): String = mainPrefs.getString(KEY_INFERENCE_TOP_P_VALUE, "1.0") ?: "1.0"
    fun saveInferenceTopPValue(value: String) = mainPrefs.edit { putString(KEY_INFERENCE_TOP_P_VALUE, value) }

    // Top K (Default: 40) - This one was already correct
    fun getInferenceTopKEnabled(): Boolean = mainPrefs.getBoolean(KEY_INFERENCE_TOP_K_ENABLED, false)
    fun saveInferenceTopKEnabled(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_INFERENCE_TOP_K_ENABLED, enabled) }
    fun getInferenceTopKValue(): Int = mainPrefs.getInt(KEY_INFERENCE_TOP_K_VALUE, 40)
    fun saveInferenceTopKValue(value: Int) = mainPrefs.edit { putInt(KEY_INFERENCE_TOP_K_VALUE, value) }

    // Min P (Default: 0.0)
    fun getInferenceMinPEnabled(): Boolean = mainPrefs.getBoolean(KEY_INFERENCE_MIN_P_ENABLED, false)
    fun saveInferenceMinPEnabled(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_INFERENCE_MIN_P_ENABLED, enabled) }
    fun getInferenceMinPValue(): String = mainPrefs.getString(KEY_INFERENCE_MIN_P_VALUE, "0.0") ?: "0.0"
    fun saveInferenceMinPValue(value: String) = mainPrefs.edit { putString(KEY_INFERENCE_MIN_P_VALUE, value) }

    // Repetition Penalty (Default: 1.0)
    fun getInferenceRepetitionPenaltyEnabled(): Boolean = mainPrefs.getBoolean(KEY_INFERENCE_REPETITION_PENALTY_ENABLED, false)
    fun saveInferenceRepetitionPenaltyEnabled(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_INFERENCE_REPETITION_PENALTY_ENABLED, enabled) }
    fun getInferenceRepetitionPenaltyValue(): String = mainPrefs.getString(KEY_INFERENCE_REPETITION_PENALTY_VALUE, "1.0") ?: "1.0"
    fun saveInferenceRepetitionPenaltyValue(value: String) = mainPrefs.edit { putString(KEY_INFERENCE_REPETITION_PENALTY_VALUE, value) }

    // Presence Penalty (Default: 0.0)
    fun getInferencePresencePenaltyEnabled(): Boolean = mainPrefs.getBoolean(KEY_INFERENCE_PRESENCE_PENALTY_ENABLED, false)
    fun saveInferencePresencePenaltyEnabled(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_INFERENCE_PRESENCE_PENALTY_ENABLED, enabled) }
    fun getInferencePresencePenaltyValue(): String = mainPrefs.getString(KEY_INFERENCE_PRESENCE_PENALTY_VALUE, "0.0") ?: "0.0"
    fun saveInferencePresencePenaltyValue(value: String) = mainPrefs.edit { putString(KEY_INFERENCE_PRESENCE_PENALTY_VALUE, value) }

    fun saveOpenRouterTransformsEnabled(enabled: Boolean) {
        mainPrefs.edit { putBoolean(KEY_OPENROUTER_TRANSFORMS_ENABLED, enabled) }
    }

    fun getOpenRouterTransformsEnabled(): Boolean {
        return mainPrefs.getBoolean(KEY_OPENROUTER_TRANSFORMS_ENABLED, false)
    }
    fun saveCustomPrompts(prompts: List<Prompt>) {
        putStoredJson(KEY_CUSTOM_PROMPTS, prompts)
    }
    fun getUseCopyButton(): Boolean {
        return mainPrefs.getBoolean(KEY_USE_COPY_BUTTON, false)  // false = Open, true = Copy
    }
    fun saveUseCopyButton(useCopy: Boolean) {
        mainPrefs.edit { putBoolean(KEY_USE_COPY_BUTTON, useCopy) }
    }
    private fun migrateSelectedSystemMessage() {
        val oldJson = mainPrefs.getString(KEY_SELECTED_SYSTEM_MESSAGE, null)
        if (oldJson != null) {
            val oldMessage: SystemMessage = gson.fromJson(oldJson, SystemMessage::class.java)
            saveSelectedSystemMessage(oldMessage)
        }
    }
    fun clearOpenRouterModels() {
        mainPrefs.edit { remove(KEY_OPEN_ROUTER_MODELS) }
    }
    fun getOpenRouterReasoningMigrated(): Boolean =
        mainPrefs.getBoolean(KEY_OPEN_ROUTER_REASONING_MIGRATED, false)
    fun saveOpenRouterReasoningMigrated() =
        mainPrefs.edit { putBoolean(KEY_OPEN_ROUTER_REASONING_MIGRATED, true) }
    fun saveBiometricEnabled(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_BIOMETRIC_ENABLED, enabled) }
    fun getBiometricEnabled(): Boolean = mainPrefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun getTrustSelfSignedLan(): Boolean = mainPrefs.getBoolean(KEY_TRUST_SELF_SIGNED_LAN, false)
    fun saveTrustSelfSignedLan(enabled: Boolean) = mainPrefs.edit {
        putBoolean(KEY_TRUST_SELF_SIGNED_LAN, enabled)
        // Off forgets what was trusted, so switching it back on pins whatever the server shows then.
        if (!enabled) clearLanCertPinsIn(this)
    }

    /** Pinned LAN certificates, keyed by host:port; see [LanCertPins]. */
    fun lanCertPinStore(): LanCertPins.Store = object : LanCertPins.Store {
        override fun pinFor(hostPort: String): String? =
            mainPrefs.getString(KEY_LAN_CERT_PIN_PREFIX + hostPort, null)

        override fun savePin(hostPort: String, pin: String) {
            mainPrefs.edit { putString(KEY_LAN_CERT_PIN_PREFIX + hostPort, pin) }
        }
    }

    fun clearLanCertPins() = mainPrefs.edit { clearLanCertPinsIn(this) }

    private fun clearLanCertPinsIn(editor: SharedPreferences.Editor) {
        mainPrefs.all.keys.filter { it.startsWith(KEY_LAN_CERT_PIN_PREFIX) }.forEach { editor.remove(it) }
    }

    /** Set when the chat database could not be opened and a fresh one was started; read once by the UI. */
    fun markChatDbRecovered() {
        mainPrefs.edit(commit = true) {
            putBoolean(KEY_CHAT_DB_RECOVERED, true)
            remove(KEY_CHAT_DB_RECOVERY_STAMP)
        }
    }

    /** True once after [markChatDbRecovered]; clears the flag. */
    fun consumeChatDbRecovered(): Boolean {
        if (!mainPrefs.getBoolean(KEY_CHAT_DB_RECOVERED, false)) return false
        mainPrefs.edit { remove(KEY_CHAT_DB_RECOVERED) }
        return true
    }

    /**
     * Written after the old database files are moved aside and before the fresh one is open, so a
     * process death in between does not create an empty database and forget to say so.
     */
    fun markRecoveryPending(stamp: Long) {
        mainPrefs.edit(commit = true) { putLong(KEY_CHAT_DB_RECOVERY_STAMP, stamp) }
    }

    fun recoveryPendingStamp(): Long? {
        // A wrong type must not read as stamp 0, which would look like a real interrupted recovery.
        val value = mainPrefs.all[KEY_CHAT_DB_RECOVERY_STAMP]
        return value as? Long
    }

    /**
     * Which file Room opens. The default is [AppDatabase.DB_NAME]. A recovery that could not move
     * the unreadable file stores a new name here so the next launch does not open the bad one again.
     * Anything that is not a single safe file name is ignored.
     */
    fun chatDbFileName(): String {
        val name = mainPrefs.getString(KEY_CHAT_DB_FILE, null)?.trim().orEmpty()
        if (name.isEmpty()) return AppDatabase.DB_NAME
        val safe = name.length <= 80 &&
            name.startsWith(AppDatabase.DB_NAME) &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        return if (safe) name else AppDatabase.DB_NAME
    }

    fun saveChatDbFileName(name: String) {
        mainPrefs.edit(commit = true) { putString(KEY_CHAT_DB_FILE, name) }
    }

    fun clearRecoveryPending() {
        mainPrefs.edit(commit = true) { remove(KEY_CHAT_DB_RECOVERY_STAMP) }
    }

    fun getAllowDestructiveTools(): Boolean = mainPrefs.getBoolean(KEY_ALLOW_DESTRUCTIVE_TOOLS, false)
    fun saveAllowDestructiveTools(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_ALLOW_DESTRUCTIVE_TOOLS, enabled) }

    fun getHapticButtons(): Boolean = mainPrefs.getBoolean(KEY_HAPTIC_BUTTONS, true)
    fun saveHapticButtons(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_HAPTIC_BUTTONS, enabled) }

    fun getHapticResponding(): Boolean = mainPrefs.getBoolean(KEY_HAPTIC_RESPONDING, true)
    fun saveHapticResponding(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_HAPTIC_RESPONDING, enabled) }

    fun getPinnedSessionIds(): Set<Long> =
        mainPrefs.getStringSet(KEY_PINNED_SESSION_IDS, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet()
            ?: emptySet()

    fun isSessionPinned(sessionId: Long): Boolean = sessionId in getPinnedSessionIds()

    fun setSessionPinned(sessionId: Long, pinned: Boolean) {
        val next = getPinnedSessionIds().toMutableSet()
        if (pinned) next.add(sessionId) else next.remove(sessionId)
        mainPrefs.edit {
            putStringSet(KEY_PINNED_SESSION_IDS, next.map { it.toString() }.toSet())
        }
    }

    fun getAdvancedReasoningEnabled(): Boolean = mainPrefs.getBoolean("advanced_reasoning_enabled", false)
    fun saveAdvancedReasoningEnabled(enabled: Boolean) = mainPrefs.edit {
        putBoolean(
            "advanced_reasoning_enabled",
            enabled
        )
    }

    fun getReasoningEffort(): String = mainPrefs.getString("reasoning_effort", "medium") ?: "medium"
    fun saveReasoningEffort(effort: String) = mainPrefs.edit {
        putString(
            "reasoning_effort",
            effort
        )
    }
    fun getVoiceInputModel(): String = mainPrefs.getString(KEY_VOICE_INPUT_MODEL, "") ?: ""
    fun setVoiceInputModel(model: String) = mainPrefs.edit { putString(KEY_VOICE_INPUT_MODEL, model) }

    fun getVoiceInputProvider(): String = mainPrefs.getString(KEY_VOICE_INPUT_PROVIDER, VoiceEngine.DEVICE.key) ?: VoiceEngine.DEVICE.key
    fun setVoiceInputProvider(provider: String) = mainPrefs.edit { putString(KEY_VOICE_INPUT_PROVIDER, provider) }
    fun saveChatMemoryCount(count: Int) {
        mainPrefs.edit { putInt(KEY_CHAT_MEMORY_COUNT, count) }
    }
    fun getChatMemoryCount(): Int {
        return mainPrefs.getInt(KEY_CHAT_MEMORY_COUNT, Int.MAX_VALUE) // Default to All messages
    }
    fun saveDisableWebSearchAfterSend(enabled: Boolean) {
        mainPrefs.edit { putBoolean(KEY_DISABLE_WEB_SEARCH_AFTER_SEND, enabled) }
    }

    fun getDisableWebSearchAfterSend(): Boolean {
        // Default to TRUE to preserve current behavior for existing users
        return mainPrefs.getBoolean(KEY_DISABLE_WEB_SEARCH_AFTER_SEND, true)
    }
    fun getReasoningExclude(): Boolean = mainPrefs.getBoolean("reasoning_exclude", true)  // Default to true (exclude)
    fun saveReasoningExclude(exclude: Boolean) = mainPrefs.edit {
        putBoolean(
            "reasoning_exclude",
            exclude
        )
    }

    fun getReasoningMaxTokens(): Int? = mainPrefs.getInt("reasoning_max_tokens", -1).takeIf { it != -1 }
    fun saveReasoningMaxTokens(tokens: Int?) = mainPrefs.edit {
        putInt(
            "reasoning_max_tokens",
            tokens ?: -1
        )
    }
    private fun migrateDefaultSystemMessage() {
        val oldJson = mainPrefs.getString(KEY_DEFAULT_SYSTEM_MESSAGE, null)
        if (oldJson != null) {
            val oldMessage: SystemMessage = gson.fromJson(oldJson, SystemMessage::class.java)
            saveDefaultSystemMessage(oldMessage)
        }
    }
    fun saveFontSize(size: Int) {
        mainPrefs.edit { putInt(KEY_FONT_SIZE, size) }
    }

    fun getFontSize(): Int {
        return mainPrefs.getInt(KEY_FONT_SIZE, 100)  // Default: 100%
    }
    fun getVolumeScrollEnabled(): Boolean = mainPrefs.getBoolean(KEY_VOLUME_SCROLL, false)

    fun saveVolumeScrollEnabled(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_VOLUME_SCROLL, enabled) }
    fun saveSortOrder(sortOrder: SortOrder) {
        mainPrefs.edit { putString(KEY_SORT_ORDER, sortOrder.name) }
    }
    fun getTimeoutMinutes(): Int = mainPrefs.getInt(KEY_TIMEOUT_MINUTES, 5)
    fun saveToolsPreference(isEnabled: Boolean) {
        mainPrefs.edit {
            putBoolean(KEY_TOOLS_ENABLED, isEnabled)
        }
    }

    fun getToolsPreference(): Boolean {
        return mainPrefs.getBoolean(KEY_TOOLS_ENABLED, false)
    }
    fun getEnabledTools(): Set<String> {
        val jsonStr = mainPrefs.getString(KEY_ENABLED_TOOLS, null)
        return if (jsonStr != null && jsonStr.isNotEmpty()) {
            try {
                json.decodeFromString<Set<String>>(jsonStr)
            } catch (e: Exception) {
                emptySet()
            }
        } else {
            emptySet() // First run: no tools enabled until user opts in
        }
    }
    fun hasEnabledToolsStored(): Boolean {
        return mainPrefs.contains(KEY_ENABLED_TOOLS)
    }
    fun saveEnabledTools(tools: Set<String>) {
        try {
            putStoredJson(KEY_ENABLED_TOOLS, tools)
        } catch (e: Exception) {
            Log.e("SharedPrefs", "Failed to save enabled tools", e)
        }
    }
    fun saveSafFolderUri(uri: String) {
        mainPrefs.edit { putString(SAF_FOLDER_URI, uri) }
    }

    fun getSafFolderUri(): String? {
        return mainPrefs.getString(SAF_FOLDER_URI, null)
    }

    /** True when the saved tree URI still has read access. */
    fun hasWorkspaceGrant(): Boolean {
        val uriString = getSafFolderUri() ?: return false
        val treeUri = uriString.toUri()
        return appContext.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }
    }
    fun saveTimeoutMinutes(minutes: Int) {
        mainPrefs.edit { putInt(KEY_TIMEOUT_MINUTES, minutes) }
    }
    fun getScrollProgressEnabled(): Boolean = mainPrefs.getBoolean(KEY_SCROLL_PROGRESS_ENABLED, false)  // Off: a full-width rule under the tabs reads as a glitch
    fun saveScrollProgressEnabled(enabled: Boolean) = mainPrefs.edit {
        putBoolean(KEY_SCROLL_PROGRESS_ENABLED, enabled)
    }
    fun getSortOrder(): SortOrder {
        val sortOrderName = mainPrefs.getString(KEY_SORT_ORDER, SortOrder.ALPHABETICAL.name)
        return SortOrder.entries.firstOrNull { it.name == sortOrderName } ?: SortOrder.ALPHABETICAL
    }
    fun getUseCopyButton2(): Boolean {
        return mainPrefs.getBoolean(KEY_USE_COPY_BUTTON2, false)  // false = Dismiss, true = Copy
    }
    fun saveUseCopyButton2(useCopy: Boolean) {
        mainPrefs.edit { putBoolean(KEY_USE_COPY_BUTTON2, useCopy) }
    }
    fun getAutoBack(): Boolean {
        return mainPrefs.getBoolean(KEY_AUTO_BACK, false)
    }

    /** Stashed alternate message tree for one-chat forks (JSON list of FlexibleMessage). */
    fun saveChatFork(sessionId: Long, forkIndex: Int, anchorIndex: Int, messagesJson: String) {
        mainPrefs.edit {
            putInt("$KEY_CHAT_FORK_INDEX_PREFIX$sessionId", forkIndex)
            putInt("$KEY_CHAT_FORK_ANCHOR_PREFIX$sessionId", anchorIndex)
            putString("$KEY_CHAT_FORK_PREFIX$sessionId", messagesJson)
        }
    }

    fun getChatForkIndex(sessionId: Long): Int =
        mainPrefs.getInt("$KEY_CHAT_FORK_INDEX_PREFIX$sessionId", -1)

    fun getChatForkAnchor(sessionId: Long): Int =
        mainPrefs.getInt("$KEY_CHAT_FORK_ANCHOR_PREFIX$sessionId", -1)

    fun getChatForkMessagesJson(sessionId: Long): String? =
        mainPrefs.getString("$KEY_CHAT_FORK_PREFIX$sessionId", null)

    fun clearChatFork(sessionId: Long) {
        mainPrefs.edit {
            remove("$KEY_CHAT_FORK_INDEX_PREFIX$sessionId")
            remove("$KEY_CHAT_FORK_ANCHOR_PREFIX$sessionId")
            remove("$KEY_CHAT_FORK_PREFIX$sessionId")
        }
    }

    /**
     * Everything kept in prefs for one chat: its fork, swipe alternates and memory facts. Call it
     * when the chat is deleted, and when a new chat is given an id, so a recycled id never
     * inherits another chat's leftovers.
     */
    fun clearSessionPrefs(sessionId: Long) {
        mainPrefs.edit {
            remove("$KEY_CHAT_FORK_INDEX_PREFIX$sessionId")
            remove("$KEY_CHAT_FORK_ANCHOR_PREFIX$sessionId")
            remove("$KEY_CHAT_FORK_PREFIX$sessionId")
            remove("$KEY_RP_SWIPE_PREFIX$sessionId")
            remove("rp_facts_$sessionId")
        }
    }

    fun saveExpandableInput(enabled: Boolean) {
        mainPrefs.edit { putBoolean(KEY_EXPANDABLE_INPUT, enabled) }
    }

    fun getExpandableInput(): Boolean {
        // Defaulting to FALSE or TRUE based on what you prefer.
        // I set it to false so it's opt-in, change to true if you want it on by default.
        return mainPrefs.getBoolean(KEY_EXPANDABLE_INPUT, false)
    }

    fun saveOpenRouterModels(models: List<LlmModel>) {
        putStoredJson(KEY_OPEN_ROUTER_MODELS, models)
    }

    fun getOpenRouterModels(): List<LlmModel> {
        val jsonString = mainPrefs.getString(KEY_OPEN_ROUTER_MODELS, null)
        return if (jsonString != null) {
            decodeOr(KEY_OPEN_ROUTER_MODELS, jsonString) { emptyList<LlmModel>() }
        } else {
            emptyList()
        }
    }
    fun getAnimateBarOnError(): Boolean {
        return mainPrefs.getBoolean(KEY_ANIMATE_BAR_ON_ERROR, false)
    }

    fun saveAnimateBarOnError(enabled: Boolean) {
        mainPrefs.edit { putBoolean(KEY_ANIMATE_BAR_ON_ERROR, enabled) }
    }
    fun saveConversationModeEnabled(isEnabled: Boolean) {
        mainPrefs.edit { putBoolean(KEY_CONVERSATION_MODE_ENABLED, isEnabled) }
    }

    fun getConversationModeEnabled(): Boolean {
        return mainPrefs.getBoolean(KEY_CONVERSATION_MODE_ENABLED, false)
    }
    fun saveStreamingPreference(isEnabled: Boolean) {
        mainPrefs.edit {
            putBoolean(KEY_STREAMING_ENABLED, isEnabled)
        }
    }

    fun getStreamingPreference(): Boolean {
        // Default ON: OpenRouter / OpenAI-compatible SSE streaming
        return mainPrefs.getBoolean(KEY_STREAMING_ENABLED, true)
    }
    fun getReasoningPreference(): Boolean {
        return mainPrefs.getBoolean(KEY_REASONING_ENABLED, false)
    }
    fun saveReasoningPreference(isEnabled: Boolean) {
        mainPrefs.edit {
            putBoolean(KEY_REASONING_ENABLED, isEnabled)
        }
    }
    fun saveSelectedFont(fontName: String) {
        mainPrefs.edit { putString(KEY_SELECTED_FONT, fontName) }
    }
    fun getWebSearchBoolean(): Boolean {
        return mainPrefs.getBoolean(KEY_WEB_SEARCH_ENABLED, false)
    }
    fun saveLastAiResponseForChannel(channelId: Int, responseText: String) {
        mainPrefs.edit { putString("${KEY_LAST_AI_RESPONSE_CHANNEL}${channelId}", responseText) }
    }

    fun getLastAiResponseForChannel(channelId: Int): String? {
        return mainPrefs.getString("${KEY_LAST_AI_RESPONSE_CHANNEL}${channelId}", null)
    }
    fun saveWebSearchEnabled(enabled: Boolean) {
        mainPrefs.edit { putBoolean(KEY_WEB_SEARCH_ENABLED, enabled) }
    }
    fun getWebSearchEngine(): String = mainPrefs.getString(KEY_WEB_SEARCH_ENGINE, "default") ?: "default"

    fun saveWebSearchEngine(engine: String) = mainPrefs.edit {
        putString(
            KEY_WEB_SEARCH_ENGINE,
            engine
        )
    }
    fun getWebSearchContextSize(): String = mainPrefs.getString("KEY_WEB_SEARCH_CONTEXT_SIZE", "medium") ?: "medium"

    fun saveWebSearchContextSize(size: String) = mainPrefs.edit {
        putString("KEY_WEB_SEARCH_CONTEXT_SIZE", size)
    }
    fun getWebSearchMaxResults(): Int = mainPrefs.getInt("KEY_WEB_SEARCH_MAX_RESULTS", 5)

    fun saveWebSearchMaxResults(results: Int) = mainPrefs.edit {
        putInt("KEY_WEB_SEARCH_MAX_RESULTS", results)
    }
    fun getThemeMode(): Int {
        return mainPrefs.getInt(
            KEY_THEME_MODE,
            THEME_DARK
        ) // Default to Dark (Grok is dark-only)
    }

    fun saveThemeMode(mode: Int) {
        mainPrefs.edit { putInt(KEY_THEME_MODE, mode) }
    }

    /** Off by default. */
    fun isRoleplayEnabled(): Boolean = mainPrefs.getBoolean(KEY_ROLEPLAY_ENABLED, false)

    fun setRoleplayEnabled(enabled: Boolean) {
        mainPrefs.edit { putBoolean(KEY_ROLEPLAY_ENABLED, enabled) }
    }

    fun getBackgroundStyle(): String = mainPrefs.getString(KEY_BACKGROUND_STYLE, "off") ?: "off"

    fun saveBackgroundStyle(key: String) {
        mainPrefs.edit { putString(KEY_BACKGROUND_STYLE, key) }
    }

    /** Empty-chat icon. Unknown values read as liquid, the shipped default. */
    fun getChatMarkStyle(): String {
        val saved = mainPrefs.getString(KEY_CHAT_MARK, CHAT_MARK_LIQUID) ?: CHAT_MARK_LIQUID
        return if (saved == CHAT_MARK_OFF || saved == CHAT_MARK_PLAIN) saved else CHAT_MARK_LIQUID
    }

    fun saveChatMarkStyle(style: String) {
        mainPrefs.edit { putString(KEY_CHAT_MARK, style) }
    }
    fun hasMigratedMaverick(): Boolean {
        return mainPrefs.getBoolean("migrated_maverick_to_openrouter", false)
    }

    fun setMigratedMaverick() {
        mainPrefs.edit(commit = true) {
            putBoolean("migrated_maverick_to_openrouter", true)
        }
    }
    fun saveExtendedTopBarEnabled(enabled: Boolean) {
        mainPrefs.edit { putBoolean(KEY_EXTENDED_TOP_BAR, enabled) }
    }
    fun getExtendedTopBarEnabled(): Boolean {
        return mainPrefs.getBoolean(KEY_EXTENDED_TOP_BAR, false)
    }
    fun getSelectedFont(): String {
        var stored = mainPrefs.getString(KEY_SELECTED_FONT, AppFonts.JAKARTA) ?: AppFonts.JAKARTA
        // One-time move to the new app face: Inter was the old default, not a choice.
        if (!mainPrefs.getBoolean(KEY_FONT_FACE_MIGRATED, false)) {
            if (stored == AppFonts.INTER) stored = AppFonts.JAKARTA
            mainPrefs.edit {
                putString(KEY_SELECTED_FONT, stored)
                putBoolean(KEY_FONT_FACE_MIGRATED, true)
            }
        }
        return AppFonts.normalizeSelectable(stored)
    }
    fun saveFontSizeCh(size: Int) {
        mainPrefs.edit { putInt(KEY_FONT_SIZEC, size) }
    }

    fun getFontSizeCh(): Int {
        return mainPrefs.getInt(KEY_FONT_SIZEC, 100)  // Default: 100%
    }
    fun getNotiPreference(): Boolean {
        return mainPrefs.getBoolean(KEY_NOTI_ENABLED, true)
    }

    fun saveNotiPreference(isEnabled: Boolean) {
        mainPrefs.edit {
            putBoolean(KEY_NOTI_ENABLED, isEnabled)
        }
    }
    fun getExtPreference(): Boolean {
        return mainPrefs.getBoolean(KEY_EXT_ENABLED, false)
    }

    fun saveExtPreference(isEnabled: Boolean) {
        mainPrefs.edit {
            putBoolean(KEY_EXT_ENABLED, isEnabled)
        }
    }
    fun saveExtPreference2(isEnabled: Boolean) {
        mainPrefs.edit {
            putBoolean(KEY_EXT_ENABLED2, isEnabled)
        }
    }
    fun getExtPreference2(): Boolean {
        return mainPrefs.getBoolean(KEY_EXT_ENABLED2, false)
    }
    fun saveKeepScreenOnPreference(enabled: Boolean) {
        mainPrefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, enabled) }
    }

    fun getKeepScreenOnPreference(): Boolean {
        return mainPrefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
    }
    fun saveBotModelPickerSortOrder(sortOrder: BotModelPickerFragment.SortOrder) {
        val value = when (sortOrder) {
            BotModelPickerFragment.SortOrder.ALPHABETICAL -> "alphabetical"
            BotModelPickerFragment.SortOrder.BY_DATE -> "by_date"
        }
        mainPrefs.edit { putString("bot_model_picker_sort_order", value) }
    }

    fun getBotModelPickerSortOrder(): BotModelPickerFragment.SortOrder {
        val value = mainPrefs.getString("bot_model_picker_sort_order", "alphabetical")  // Default to alphabetical
        return when (value) {
            "by_date" -> BotModelPickerFragment.SortOrder.BY_DATE
            else -> BotModelPickerFragment.SortOrder.ALPHABETICAL
        }
    }
    // --- OpenRouterModelsFragment Filters ---
    fun saveOpenRouterFilterType(type: String) = mainPrefs.edit { putString("open_router_filter_type", type) }
    fun getOpenRouterFilterType(): String = mainPrefs.getString("open_router_filter_type", "ALL") ?: "ALL"

    fun saveOpenRouterCostFilter(cost: String) = mainPrefs.edit { putString("open_router_cost_filter", cost) }
    fun getOpenRouterCostFilter(): String = mainPrefs.getString("open_router_cost_filter", "ALL") ?: "ALL"

    // --- BotModelPickerFragment Filters ---
    fun saveBotPickerFilterType(type: String) = mainPrefs.edit { putString("bot_picker_filter_type", type) }
    fun getBotPickerFilterType(): String = mainPrefs.getString("bot_picker_filter_type", "ALL") ?: "ALL"

    fun saveBotPickerCostFilter(cost: String) = mainPrefs.edit { putString("bot_picker_cost_filter", cost) }
    fun getBotPickerCostFilter(): String = mainPrefs.getString("bot_picker_cost_filter", "ALL") ?: "ALL"

    fun getGeminiAspectRatio(): String? = mainPrefs.getString("gemini_aspect_ratio", null)

    fun saveGeminiAspectRatio(ratio: String) {
        mainPrefs.edit { putString("gemini_aspect_ratio", ratio) }
    }
    // --- API Key Management ---

    fun saveApiKey(alias: String, apiKey: String): Boolean {
        // Keep the old key until the new one proves it decrypts, so a failed save is not a lost key.
        val oldEncrypted = apiKeysPrefs.getString("${alias}_encrypted", null)
        val oldIv = apiKeysPrefs.getString("${alias}_iv", null)
        fun restoreOld() = apiKeysPrefs.edit {
            if (oldEncrypted != null && oldIv != null) {
                putString("${alias}_encrypted", oldEncrypted)
                putString("${alias}_iv", oldIv)
            } else {
                remove("${alias}_encrypted")
                remove("${alias}_iv")
            }
        }
        return try {
            val secretKey = getOrCreateSecretKey(alias)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val encryptedApiKey = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

            val ivString = Base64.encodeToString(iv, Base64.DEFAULT)
            val encryptedKeyString = Base64.encodeToString(encryptedApiKey, Base64.DEFAULT)

            apiKeysPrefs.edit {
                putString("${alias}_encrypted", encryptedKeyString)
                putString("${alias}_iv", ivString)
            }

            val roundTrip = getApiKeyFromPrefs(alias)
            if (roundTrip != apiKey) {
                Log.e("API_KEY_STORAGE", "Round-trip verification failed for $alias")
                restoreOld()
                return false
            }
            true
        } catch (e: Exception) {
            Log.e("API_KEY_STORAGE", "Error encrypting $alias", e)
            restoreOld()
            false
        }
    }

    /** 32-byte SQLCipher passphrase, wrapped by Keystore in ApiKeysPrefsStore. */
    fun getOrCreateChatDbPassphrase(): ByteArray {
        val existing = getApiKeyFromPrefs(CHAT_DB_PASSPHRASE_ALIAS).trim()
        if (existing.isNotBlank()) return decodeChatDbPassphrase(existing)
        // Encrypted prefs present but decrypt failed — do not mint a new key
        // (that would brick an existing SQLCipher DB).
        val hasWrapped = !apiKeysPrefs.getString("${CHAT_DB_PASSPHRASE_ALIAS}_encrypted", null)
            .isNullOrBlank()
        if (hasWrapped) {
            throw IllegalStateException(
                "Chat DB passphrase is stored but could not be decrypted from Keystore"
            )
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (!saveApiKey(CHAT_DB_PASSPHRASE_ALIAS, encoded)) {
            throw IllegalStateException("Failed to persist chat DB passphrase in Keystore")
        }
        return bytes
    }

    /**
     * Copies the wrapped chat-database passphrase under [stamp] before recovery replaces the
     * active one. The set-aside file can only be opened with this blob; deleting the active copy
     * used to make the backup permanently unreadable.
     * Returns false when there was nothing to copy.
     */
    fun archiveChatDbPassphrase(stamp: Long): Boolean {
        val encrypted = apiKeysPrefs.getString("${CHAT_DB_PASSPHRASE_ALIAS}_encrypted", null)
        val iv = apiKeysPrefs.getString("${CHAT_DB_PASSPHRASE_ALIAS}_iv", null)
        if (encrypted.isNullOrBlank() && iv.isNullOrBlank()) return false
        val prefix = chatDbPassphraseArchivePrefix(stamp)
        apiKeysPrefs.edit(commit = true) {
            if (!encrypted.isNullOrBlank()) putString("${prefix}_encrypted", encrypted)
            if (!iv.isNullOrBlank()) putString("${prefix}_iv", iv)
        }
        return true
    }

    /**
     * Last resort when the chat database cannot be opened with the stored key (Keystore wiped or
     * restored from a backup): forget the active wrapped passphrase and mint a new one. The caller
     * has already archived the old blob and set the old database aside; nothing here deletes either.
     */
    fun resetChatDbPassphrase(): ByteArray {
        discardActiveChatDbPassphrase()
        return getOrCreateChatDbPassphrase()
    }

    /** Drops the active wrapped passphrase. Archived copies (see [archiveChatDbPassphrase]) stay. */
    internal fun discardActiveChatDbPassphrase() {
        apiKeysPrefs.edit(commit = true) {
            remove("${CHAT_DB_PASSPHRASE_ALIAS}_encrypted")
            remove("${CHAT_DB_PASSPHRASE_ALIAS}_iv")
        }
    }
    fun getShowCitations(): Boolean = mainPrefs.getBoolean(KEY_SHOW_CITATIONS, true)  // Default true (show citations)

    fun saveShowCitations(show: Boolean) = mainPrefs.edit { putBoolean(KEY_SHOW_CITATIONS, show) }
    fun getApiKeyFromPrefs(alias: String): String {
        val encryptedKeyString = apiKeysPrefs.getString("${alias}_encrypted", "")?.trim().orEmpty()
        val ivString = apiKeysPrefs.getString("${alias}_iv", "")?.trim().orEmpty()

        return if (encryptedKeyString.isBlank() || ivString.isBlank()) {
            ""
        } else {
            try {
                val encryptedKey = Base64.decode(encryptedKeyString, Base64.DEFAULT)
                val iv = Base64.decode(ivString, Base64.DEFAULT)
                decryptApiKey(alias, iv, encryptedKey)
            } catch (e: Exception) {
                //    Log.e("API_KEY_RETRIEVAL", "Error decrypting $alias", e)
                ""
            }
        }
    }

    private fun getOrCreateSecretKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        return if (keyStore.containsAlias(alias)) {
            keyStore.getKey(alias, null) as SecretKey
        } else {
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }

    private fun decryptApiKey(alias: String, iv: ByteArray, encryptedData: ByteArray): String {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val secretKey = keyStore.getKey(alias, null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedData = cipher.doFinal(encryptedData)
            return String(decryptedData, Charsets.UTF_8)
        } catch (e: Exception) {
            //  Log.e("API_KEY_DECRYPTION", "Error decrypting $alias", e)
            return ""
        }
    }

    // --- Model Preferences ---

    fun savePreferenceModelnewchat(value: String) {
        mainPrefs.edit(commit = true) {
            putString(KEY_MODEL_NEW_CHAT, value)
        }
    }

    fun getPreferenceModelnew(): String {
        return mainPrefs.getString(KEY_MODEL_NEW_CHAT, "openrouter/free").toString()
    }
    fun getScrollersPreference(): Boolean {
        return mainPrefs.getBoolean(KEY_SCROLLERS_ENABLED, false)
    }
    fun saveScrollersPreference(isEnabled: Boolean) {
        mainPrefs.edit {
            putBoolean(KEY_SCROLLERS_ENABLED, isEnabled)
        }
    }
    fun saveMaxTokens(value: String) {
        mainPrefs.edit(commit = true) {
            putString(KEY_MAX_TOKENS, value)
        }
    }

    fun getMaxTokens(): String {
        return mainPrefs.getString(KEY_MAX_TOKENS, "12000").toString()
    }
    fun getCustomModels(): MutableList<LlmModel> {
        val jsonString = mainPrefs.getString(KEY_CUSTOM_MODELS, null)
        return if (jsonString != null) {
            decodeOr(KEY_CUSTOM_MODELS, jsonString) { mutableListOf<LlmModel>() }
        } else {
            mutableListOf()
        }
    }

    fun saveCustomModels(models: List<LlmModel>) {
        putStoredJson(KEY_CUSTOM_MODELS, models)
    }

    /**
     * New installs start with just the demo model (plus the built-in free router); people pick
     * their own from the catalogs. Installs seeded with the old fourteen defaults lose the ones
     * left exactly as seeded, except the model in use.
     */
    fun seedDefaultModelsIfNeeded() {
        if (customModelsUnreadable()) {
            Log.w("SharedPrefs", "Leaving unreadable $KEY_CUSTOM_MODELS in place")
            return
        }
        ensureDemoModel()
        if (mainPrefs.getBoolean(KEY_DEFAULT_MODELS_SEEDED, false) && !mainPrefs.getBoolean(KEY_OLD_DEFAULTS_PRUNED, false)) {
            val active = getPreferenceModelnew()
            val old = OLD_DEFAULT_MODELS.toSet()
            val kept = getCustomModels().filterNot {
                (it.displayName to it.apiIdentifier) in old && it.apiIdentifier != active
            }
            saveCustomModels(kept)
        }
        mainPrefs.edit {
            putBoolean(KEY_DEFAULT_MODELS_SEEDED, true)
            putBoolean(KEY_OLD_DEFAULTS_PRUNED, true)
        }
    }

    /** The demo character went into the roleplay library once ([DemoCharacter]). */
    fun isDemoCharacterSeeded(): Boolean = mainPrefs.getBoolean(KEY_DEMO_CHARACTER_SEEDED, false)
    fun markDemoCharacterSeeded() = mainPrefs.edit { putBoolean(KEY_DEMO_CHARACTER_SEEDED, true) }

    /** Packaged Vesna avatar revision already written ([DemoCharacter.STOCK_AVATAR_REVISION]). */
    fun demoCharacterAvatarRevision(): Int = mainPrefs.getInt(KEY_DEMO_CHARACTER_AVATAR_REV, 0)
    fun setDemoCharacterAvatarRevision(revision: Int) =
        mainPrefs.edit { putInt(KEY_DEMO_CHARACTER_AVATAR_REV, revision) }

    /** The built-in demo model is always in the list (first), for new and existing installs. */
    private fun ensureDemoModel() {
        val models = getCustomModels()
        if (models.none { DemoModel.isDemo(it.apiIdentifier) }) {
            models.add(0, DemoModel.model())
            saveCustomModels(models)
        }
    }

    // --- System Message Preferences ---

    fun saveSelectedSystemMessage(systemMessage: SystemMessage) {
        putStoredJson(KEY_SELECTED_SYSTEM_MESSAGE, systemMessage)
    }

    fun getSelectedSystemMessage(): SystemMessage {
        val jsonString = mainPrefs.getString(KEY_SELECTED_SYSTEM_MESSAGE, null)
        return if (jsonString != null) {
            decodeOr(KEY_SELECTED_SYSTEM_MESSAGE, jsonString) { getDefaultSystemMessage() }
        } else {
            // Return the saved default message instead of creating a new instance
            getDefaultSystemMessage()
        }
    }

    fun getCustomSystemMessages(): List<SystemMessage> {
        val jsonString = mainPrefs.getString(KEY_CUSTOM_SYSTEM_MESSAGES, null)
        return if (jsonString != null) {
            decodeOr(KEY_CUSTOM_SYSTEM_MESSAGES, jsonString) { emptyList<SystemMessage>() }
        } else {
            emptyList()
        }
    }

    fun saveCustomSystemMessages(systemMessages: List<SystemMessage>) {
        putStoredJson(KEY_CUSTOM_SYSTEM_MESSAGES, systemMessages)
    }

    fun seedDefaultSystemMessagesIfNeeded() {
        if (storedJsonUnreadable<List<SystemMessage>>(KEY_CUSTOM_SYSTEM_MESSAGES)) {
            Log.w("SharedPrefs", "Leaving unreadable $KEY_CUSTOM_SYSTEM_MESSAGES in place")
            return
        }
        if (!mainPrefs.getBoolean(KEY_DEFAULT_SYSTEM_MESSAGES_SEEDED, false)) {
            val defaultSystemMessages = listOf(
                SystemMessage("Spelling Corrector", "Correct the spelling and grammar of the following text. Only provide the corrected text, without any additional commentary or explanation.", isDefault = false),
                SystemMessage("Summarizer", "Summarize the following text. Provide a concise summary, without any additional commentary or explanation. Markdown rendering is supported in your response", isDefault = false)
            )
            val customSystemMessages = getCustomSystemMessages().toMutableList()
            customSystemMessages.addAll(defaultSystemMessages)
            saveCustomSystemMessages(customSystemMessages)
            mainPrefs.edit { putBoolean(KEY_DEFAULT_SYSTEM_MESSAGES_SEEDED, true) }
        }
    }

    fun getDefaultSystemMessage(): SystemMessage {
        val jsonString = mainPrefs.getString(KEY_DEFAULT_SYSTEM_MESSAGE, null)
        return if (jsonString != null) {
            decodeOr(KEY_DEFAULT_SYSTEM_MESSAGE, jsonString) { builtInDefaultSystemMessage() }
        } else {
            builtInDefaultSystemMessage()
        }
    }

    private fun builtInDefaultSystemMessage() =
        SystemMessage("Default", "You are a helpful assistant. Markdown rendering is supported in your response", isDefault = true)

    // Add this method to save the default system message
    fun saveDefaultSystemMessage(systemMessage: SystemMessage) {
        putStoredJson(KEY_DEFAULT_SYSTEM_MESSAGE, systemMessage)
    }
    fun savePresets(presets: List<Preset>) {
        putStoredJson(KEY_PRESETS, presets)
    }

    fun getPresets(): List<Preset> {
        val jsonString = mainPrefs.getString(KEY_PRESETS, null)
        return if (jsonString != null) {
            try {
                json.decodeFromString(jsonString)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    fun setLanEndpoint(url: String?) {
        // Removing the entry is handy for “clear” / “reset” actions
        mainPrefs.edit {
            if (url.isNullOrBlank()) remove(KEY_LAN_ENDPOINT)
            else putString(KEY_LAN_ENDPOINT, url)
        }
    }

    /** @return false if [apiKey] non-blank but Keystore encrypt failed */
    fun setLanApiKey(apiKey: String?): Boolean {
        if (apiKey.isNullOrBlank()) {
            apiKeysPrefs.edit {
                remove("${LAN_API_KEY_ALIAS}_encrypted")
                remove("${LAN_API_KEY_ALIAS}_iv")
            }
            return true
        }
        return saveApiKey(LAN_API_KEY_ALIAS, apiKey)
    }

    fun getLanApiKey(): String {
        return getApiKeyFromPrefs(LAN_API_KEY_ALIAS)
    }

    /** Placeholder used only at request time when no LAN key is configured. */
    fun getLanApiKeyForRequest(): String {
        val key = getLanApiKey()
        return if (key.isBlank()) "any-non-empty-string" else key
    }
    fun getLanEndpoint(): String? {
        // May be null if the user never set a value
        return mainPrefs.getString(KEY_LAN_ENDPOINT, null)
    }
    fun getLanProvider(): String {
        return mainPrefs.getString(LAN_PROVIDER_KEY, LAN_PROVIDER_OLLAMA) ?: LAN_PROVIDER_OLLAMA
    }
    fun setLanProvider(provider: String) {
        mainPrefs.edit { putString(LAN_PROVIDER_KEY, provider) }
    }
    fun saveClearChatDefault(checked: Boolean) {
        mainPrefs.edit { putBoolean(KEY_CLEAR_CHAT_DEFAULT, checked) }
    }

    fun getClearChatDefault(): Boolean {
        return mainPrefs.getBoolean(KEY_CLEAR_CHAT_DEFAULT, false)  // Default to unchecked (false)
    }
    fun saveClearChatDefault2(checked: Boolean) {
        mainPrefs.edit { putBoolean(KEY_CLEAR_CHAT_DEFAULT2, checked) }
    }

    fun getClearChatDefault2(): Boolean {
        return mainPrefs.getBoolean(KEY_CLEAR_CHAT_DEFAULT2, false)  // Default to unchecked (false)
    }

    // --- GradatiON RP ---

    fun getChatMode(): ChatMode = ChatMode.fromStorage(mainPrefs.getString(KEY_CHAT_MODE, ChatMode.ASK.storageValue))
    fun saveChatMode(mode: ChatMode) = mainPrefs.edit { putString(KEY_CHAT_MODE, mode.storageValue) }

    fun getRpActiveCharacterId(): Long? {
        val id = mainPrefs.getLong(KEY_RP_ACTIVE_CHARACTER_ID, -1L)
        return if (id < 0) null else id
    }

    fun saveRpActiveCharacterId(id: Long?) {
        mainPrefs.edit {
            if (id == null) remove(KEY_RP_ACTIVE_CHARACTER_ID)
            else putLong(KEY_RP_ACTIVE_CHARACTER_ID, id)
        }
    }

    fun getRpPersona(): String = mainPrefs.getString(KEY_RP_PERSONA, "") ?: ""
    /** Whether chats use your persona. Off keeps it saved but sends and shows nothing of it. */
    fun isRpPersonaEnabled(): Boolean = mainPrefs.getBoolean(KEY_RP_PERSONA_ENABLED, true)
    fun setRpPersonaEnabled(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_RP_PERSONA_ENABLED, enabled) }
    /** What a chat should use: the persona's description, or nothing while it is off. */
    fun activeRpPersona(): String = if (isRpPersonaEnabled()) getRpPersona() else ""
    /** What a chat should call you: the persona's name, or nothing while it is off. */
    fun activeRpPersonaName(): String = if (isRpPersonaEnabled()) getRpPersonaName() else ""
    fun saveRpPersona(persona: String) = mainPrefs.edit { putString(KEY_RP_PERSONA, persona) }
    fun saveRpPersonaName(name: String) = mainPrefs.edit { putString(KEY_RP_PERSONA_NAME, name.trim()) }
    /** Your portrait's file name in [RpAvatarStorage.personaFile]; null shows your initial. */
    fun getRpPersonaPhoto(): String? = mainPrefs.getString(KEY_RP_PERSONA_PHOTO, null)
    fun saveRpPersonaPhoto(photo: String?) = mainPrefs.edit {
        if (photo == null) remove(KEY_RP_PERSONA_PHOTO) else putString(KEY_RP_PERSONA_PHOTO, photo)
    }

    fun getRpPersonaPresets(): List<RpPersonaPreset> {
        val raw = mainPrefs.getString(KEY_RP_PERSONA_PRESETS, null) ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveRpPersonaPresets(presets: List<RpPersonaPreset>) {
        putStoredJson(KEY_RP_PERSONA_PRESETS, presets)
    }

    /** Per-character story memory (pinned facts), injected into every RP prompt. Null id = GradatiON (LLM mode). */
    fun getRpMemory(characterId: Long?): String =
        mainPrefs.getString(rpMemoryKey(characterId), "") ?: ""
    fun saveRpMemory(characterId: Long?, text: String) = mainPrefs.edit {
        if (text.isBlank()) remove(rpMemoryKey(characterId)) else putString(rpMemoryKey(characterId), text.trim())
    }
    private fun rpMemoryKey(characterId: Long?) = "rp_memory_" + (characterId?.toString() ?: "llm")

    /** Lorebook pinned to one character. Null means "use whichever book is active". */
    fun getRpLorebookId(characterId: Long): Long? {
        val id = mainPrefs.getLong("rp_lorebook_$characterId", -1L)
        return if (id < 0) null else id
    }
    fun saveRpLorebookId(characterId: Long, lorebookId: Long?) = mainPrefs.edit {
        if (lorebookId == null || lorebookId < 0) remove("rp_lorebook_$characterId")
        else putLong("rp_lorebook_$characterId", lorebookId)
    }

    /** RP chat layout per character: [RP_LAYOUT_CLASSIC], [RP_LAYOUT_BUBBLES] or [RP_LAYOUT_BOOK]. */
    fun getRpLayout(characterId: Long?): String =
        mainPrefs.getString("rp_layout_" + (characterId?.toString() ?: "llm"), RP_LAYOUT_CLASSIC) ?: RP_LAYOUT_CLASSIC
    fun saveRpLayout(characterId: Long?, layout: String) =
        mainPrefs.edit { putString("rp_layout_" + (characterId?.toString() ?: "llm"), layout) }

    /** Read-aloud voice per character: a system TTS voice name (null = engine default), pitch and speed. */
    data class RpVoice(val name: String?, val pitch: Float, val rate: Float)
    fun getRpVoice(characterId: Long?): RpVoice {
        val k = characterId?.toString() ?: "llm"
        return RpVoice(
            mainPrefs.getString("rp_voice_$k", null),
            mainPrefs.getFloat("rp_voice_pitch_$k", 1f),
            mainPrefs.getFloat("rp_voice_rate_$k", 1f)
        )
    }
    fun saveRpVoice(characterId: Long?, voice: RpVoice) = mainPrefs.edit {
        val k = characterId?.toString() ?: "llm"
        if (voice.name == null) remove("rp_voice_$k") else putString("rp_voice_$k", voice.name)
        putFloat("rp_voice_pitch_$k", voice.pitch)
        putFloat("rp_voice_rate_$k", voice.rate)
    }

    /**
     * Everything stored per character outside Room, for when the character is deleted. Ids are
     * never reused by Room's autoincrement, so these would otherwise sit there for good (the
     * wallpaper photo most visibly, as a file the user can no longer reach).
     */
    fun clearRpCharacterPrefs(characterId: Long) {
        mainPrefs.edit {
            remove(rpMemoryKey(characterId))
            remove("rp_layout_$characterId")
            remove("rp_voice_$characterId")
            remove("rp_voice_pitch_$characterId")
            remove("rp_voice_rate_$characterId")
            remove("rp_lorebook_$characterId")
        }
        BackgroundPhoto.delete(appContext, BackgroundPhoto.slotForCharacter(characterId))
    }

    /** Let the model keep each character's Memory up to date as long chats outgrow the API window. */
    fun isRpAutoMemory(): Boolean = mainPrefs.getBoolean("rp_auto_memory", true)
    fun saveRpAutoMemory(on: Boolean) = mainPrefs.edit { putBoolean("rp_auto_memory", on) }

    /** The name characters call you. Before it was its own field it came from a matching preset. */
    fun getRpPersonaName(): String {
        mainPrefs.getString(KEY_RP_PERSONA_NAME, null)?.let { return it }
        val persona = getRpPersona().trim()
        if (persona.isEmpty()) return ""
        return getRpPersonaPresets().firstOrNull { it.description.trim() == persona }?.name.orEmpty()
    }

    fun isRpLoreEnabled(): Boolean = mainPrefs.getBoolean(KEY_RP_LORE_ENABLED, true)
    fun saveRpLoreEnabled(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_RP_LORE_ENABLED, enabled) }

    fun isRpThirdPerson(): Boolean = mainPrefs.getBoolean(KEY_RP_THIRD_PERSON, false)
    fun saveRpThirdPerson(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_RP_THIRD_PERSON, enabled) }

    fun isRpShowThoughts(): Boolean = mainPrefs.getBoolean(KEY_RP_SHOW_THOUGHTS, false)
    fun saveRpShowThoughts(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_RP_SHOW_THOUGHTS, enabled) }

    /** Chat shows the model's thinking as a folded block above each reply (the Thoughts tile). */
    fun isShowThinkingBlocks(): Boolean = mainPrefs.getBoolean(KEY_SHOW_THINKING_BLOCKS, true)
    fun saveShowThinkingBlocks(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_SHOW_THINKING_BLOCKS, enabled) }

    /**
     * The chat lost its web search toggle; switch search off once so nobody is left paying for
     * searches they can no longer see or turn off. Presets can still turn it on.
     */
    fun retireWebSearchToggleOnce() {
        if (mainPrefs.getBoolean(KEY_WEB_SEARCH_RETIRED, false)) return
        mainPrefs.edit {
            putBoolean(KEY_WEB_SEARCH_RETIRED, true)
            putBoolean(KEY_WEB_SEARCH_ENABLED, false)
        }
    }

    fun isRpLlmMode(): Boolean = mainPrefs.getBoolean(KEY_RP_LLM_MODE, false)
    fun saveRpLlmMode(enabled: Boolean) = mainPrefs.edit { putBoolean(KEY_RP_LLM_MODE, enabled) }

    fun getRpDraftSessionId(mode: ChatMode): Long? {
        val key = if (mode == ChatMode.ASK) KEY_RP_DRAFT_SESSION_ASK else KEY_RP_DRAFT_SESSION_RP
        val id = mainPrefs.getLong(key, -1L)
        return if (id < 0) null else id
    }

    fun saveRpDraftSessionId(mode: ChatMode, sessionId: Long?) {
        val key = if (mode == ChatMode.ASK) KEY_RP_DRAFT_SESSION_ASK else KEY_RP_DRAFT_SESSION_RP
        mainPrefs.edit {
            if (sessionId == null) remove(key) else putLong(key, sessionId)
        }
    }

    fun getComposerDraft(mode: ChatMode): String {
        val key = if (mode == ChatMode.ASK) KEY_COMPOSER_DRAFT_ASK else KEY_COMPOSER_DRAFT_RP
        return mainPrefs.getString(key, "") ?: ""
    }

    fun saveComposerDraft(mode: ChatMode, text: String) {
        val key = if (mode == ChatMode.ASK) KEY_COMPOSER_DRAFT_ASK else KEY_COMPOSER_DRAFT_RP
        mainPrefs.edit { putString(key, text) }
    }

    fun getAskComposerDrafts(): Map<String, String> =
        ComposerDrafts.decode(mainPrefs.getString(KEY_ASK_COMPOSER_DRAFTS, "") ?: "")

    fun saveAskComposerDrafts(store: Map<String, String>) {
        mainPrefs.edit {
            if (store.isEmpty()) remove(KEY_ASK_COMPOSER_DRAFTS)
            else putString(KEY_ASK_COMPOSER_DRAFTS, ComposerDrafts.encode(store))
        }
    }

    fun getRpSwipeJson(sessionId: Long): String? =
        mainPrefs.getString("$KEY_RP_SWIPE_PREFIX$sessionId", null)

    fun saveRpSwipeJson(sessionId: Long, jsonText: String) {
        mainPrefs.edit { putString("$KEY_RP_SWIPE_PREFIX$sessionId", jsonText) }
    }

    fun clearRpSwipeJson(sessionId: Long) {
        mainPrefs.edit { remove("$KEY_RP_SWIPE_PREFIX$sessionId") }
    }

    /** Facts the model keeps for one chat. Separate from the Memory note the user wrote. */
    fun getRpFacts(sessionId: Long): String = mainPrefs.getString("rp_facts_$sessionId", "") ?: ""
    fun saveRpFacts(sessionId: Long, text: String) = mainPrefs.edit {
        if (text.isBlank()) remove("rp_facts_$sessionId") else putString("rp_facts_$sessionId", text.trim())
    }

    fun getRpPendingInstruct(): String? = mainPrefs.getString(KEY_RP_PENDING_INSTRUCT, null)
    fun saveRpPendingInstruct(text: String?) {
        mainPrefs.edit {
            if (text.isNullOrBlank()) remove(KEY_RP_PENDING_INSTRUCT) else putString(KEY_RP_PENDING_INSTRUCT, text)
        }
    }

    /** Remember a deleted character's exportKey → old Room id so re-import can rematch sessions. */
    fun rememberDeletedRpCharacter(exportKey: String, oldId: Long) {
        if (exportKey.isBlank() || oldId <= 0L) return
        val map = getDeletedRpCharacterRemap().toMutableMap()
        map[exportKey] = oldId
        putStoredJson<Map<String, Long>>(KEY_RP_DELETED_CHAR_REMAP, map)
    }

    fun takeDeletedRpCharacterId(exportKey: String): Long? {
        if (exportKey.isBlank()) return null
        // An unreadable map decodes as empty, so "removing" a key would replace it with {}.
        if (storedJsonUnreadable<Map<String, Long>>(KEY_RP_DELETED_CHAR_REMAP)) return null
        val map = getDeletedRpCharacterRemap().toMutableMap()
        val oldId = map.remove(exportKey) ?: return null
        putStoredJson<Map<String, Long>>(KEY_RP_DELETED_CHAR_REMAP, map)
        return oldId
    }

    private fun getDeletedRpCharacterRemap(): Map<String, Long> {
        val raw = mainPrefs.getString(KEY_RP_DELETED_CHAR_REMAP, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, Long>>(raw)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun string(@androidx.annotation.StringRes resId: Int): String = appContext.getString(resId)
    fun string(@androidx.annotation.StringRes resId: Int, vararg args: Any): String =
        appContext.getString(resId, *args)
}


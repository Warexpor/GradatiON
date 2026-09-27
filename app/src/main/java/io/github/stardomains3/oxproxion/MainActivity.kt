package io.github.stardomains3.oxproxion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import io.github.stardomains3.oxproxion.code.CodeAwayNotifier
import io.github.stardomains3.oxproxion.code.CodeHub
import io.github.stardomains3.oxproxion.code.CodeSessionPending
import io.github.stardomains3.oxproxion.code.CodePairPending
import io.github.stardomains3.oxproxion.code.CodePairing

class MainActivity : AppCompatActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedTheme = SharedPreferencesHelper(this).getThemeMode()
        val mode = when (savedTheme) {
            SharedPreferencesHelper.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SharedPreferencesHelper.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        super.onCreate(savedInstanceState)
        GlassQuality.init(this)
        GlassChrome.install(supportFragmentManager)

        /* ------------------------------------------------------ */
        /* 1.  Cold-start gate:  finish() if auth fails / none    */
        /* ------------------------------------------------------ */


        if (savedInstanceState == null && SharedPreferencesHelper(this).getBiometricEnabled()) {
            BiometricGateHelper.gateIfNeeded(this) {
                continueOnCreate()
            }
            return
        }

        continueOnCreate()
    }

    private fun continueOnCreate() {
        setContentView(R.layout.activity_main)
        askNotificationPermission()
        handleCodePairIntent(intent)
        handleCodeAwayIntent(intent)
        val sharedPreferencesHelper = SharedPreferencesHelper(this)
        sharedPreferencesHelper.seedDefaultModelsIfNeeded()
        sharedPreferencesHelper.seedDefaultSystemMessagesIfNeeded()
        if (!sharedPreferencesHelper.hasMigratedMaverick()) {
            // 1. Swap the active model if it was Maverick
            val currentSavedModel = sharedPreferencesHelper.getPreferenceModelnew()
            if (currentSavedModel == "meta-llama/llama-4-maverick") {
                sharedPreferencesHelper.savePreferenceModelnewchat("openrouter/free")
            }

            // 2. Scrub any old custom "openrouter/free" entries to prevent duplicates
            val customModels = sharedPreferencesHelper.getCustomModels()
            val initialSize = customModels.size

            // Remove any custom model matching the identifier (ignoring case just in case)
            customModels.removeAll { it.apiIdentifier.equals("openrouter/free", ignoreCase = true) }

            // If we actually deleted something, save the clean list back to SharedPreferences
            if (customModels.size < initialSize) {
                sharedPreferencesHelper.saveCustomModels(customModels)
            }

            // 3. Mark as complete so this never runs again
            sharedPreferencesHelper.setMigratedMaverick()
        }

        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            val chatFragment = ChatFragment().apply {
                arguments = Bundle().apply {
                    if (intent?.action == Intent.ACTION_SEND && "text/plain" == intent.type) {
                        putString("shared_text", intent.getStringExtra(Intent.EXTRA_TEXT))
                    }
                    if (intent?.action in listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)) {
                        // STT disabled
                        // putBoolean("start_stt_on_launch", true)
                    }
                }
            }
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, chatFragment, "ChatFragment")
                .commitNow()
        }
        // NEW: Auto‑apply "digital assistant" preset for assistant launches
        if (intent?.action in listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)) {
            val digitalAssistantPreset = findDigitalAssistantPreset()
            if (digitalAssistantPreset != null) {
                val vm: ChatViewModel by viewModels()
                PresetManager.applyPreset(this, vm, digitalAssistantPreset)
                vm.signalPresetApplied()
            }
        }
        val vm: ChatViewModel by viewModels()
        handlePresetIntent(intent)
        if (intent.getBooleanExtra("autosend", false)) {
            intent.getStringExtra("shared_text")?.let { text ->
                val clearChat = intent.getBooleanExtra("clear_chat", false)
                if (clearChat) vm.startFreshChatForCurrentMode()
                vm.consumeSharedTextautosend(text)
            }
        } else if (intent.getBooleanExtra("input_only", false)) {
            intent.getStringExtra("shared_text")?.let { text ->
                val clearChat = intent.getBooleanExtra("clear_chat", false)
                if (clearChat) vm.startFreshChatForCurrentMode()
                vm.consumeSharedText(text)
                val sharedPreferencesHelper = SharedPreferencesHelper(this)
                val systemMessageTitle = sharedPreferencesHelper.getSelectedSystemMessage().title
                AppToast.makeText(this, systemMessageTitle, AppToast.LENGTH_SHORT).show()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                    return
                }
                val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                val handled = (fragment as? ChatFragment)?.onBackPressed() ?: false
                if (!handled) {
                    moveTaskToBack(true)
                }
            }
        })
        // Answer-ready notifications only — no sticky "Running" FGS chrome
        ForegroundService.clearLegacyRunningNotification(this)
    }

    override fun onResume() {
        super.onResume()
        ForegroundService.clearLegacyRunningNotification(this)
    }
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment is OnKeyboardShortcutListener) {
            // We pass the event back into handleKeyDown, but the Fragment's 'isLongPress' check
            // will now be 'true' because the system successfully tracked it.
            if (currentFragment.handleKeyDown(keyCode, event)) {
                return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            event?.startTracking() // Required for isLongPress to work in the Fragment!
        }
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container) // Or however you get your current fragment

        if (currentFragment is OnKeyboardShortcutListener) {
            if (currentFragment.handleKeyDown(keyCode, event)) {
                return true // The fragment handled the shortcut
            }
        }

        // 2. If the fragment didn't handle it, or no fragment is active,
        //    handle it in the Activity (for global app shortcuts)


        // Let the system handle unhandled keys (e.g., volume, back button)
        return super.onKeyDown(keyCode, event)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {   // 33
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // on 31/32 the permission doesn’t exist, notifications are enabled by default
    }
    /*private fun startForegroundService() {
        if (ForegroundService.isRunningForeground) return  // Guard to prevent restarts
        try {
            val serviceIntent = Intent(this, ForegroundService::class.java)
            // Optionally pass initial title if needed
           // val vm: ChatViewModel by viewModels()
           // val displayName = vm.getModelDisplayName(vm.activeChatModel.value ?: "Unknown Model")
           // serviceIntent.putExtra("initial_title", displayName)
            startService(serviceIntent)
        } catch (e: Exception) {
           // Log.e("MainActivity", "Failed to start foreground service", e)
        }
    }*/

    /*private fun startForegroundService() {
        try {
            val serviceIntent = Intent(this, ForegroundService::class.java)
            startService(serviceIntent)
        } catch (e: Exception) {
            Log.e("ChatFragment", "Failed to start foreground service", e)
        }
    }*/

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCodePairIntent(intent)
        handleCodeAwayIntent(intent)
        if (intent.getBooleanExtra("from_notification", false)) {
            (supportFragmentManager.findFragmentById(R.id.fragment_container) as? ChatFragment)
                ?.onOpenedFromNotification()
        }
        handlePresetIntent(intent)

        if (intent.action == Intent.ACTION_SEND && "text/plain" == intent.type && !intent.getBooleanExtra("autosend", false)) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                val vm: ChatViewModel by viewModels()
                val sharedPreferencesHelper = SharedPreferencesHelper(this)
                vm.consumeSharedText(text)
                val systemMessageTitle = sharedPreferencesHelper.getSelectedSystemMessage().title
                AppToast.makeText(this, systemMessageTitle, AppToast.LENGTH_SHORT).show()
                //AppToast.makeText(this, "Text received", AppToast.LENGTH_LONG).show()
                //val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                //if (fragment is ChatFragment) {
                //    fragment.setSharedText(text)
                //  }
            }

        }

        /*  if (intent.getBooleanExtra("autosend", false)) {
              intent.getStringExtra("shared_text")?.let { text ->
                  val vm: ChatViewModel by viewModels()
                  val sharedPreferencesHelper = SharedPreferencesHelper(this)
                  val clearChat = intent.getBooleanExtra("clear_chat", false)
                  if (clearChat) {
                      vm.startNewChat()  // Clear the chat
                  }
                  vm.consumeSharedTextautosend(text)
                  val systemMessageTitle = sharedPreferencesHelper.getSelectedSystemMessage().title
                  AppToast.makeText(this, "System: $systemMessageTitle", AppToast.LENGTH_SHORT).show()
              }
          }*/
        val vm: ChatViewModel by viewModels()
        if (intent.getBooleanExtra("autosend", false)) {
            intent.getStringExtra("shared_text")?.let { text ->
                val clearChat = intent.getBooleanExtra("clear_chat", false)
                if (clearChat) vm.startFreshChatForCurrentMode()
                vm.consumeSharedTextautosend(text)
            }
        } else if (intent.getBooleanExtra("input_only", false)) {
            intent.getStringExtra("shared_text")?.let { text ->
                val clearChat = intent.getBooleanExtra("clear_chat", false)
                if (clearChat) vm.startFreshChatForCurrentMode()
                vm.consumeSharedText(text)
                val sharedPreferencesHelper = SharedPreferencesHelper(this)
                val systemMessageTitle = sharedPreferencesHelper.getSelectedSystemMessage().title
                AppToast.makeText(this, systemMessageTitle, AppToast.LENGTH_SHORT).show()
            }
        }
        val isAssistLaunch = intent.action in listOf(Intent.ACTION_ASSIST)
        if (isAssistLaunch) {
            val digitalAssistantPreset = findDigitalAssistantPreset()
            if (digitalAssistantPreset != null) {
                val vm: ChatViewModel by viewModels()
                PresetManager.applyPreset(this, vm, digitalAssistantPreset)
                vm.signalPresetApplied()
            }
            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? ChatFragment
            // STT disabled
            // fragment?.startSpeechRecognitionSafely()
        }
    }

    /**
     * Away-notification tap: queue [CodeSessionPending] so [io.github.stardomains3.oxproxion.code.CodeModeHost]
     * opens the session. Does not touch ChatFragment.
     *
     * A1: extras on this exported Activity are forgeable. Accept only when
     * [CodeAwayNotifier.EXTRA_FROM_AWAY] is set, a one-shot open token matches, and the
     * session still exists in the hub — never trust a bare session id from an external Intent.
     */
    private fun handleCodeAwayIntent(intent: Intent?) {
        if (intent == null) return
        val sessionId = intent.getStringExtra(CodeAwayNotifier.EXTRA_SESSION_ID) ?: return
        val fromAway = intent.getBooleanExtra(CodeAwayNotifier.EXTRA_FROM_AWAY, false)
        val token = intent.getStringExtra(CodeAwayNotifier.EXTRA_OPEN_TOKEN)
        intent.removeExtra(CodeAwayNotifier.EXTRA_SESSION_ID)
        intent.removeExtra(CodeAwayNotifier.EXTRA_FROM_AWAY)
        intent.removeExtra(CodeAwayNotifier.EXTRA_OPEN_TOKEN)
        if (!fromAway) return
        val hub = CodeHub.get(this)
        if (!hub.awayNotifier.consumeOpenToken(sessionId, token)) return
        val session = hub.sessions.value[sessionId] ?: return
        hub.store.enabled = true
        hub.store.lastTabWasCode = true
        // B4: bind home/active host to this session's machine before opening.
        hub.selectHost(session.summary.hostId)
        hub.awayNotifier.cancelSession(sessionId)
        CodeSessionPending.offer(sessionId)
    }

    /**
     * `gradation://pair?...` deep link: queue pairing for [io.github.stardomains3.oxproxion.code.CodeModeHost]
     * (enables Code tab + opens host dialog). Does not touch ChatFragment.
     */
    private fun handleCodePairIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (!CodePairing.isPairUri(data.scheme, data.host)) return
        when (val parsed = CodePairing.parse(data.toString())) {
            is CodePairing.ParseResult.Ok -> {
                val hub = CodeHub.get(this)
                hub.store.enabled = true
                hub.store.lastTabWasCode = true
                CodePairPending.offer(parsed.pairing)
                // Prevent re-handling on recreate / second onNewIntent with same Intent.
                intent.data = null
            }
            is CodePairing.ParseResult.Err -> {
                val msg = when (parsed.reason) {
                    CodePairing.Reason.NOT_PAIR_URI -> R.string.code_pair_bad_qr
                    CodePairing.Reason.MISSING_URL -> R.string.code_pair_missing_url
                    CodePairing.Reason.MISSING_TOKEN -> R.string.code_pair_missing_token
                    CodePairing.Reason.BAD_URL -> R.string.code_host_bad_url
                    CodePairing.Reason.BAD_FINGERPRINT -> R.string.code_pair_bad_fingerprint
                    CodePairing.Reason.PIN_REQUIRES_WSS -> R.string.code_pair_pin_requires_wss
                }
                AppToast.makeText(this, getString(msg), AppToast.LENGTH_LONG).show()
                intent.data = null
            }
        }
    }

    private fun findDigitalAssistantPreset(): Preset? {
        val repository = PresetRepository(this)
        val allPresets = repository.getAll()
        return allPresets.find { preset ->
            preset.title.lowercase().trim() == "digital assistant"
        }
    }
    private fun handlePresetIntent(intent: Intent) {
        if (intent.getBooleanExtra("apply_preset", false)) {
            val presetId = intent.getStringExtra("preset_id") ?: return

            // 1. Fetch the full Preset object from Repository
            val repository = PresetRepository(this)
            val preset = repository.findById(presetId)

            if (preset == null) {
                AppToast.makeText(this, "Preset not found (ID: $presetId)", AppToast.LENGTH_LONG).show()
                return
            }

            val vm: ChatViewModel by viewModels()
            val prefs = SharedPreferencesHelper(this)

            // 2. Validation (Model and System Message still exist)
            val allModels = (vm.getBuiltInModels() + prefs.getCustomModels()).distinctBy { it.apiIdentifier.lowercase() }
            if (allModels.none { it.apiIdentifier.equals(preset.modelIdentifier, ignoreCase = true) }) {
                AppToast.makeText(this, "Preset not applied: Model \"${preset.modelIdentifier}\" no longer exists.", AppToast.LENGTH_LONG).show()
                return
            }

            val allMessages = listOf(prefs.getDefaultSystemMessage()) + prefs.getCustomSystemMessages()
            if (allMessages.none { it.title == preset.systemMessage.title && it.prompt == preset.systemMessage.prompt }) {
                AppToast.makeText(this, "Preset not applied: System message \"${preset.systemMessage.title}\" no longer exists.", AppToast.LENGTH_LONG).show()
                return
            }

            // 3. Apply the Preset (This now includes Web Search via PresetManager)
            PresetManager.applyPreset(this, vm, preset)
            vm.signalPresetApplied()

            // 4. Handle the shared text
            val sharedText = intent.getStringExtra("shared_text")
            if (sharedText != null) {
                if (intent.getBooleanExtra("clear_chat", false)) {
                    vm.startFreshChatForCurrentMode()
                }

                if (intent.getBooleanExtra("autosend_preset", false)) {
                    vm.consumeSharedTextautosend(sharedText)
                } else if (intent.getBooleanExtra("input_only_preset", false)) {
                    vm.consumeSharedText(sharedText)
                }
            }

            // Clear the flag to prevent re-applying on rotation
            intent.removeExtra("apply_preset")
        }
    }
   /* private fun handlePresetIntent(intent: Intent) {
        if (intent.getBooleanExtra("apply_preset", false)) {
            val presetId = intent.getStringExtra("preset_id") ?: return
            val presetTitle = intent.getStringExtra("preset_title") ?: return
            val presetModel = intent.getStringExtra("preset_model") ?: return
            val systemMessageTitle = intent.getStringExtra("preset_system_message_title") ?: return
            val systemMessagePrompt = intent.getStringExtra("preset_system_message_prompt") ?: return
            val systemMessageIsDefault = intent.getBooleanExtra("preset_system_message_is_default", false)
            val streaming = intent.getBooleanExtra("preset_streaming", false)
            val reasoning = intent.getBooleanExtra("preset_reasoning", false)
            val conversationMode = intent.getBooleanExtra("preset_conversation_mode", false)
            val systemMessage = SystemMessage(systemMessageTitle, systemMessagePrompt, isDefault = systemMessageIsDefault)
            val preset = Preset(
                id = presetId,
                title = presetTitle,
                modelIdentifier = presetModel,
                systemMessage = systemMessage,
                streaming = streaming,
                reasoning = reasoning,
                conversationMode = conversationMode
            )

            val vm: ChatViewModel by viewModels()
            // --- VALIDATION ---
            val prefs = SharedPreferencesHelper(this)
            val allModels = (vm.getBuiltInModels() + prefs.getCustomModels()).distinctBy { it.apiIdentifier.lowercase() }
            if (allModels.none { it.apiIdentifier.equals(preset.modelIdentifier, ignoreCase = true) }) {
                AppToast.makeText(this, "Preset not applied: Model \"${preset.modelIdentifier}\" no longer exists.", AppToast.LENGTH_LONG).show()
                return
            }

            val allMessages = listOf(prefs.getDefaultSystemMessage()) + prefs.getCustomSystemMessages()
            if (allMessages.none { it.title == preset.systemMessage.title && it.prompt == preset.systemMessage.prompt }) {
                AppToast.makeText(this, "Preset not applied: System message \"${preset.systemMessage.title}\" no longer exists.", AppToast.LENGTH_LONG).show()
                return
            }
            // --- END VALIDATION ---


            PresetManager.applyPreset(this, vm, preset)
          /*  if (ForegroundService.isRunningForeground && prefs.getNotiPreference()) {
                val displayName = vm.getModelDisplayName(preset.title)
                ForegroundService.updateNotificationStatusSilently(displayName, "Preset Applied")
            }*/
            vm.signalPresetApplied()
            //AppToast.makeText(this, "Preset Applied: ${preset.title}", AppToast.LENGTH_SHORT).show()

            val sharedText = intent.getStringExtra("shared_text")
            if (sharedText != null) {
                val clearChat = intent.getBooleanExtra("clear_chat", false)
                if (clearChat) {
                    vm.startNewChat()
                }

                if (intent.getBooleanExtra("autosend_preset", false)) {
                    vm.consumeSharedTextautosend(sharedText)
                } else if (intent.getBooleanExtra("input_only_preset", false)) {
                    vm.consumeSharedText(sharedText)
                }
            }
            // Clear the preset flag to prevent re-applying on config change
            intent.removeExtra("apply_preset")
        }
    }*/



}

package io.github.stardomains3.oxproxion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentManager
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

    private var navLockUntil = 0L

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
            android.os.SystemClock.uptimeMillis() < navLockUntil
        ) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedTheme = SharedPreferencesHelper(this).getThemeMode()
        val mode = when (savedTheme) {
            SharedPreferencesHelper.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            SharedPreferencesHelper.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
        // Locked means the gate has to run before anything shows. A restored activity (process death,
        // or the system reclaiming it while backgrounded) must not come back open, and its saved
        // fragments cannot be restored without the layout the gate holds back: start it clean.
        BiometricGateHelper.relockIfAway()
        val locked = SharedPreferencesHelper(this).getBiometricEnabled() && !BiometricGateHelper.unlocked
        val state = if (locked) null else savedInstanceState
        // Process death restores the last code session on the back stack. A fresh process
        // should land on the mode's home instead. Rotation keeps sawActivity, so it does not.
        val reopenAfterDeath = state != null && !sawActivity
        sawActivity = true
        super.onCreate(state)
        if (reopenAfterDeath) {
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        GlassQuality.init(this)
        GlassChrome.install(supportFragmentManager)
        // While a screen slides in or out, taps would stack a second copy on top (a double tap
        // on a settings row sank the stack a level too deep). Hold touches for the transition.
        supportFragmentManager.addOnBackStackChangedListener {
            // With animations off there is no transition to protect, and the lock would only eat taps.
            if (!Motion.areAnimationsEnabled(this)) return@addOnBackStackChangedListener
            navLockUntil = android.os.SystemClock.uptimeMillis() +
                resources.getInteger(R.integer.motion_fragment)
        }

        // Closes the app if the unlock is cancelled; otherwise carries on once it succeeds.
        if (locked) {
            BiometricGateHelper.gateIfNeeded(this) { continueOnCreate(state) }
            return
        }

        continueOnCreate(state)
    }

    /** Coming back after a while away: the lock re-arms, so ask again. */
    override fun onRestart() {
        super.onRestart()
        BiometricGateHelper.relockIfAway()
        if (SharedPreferencesHelper(this).getBiometricEnabled() && !BiometricGateHelper.unlocked) {
            BiometricGateHelper.gateIfNeeded(this) {}
        }
    }

    override fun onStop() {
        // A rotation stops the activity too, and is not leaving.
        if (!isChangingConfigurations) BiometricGateHelper.noteStopped()
        super.onStop()
    }

    private fun continueOnCreate(savedInstanceState: Bundle?) {
        // Edge to edge on every version (Android 15+ already forces it): screens pad themselves,
        // and chat lets its background run under the status bar.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        // A rotation recreates the activity; asking again would re-show the system dialog each time.
        if (savedInstanceState == null) askNotificationPermission()
        handleCodePairIntent(intent)
        handleCodeAwayIntent(intent)
        val sharedPreferencesHelper = SharedPreferencesHelper(this)
        sharedPreferencesHelper.seedDefaultModelsIfNeeded()
        sharedPreferencesHelper.seedDefaultSystemMessagesIfNeeded()
        if (!sharedPreferencesHelper.hasMigratedMaverick()) {
            if (sharedPreferencesHelper.customModelsUnreadable()) {
                // An empty decode is not "no Maverick entries". Retry once the list can be read.
                Log.w("MainActivity", "Skipping Maverick model cleanup until custom_models can be read")
            } else {
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
        // What the launching intent asked for runs once, on a fresh create. A rotation or a restore
        // hands the same intent back, and replaying it would re-send the message or re-apply the preset.
        if (savedInstanceState == null) {
            // Auto-apply the "digital assistant" preset for assistant launches
            if (intent?.action in listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)) {
                applyDigitalAssistantPreset()
                intent.action = null
            }
            handlePresetIntent(intent)
            consumeSharedTextIntent(intent)
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
        Motion.refreshAnimations(this)
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
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.removeExtra(Intent.EXTRA_TEXT)
            if (text != null) {
                val vm: ChatViewModel by viewModels { AppViewModelFactory(application) }
                vm.consumeSharedText(text)
            }
        }

        consumeSharedTextIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) {
            applyDigitalAssistantPreset()
            intent.action = null
        }
    }

    /**
     * The autosend / input-only hand-offs (shortcuts, other apps): each runs once, then its extras
     * go, so the same intent coming back on a recreate cannot send the message again.
     */
    private fun consumeSharedTextIntent(intent: Intent) {
        val autosend = intent.getBooleanExtra("autosend", false)
        val inputOnly = !autosend && intent.getBooleanExtra("input_only", false)
        if (!autosend && !inputOnly) return
        val text = intent.getStringExtra("shared_text")
        val clearChat = intent.getBooleanExtra("clear_chat", false)
        intent.removeExtra("autosend")
        intent.removeExtra("input_only")
        intent.removeExtra("shared_text")
        intent.removeExtra("clear_chat")
        if (text == null) return
        val vm: ChatViewModel by viewModels { AppViewModelFactory(application) }
        if (clearChat) vm.startFreshChatForCurrentMode()
        if (autosend) vm.consumeSharedTextautosend(text) else vm.consumeSharedText(text)
    }

    private fun applyDigitalAssistantPreset() {
        val preset = findDigitalAssistantPreset() ?: return
        val vm: ChatViewModel by viewModels { AppViewModelFactory(application) }
        PresetManager.applyPreset(this, vm, preset)
        vm.signalPresetApplied()
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
        // Sessions load off the main thread now; on a cold start they are not in yet.
        hub.awaitSessionsLoaded()
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
                GlassNotice.show(this, getString(msg))
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
                GlassNotice.show(this, getString(R.string.notice_preset_not_found, presetId))
                return
            }

            val vm: ChatViewModel by viewModels { AppViewModelFactory(application) }
            val prefs = SharedPreferencesHelper(this)

            // 2. Validation (Model and System Message still exist)
            val allModels = (vm.getBuiltInModels() + prefs.getCustomModels()).distinctBy { it.apiIdentifier.lowercase() }
            if (allModels.none { it.apiIdentifier.equals(preset.modelIdentifier, ignoreCase = true) }) {
                GlassNotice.show(this, getString(R.string.notice_preset_model_missing, preset.modelIdentifier))
                return
            }

            val allMessages = listOf(prefs.getDefaultSystemMessage()) + prefs.getCustomSystemMessages()
            if (allMessages.none { it.title == preset.systemMessage.title && it.prompt == preset.systemMessage.prompt }) {
                GlassNotice.show(this, getString(R.string.notice_preset_system_message_missing, preset.systemMessage.title))
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

    override fun onDestroy() {
        if (!isChangingConfigurations) sawActivity = false
        super.onDestroy()
    }

    companion object {
        /** Survives rotation, not a real close. */
        private var sawActivity = false
    }
}

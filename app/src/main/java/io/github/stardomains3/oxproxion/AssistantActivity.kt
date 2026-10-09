package io.github.stardomains3.oxproxion
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity

class AssistantActivity : AppCompatActivity() {

    private var stateSnapshot: AssistantStateSnapshot? = null
    private var biometricUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Also on a recreate: a rotation while the prompt is up must ask again. Once unlocked this
        // passes straight through.
        BiometricGateHelper.gateIfNeeded(this) {
            biometricUnlocked = true
            continueOnCreate()
        }
    }

    private fun continueOnCreate() {
        val vm: ChatViewModel by viewModels { AppViewModelFactory(application) }
        val repository = PresetRepository(this)
        val allPresets = repository.getAll()

        val digitalAssistantPreset = allPresets.find {
            it.title.lowercase().trim() == "digital assistant"
        }
        val transcriptionPreset = allPresets.find {
            it.title.lowercase().trim() == "transcription"
        }

        if (digitalAssistantPreset != null) {
            setupAssistantMode(vm, digitalAssistantPreset)
        } else if (transcriptionPreset != null) {
            // STT disabled — do not launch Transactivity; fall through to chat
            // launchTranscriptionActivity()
            setupStandardChatMode(vm)
        } else {
            setupStandardChatMode(vm)
        }
    }

    private fun setupAssistantMode(vm: ChatViewModel, preset: Preset) {
        setContentView(R.layout.activity_main)
        stateSnapshot = PresetManager.captureCurrentState(this, vm)
        PresetManager.applyPreset(this, vm, preset)
        vm.signalPresetApplied()
        loadChatFragment()
    }

    private fun setupStandardChatMode(vm: ChatViewModel) {
        setContentView(R.layout.activity_main)
        loadChatFragment()
    }

    private fun launchTranscriptionActivity() {
        val intent = Intent(this, Transactivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun loadChatFragment() {
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            val chatFragment = ChatFragment().apply {
                arguments = Bundle().apply {
                    // STT disabled
                    // putBoolean("start_stt_on_launch", true)
                }
            }
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, chatFragment, "ChatFragment")
                .commitNow()
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) BiometricGateHelper.noteStopped()
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        stateSnapshot?.let { snapshot ->
            val vm: ChatViewModel by viewModels { AppViewModelFactory(application) }
            PresetManager.restoreState(this, vm, snapshot)
        }
    }
}

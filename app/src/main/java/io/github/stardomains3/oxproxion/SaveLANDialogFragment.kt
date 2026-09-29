package io.github.stardomains3.oxproxion

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Local server" sheet: pick the server kind, type where it is, test it, choose models.
 * Everything the user has to know (port, http://, /v1) is filled in or cleaned for them.
 */
class SaveLANDialogFragment : DialogFragment() {

    private val viewModel: ChatViewModel by activityViewModels { AppViewModelFactory(requireActivity().application) }

    private var type = LanServerType.OLLAMA

    /** An older install's provider (MLX LM, oMLX, ...) shows as "Other" but is kept when saved as such. */
    private var legacyProvider: String? = null
    private var testJob: Job? = null

    private lateinit var prefs: SharedPreferencesHelper
    private lateinit var editUrl: TextInputEditText
    private lateinit var editKey: TextInputEditText
    private lateinit var editContext: TextInputEditText
    private lateinit var status: TextView
    private lateinit var btnTest: MaterialButton
    private lateinit var btnChoose: MaterialButton
    private lateinit var advancedGroup: View
    private lateinit var ollamaHint: View
    private lateinit var chips: Map<LanServerType, TextView>

    private val localNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            resolveServer()?.let { runTest(it) }
        } else {
            showStatus(getString(R.string.lan_permission_denied))
        }
    }

    companion object {
        const val TAG = "SaveLANDialogFragment"

        /** Posted on the activity's fragment manager when the server was saved. */
        const val RESULT_SAVED = "lan_server_saved"

        /** True when the sheet itself sent the user on to the model list. */
        const val EXTRA_OPENED_MODELS = "opened_models"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_save_lan, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog?.window?.let { GlassDialogs.frost(it) }

        prefs = SharedPreferencesHelper(requireContext())
        editUrl = view.findViewById(R.id.edit_text_lan_url)
        editKey = view.findViewById(R.id.edit_text_lan_api_key)
        editContext = view.findViewById(R.id.edit_text_lan_context)
        status = view.findViewById(R.id.lan_status)
        btnTest = view.findViewById(R.id.button_test_lan)
        btnChoose = view.findViewById(R.id.button_choose_models)
        advancedGroup = view.findViewById(R.id.lan_advanced_group)
        ollamaHint = view.findViewById(R.id.lan_context_ollama_hint)
        chips = mapOf(
            LanServerType.OLLAMA to view.findViewById(R.id.chip_ollama),
            LanServerType.LM_STUDIO to view.findViewById(R.id.chip_lm_studio),
            LanServerType.LLAMA_CPP to view.findViewById(R.id.chip_llama_cpp),
            LanServerType.KOBOLDCPP to view.findViewById(R.id.chip_koboldcpp),
            LanServerType.OTHER to view.findViewById(R.id.chip_other),
        )

        val savedEndpoint = prefs.getLanEndpoint()
        val savedProvider = prefs.getLanProvider()
        // First open: Ollama is the common case, so it starts selected; its port is added on save.
        type = if (savedEndpoint == null) LanServerType.OLLAMA else LanServerType.fromProvider(savedProvider)
        legacyProvider = savedProvider.takeIf {
            LanServerType.fromProvider(it) == LanServerType.OTHER && it != LanServerType.OTHER.provider
        }
        if (savedEndpoint != null) {
            editUrl.setText(LanEndpoints.editText(savedEndpoint))
        }
        editKey.setText(prefs.getLanApiKey())
        prefs.getLanContextSize().takeIf { it > 0 }?.let { editContext.setText(it.toString()) }
        // A saved key or context size means the fold has something worth showing.
        if (editKey.text?.isNotBlank() == true || editContext.text?.isNotBlank() == true) setAdvanced(true)

        chips.forEach { (t, chip) -> chip.setOnClickListener { selectType(t) } }
        renderType()

        val toggle = view.findViewById<TextView>(R.id.lan_advanced_toggle)
        toggle.setOnClickListener { setAdvanced(!advancedGroup.isVisible) }

        // Whatever a test said is about the old address.
        editUrl.doAfterTextChanged { clearStatus() }
        editKey.doAfterTextChanged { clearStatus() }

        btnTest.setOnClickListener { startTest() }
        btnChoose.setOnClickListener {
            if (save(openedModels = true)) {
                openModels()
                dismiss()
            }
        }
        view.findViewById<MaterialButton>(R.id.button_save_lan).setOnClickListener {
            if (save()) dismiss()
        }
        view.findViewById<MaterialButton>(R.id.button_cancel_lan).setOnClickListener { dismiss() }

        editUrl.requestFocus()
    }

    override fun onDestroyView() {
        testJob?.cancel()
        super.onDestroyView()
    }

    private fun setAdvanced(open: Boolean) {
        advancedGroup.isVisible = open
        val icon = if (open) R.drawable.ic_expand_less2 else R.drawable.ic_expand_more
        view?.findViewById<TextView>(R.id.lan_advanced_toggle)
            ?.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, icon, 0)
    }

    private fun selectType(next: LanServerType) {
        if (next == type) return
        val previous = type
        type = next
        val current = editUrl.text?.toString().orEmpty()
        val updated = LanEndpoints.retargetPort(current, previous, next)
        if (updated != current) {
            editUrl.setText(updated)
            editUrl.setSelection(updated.length)
        }
        renderType()
        clearStatus()
    }

    private fun renderType() {
        val ink = ContextCompat.getColor(requireContext(), R.color.xai_ink)
        val body = ContextCompat.getColor(requireContext(), R.color.xai_body)
        chips.forEach { (t, chip) ->
            chip.isSelected = t == type
            chip.setTextColor(if (t == type) ink else body)
        }
        ollamaHint.isVisible = type == LanServerType.OLLAMA
        view?.findViewById<TextView>(R.id.lan_host_help)?.text = type.defaultPort
            ?.let { getString(R.string.lan_host_help_port, it) } ?: getString(R.string.lan_host_help)
    }

    private fun providerToSave(): String =
        if (type == LanServerType.OTHER) legacyProvider ?: LanServerType.OTHER.provider else type.provider

    /** The server the fields describe, or null after flagging what's wrong with the address. */
    private fun resolveServer(): LanServer? {
        val raw = editUrl.text?.toString()?.trim().orEmpty()
        // ":11434" alone is only the auto-filled port; the host is still missing.
        if (raw.isEmpty() || raw.matches(Regex("^:\\d*$"))) {
            editUrl.error = getString(R.string.lan_host_required)
            return null
        }
        val url = LanEndpoints.normalize(raw, type.defaultPort)
        if (url == null) {
            editUrl.error = getString(R.string.lan_host_invalid)
            return null
        }
        LanEndpointValidator.validate(url)?.let {
            editUrl.error = it
            return null
        }
        return LanServer(url, providerToSave(), editKey.text?.toString()?.trim().orEmpty())
    }

    private fun startTest() {
        val server = resolveServer() ?: return
        // Android 17 asks before an app talks to devices on the local network; ask here, where the reason is obvious.
        if (Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(
                requireContext(), "android.permission.ACCESS_LOCAL_NETWORK"
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetworkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
            return
        }
        runTest(server)
    }

    private fun runTest(server: LanServer) {
        showStatus(getString(R.string.lan_testing))
        btnTest.isEnabled = false
        testJob?.cancel()
        testJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = viewModel.probeLanServer(server)
            showProbeResult(result)
        }
    }

    /** One line under the fields: what the test found, or why it couldn't. */
    internal fun showProbeResult(state: LanFetchState) {
        btnTest.isEnabled = true
        when (state) {
            LanFetchState.Loading -> showStatus(getString(R.string.lan_testing))
            is LanFetchState.Failed -> showStatus(state.message)
            is LanFetchState.Loaded -> {
                val n = state.models.size
                if (n == 0) {
                    showStatus(getString(R.string.lan_test_no_models))
                } else {
                    showStatus(resources.getQuantityString(R.plurals.lan_test_connected, n, n))
                    btnChoose.isVisible = true
                }
            }
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.isVisible = true
    }

    private fun clearStatus() {
        if (!::status.isInitialized) return
        testJob?.cancel()
        btnTest.isEnabled = true
        status.isVisible = false
        btnChoose.isVisible = false
    }

    /** @return false when the fields are invalid or the key couldn't be stored (nothing is saved then). */
    private fun save(openedModels: Boolean = false): Boolean {
        val server = resolveServer() ?: return false
        val keyOk = prefs.setLanApiKey(server.apiKey.takeIf { it.isNotBlank() })
        if (!keyOk) {
            showStatus(getString(R.string.lan_key_save_failed))
            return false
        }
        prefs.setLanEndpoint(server.endpoint)
        prefs.setLanProvider(server.provider)
        prefs.setLanContextSize(editContext.text?.toString()?.trim()?.toIntOrNull() ?: 0)
        viewModel.refreshLanHttpClient()
        GlassNotice.show(requireContext(), getString(R.string.lan_saved))
        requireActivity().supportFragmentManager.setFragmentResult(RESULT_SAVED, bundleOf(EXTRA_OPENED_MODELS to openedModels))
        return true
    }

    /** Straight to the model list; when it is already open it refetches from the result instead. */
    private fun openModels() {
        val fm = requireActivity().supportFragmentManager
        val top = fm.fragments.lastOrNull { it.isVisible && it.id == R.id.fragment_container }
        if (top is LanModelsFragment) return
        fm.beginTransaction()
            .withGrokStackAnimations()
            .apply { top?.let { hide(it) } }
            .add(R.id.fragment_container, LanModelsFragment())
            .addToBackStack(null)
            .commit()
    }
}

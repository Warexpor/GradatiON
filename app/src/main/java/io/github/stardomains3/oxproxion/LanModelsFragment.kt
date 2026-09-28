package io.github.stardomains3.oxproxion

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class LanModelsFragment : Fragment() {

    private lateinit var viewModel: ChatViewModel
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LanModelsAdapter
    private lateinit var stateGroup: View
    private lateinit var stateText: TextView
    private lateinit var progress: View
    private lateinit var retryButton: View
    private lateinit var editButton: View
    private lateinit var serverLabel: TextView
    private var allModels: List<LlmModel> = emptyList()

    // Android 17+ asks before the app talks to devices on the local network.
    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.startLanModelsFetch()
        } else {
            showState(getString(R.string.lan_permission_denied), loading = false)
        }
    }

    companion object {
        const val TAG = "LanModelsFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_lan_models, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity(), AppViewModelFactory(requireActivity().application))[ChatViewModel::class.java]

        stateGroup = view.findViewById(R.id.lanState)
        stateText = view.findViewById(R.id.lanStateText)
        progress = view.findViewById(R.id.lanProgress)
        retryButton = view.findViewById(R.id.lanRetry)
        editButton = view.findViewById(R.id.lanEditServer)
        serverLabel = view.findViewById(R.id.lanServerLabel)

        // CANCEL BEFORE BACK
        view.findViewById<View>(R.id.lanBack).setOnClickListener {
            viewModel.cancelCurrentRequest()
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.lanRefresh).setOnClickListener { checkLocalNetworkAndFetch() }
        retryButton.setOnClickListener { checkLocalNetworkAndFetch() }
        view.findViewById<View>(R.id.lanServerRow).setOnClickListener { editServer() }
        editButton.setOnClickListener { editServer() }
        renderServerLabel()

        recyclerView = view.findViewById(R.id.recyclerViewLanModels)
        recyclerView.layoutManager = LinearLayoutManager(context)

        // Eject/load only exist on llama.cpp's router mode
        val isLlamaCpp = viewModel.getCurrentLanProvider() == SharedPreferencesHelper.LAN_PROVIDER_LLAMA_CPP
        adapter = LanModelsAdapter(
            models = emptyList(),
            isLlamaCppProvider = isLlamaCpp,
            isModelInLibrary = { id -> viewModel.modelExists(id) },
            isModelSelected = { id -> viewModel.activeChatModel.value == id },
            onItemClicked = { model ->
                chooseModel(model)
            },
            onEjectClicked = if (isLlamaCpp) { model ->
                unloadModel(model)
            } else null,
            onLoadClicked = if (isLlamaCpp) { model ->
                loadModel(model)
            } else null
        )
        recyclerView.adapter = adapter

        viewModel.lanFetchState.observe(viewLifecycleOwner) { state ->
            when (state) {
                LanFetchState.Loading -> {
                    // A refresh keeps the current rows on screen; only an empty list shows the spinner.
                    if (adapter.currentCount() == 0) showState(getString(R.string.lan_loading), loading = true)
                }
                is LanFetchState.Failed -> {
                    allModels = emptyList()
                    adapter.updateModels(allModels)
                    showState(state.message, loading = false)
                }
                is LanFetchState.Loaded -> {
                    allModels = state.models.sortedBy { it.displayName.lowercase() }
                    adapter.updateModels(allModels)
                    if (allModels.isEmpty()) {
                        showState(getString(R.string.lan_models_empty), loading = false)
                    } else {
                        stateGroup.isVisible = false
                    }
                }
            }
        }

        viewModel.customModelsUpdated.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                adapter.refreshAddedStates()
            }
        }
        viewModel.activeChatModel.observe(viewLifecycleOwner) { adapter.refreshAddedStates() }

        // The sheet saved a different server: show it and look again.
        requireActivity().supportFragmentManager.setFragmentResultListener(
            SaveLANDialogFragment.RESULT_SAVED, viewLifecycleOwner
        ) { _, _ ->
            renderServerLabel()
            checkLocalNetworkAndFetch()
        }

        // START FETCH (with permission check)
        checkLocalNetworkAndFetch()
    }

    private fun renderServerLabel() {
        val prefs = SharedPreferencesHelper(requireContext())
        val endpoint = prefs.getLanEndpoint()
        serverLabel.text = if (endpoint == null) {
            getString(R.string.lan_no_server)
        } else {
            "${providerLabel(prefs.getLanProvider())} · ${LanEndpoints.hostLabel(endpoint)}"
        }
    }

    private fun providerLabel(provider: String): String = when (provider) {
        "lm_studio" -> "LM Studio"
        "llama_cpp" -> "llama.cpp"
        "mlx_lm" -> "MLX LM"
        "ollama" -> "Ollama"
        "omlx" -> "oMLX"
        "nativ" -> "Nativ"
        "hermes_agent" -> "Hermes Agent"
        "koboldcpp" -> "KoboldCpp"
        else -> getString(R.string.model_lan_title)
    }

    private fun editServer() {
        SaveLANDialogFragment().show(requireActivity().supportFragmentManager, SaveLANDialogFragment.TAG)
    }

    /** Loading, empty and failed all share one centered block; [loading] swaps the buttons for a spinner. */
    private fun showState(message: String, loading: Boolean) {
        stateText.text = message
        progress.isVisible = loading
        retryButton.isVisible = !loading
        editButton.isVisible = !loading
        stateGroup.isVisible = true
    }

    private fun checkLocalNetworkAndFetch() {
        // Android 17 (API 37) requires explicit local network permission
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                "android.permission.ACCESS_LOCAL_NETWORK"
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
        } else {
            viewModel.startLanModelsFetch()
        }
    }

    /** One tap adds the model to the library and makes it the one in use. */
    private fun chooseModel(model: LlmModel) {
        viewModel.addCustomModel(model)
        viewModel.setModel(model.apiIdentifier)
        adapter.animateAdded(model.apiIdentifier)
        adapter.refreshAddedStates()
        GlassNotice.show(requireContext(), getString(R.string.lan_model_selected, model.apiIdentifier))
    }

    private fun loadModel(model: LlmModel) {
        // Show spinner for this model
        adapter.setLoadingState(model.apiIdentifier, true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                GlassNotice.show(requireContext(), getString(R.string.lan_llama_loading))
                val success = viewModel.loadLlamaCppModel(model)
                if (success) {
                    GlassNotice.show(requireContext(), getString(R.string.lan_llama_loaded, model.apiIdentifier))
                } else {
                    GlassNotice.show(requireContext(), getString(R.string.lan_llama_load_failed))
                }
            } catch (e: Exception) {
                GlassNotice.show(requireContext(), getString(R.string.lan_llama_load_error, e.message.orEmpty()))
            } finally {
                kotlinx.coroutines.delay(1600.milliseconds)
                // Hide spinner and fetch updated list
                adapter.setLoadingState(model.apiIdentifier, false)

                viewModel.startLanModelsFetch()
            }
        }
    }
    private fun unloadModel(model: LlmModel) {
        // Show spinner for this model
        adapter.setLoadingState(model.apiIdentifier, true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = viewModel.unloadLlamaCppModel(model)
                if (success) {
                    GlassNotice.show(requireContext(), getString(R.string.lan_llama_unloaded, model.apiIdentifier))
                } else {
                    GlassNotice.show(requireContext(), getString(R.string.lan_llama_unload_failed))
                }
            } catch (e: Exception) {
                GlassNotice.show(requireContext(), getString(R.string.lan_llama_unload_error, e.message.orEmpty()))
            } finally {
                kotlinx.coroutines.delay(1600.milliseconds)
                // Hide spinner and fetch updated list
                adapter.setLoadingState(model.apiIdentifier, false)
                viewModel.startLanModelsFetch()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.cancelCurrentRequest()
    }
}

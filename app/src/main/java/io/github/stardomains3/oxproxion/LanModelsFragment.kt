package io.github.stardomains3.oxproxion

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
    private var allModels: List<LlmModel> = emptyList()

    // NEW: Permission Launcher for Android 17+ Local Network
    private val localNetworkPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted! Proceed with fetching models.
            viewModel.startLanModelsFetch()
        } else {
            // Permission denied. Explain to the user.
            GlassNotice.show(requireContext(), getString(R.string.lan_models_permission_needed))
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

        val provider = viewModel.getCurrentLanProvider()
        val title = LanProviderNames.of(provider) ?: getString(R.string.model_lan_title)
        view.findViewById<android.widget.TextView>(R.id.lanTitle).text = title

        view.findViewById<View>(R.id.lanBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.lanRefresh).setOnClickListener { checkLocalNetworkAndFetch() }

        recyclerView = view.findViewById(R.id.recyclerViewLanModels)
        recyclerView.layoutManager = LinearLayoutManager(context)

        // Pass provider to adapter to determine eject button visibility
        val isLlamaCpp = provider == "llama_cpp"
        adapter = LanModelsAdapter(
            models = emptyList(),
            isLlamaCppProvider = isLlamaCpp,
            isModelInLibrary = { id -> viewModel.modelExists(id) },
            onItemClicked = { model ->
                addModel(model)
            },
            onEjectClicked = if (isLlamaCpp) { model ->
                unloadModel(model)
            } else null,
            onLoadClicked = if (isLlamaCpp) { model ->
                loadModel(model)
            } else null
        )
        recyclerView.adapter = adapter

        // OBSERVE MODELS (REACTIVE)
        viewModel.lanModels.observe(viewLifecycleOwner) { models ->
            allModels = models.sortedBy { it.displayName.lowercase() }
            adapter.updateModels(allModels)

            // Say why the list is empty, since the screen would otherwise just look broken.
            if (models.isEmpty()) {
                val emptyMessage = when (provider) {
                    "lm_studio", "llama_cpp", "mlx_lm", "hermes_agent" ->
                        getString(R.string.lan_models_empty_running, title)
                    "ollama", "omlx", "nativ" ->
                        getString(R.string.lan_models_empty_installed, title)
                    else -> getString(R.string.lan_models_empty_generic)
                }
                GlassNotice.show(requireContext(), emptyMessage)
            }
        }

        // OBSERVE ERRORS
        viewModel.toolUiEvent.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                GlassNotice.show(requireContext(), it)
            }
        }

        viewModel.customModelsUpdated.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                adapter.refreshAddedStates()
            }
        }

        // START FETCH (with permission check)
        checkLocalNetworkAndFetch()
    }

    // NEW: Optimized helper function to check permission before fetching
    private fun checkLocalNetworkAndFetch() {
        // Android 17 (API 37) requires explicit local network permission
        if (Build.VERSION.SDK_INT >= 37) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    "android.permission.ACCESS_LOCAL_NETWORK"
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Already granted, fetch normally
                viewModel.startLanModelsFetch()
            } else {
                // Ask the user for permission
                localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
            }
        } else {
            // Pre-Android 17, standard INTERNET permission is enough
            viewModel.startLanModelsFetch()
        }
    }

    private fun addModel(model: LlmModel) {
        if (viewModel.modelExists(model.apiIdentifier)) {
            adapter.animateAdded(model.apiIdentifier)
        } else {
            viewModel.addCustomModel(model)
            adapter.animateAdded(model.apiIdentifier)
        }
    }
    private fun loadModel(model: LlmModel) {
        // Show spinner for this model
        adapter.setLoadingState(model.apiIdentifier, true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // The row's spinner and the refreshed list already show progress and success.
                val success = viewModel.loadLlamaCppModel(model)
                if (!success) {
                    context?.let { GlassNotice.show(it, getString(R.string.lan_model_load_refused)) }
                }
            } catch (e: Exception) {
                context?.let { GlassNotice.show(it, getString(R.string.lan_model_load_failed, e.message.orEmpty())) }
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
                if (!success) {
                    context?.let { GlassNotice.show(it, getString(R.string.lan_model_unload_refused)) }
                }
            } catch (e: Exception) {
                context?.let { GlassNotice.show(it, getString(R.string.lan_model_unload_failed, e.message.orEmpty())) }
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
        // Only the model-list fetch: a chat reply may still be streaming behind this screen.
        viewModel.cancelLanModelFetch()
    }
}

/** How a local server's provider id reads on screen. Null for an id the app does not know. */
internal object LanProviderNames {
    fun of(id: String): String? = when (id) {
        "lm_studio" -> "LM Studio"
        "llama_cpp" -> "llama.cpp"
        "mlx_lm" -> "MLX LM"
        "ollama" -> "Ollama"
        "omlx" -> "oMLX"
        "nativ" -> "Nativ"
        "hermes_agent" -> "Hermes Agent"
        else -> null
    }
}

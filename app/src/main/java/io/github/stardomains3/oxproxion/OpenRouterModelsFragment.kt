package io.github.stardomains3.oxproxion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The OpenRouter catalog: tap a row to add it to your models, long-press for its web page. */
class OpenRouterModelsFragment : Fragment() {

    private lateinit var viewModel: ChatViewModel
    private lateinit var adapter: OpenRouterModelsAdapter
    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper
    private lateinit var searchInput: EditText
    private lateinit var chips: ModelFilterChips
    private var allModels: List<LlmModel> = emptyList()
    private var currentFilter = ModelFilter.ALL

    companion object {
        const val TAG = "OpenRouterModelsFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_open_router_models, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity(), AppViewModelFactory(requireActivity().application))[ChatViewModel::class.java]
        sharedPreferencesHelper = SharedPreferencesHelper(requireContext())

        view.findViewById<View>(R.id.catalogBack).setOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<View>(R.id.catalogRefresh).setOnClickListener {
            GlassNotice.show(requireContext(), getString(R.string.model_catalog_refreshing))
            viewModel.fetchOpenRouterModels()
        }

        searchInput = view.findViewById(R.id.catalogSearchInput)
        searchInput.doAfterTextChanged { filterModels() }

        currentFilter = ModelFilter.fromPrefs(
            sharedPreferencesHelper.getOpenRouterFilterType(),
            sharedPreferencesHelper.getOpenRouterCostFilter()
        )
        chips = ModelFilterChips(
            view.findViewById<LinearLayout>(R.id.catalogFilterChips),
            newestFirst = viewModel.sortOrder.value == SortOrder.BY_DATE,
            selected = currentFilter.takeIf { it != ModelFilter.LOCAL } ?: ModelFilter.ALL,
            filters = ModelFilter.entries - ModelFilter.LOCAL,
            onSort = { newest -> viewModel.setSortOrder(if (newest) SortOrder.BY_DATE else SortOrder.ALPHABETICAL) },
            onFilter = { f ->
                currentFilter = f
                sharedPreferencesHelper.saveOpenRouterFilterType(ModelFilter.typePref(f))
                sharedPreferencesHelper.saveOpenRouterCostFilter(ModelFilter.costPref(f))
                filterModels()
            }
        )

        adapter = OpenRouterModelsAdapter(emptyList(), isAdded = { viewModel.modelExists(it.apiIdentifier) }) { model ->
            addModel(model)
        }
        view.findViewById<RecyclerView>(R.id.recyclerViewOpenRouterModels).adapter = adapter

        viewModel.openRouterModels.observe(viewLifecycleOwner) { models ->
            allModels = models
            filterModels()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sortOrder.collectLatest { filterModels() }
        }

        viewModel.getOpenRouterModels()
    }

    private fun filterModels() {
        val query = searchInput.text?.toString().orEmpty()
        val filtered = allModels.filter { currentFilter.matches(it) }.let { list ->
            if (query.isEmpty()) list else list.filter { model ->
                model.displayName.contains(query, ignoreCase = true) ||
                    model.apiIdentifier.contains(query, ignoreCase = true)
            }
        }
        adapter.updateModels(filtered)
    }

    private fun addModel(model: LlmModel) {
        if (viewModel.modelExists(model.apiIdentifier)) {
            GlassNotice.show(requireContext(), getString(R.string.model_catalog_already))
        } else {
            viewModel.addCustomModel(model)
            val name = ModelNames.withoutProvider(model.displayName, model.apiIdentifier)
            GlassNotice.show(requireContext(), getString(R.string.model_catalog_added, name))
            adapter.markAdded(model.apiIdentifier)
        }
    }
}

package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

class BotModelPickerFragment : Fragment() {

    var onModelSelected: ((String) -> Unit)? = null
    private lateinit var adapter: BotModelAdapter
    private var models = mutableListOf<LlmModel>()
    private lateinit var chatViewModel: ChatViewModel
    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper
    private lateinit var searchInput: TextInputEditText
    private lateinit var modelPickerCount: TextView
    private lateinit var modelPickerEmpty: View
    private var filteredModels: MutableList<LlmModel> = mutableListOf()

    private lateinit var sortBar: MaterialButtonToggleGroup
    private lateinit var filterBar: MaterialButtonToggleGroup
    private lateinit var costFilterBar: MaterialButtonToggleGroup

    private var currentFilterType: FilterType = FilterType.ALL
    private var currentCostFilter: CostFilter = CostFilter.ALL
    private var currentSortOrder: SortOrder = SortOrder.ALPHABETICAL

    enum class FilterType { ALL, VISION, IMAGE_GEN, TRANSCRIPTION }
    enum class CostFilter { ALL, FREE, PAID }
    enum class SortOrder { ALPHABETICAL, BY_DATE }

    companion object {
        const val TAG = "BotModelPickerFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_model_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        chatViewModel = ViewModelProvider(requireActivity(), AppViewModelFactory(requireActivity().application))[ChatViewModel::class.java]
        sharedPreferencesHelper = SharedPreferencesHelper(requireContext())

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewModels)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        searchInput = view.findViewById(R.id.modelSearchInput)
        modelPickerCount = view.findViewById(R.id.modelPickerCount)
        modelPickerEmpty = view.findViewById(R.id.modelPickerEmpty)
        sortBar = view.findViewById(R.id.sortBar)
        filterBar = view.findViewById(R.id.filterBar)
        costFilterBar = view.findViewById(R.id.costFilterBar)

        currentSortOrder = sharedPreferencesHelper.getBotModelPickerSortOrder()

        currentFilterType = when (sharedPreferencesHelper.getBotPickerFilterType()) {
            "VISION" -> FilterType.VISION
            "IMAGE_GEN" -> FilterType.IMAGE_GEN
            "TRANSCRIPTION" -> FilterType.TRANSCRIPTION
            else -> FilterType.ALL
        }

        currentCostFilter = when (sharedPreferencesHelper.getBotPickerCostFilter()) {
            "FREE" -> CostFilter.FREE
            "PAID" -> CostFilter.PAID
            else -> CostFilter.ALL
        }

        updateSortButtons(currentSortOrder)

        filterBar.check(when (currentFilterType) {
            FilterType.ALL -> R.id.filterAllButton
            FilterType.VISION -> R.id.filterVisionButton
            FilterType.IMAGE_GEN -> R.id.filterImageGenButton
            FilterType.TRANSCRIPTION -> R.id.filterTranscriptionButton
        })

        costFilterBar.check(when (currentCostFilter) {
            CostFilter.ALL -> R.id.costFilterAllButton
            CostFilter.FREE -> R.id.costFilterFreeButton
            CostFilter.PAID -> R.id.costFilterPaidButton
        })

        toolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        view.findViewById<View>(R.id.btnBrowseLan).setOnClickListener {
            openLanModels()
        }
        view.findViewById<View>(R.id.btnBrowseCloud).setOnClickListener {
            openOpenRouterModels()
        }

        view.findViewById<MaterialButton>(R.id.btnClearFilters).setOnClickListener {
            clearFilters()
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filterAndSortModels(s?.toString().orEmpty())
            }
        })

        sortBar.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSortOrder = when (checkedId) {
                    R.id.sortAlphabeticalButton -> SortOrder.ALPHABETICAL
                    R.id.sortDateButton -> SortOrder.BY_DATE
                    else -> SortOrder.ALPHABETICAL
                }
                sharedPreferencesHelper.saveBotModelPickerSortOrder(currentSortOrder)
                filterAndSortModels(searchInput.text?.toString().orEmpty())
            }
        }

        filterBar.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentFilterType = when (checkedId) {
                    R.id.filterAllButton -> FilterType.ALL
                    R.id.filterVisionButton -> FilterType.VISION
                    R.id.filterImageGenButton -> FilterType.IMAGE_GEN
                    R.id.filterTranscriptionButton -> FilterType.TRANSCRIPTION
                    else -> FilterType.ALL
                }
                sharedPreferencesHelper.saveBotPickerFilterType(currentFilterType.name)
                filterAndSortModels(searchInput.text?.toString().orEmpty())
            }
        }

        costFilterBar.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentCostFilter = when (checkedId) {
                    R.id.costFilterAllButton -> CostFilter.ALL
                    R.id.costFilterFreeButton -> CostFilter.FREE
                    R.id.costFilterPaidButton -> CostFilter.PAID
                    else -> CostFilter.ALL
                }
                sharedPreferencesHelper.saveBotPickerCostFilter(currentCostFilter.name)
                filterAndSortModels(searchInput.text?.toString().orEmpty())
            }
        }

        adapter = BotModelAdapter(filteredModels, sharedPreferencesHelper.getPreferenceModelnew(),
            onItemClicked = { selectedModel ->
                onModelSelected?.invoke(selectedModel.apiIdentifier)
                parentFragmentManager.popBackStack()
            },
            onItemEdit = { showEditModelDialog(it) },
            onItemDelete = { showDeleteConfirmationDialog(it) }
        )
        recyclerView.adapter = adapter

        chatViewModel.customModelsUpdated.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                loadModels()
            }
        }

        loadModels()
    }

    // Push over this list: replace would also tear down the chat screen underneath and rebuild it on return.
    private fun openLanModels() {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this)
            .add(R.id.fragment_container, LanModelsFragment())
            .addToBackStack(null)
            .commit()
    }

    private fun openOpenRouterModels() {
        parentFragmentManager.beginTransaction()
            .withGrokStackAnimations()
            .hide(this)
            .add(R.id.fragment_container, OpenRouterModelsFragment())
            .addToBackStack(null)
            .commit()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) loadModels()
    }

    private fun loadModels() {
        val builtInModels = getModelsList()
        val customModels = sharedPreferencesHelper.getCustomModels()
        models.clear()
        models.addAll(builtInModels)
        models.addAll(customModels)
        filterAndSortModels(searchInput.text?.toString().orEmpty())
    }

    private fun filterAndSortModels(query: String) {
        var tempFiltered = models.toMutableList()

        tempFiltered = when (currentFilterType) {
            FilterType.ALL -> tempFiltered
            FilterType.VISION -> tempFiltered.filter { it.isVisionCapable }.toMutableList()
            FilterType.IMAGE_GEN -> tempFiltered.filter { it.isImageGenerationCapable }.toMutableList()
            FilterType.TRANSCRIPTION -> tempFiltered.filter { it.isTranscription }.toMutableList()
        }

        tempFiltered = when (currentCostFilter) {
            CostFilter.ALL -> tempFiltered
            CostFilter.FREE -> tempFiltered.filter { it.isFree }.toMutableList()
            CostFilter.PAID -> tempFiltered.filter { !it.isFree }.toMutableList()
        }

        if (query.isNotEmpty()) {
            tempFiltered = tempFiltered.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.apiIdentifier.contains(query, ignoreCase = true)
            }.toMutableList()
        }

        when (currentSortOrder) {
            SortOrder.ALPHABETICAL -> tempFiltered.sortBy { it.displayName.lowercase() }
            SortOrder.BY_DATE -> tempFiltered.sortByDescending { it.created }
        }

        filteredModels = tempFiltered
        adapter.updateModels(filteredModels)
        updateListMeta(filteredModels.size)
    }

    private fun updateListMeta(count: Int) {
        modelPickerCount.text = when (count) {
            0 -> getString(R.string.model_picker_count_none)
            1 -> getString(R.string.model_picker_count_one)
            else -> getString(R.string.model_picker_count_many, count)
        }
        modelPickerEmpty.isVisible = count == 0
    }

    private fun clearFilters() {
        currentFilterType = FilterType.ALL
        currentCostFilter = CostFilter.ALL
        currentSortOrder = SortOrder.ALPHABETICAL
        sharedPreferencesHelper.saveBotPickerFilterType(currentFilterType.name)
        sharedPreferencesHelper.saveBotPickerCostFilter(currentCostFilter.name)
        sharedPreferencesHelper.saveBotModelPickerSortOrder(currentSortOrder)
        filterBar.check(R.id.filterAllButton)
        costFilterBar.check(R.id.costFilterAllButton)
        updateSortButtons(currentSortOrder)
        searchInput.setText("")
        filterAndSortModels("")
    }

    private fun showEditModelDialog(modelToEdit: LlmModel) {
        val dialog = EditModelDialogFragment().apply {
            arguments = Bundle().apply {
                putString("displayName", modelToEdit.displayName)
                putString("apiIdentifier", modelToEdit.apiIdentifier)
                putBoolean("isVisionCapable", modelToEdit.isVisionCapable)
                putBoolean("isReasoningCapable", modelToEdit.isReasoningCapable)
                putBoolean("isImageGenerationCapable", modelToEdit.isImageGenerationCapable)
                putBoolean("isTranscription", modelToEdit.isTranscription)
                putLong("created", modelToEdit.created)
                putBoolean("isLANModel", modelToEdit.isLANModel)
                putBoolean("isFree", modelToEdit.isFree)
            }
        }
        dialog.onModelUpdated = { old, new -> updateModel(old, new) }
        dialog.show(parentFragmentManager, "edit_model_dialog")
    }

    private fun showDeleteConfirmationDialog(model: LlmModel) {
        GrokConfirmDialog.show(
            fragment = this,
            title = getString(R.string.delete_model_title),
            message = getString(R.string.delete_model_body, model.displayName),
            confirmText = getString(R.string.delete_message_confirm),
            onConfirm = { deleteModel(model) }
        )
    }

    private fun updateModel(oldModel: LlmModel, newModel: LlmModel) {
        val index = models.indexOfFirst { it.apiIdentifier == oldModel.apiIdentifier }
        if (index != -1) {
            models[index] = newModel
            saveCustomModels()

            val currentActiveId = sharedPreferencesHelper.getPreferenceModelnew()

            if (oldModel.apiIdentifier == currentActiveId) {
                sharedPreferencesHelper.savePreferenceModelnewchat(newModel.apiIdentifier)
                chatViewModel.setModel(newModel.apiIdentifier)
            } else if (newModel.apiIdentifier == currentActiveId) {
                chatViewModel.setModel(newModel.apiIdentifier)
            }

            filterAndSortModels(searchInput.text?.toString().orEmpty())
        }
    }

    private fun deleteModel(model: LlmModel) {
        if (model.apiIdentifier == sharedPreferencesHelper.getPreferenceModelnew()) {
            val default = "openrouter/free"
            sharedPreferencesHelper.savePreferenceModelnewchat(default)
            chatViewModel.setModel(default)
            adapter.updateCurrentModel(default)
        }
        models.remove(model)
        saveCustomModels()
        filterAndSortModels(searchInput.text?.toString().orEmpty())
    }

    private fun saveCustomModels() {
        val builtInIds = getModelsList().map { it.apiIdentifier }
        val custom = models.filter { !builtInIds.contains(it.apiIdentifier) }
        sharedPreferencesHelper.saveCustomModels(custom)
    }

    private fun getModelsList() = listOf(
        LlmModel(
            displayName = "OpenRouter: Free",
            apiIdentifier = "openrouter/free",
            isVisionCapable = true,
            isReasoningCapable = true,
            isFree = true
        )
    )

    private fun updateSortButtons(sortOrder: SortOrder) {
        sortBar.check(if (sortOrder == SortOrder.ALPHABETICAL) R.id.sortAlphabeticalButton else R.id.sortDateButton)
    }
}

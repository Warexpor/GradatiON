package io.github.stardomains3.oxproxion

import io.github.stardomains3.oxproxion.Motion.withGrokStackAnimations

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

class BotModelPickerFragment : Fragment() {

    var onModelSelected: ((String) -> Unit)? = null
    private lateinit var adapter: BotModelAdapter
    private var models = mutableListOf<LlmModel>()
    private lateinit var chatViewModel: ChatViewModel
    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper
    private lateinit var searchInput: EditText
    private lateinit var modelPickerEmpty: View
    private lateinit var chips: ModelFilterChips
    private var filteredModels: MutableList<LlmModel> = mutableListOf()
    private var addPopover: PickerPopover? = null

    private var currentFilter = ModelFilter.ALL
    private var currentSortOrder: SortOrder = SortOrder.ALPHABETICAL

    enum class SortOrder { ALPHABETICAL, BY_DATE }

    companion object {
        const val TAG = "BotModelPickerFragment"
        private const val DEFAULT_MODEL_ID = "openrouter/free"
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
        searchInput = view.findViewById(R.id.modelSearchInput)
        modelPickerEmpty = view.findViewById(R.id.modelPickerEmpty)

        currentSortOrder = sharedPreferencesHelper.getBotModelPickerSortOrder()
        currentFilter = ModelFilter.fromPrefs(
            sharedPreferencesHelper.getBotPickerFilterType(),
            sharedPreferencesHelper.getBotPickerCostFilter()
        )

        view.findViewById<View>(R.id.modelPickerBack).setOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<View>(R.id.modelPickerAdd).setOnClickListener { showAddPopover(it) }
        view.findViewById<MaterialButton>(R.id.btnClearFilters).setOnClickListener { clearFilters() }

        chips = ModelFilterChips(
            view.findViewById<LinearLayout>(R.id.modelFilterChips),
            newestFirst = currentSortOrder == SortOrder.BY_DATE,
            selected = currentFilter,
            onSort = { newest ->
                currentSortOrder = if (newest) SortOrder.BY_DATE else SortOrder.ALPHABETICAL
                sharedPreferencesHelper.saveBotModelPickerSortOrder(currentSortOrder)
                refilter()
            },
            onFilter = { f ->
                currentFilter = f
                sharedPreferencesHelper.saveBotPickerFilterType(ModelFilter.typePref(f))
                sharedPreferencesHelper.saveBotPickerCostFilter(ModelFilter.costPref(f))
                refilter()
            }
        )

        searchInput.doAfterTextChanged { refilter() }

        adapter = BotModelAdapter(filteredModels, sharedPreferencesHelper.getPreferenceModelnew(),
            onItemClicked = { selectedModel ->
                onModelSelected?.invoke(selectedModel.apiIdentifier)
                parentFragmentManager.popBackStack()
            },
            onItemOptions = { showOptionsSheet(it) }
        )
        recyclerView.adapter = adapter

        chatViewModel.customModelsUpdated.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                loadModels()
            }
        }

        loadModels()
    }

    override fun onDestroyView() {
        addPopover?.dismiss(animated = false)
        addPopover = null
        super.onDestroyView()
    }

    private fun showAddPopover(anchor: View) {
        val host = view as? FrameLayout ?: return
        if (addPopover?.isShowing == true) {
            addPopover?.dismiss()
            return
        }
        val rows = listOf(
            PickerPopover.Row(
                getString(R.string.model_add_openrouter), getString(R.string.model_add_openrouter_sub),
                R.drawable.ic_brand_openrouter
            ) { openOpenRouterModels() },
            PickerPopover.Row(
                getString(R.string.model_add_lan), getString(R.string.model_add_lan_sub),
                R.drawable.ic_local_network
            ) { openLanModels() },
            PickerPopover.Row(
                getString(R.string.model_add_custom), getString(R.string.model_add_custom_sub),
                R.drawable.ic_edit
            ) { showAddByIdDialog() },
        )
        addPopover = PickerPopover(host, anchor, host.findViewById(R.id.modelPickerBackdrop)).also { p ->
            p.onDismiss = { if (addPopover === p) addPopover = null }
            p.show(getString(R.string.model_add_title), rows)
        }
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
        refilter()
    }

    private fun refilter() = filterAndSortModels(searchInput.text?.toString().orEmpty())

    private fun filterAndSortModels(query: String) {
        var tempFiltered = models.filter { currentFilter.matches(it) }
        if (query.isNotEmpty()) {
            tempFiltered = tempFiltered.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.apiIdentifier.contains(query, ignoreCase = true) ||
                    ModelBrands.of(it)?.name?.contains(query, ignoreCase = true) == true
            }
        }
        filteredModels = when (currentSortOrder) {
            SortOrder.ALPHABETICAL -> tempFiltered.sortedBy {
                ModelNames.withoutProvider(it.displayName, it.apiIdentifier).lowercase()
            }
            SortOrder.BY_DATE -> tempFiltered.sortedByDescending { it.created }
        }.toMutableList()
        adapter.updateModels(filteredModels)
        modelPickerEmpty.isVisible = filteredModels.isEmpty()
    }

    private fun clearFilters() {
        currentFilter = ModelFilter.ALL
        sharedPreferencesHelper.saveBotPickerFilterType(ModelFilter.typePref(currentFilter))
        sharedPreferencesHelper.saveBotPickerCostFilter(ModelFilter.costPref(currentFilter))
        chips.select(currentFilter)
        searchInput.setText("")
        refilter()
    }

    /** Long-press on a row: edit, open its OpenRouter page, or remove it. */
    private fun showOptionsSheet(model: LlmModel) {
        val dialog = BottomSheetDialog(requireContext(), R.style.ThemeOverlay_Grokion_BottomSheet)
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_model_options, null)
        dialog.setContentView(sheet)
        val locked = model.apiIdentifier == DEFAULT_MODEL_ID
        val hasPage = !model.isLANModel && !model.apiIdentifier.startsWith("@preset") &&
            !DemoModel.isDemo(model.apiIdentifier)
        ModelRowViews(sheet.findViewById(R.id.modelOptionsHeader)).bind(model, selected = false)
        sheet.findViewById<View>(R.id.modelOptionsHeader).isClickable = false
        sheet.findViewById<View>(R.id.menu_edit).apply {
            isVisible = !locked
            setOnClickListener { dialog.dismiss(); showEditModelDialog(model) }
        }
        sheet.findViewById<View>(R.id.menu_open_page).apply {
            isVisible = hasPage
            setOnClickListener { dialog.dismiss(); openModelPage(model) }
        }
        sheet.findViewById<View>(R.id.menu_delete).apply {
            isVisible = !locked
            setOnClickListener { dialog.dismiss(); showDeleteConfirmationDialog(model) }
        }
        sheet.findViewById<View>(R.id.modelOptionsLocked).isVisible = locked
        // Glass before show: first frame must not stack content bg_bottom_sheet under the container.
        GlassChrome.glassDialog(dialog)
        dialog.show()
    }

    private fun openModelPage(model: LlmModel) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "https://openrouter.ai/${model.apiIdentifier}".toUri()))
        } catch (_: Exception) {
            GlassNotice.show(requireContext(), getString(R.string.toast_open_browser_failed))
        }
    }

    private fun showAddByIdDialog() {
        val dialog = EditModelDialogFragment()
        dialog.onModelAdded = { m ->
            if (chatViewModel.modelExists(m.apiIdentifier)) {
                GlassNotice.show(requireContext(), getString(R.string.model_catalog_already))
            } else {
                chatViewModel.addCustomModel(m)
                loadModels()
            }
        }
        dialog.show(parentFragmentManager, "add_model_dialog")
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
            message = getString(
                R.string.delete_model_body,
                ModelNames.withoutProvider(model.displayName, model.apiIdentifier),
            ),
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

            refilter()
        }
    }

    private fun deleteModel(model: LlmModel) {
        if (model.apiIdentifier == sharedPreferencesHelper.getPreferenceModelnew()) {
            sharedPreferencesHelper.savePreferenceModelnewchat(DEFAULT_MODEL_ID)
            chatViewModel.setModel(DEFAULT_MODEL_ID)
            adapter.updateCurrentModel(DEFAULT_MODEL_ID)
        }
        models.remove(model)
        saveCustomModels()
        refilter()
    }

    private fun saveCustomModels() {
        val builtInIds = getModelsList().map { it.apiIdentifier }
        val custom = models.filter { !builtInIds.contains(it.apiIdentifier) }
        sharedPreferencesHelper.saveCustomModels(custom)
    }

    private fun getModelsList() = listOf(
        LlmModel(
            displayName = "OpenRouter: Free",
            apiIdentifier = DEFAULT_MODEL_ID,
            isVisionCapable = true,
            isReasoningCapable = true,
            isFree = true
        )
    )
}

package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Builds the app ViewModels from the Application handed in.
 * The framework's AndroidViewModelFactory caches the first Application in a static, which is
 * wrong under tests (a new Application per run) and is not used here.
 */
class AppViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val viewModel: ViewModel = when {
            ChatViewModel::class.java.isAssignableFrom(modelClass) -> ChatViewModel(application)
            SavedChatsViewModel::class.java.isAssignableFrom(modelClass) -> SavedChatsViewModel(application)
            else -> throw IllegalArgumentException("Unknown model ${modelClass.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return viewModel as T
    }
}

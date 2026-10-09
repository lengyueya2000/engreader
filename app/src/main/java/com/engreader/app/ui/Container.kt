package com.engreader.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engreader.app.data.AppContainer

/** Makes the manual DI graph reachable from any composable without threading it through. */
val LocalContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer was not provided")
}

/**
 * Builds a ViewModel with a constructor argument, using the app's container.
 *
 * [key] must identify the constructor arguments, and must be stable for the life of
 * the destination. There is no NavHost here, so the `ViewModelStoreOwner` is the
 * activity and its store lives as long as the activity does: without a key, a
 * ViewModel built for one argument set would be handed back for every later call,
 * and the reader would keep serving the first article it ever opened.
 *
 * The ViewModel outlives the composable, which is what a tab wants — switching tabs
 * should not throw away a loaded feed. A destination opened per item should use
 * [containerScopedViewModel] instead, so its ViewModels are released on exit.
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String,
    crossinline factory: (AppContainer) -> VM,
): VM = viewModel(key = key, factory = containerFactory(key, factory))

/**
 * Builds a ViewModel in a store that belongs to this composable.
 *
 * The store is cleared when the composable leaves composition. That is the right
 * scope for the reader: each article gets a fresh ViewModel, and closing the reader
 * releases it instead of accumulating one ViewModel per article for the life of the
 * activity.
 */
@Composable
inline fun <reified VM : ViewModel> containerScopedViewModel(
    key: String,
    crossinline factory: (AppContainer) -> VM,
): VM {
    val owner = remember(key) { ScopedViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return viewModel(
        viewModelStoreOwner = owner,
        key = key,
        factory = containerFactory(key, factory),
    )
}

/** A `ViewModelStoreOwner` that is not tied to the activity. */
@PublishedApi
internal class ScopedViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@PublishedApi
@Composable
internal inline fun <reified VM : ViewModel> containerFactory(
    key: String,
    crossinline factory: (AppContainer) -> VM,
): ViewModelProvider.Factory {
    val container = LocalContainer.current
    return remember(container, key) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = factory(container) as T
        }
    }
}

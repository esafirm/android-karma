package stream.nolambda.karma

import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import stream.nolambda.karma.config.ActionExecutorFactory
import stream.nolambda.karma.config.DefaultActionExecutorFactory
import stream.nolambda.karma.observer.DefaultStateObserverFactory
import stream.nolambda.karma.observer.StateObserverFactory
import stream.nolambda.karma.ui.PresenterHolder

object Karma {
    var isTestMode: Boolean = false
    var executor: ActionExecutorFactory = DefaultActionExecutorFactory()
    var stateObserver: StateObserverFactory = DefaultStateObserverFactory()
    var enableLog = false

    fun setTestMode() {
        isTestMode = true
    }

    /**
     * Bind all required component to create Karma
     * The [LifecycleOwner] and [ViewModelStoreOwner] is separated to accommodate [Fragment]
     * leaky nature
     *
     * Use the extension function for [Activity]
     */
    fun <S, P : KarmaPresenter<S>> bind(
        lifecycleOwner: LifecycleOwner,
        viewModelStoreOwner: ViewModelStoreOwner,
        presenter: () -> P,
        render: (S, P) -> Unit
    ) {

        val presenterHolder: PresenterHolder by lazy {
            ViewModelProvider(viewModelStoreOwner).get(PresenterHolder::class.java)
        }

        val currentPresenter = presenterHolder.bindPresenter(presenter)

        currentPresenter.attach(lifecycleOwner) {
            render(it, currentPresenter)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : KarmaPresenter<*>> PresenterHolder.bindPresenter(block: () -> T): T {
        val currentPresenter = presenter ?: block()
        presenter = currentPresenter
        return currentPresenter as T
    }
}

/**
 * extension function for [Karma.bind]
 */
fun <S, P : KarmaPresenter<S>> Fragment.bind(
    presenter: () -> P,
    render: (S, P) -> Unit
) {
    Karma.bind(viewLifecycleOwner, this, presenter, render)
}

fun <S, P : KarmaPresenter<S>> ComponentActivity.bind(
    presenter: () -> P,
    render: (S, P) -> Unit
) {
    Karma.bind(this, this, presenter, render)
}

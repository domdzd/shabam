package app.nanotune

import android.app.Application
import kotlinx.coroutines.launch

class NanoTuneApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            // A session can only be running if its foreground service is alive in this process;
            // anything still marked open was interrupted (process death, revoked permission...).
            if (!container.sessionState.state.value.active) {
                container.historyRepository.closeDanglingSessions()
            }
            container.serverRepository.refresh()
            container.syncRepository.syncNow()
        }
    }
}

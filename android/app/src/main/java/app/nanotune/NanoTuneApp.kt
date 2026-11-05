package app.nanotune

import android.app.Application
import app.nanotune.feature.recordings.RecordingsViewModel
import app.nanotune.service.ClipRetryWorker
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
            // Encrypt recordings saved by 0.2, apply the retention period, drop copies shared earlier,
            // and pick up recordings still waiting to be identified.
            container.clipRepository.sealLegacy()
            container.clipRepository.applyRetention()
            runCatching { RecordingsViewModel.sharedDir(this@NanoTuneApp).listFiles()?.forEach { it.delete() } }
            if (container.clipRepository.pendingRetries().isNotEmpty()) ClipRetryWorker.schedule(this@NanoTuneApp)
            container.syncRepository.syncNow()
        }
    }
}

package fr.lecteur

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Settings.init(this)
        Settings.applyNightMode()
        // v22 : l'égaliseur de volume est remis à « désactivé » (grésillements signalés)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("normalizeReset22", false)) {
            Settings.normalize = false
            prefs.edit().putBoolean("normalizeReset22", true).apply()
        }
        Playback.init(this)
        Music.init(this)
        PoToken.init(this)
        NewContentWorker.schedule(this)
        NewPipe.init(DownloaderImpl.instance, Localization("fr", "FR"), ContentCountry("FR"))
    }
}

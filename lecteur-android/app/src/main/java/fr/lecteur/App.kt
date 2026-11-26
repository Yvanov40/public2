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
        // v33 : jeton anti-robot désactivé par défaut (coupures en milieu de titre)
        if (!prefs.getBoolean("poTokenReset33", false)) {
            Settings.poToken = false
            prefs.edit().putBoolean("poTokenReset33", true).apply()
        }
        if (!prefs.getBoolean("normalizeReset22", false)) {
            Settings.normalize = false
            prefs.edit().putBoolean("normalizeReset22", true).apply()
        }
        Playback.init(this)
        Music.init(this)
        PoToken.init(this)
        LocalStream.init(this)
        Caster.init(this)
        NewContentWorker.schedule(this)
        NewPipe.init(DownloaderImpl.instance, Localization("fr", "FR"), ContentCountry("FR"))
    }
}

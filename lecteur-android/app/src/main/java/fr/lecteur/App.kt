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
        Playback.init(this)
        NewContentWorker.schedule(this)
        NewPipe.init(DownloaderImpl.instance, Localization("fr", "FR"), ContentCountry("FR"))
    }
}

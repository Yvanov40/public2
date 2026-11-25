package fr.lecteur

import android.app.Application
import com.google.android.material.color.DynamicColors
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        NewPipe.init(DownloaderImpl.instance, Localization("fr", "FR"), ContentCountry("FR"))
    }
}

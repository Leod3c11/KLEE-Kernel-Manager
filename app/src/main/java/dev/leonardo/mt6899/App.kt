package dev.leonardo.mt6899

import android.app.Application
import com.topjohnwu.superuser.Shell

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(20))
    }
}

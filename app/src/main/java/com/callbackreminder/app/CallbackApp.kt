package com.callbackreminder.app

import android.app.Application

class CallbackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        PhoneOff.markAlive(this)
        ReminderStore.init(this)
        RecordingStore.init(this)
        SmsStore.init(this)
        OffDays.init(this)
        ReviewPrefs.migrate(this)
        Notifier.createChannels(this)
        SmsNotifier.createChannel(this)
        WidgetSync.start(this)
        TeamRepo.init(this)
    }
}

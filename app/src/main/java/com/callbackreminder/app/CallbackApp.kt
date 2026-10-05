package com.callbackreminder.app

import android.app.Application

class CallbackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReminderStore.init(this)
        Notifier.createChannels(this)
    }
}

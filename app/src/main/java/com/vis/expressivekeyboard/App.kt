package com.vis.expressivekeyboard

import android.app.Application
import com.vis.expressivekeyboard.ai.AiEngine
import com.vis.expressivekeyboard.data.ClipboardStore
import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.engine.Dictionary
import com.vis.expressivekeyboard.engine.EmojiData

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        ClipboardStore.init(this)
        Dictionary.loadAsync(this)
        EmojiData.loadAsync(this)
        AiEngine.init(this)
        com.vis.expressivekeyboard.voice.VoiceEngine.init(this)
        com.vis.expressivekeyboard.update.Updater.init(this)
    }
}

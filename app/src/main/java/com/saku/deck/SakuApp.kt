package com.saku.deck

import android.app.Application

class SakuApp : Application() {
    lateinit var controller: DeckController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = DeckController(this)
        controller.init()
    }
}

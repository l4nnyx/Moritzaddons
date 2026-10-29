package com.github.noamm9.moritzaddons

import com.github.noamm9.NoammAddons
import net.fabricmc.api.ClientModInitializer

object Moritzaddons: ClientModInitializer {
    override fun onInitializeClient() {
        NoammAddons.logger.info("Hi from ${this.javaClass.simpleName}!")
    }
}
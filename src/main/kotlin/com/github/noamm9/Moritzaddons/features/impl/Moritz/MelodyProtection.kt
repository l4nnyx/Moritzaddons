package com.github.noamm9.features.impl.floor7.terminals

import com.github.noamm9.config.types.SliderSetting
import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.ContainerEvent
import com.github.noamm9.event.impl.TerminalEvent
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.floor7.terminals.TerminalListener
import com.github.noamm9.features.impl.floor7.terminals.impl.MelodyTerminal
import com.github.noamm9.utils.ChatUtils
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ChestMenu

object MelodyProtection : Feature(
    name = "Melody Anti-Missclick",
    description = "Prevents missclicking in the Melody terminal when the pane is not aligned.",
    toggled = false
) {
    private val blockWrongSlots by ToggleSetting("Block Non-Button Slots", true)
        .section("Melody Rules")
        .withDescription("Cancels clicks on any slot other than the active row's button.")

    private val blockUnaligned by ToggleSetting("Block Unaligned Clicks", true)
        .withDescription("Cancels clicks on the button if the green pane is not on the target column.")

    private val preventDoubleClick by ToggleSetting("Prevent Double Click", true)
        .withDescription("Blocks clicking the same row button multiple times before the server updates.")

    private val clickCooldown by SliderSetting("Spam Cooldown", 250, 0, 500, 10, "ms")
        .withDescription("Minimum delay between valid button clicks.")

    private val chatAlerts by ToggleSetting("Chat Feedback", false)
        .section("Notifications")
        .withDescription("Prints a message in chat whenever a missclick is blocked.")

    private var lastClickTime = 0L
    private var lastClickedSlot: Int? = null

    private const val SIM_SCREEN_CLASS =
        "com.github.noamm9.features.impl.floor7.terminals.sim.TerminalSimScreen"

    /** The sim screen if it's currently open (resolved by name, so no compile-time dependency). */
    private val simScreen: Screen?
        get() = mc.screen?.takeIf { it.javaClass.name == SIM_SCREEN_CLASS }

    /** The sim screen's terminal handler, read via its `getTerminal()` getter. */
    private val simTerminal: Any?
        get() {
            val screen = simScreen ?: return null
            return try {
                screen.javaClass.methods
                    .firstOrNull { it.name == "getTerminal" && it.parameterCount == 0 }
                    ?.invoke(screen)
            } catch (_: Throwable) {
                null
            }
        }

    private val currentHandler: Any?
        get() = simTerminal ?: TerminalListener.currentHandler

    override fun init() {
        register<TerminalEvent.SlotUpdate> {
            if (event.handler is MelodyTerminal) {
                lastClickedSlot = null
            }
        }

        register<TerminalEvent.Break> {
            lastClickedSlot = null
        }

        register<TerminalEvent.Close> {
            lastClickedSlot = null
            lastClickTime = 0L
        }

        register<ContainerEvent.MouseClick>(EventPriority.HIGH) {
            val handler = currentHandler ?: return@register
            if (handler !is MelodyTerminal) return@register

            val current = MelodyTerminal.current ?: return@register
            val correct = MelodyTerminal.correct ?: return@register
            val buttonRow = MelodyTerminal.buttonRow ?: return@register
            val actualSlot = buttonRow * 9 + 16

            // Resolve the clicked slot ID from the container screen
            val clickedSlotId = getHoveredSlotId()
            if (clickedSlotId != null && blockWrongSlots.value && clickedSlotId != actualSlot) {
                event.isCanceled = true
                alert("Blocked click on wrong slot ($clickedSlotId != $actualSlot)")
                return@register
            }

            // Block if the moving green pane isn't aligned with the magenta target
            if (blockUnaligned.value && current != correct) {
                event.isCanceled = true
                alert("Blocked early/late click (current: ${current + 1}, target: ${correct + 1})")
                return@register
            }

            // Block accidental double-clicks on the same row before the terminal updates
            if (preventDoubleClick.value && simScreen == null && lastClickedSlot == actualSlot) {
                event.isCanceled = true
                alert("Blocked duplicate click on row ${buttonRow + 1}")
                return@register
            }

            // Block clicks faster than the cooldown threshold
            val now = System.currentTimeMillis()
            if (now - lastClickTime < clickCooldown.value) {
                event.isCanceled = true
                return@register
            }

            lastClickTime = now
            lastClickedSlot = actualSlot
        }
    }

    /**
     * Finds the slot under the mouse without touching the protected `hoveredSlot` field.
     * Returns null if not in a container screen or the mouse isn't over a slot.
     */
    private fun getHoveredSlotId(): Int? {
        val screen = mc.screen as? AbstractContainerScreen<*> ?: return null
        val window = mc.window

        // Mouse position in GUI-scaled coordinates
        val mouseX = mc.mouseHandler.xpos() * window.guiScaledWidth / window.screenWidth
        val mouseY = mc.mouseHandler.ypos() * window.guiScaledHeight / window.screenHeight

        // Same layout math vanilla uses for chest-style GUIs (leftPos/topPos are protected)
        val rows = (screen.menu as? ChestMenu)?.rowCount ?: 6
        val imageWidth = 176
        val imageHeight = 114 + rows * 18
        val leftPos = (screen.width - imageWidth) / 2
        val topPos = (screen.height - imageHeight) / 2

        return screen.menu.slots.firstOrNull { slot ->
            mouseX >= leftPos + slot.x - 1 && mouseX < leftPos + slot.x + 17 &&
                    mouseY >= topPos + slot.y - 1 && mouseY < topPos + slot.y + 17
        }?.index
    }

    private fun alert(message: String) {
        if (chatAlerts.value) {
            ChatUtils.modMessage("§c[MelodyProtection] §7$message")
        }
    }
}
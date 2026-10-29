package com.github.noamm9.moritzaddons.features.impl.floor7

import com.github.noamm9.config.types.ToggleSetting
import com.github.noamm9.event.impl.PacketEvent
import com.github.noamm9.event.impl.TerminalEvent
import com.github.noamm9.event.priority.EventPriority
import com.github.noamm9.features.Feature
import com.github.noamm9.features.impl.floor7.terminals.TerminalListener
import com.github.noamm9.features.impl.floor7.terminals.impl.MelodyTerminal
import com.github.noamm9.utils.ChatUtils
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.world.item.Items

object MelodyProtection : Feature(
    name = "Melody Anti-Missclick",
    description = "Blocks wrong, skipped and duplicate clicks in the Melody terminal.",
    toggled = false
) {
    private val blockDuringFcDelay by ToggleSetting("Block During First-Click Delay", false)
        .section("Protections")
        .withDescription("Also blocks clicks during the first-click delay. Leave off if every click gets blocked.")

    private val debug by ToggleSetting("Debug Messages", false)
        .section("Debug")
        .withDescription("Prints every Melody click and the reason for each block in chat.")

    private const val CLICK_COOLDOWN = 150L

    private var lastClickTime = 0L
    private var lastClickedSlot: Int? = null

    private const val SIM_SCREEN_CLASS =
        "com.github.noamm9.features.impl.floor7.terminals.sim.TerminalSimScreen"

    private val simScreen: Screen?
        get() = mc.screen?.takeIf { it.javaClass.name == SIM_SCREEN_CLASS }

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

    private fun block(event: PacketEvent.Sent, reason: String) {
        event.isCanceled = true
        if (debug.value) ChatUtils.modMessage("§c[MP] blocked: $reason")
    }

    override fun init() {
        register<TerminalEvent.Break> {
            lastClickedSlot = null
        }

        register<TerminalEvent.Close> {
            lastClickedSlot = null
            lastClickTime = 0L
        }

        // Intercepts the click packet, so it covers vanilla clicks,
        // TerminalSolver and AutoTerminal.
        register<PacketEvent.Sent>(EventPriority.HIGHEST) {
            val packet = event.packet as? ServerboundContainerClickPacket ?: return@register
            val handler = currentHandler ?: return@register
            if (handler !is MelodyTerminal) return@register

            val clickedSlotId = packet.slotNum.toInt()
            if (clickedSlotId < 0) return@register

            val current = MelodyTerminal.current ?: return@register
            val correct = MelodyTerminal.correct ?: return@register
            val activeRow = MelodyTerminal.buttonRow ?: return@register

            if (debug.value) {
                ChatUtils.modMessage("§e[MP] slot=$clickedSlotId current=$current correct=$correct row=$activeRow")
            }

            // Optional: block clicks during the first-click delay.
            if (blockDuringFcDelay.value) {
                val inDelay = try {
                    val sim = simScreen
                    if (sim != null) {
                        sim.javaClass.methods
                            .firstOrNull { it.name == "isFirstClickDelay" && it.parameterCount == 0 }
                            ?.invoke(sim) as? Boolean ?: false
                    } else {
                        TerminalListener.checkFcDelay()
                    }
                } catch (_: Throwable) {
                    false
                }
                if (inDelay) {
                    block(event, "first-click delay")
                    return@register
                }
            }

            // Only the four button slots (16, 25, 34, 43) are ever checked.
            val isButtonSlot = clickedSlotId % 9 == 7 && clickedSlotId in 16..43
            if (!isButtonSlot) return@register

            val clickedRow = (clickedSlotId - 16) / 9

            // Clicks on rows that are already done are harmless.
            if (clickedRow < activeRow) return@register

            if (clickedRow > activeRow) {
                // Skip click. On the first row it triggers the Hypixel freeze penalty.
                if (activeRow == 0) {
                    block(event, "skip on first row (clicked row $clickedRow)")
                    return@register
                }

                // Later rows: only allow the skip if the clicked row's target
                // already holds the moving pane. If the slots can't be read, let it through.
                val screen = mc.screen as? AbstractContainerScreen<*> ?: return@register
                val slots = screen.menu.slots
                val movingPane = slots.getOrNull((activeRow + 1) * 9 + 1 + current)?.item?.item
                val itemAtTarget = slots.getOrNull((clickedRow + 1) * 9 + 1 + correct)?.item?.item
                if (movingPane == null || itemAtTarget == null) return@register

                if (itemAtTarget != movingPane || movingPane == Items.AIR) {
                    block(event, "bad skip (clicked row $clickedRow, active row $activeRow)")
                    return@register
                }
            } else {
                // Active row: the moving pane has to be on the target column.
                if (current != correct) {
                    block(event, "not aligned (current=$current, correct=$correct)")
                    return@register
                }
            }

            // Duplicate click on the same button within the cooldown.
            val now = System.currentTimeMillis()
            if (lastClickedSlot == clickedSlotId && now - lastClickTime < CLICK_COOLDOWN) {
                block(event, "double click on slot $clickedSlotId")
                return@register
            }

            lastClickTime = now
            lastClickedSlot = clickedSlotId
        }
    }
}
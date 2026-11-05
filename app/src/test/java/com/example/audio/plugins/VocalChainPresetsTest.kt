package com.example.audio.plugins

import com.overlay.audio.plugins.PluginChain
import com.overlay.audio.plugins.PluginKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocalChainPresetsTest {

    @Test
    fun standardChainIsOrderedCleanToSpace() {
        val kinds = VocalChainPresets.standard().map { it.kind }
        val denoise = kinds.indexOf(PluginKind.PHONE_NOISE_REDUCTION)
        val rider = kinds.indexOf(PluginKind.VOCAL_RIDER)
        val eq = kinds.indexOf(PluginKind.NEVE_1073)
        val resonance = kinds.indexOf(PluginKind.RESONANCE_SMOOTHER)
        val comp1 = kinds.indexOf(PluginKind.FET_1176)
        val comp2 = kinds.indexOf(PluginKind.OPTO_LA2A)
        val deEss = kinds.indexOf(PluginKind.DE_ESSER)
        val delay = kinds.indexOf(PluginKind.TEMPO_DELAY)
        val reverb = kinds.indexOf(PluginKind.PLATE_REVERB)

        assertTrue(denoise < rider)
        assertTrue(rider < eq)
        assertTrue(eq < resonance)
        assertTrue(resonance < comp1)
        assertTrue(comp1 < comp2)
        assertTrue(comp2 < deEss)
        assertTrue(deEss < delay)
        assertTrue(delay < reverb)
        assertEquals(kinds.lastIndex, reverb)
    }

    @Test
    fun chainSlotsHaveStableUniqueIds() {
        val chain = VocalChainPresets.standard()
        assertEquals(chain.size, chain.map { it.id }.toSet().size)
        assertEquals(chain.size, chain.map { it.kind }.toSet().size)
    }

    @Test
    fun replacingASlotKeepsItsPositionAndCompatibleParameters() {
        val chain = PluginChain(VocalChainPresets.standard())
        val eqSlot = chain.slots.first { it.kind == PluginKind.NEVE_1073 }
        val index = chain.slots.indexOf(eqSlot)

        val replaced = chain.replace(eqSlot.id, PluginKind.SSL_CHANNEL_EQ)
        val newSlot = replaced.slots[index]

        assertEquals(eqSlot.id, newSlot.id)
        assertEquals(PluginKind.SSL_CHANNEL_EQ, newSlot.kind)
        assertEquals(index, replaced.slots.indexOfFirst { it.id == eqSlot.id })
        // Both channel EQs expose an HPF under slightly different keys, so the
        // frequency survives the swap instead of silently resetting.
        assertEquals(eqSlot.value("hpfHz"), newSlot.value("hpHz"), 0.001f)
        // A parameter that only existed on the Neve is dropped, not carried
        // over as a stale key the SSL cannot interpret.
        assertTrue("driveDb" !in newSlot.params)
    }

    @Test
    fun replacingWithTheSameKindIsIdempotent() {
        val chain = PluginChain(VocalChainPresets.standard())
        val slot = chain.slots.first()
        val replaced = chain.replace(slot.id, slot.kind)
        assertEquals(slot, replaced.slots.first())
        assertNotEquals(0, replaced.slots.size)
    }
}

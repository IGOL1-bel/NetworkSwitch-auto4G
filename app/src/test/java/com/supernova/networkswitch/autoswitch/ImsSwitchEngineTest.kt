package com.supernova.networkswitch.autoswitch

import com.supernova.networkswitch.autoswitch.ImsSwitchEngine.Companion.NO_SAVED_MODE
import com.supernova.networkswitch.autoswitch.ImsSwitchEngine.Companion.SAMPLE_NO_VOLTE
import com.supernova.networkswitch.autoswitch.ImsSwitchEngine.Companion.SAMPLE_UNKNOWN
import com.supernova.networkswitch.autoswitch.ImsSwitchEngine.Companion.SAMPLE_VOLTE
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImsSwitchEngineTest {

    private val lteOnly = 11
    private val nrLteGsmWcdma = 26

    /** Stand-in for the radio and the persisted state. */
    private class Fake(var mode: Int = 26, var saved: Int = NO_SAVED_MODE) {
        var writeSucceeds = true

        /** The write takes effect although it is reported as failed (a lagging read-back). */
        var appliesButReportsFailure = false
        var readable = true
        val writes = mutableListOf<Int>()

        fun engine(confirmations: Int = 2, cooldown: Int = 3) = ImsSwitchEngine(
            readMode = { if (readable) mode else null },
            writeMode = { target ->
                writes += target
                if (writeSucceeds || appliesButReportsFailure) mode = target
                writeSucceeds
            },
            loadSavedMode = { saved },
            storeSavedMode = { saved = it },
            confirmations = confirmations,
            failureCooldown = cooldown,
        )
    }

    @Test
    fun `a single VoLTE sample does not switch`() = runTest {
        val fake = Fake()
        fake.engine().onSample(SAMPLE_VOLTE)

        assertTrue(fake.writes.isEmpty())
        assertEquals(26, fake.mode)
    }

    @Test
    fun `confirmed VoLTE switches to 4G only and remembers the previous mode`() = runTest {
        val fake = Fake(mode = 26)
        val engine = fake.engine()

        engine.onSample(SAMPLE_VOLTE)
        engine.onSample(SAMPLE_VOLTE)

        assertEquals(lteOnly, fake.mode)
        assertEquals(nrLteGsmWcdma, fake.saved)
        assertEquals(listOf(lteOnly), fake.writes)
    }

    @Test
    fun `leaving VoLTE coverage restores the previous mode`() = runTest {
        val fake = Fake(mode = 26)
        val engine = fake.engine()
        engine.onSample(SAMPLE_VOLTE)
        engine.onSample(SAMPLE_VOLTE)

        engine.onSample(SAMPLE_NO_VOLTE)
        assertEquals("one lost sample is not enough", lteOnly, fake.mode)
        engine.onSample(SAMPLE_NO_VOLTE)

        assertEquals(nrLteGsmWcdma, fake.mode)
        assertEquals(NO_SAVED_MODE, fake.saved)
    }

    @Test
    fun `a brief VoLTE dropout does not restore`() = runTest {
        val fake = Fake(mode = 26)
        val engine = fake.engine()
        engine.onSample(SAMPLE_VOLTE)
        engine.onSample(SAMPLE_VOLTE)

        engine.onSample(SAMPLE_NO_VOLTE)
        engine.onSample(SAMPLE_VOLTE)
        engine.onSample(SAMPLE_NO_VOLTE)

        assertEquals(lteOnly, fake.mode)
        assertEquals(nrLteGsmWcdma, fake.saved)
    }

    @Test
    fun `unknown samples reset the confirmation streak`() = runTest {
        val fake = Fake(mode = 26)
        val engine = fake.engine()

        engine.onSample(SAMPLE_VOLTE)
        engine.onSample(SAMPLE_UNKNOWN)
        engine.onSample(SAMPLE_VOLTE)

        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun `already on 4G only saves nothing and restores nothing later`() = runTest {
        val fake = Fake(mode = lteOnly)
        val engine = fake.engine()

        engine.onSample(SAMPLE_VOLTE)
        engine.onSample(SAMPLE_VOLTE)
        engine.onSample(SAMPLE_NO_VOLTE)
        engine.onSample(SAMPLE_NO_VOLTE)

        assertEquals(NO_SAVED_MODE, fake.saved)
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun `an unreadable current mode means no switch`() = runTest {
        val fake = Fake(mode = 26).apply { readable = false }
        val engine = fake.engine()

        engine.onSample(SAMPLE_VOLTE)
        engine.onSample(SAMPLE_VOLTE)

        assertTrue(fake.writes.isEmpty())
        assertEquals(NO_SAVED_MODE, fake.saved)
    }

    @Test
    fun `a failed switch forgets the saved mode and backs off before retrying`() = runTest {
        val fake = Fake(mode = 26).apply { writeSucceeds = false }
        val engine = fake.engine(cooldown = 2)

        engine.onSample(SAMPLE_VOLTE)
        engine.onSample(SAMPLE_VOLTE) // first attempt, fails
        assertEquals(NO_SAVED_MODE, fake.saved)
        assertEquals(1, fake.writes.size)

        engine.onSample(SAMPLE_VOLTE) // cooldown 2 -> 1
        engine.onSample(SAMPLE_VOLTE) // cooldown 1 -> 0
        assertEquals(1, fake.writes.size)

        fake.writeSucceeds = true
        engine.onSample(SAMPLE_VOLTE) // retries and succeeds
        assertEquals(2, fake.writes.size)
        assertEquals(lteOnly, fake.mode)
        assertEquals(nrLteGsmWcdma, fake.saved)
    }

    @Test
    fun `a failed restore keeps the saved mode so it is retried`() = runTest {
        val fake = Fake(mode = lteOnly, saved = nrLteGsmWcdma).apply { writeSucceeds = false }
        val engine = fake.engine(cooldown = 1)

        engine.onSample(SAMPLE_NO_VOLTE)
        engine.onSample(SAMPLE_NO_VOLTE) // fails
        assertEquals(nrLteGsmWcdma, fake.saved)

        engine.onSample(SAMPLE_NO_VOLTE) // cooldown
        fake.writeSucceeds = true
        engine.onSample(SAMPLE_NO_VOLTE) // retry succeeds

        assertEquals(nrLteGsmWcdma, fake.mode)
        assertEquals(NO_SAVED_MODE, fake.saved)
    }

    @Test
    fun `a saved mode from before a restart is restored once VoLTE is gone`() = runTest {
        val fake = Fake(mode = lteOnly, saved = nrLteGsmWcdma)
        val engine = fake.engine() // fresh engine, as after a process restart

        engine.onSample(SAMPLE_NO_VOLTE)
        engine.onSample(SAMPLE_NO_VOLTE)

        assertEquals(nrLteGsmWcdma, fake.mode)
        assertEquals(NO_SAVED_MODE, fake.saved)
    }

    @Test
    fun `restoreIfActive gives the mode back and is a no-op otherwise`() = runTest {
        val idle = Fake(mode = 26)
        assertEquals(null, idle.engine().restoreIfActive())
        assertTrue(idle.writes.isEmpty())

        val active = Fake(mode = lteOnly, saved = nrLteGsmWcdma)
        active.engine().restoreIfActive()
        assertEquals(nrLteGsmWcdma, active.mode)
        assertEquals(NO_SAVED_MODE, active.saved)
    }

    @Test
    fun `a switch that took effect but was reported as failed keeps the saved mode`() = runTest {
        val fake = Fake(mode = 26).apply { appliesButReportsFailure = true }
        val engine = fake.engine()

        engine.onSample(SAMPLE_VOLTE)
        engine.onSample(SAMPLE_VOLTE)

        assertEquals(lteOnly, fake.mode)
        assertEquals(nrLteGsmWcdma, fake.saved)

        fake.appliesButReportsFailure = false
        engine.onSample(SAMPLE_NO_VOLTE)
        engine.onSample(SAMPLE_NO_VOLTE)
        assertEquals(nrLteGsmWcdma, fake.mode)
        assertEquals(NO_SAVED_MODE, fake.saved)
    }

    @Test
    fun `a restore that took effect but was reported as failed still clears the saved mode`() = runTest {
        val fake = Fake(mode = lteOnly, saved = nrLteGsmWcdma).apply { appliesButReportsFailure = true }
        val engine = fake.engine()

        engine.onSample(SAMPLE_NO_VOLTE)
        engine.onSample(SAMPLE_NO_VOLTE)

        assertEquals(nrLteGsmWcdma, fake.mode)
        assertEquals(NO_SAVED_MODE, fake.saved)
    }

    @Test
    fun `a mode changed meanwhile is not overwritten when VoLTE goes away`() = runTest {
        val threeG = 2
        val fake = Fake(mode = threeG, saved = nrLteGsmWcdma) // user left 4G only on purpose
        val engine = fake.engine()

        engine.onSample(SAMPLE_NO_VOLTE)
        engine.onSample(SAMPLE_NO_VOLTE)

        assertEquals(threeG, fake.mode)
        assertTrue(fake.writes.isEmpty())
        assertEquals(NO_SAVED_MODE, fake.saved)
    }
}

package app.toque.core

import app.toque.core.machine.Accessory
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.MachineLoop
import app.toque.core.machine.Snapshot
import app.toque.core.machine.TcpTransport
import app.toque.core.recipes.Cookomix
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Recettes Cookomix converties, jouées contre le jumeau numérique du SDK (tools/twin.py), comme
 * le ferait l'écran de cuisson : chaque réglage converti doit être accepté par le contrôleur et
 * arriver tel quel à la base ; un réglage hors limites doit être refusé avant d'atteindre la base.
 * Ignoré si le jumeau n'est pas lancé.
 */
class CookomixTwinTest {
    @Volatile private var snap = Snapshot()

    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use { it.readText() }

    private fun control(cmd: String): String = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", 8770), 2_000)
        s.soTimeout = 3_000
        s.getOutputStream().write((cmd + "\n").toByteArray())
        s.getInputStream().bufferedReader().readLine() ?: ""
    }

    @Suppress("UNCHECKED_CAST")
    private fun twin(): Map<String, Any?> = app.toque.core.json.Json.parse(control("state")) as Map<String, Any?>

    private fun twinUp(): Boolean = try {
        control("state").startsWith("{")
    } catch (_: Exception) {
        false
    }

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("délai dépassé : $what")
    }

    @Test fun cookomixStepsAgainstSdkTwin() {
        assumeTrue("jumeau non lancé", twinUp())
        control("lid 0"); control("error 0"); control("silent 0"); control("temp 20")
        val loop = MachineLoop(transportFactory = { TcpTransport("127.0.0.1", 8765) }, onSnapshot = { snap = it }, onEvent = {})
        loop.start()
        try {
            waitFor(4_000, "liaison prête") { snap.link == LinkState.READY }

            val recipe = Cookomix.parsePage(fixture("cookomix-page.html"), "https://www.cookomix.com/recettes/essai/").recipe!!
            val machines = recipe.steps.mapNotNull { it.machine }.filter { it.program != StepProgram.TURBO }
            assertTrue(machines.size >= 6)
            for ((i, full) in machines.withIndex()) {
                // Même réglage, durée raccourcie pour l'essai.
                val m = full.copy(seconds = minOf(full.seconds, 3))
                assertTrue("étape $i : ${m.violations()}", m.valid)
                val v = loop.call { it.startJob(m.toJob("essai $i", hotBlendConfirmed = true)!!) }
                assertTrue("étape $i (${m.summary}) refusée : ${v.message}", v.ok)
                waitFor(3_000, "étape $i en marche") { twin()["motor_on"] == true || twin()["heater_on"] == true }
                if (m.program == StepProgram.MANUAL && m.tempC > 0)
                    assertEquals("étape $i : température envoyée", m.tempC, (twin()["target_temperature"] as Number).toInt())
                waitFor(15_000, "fin de l'étape $i") { snap.jobState == JobState.FINISHED.name }
                waitFor(4_000, "base arrêtée après l'étape $i") { (twin()["rpm"] as Number).toInt() == 0 }
                loop.call { it.clearFinished() }
                control("temp 20")
            }

            // Réglages hors limites du MCC : refusés par le contrôleur, la base ne bouge pas.
            for (bad in listOf(
                StepMachine(seconds = 30, speed = 6, tempC = 90),
                StepMachine(seconds = 30, speed = 5, reverse = true),
                StepMachine(seconds = 30, speed = 6, accessory = Accessory.WHISK),
            )) {
                assertFalse("$bad aurait dû être refusé", loop.call { it.startJob(bad.toJob("interdit")!!) }.ok)
                Thread.sleep(300)
                assertEquals(0, (twin()["rpm"] as Number).toInt())
                assertEquals(false, twin()["heater_on"])
            }
            // Turbo avec le fouet en place (étape Turbo convertie après « Ajouter le fouet ») : refusé.
            assertFalse(loop.call { it.turboPress(Accessory.WHISK) }.ok)
            // Turbo à chaud : refusé.
            control("temp 80")
            Thread.sleep(600)
            assertFalse(loop.call { it.turboPress(Accessory.BLADE) }.ok)
            control("temp 20")
        } finally {
            loop.shutdown()
        }
    }
}

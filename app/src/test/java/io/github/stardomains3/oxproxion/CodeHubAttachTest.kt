package io.github.stardomains3.oxproxion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.stardomains3.oxproxion.code.ApprovalOption
import io.github.stardomains3.oxproxion.code.CodeHub
import io.github.stardomains3.oxproxion.code.HarnessKind
import io.github.stardomains3.oxproxion.code.NewSessionRequest
import io.github.stardomains3.oxproxion.code.PermissionMode
import io.github.stardomains3.oxproxion.code.store.CodeStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hub attach / saveHost sticky-attached (review #20 F1) and related bookkeeping.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*CodeHubAttachTest*'
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ScreenshotApp::class, sdk = [35])
class CodeHubAttachTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        AppDatabase.setInstanceForTesting(
            Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build(),
        )
        CodeHub.resetForTesting()
        ctx.getSharedPreferences(CodeStore.PREFS_NAME, 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        CodeHub.resetForTesting()
        AppDatabase.setInstanceForTesting(null)
    }

    @Test
    fun saveHostClearsStickyAttachedAndReattaches() = runBlocking {
        val hub = CodeHub.get(ctx)
        hub.store.enabled = true
        val host = hub.addDemoHost()
        val id = hub.startSession(
            NewSessionRequest(
                hostId = host.id,
                harness = HarnessKind.CLAUDE_CODE,
                workspace = "~/code/GradatiON",
                prompt = "F1 sticky attach",
                permissionMode = PermissionMode.ASK,
            ),
        ).getOrThrow()
        assertTrue("startSession marks attached", hub.sessions.value[id]!!.attached)
        // Stop the demo turn so E4 running-clear is observable (chunks would revive it).
        hub.cancel(id)
        withTimeout(3_000) {
            while (hub.sessions.value[id]?.running == true) delay(5)
        }

        // E4/F1: rebuild backend for this host.
        hub.saveHost(host.copy(name = "Demo renamed"))

        // Re-attach is launched on Main; wait until Hub attached is true again.
        withTimeout(3_000) {
            while (hub.sessions.value[id]?.attached != true) delay(5)
        }
        assertTrue(hub.sessions.value[id]!!.attached)
        assertFalse("E4 running stays clear after rebuild", hub.sessions.value[id]!!.running)
        assertEquals("Demo renamed", hub.hosts.value.find { it.id == host.id }!!.name)
    }

    @Test
    fun answerFromAwayAttemptsWireWhenEventsEmpty() = runBlocking {
        // AWAY-01 / G3: after process death Room has the session index but no transcript
        // events. Away Allow/Deny must still hit the wire (demo answers immediately).
        val hub = CodeHub.get(ctx)
        hub.store.enabled = true
        val host = hub.addDemoHost()
        val id = hub.startSession(
            NewSessionRequest(
                hostId = host.id,
                harness = HarnessKind.CLAUDE_CODE,
                workspace = "~/code/GradatiON",
                prompt = "G3 cold away answer",
                permissionMode = PermissionMode.ASK,
            ),
        ).getOrThrow()
        // Wait until Room has the index row (async persist).
        val dao = AppDatabase.getDatabase(ctx).codeSessionDao()
        withTimeout(3_000) {
            while (dao.getAll().none { it.id == id }) delay(5)
        }
        CodeHub.resetForTesting()
        val cold = CodeHub.get(ctx)
        cold.store.enabled = true
        assertTrue("session reloaded from Room", cold.sessions.value.containsKey(id))
        assertTrue("cold start has empty transcript", cold.sessions.value[id]!!.events.isEmpty())

        val done = CompletableDeferred<Boolean>()
        cold.answerFromAway(
            id,
            "req-cold",
            ApprovalOption("allow", "Allow", ApprovalOption.Kind.ALLOW_ONCE),
        ) { done.complete(it) }
        assertTrue(
            "missing local approval row must still attempt wire (demo answers)",
            withTimeout(3_000) { done.await() },
        )
    }

}

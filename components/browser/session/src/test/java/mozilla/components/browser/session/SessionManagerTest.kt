/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.session

import android.graphics.Bitmap
import mozilla.components.browser.session.storage.SessionWithState
import mozilla.components.browser.session.storage.SessionsSnapshot
import mozilla.components.browser.session.tab.CustomTabConfig
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.support.test.mock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.Mockito.reset
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy

class SessionManagerTest {
    @Test
    fun `default session can be specified`() {
        val manager = SessionManager(mock(), defaultSession = { Session("http://www.mozilla.org") })
        assertEquals(0, manager.size)
    }

    @Test
    fun `default session is used when manager becomes empty`() {
        val session0 = Session("about:blank")
        val session1 = Session("http://www.firefox.com")

        val manager = SessionManager(mock(), defaultSession = { session0 })

        manager.add(session1)
        assertEquals(1, manager.size)
        assertEquals("http://www.firefox.com", manager.selectedSessionOrThrow.url)

        manager.remove(session1)
        assertEquals(1, manager.size)
        assertEquals("about:blank", manager.selectedSessionOrThrow.url)

        manager.add(session1)
        manager.removeAll()
        assertEquals(1, manager.size)
        assertEquals("about:blank", manager.selectedSessionOrThrow.url)

        manager.removeSessions()
        assertEquals(1, manager.size)
        assertEquals("about:blank", manager.selectedSessionOrThrow.url)
    }

    @Test
    fun `session can be added`() {
        val manager = SessionManager(mock())
        manager.add(Session("http://getpocket.com"))
        manager.add(Session("http://www.firefox.com"), true)

        assertEquals(2, manager.size)
        assertEquals("http://www.firefox.com", manager.selectedSessionOrThrow.url)
    }

    @Test
    fun `session can be added by specifying parent`() {
        val manager = SessionManager(mock())
        val session1 = Session("https://www.mozilla.org")
        val session2 = Session("https://www.firefox.com")
        val session3 = Session("https://wiki.mozilla.org")
        val session4 = Session("https://github.com/mozilla-mobile/android-components")

        manager.add(session1)
        manager.add(session2)
        manager.add(session3, parent = session1)
        manager.add(session4, parent = session2)

        assertNull(manager.sessions[0].parentId)
        assertNull(manager.sessions[2].parentId)
        assertEquals(session1.id, manager.sessions[1].parentId)
        assertEquals(session2.id, manager.sessions[3].parentId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `session manager throws exception if parent is not in session manager`() {
        val parent = Session("https://www.mozilla.org")
        val session = Session("https://www.firefox.com")

        val manager = SessionManager(mock())
        manager.add(session, parent = parent)
    }

    @Test
    fun `session can be selected`() {
        val session1 = Session("http://www.mozilla.org")
        val session2 = Session("http://www.firefox.com")

        val manager = SessionManager(mock())
        manager.add(session1)
        manager.add(session2)

        assertEquals("http://www.mozilla.org", manager.selectedSessionOrThrow.url)
        manager.select(session2)
        assertEquals("http://www.firefox.com", manager.selectedSessionOrThrow.url)
    }

    @Test
    fun `observer gets notified when session gets selected`() {
        val session1 = Session("http://www.mozilla.org")
        val session2 = Session("http://www.firefox.com")

        val manager = SessionManager(mock())
        manager.add(session1)
        manager.add(session2)

        val observer: SessionManager.Observer = mock()
        manager.register(observer)

        manager.select(session2)

        verify(observer).onSessionSelected(session2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `manager throws exception if unknown session is selected`() {
        val manager = SessionManager(mock())
        manager.add(Session("http://www.mozilla.org"))

        manager.select(Session("https://getpocket.com"))
    }

    @Test
    fun `observer does not get notified after unregistering`() {
        val session1 = Session("http://www.mozilla.org")
        val session2 = Session("http://www.firefox.com")

        val manager = SessionManager(mock())
        manager.add(session1)
        manager.add(session2)

        val observer: SessionManager.Observer = mock()
        manager.register(observer)

        manager.select(session2)

        verify(observer).onSessionSelected(session2)
        verifyNoMoreInteractions(observer)

        manager.unregister(observer)

        manager.select(session1)

        verify(observer, never()).onSessionSelected(session1)
        verifyNoMoreInteractions(observer)
    }

    @Test
    fun `observer is called when session is added`() {
        val manager = SessionManager(mock())
        val session = Session("https://www.mozilla.org")

        val observer: SessionManager.Observer = mock()
        manager.register(observer)

        manager.add(session)

        verify(observer).onSessionAdded(session)
        verify(observer).onSessionSelected(session) // First session is selected automatically
        verifyNoMoreInteractions(observer)
    }

    @Test
    fun `observer is called when all sessions removed and default session present`() {
        val session0 = Session("https://www.mozilla.org")
        val manager = SessionManager(mock(), defaultSession = { session0 })
        val observer: SessionManager.Observer = mock()

        manager.register(observer)

        manager.removeAll()

        assertEquals(1, manager.size)
        verify(observer).onSessionAdded(session0)
        verify(observer).onSessionSelected(session0)
        verify(observer).onAllSessionsRemoved()

        val observer2: SessionManager.Observer = mock()

        manager.register(observer2)

        manager.removeSessions()

        assertEquals(1, manager.size)
        verify(observer2).onSessionAdded(session0)
        verify(observer2).onSessionSelected(session0)
        verify(observer2).onAllSessionsRemoved()
    }

    @Test
    fun `default session not used when all sessions were removed and they were all CustomTab`() {
        val session1 = Session("https://www.mozilla.org")
        session1.customTabConfig = Mockito.mock(CustomTabConfig::class.java)

        val manager = SessionManager(mock(), defaultSession = { Session("about:blank") })
        val observer: SessionManager.Observer = mock()

        manager.add(session1)
        manager.register(observer)

        manager.removeAll()

        assertEquals(0, manager.size)
        verify(observer).onAllSessionsRemoved()
    }

    @Test
    fun `default session is used when all sessions were removed and they were mixed CustomTab and regular`() {
        val session1 = Session("https://www.mozilla.org")
        session1.customTabConfig = Mockito.mock(CustomTabConfig::class.java)
        val session2 = Session("https://www.firefox.com")

        val session0 = Session("about:blank")
        val manager = SessionManager(mock(), defaultSession = { session0 })
        val observer: SessionManager.Observer = mock()

        manager.register(observer)

        manager.add(session1)
        manager.add(session2)
        manager.removeAll()

        assertEquals(1, manager.size)
        assertEquals("about:blank", manager.selectedSessionOrThrow.url)

        verify(observer).onAllSessionsRemoved()
        verify(observer).onSessionSelected(session0)
        verify(observer).onSessionAdded(session0)
    }

    @Test
    fun `observer is called when session is removed`() {
        val manager = SessionManager(mock())
        val session1 = Session("https://www.mozilla.org")
        val session2 = Session("https://www.firefox.com")

        manager.add(session1)
        manager.add(session2)

        val observer: SessionManager.Observer = mock()
        manager.register(observer)

        manager.remove(session1)

        verify(observer).onSessionRemoved(session1)
        verifyNoMoreInteractions(observer)
    }

    @Test
    fun `observer is not called when session to remove is not in list`() {
        val manager = SessionManager(mock())
        val session1 = Session("https://www.mozilla.org")
        val session2 = Session("https://www.firefox.com")

        manager.add(session1)

        val observer: SessionManager.Observer = mock()
        manager.register(observer)

        manager.remove(session2)

        verifyNoMoreInteractions(observer)
    }

    @Test
    fun `initial session is selected`() {
        val session = Session("https://www.mozilla.org")

        val manager = SessionManager(mock())
        manager.add(session)

        assertEquals(1, manager.size)
        assertEquals(session, manager.selectedSession)
    }

    @Test
    fun `manager can have no session`() {
        val manager = SessionManager(mock())

        assertEquals(0, manager.size)
    }

    @Test
    fun `createSnapshot works when manager has no sessions`() {
        val manager = SessionManager(mock())
        assertNull(manager.createSnapshot())
    }

    @Test
    fun `createSnapshot ignores private sessions`() {
        val manager = SessionManager(mock())
        val session = Session("http://mozilla.org", true)
        manager.add(session)

        assertNull(manager.createSnapshot())
    }

    @Test
    fun `createSnapshot ignores CustomTab sessions`() {
        val manager = SessionManager(mock())
        val session = Session("http://mozilla.org")
        session.customTabConfig = Mockito.mock(CustomTabConfig::class.java)
        manager.add(session)

        assertNull(manager.createSnapshot())
    }

    @Test
    fun `createSnapshot ignores private CustomTab sessions`() {
        val manager = SessionManager(mock())
        val session = Session("http://mozilla.org", true)
        session.customTabConfig = Mockito.mock(CustomTabConfig::class.java)
        manager.add(session)

        assertNull(manager.createSnapshot())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `restore checks validity of a snapshot - empty`() {
        val manager = SessionManager(mock())
        manager.restore(SessionsSnapshot(listOf(), selectedSessionIndex = 0))
    }

    @Test
    fun `restore may be used to bulk-add session from a SessionsSnapshot`() {
        val manager = SessionManager(mock())

        // Just one session in the snapshot.
        manager.restore(
            SessionsSnapshot(
                listOf(SessionWithState(session = Session("http://www.mozilla.org"))),
                selectedSessionIndex = 0
            )
        )
        assertEquals(1, manager.size)
        assertEquals("http://www.mozilla.org", manager.selectedSessionOrThrow.url)

        // Multiple sessions in the snapshot.
        val regularSession = Session("http://www.firefox.com")
        val engineSessionState = mutableMapOf("k0" to "v0", "k1" to 1, "k2" to true, "k3" to emptyList<Any>())
        val engineSession = mock(EngineSession::class.java)
        `when`(engineSession.saveState()).thenReturn(engineSessionState)

        val snapshot = SessionsSnapshot(
            listOf(
                SessionWithState(session = regularSession, engineSession = engineSession),
                SessionWithState(session = Session("http://www.wikipedia.org"))
            ),
            selectedSessionIndex = 0
        )
        manager.restore(snapshot)
        assertEquals(3, manager.size)
        assertEquals("http://www.firefox.com", manager.selectedSessionOrThrow.url)
        val snapshotState = manager.selectedSessionOrThrow.engineSessionHolder.engineSession!!.saveState()
        assertEquals(4, snapshotState.size)
        assertEquals("v0", snapshotState["k0"])
        assertEquals(1, snapshotState["k1"])
        assertEquals(true, snapshotState["k2"])
        assertEquals(emptyList<Any>(), snapshotState["k3"])
    }

    @Test
    fun `restore fires correct notifications`() {
        val manager = SessionManager(mock())

        val observer: SessionManager.Observer = mock()
        manager.register(observer)

        val session = Session("http://www.mozilla.org")
        // Snapshot with a single session.
        manager.restore(SessionsSnapshot(listOf(SessionWithState(session)), 0))

        verify(observer, times(1)).onSessionsRestored()
        verify(observer, never()).onSessionAdded(session)
        verify(observer, times(1)).onSessionSelected(session)

        manager.removeAll()
        reset(observer)

        val session2 = Session("http://www.firefox.com")
        val session3 = Session("http://www.wikipedia.org")
        // Snapshot with multiple sessions.
        manager.restore(SessionsSnapshot(
            listOf(SessionWithState(session2), SessionWithState(session3), SessionWithState(session)),
            1
        ))

        assertEquals(3, manager.size)
        verify(observer, times(1)).onSessionsRestored()
        verify(observer, never()).onSessionAdded(session)
        verify(observer, never()).onSessionAdded(session2)
        verify(observer, never()).onSessionAdded(session3)
        verify(observer, never()).onSessionSelected(session)
        verify(observer, never()).onSessionSelected(session2)
        verify(observer, times(1)).onSessionSelected(session3)
    }

    @Test
    fun `createSnapshot produces a correct snapshot of sessions`() {
        val manager = SessionManager(mock())
        val customTabSession = Session("http://mozilla.org")
        customTabSession.customTabConfig = Mockito.mock(CustomTabConfig::class.java)
        val privateSession = Session("http://www.secret.com", true)
        val privateCustomTabSession = Session("http://very.secret.com", true)
        privateCustomTabSession.customTabConfig = Mockito.mock(CustomTabConfig::class.java)

        val regularSession = Session("http://www.firefox.com")
        val engineSessionState = mutableMapOf("k0" to "v0", "k1" to 1, "k2" to true, "k3" to emptyList<Any>())
        val engineSession = mock(EngineSession::class.java)
        `when`(engineSession.saveState()).thenReturn(engineSessionState)

        val engine = mock(Engine::class.java)
        `when`(engine.name()).thenReturn("gecko")
        `when`(engine.createSession()).thenReturn(mock(EngineSession::class.java))

        manager.add(regularSession, false, engineSession)
        manager.add(Session("http://firefox.com"), true, engineSession)
        manager.add(Session("http://wikipedia.org"), false, engineSession)
        manager.add(privateSession)
        manager.add(customTabSession)
        manager.add(privateCustomTabSession)

        val snapshot = manager.createSnapshot()
        assertEquals(3, snapshot!!.sessions.size)
        assertEquals(1, snapshot.selectedSessionIndex)

        val snapshotSession = snapshot.sessions[0]
        assertEquals("http://www.firefox.com", snapshotSession.session.url)

        val snapshotState = snapshotSession.engineSession!!.saveState()
        assertEquals(4, snapshotState.size)
        assertEquals("v0", snapshotState["k0"])
        assertEquals(1, snapshotState["k1"])
        assertEquals(true, snapshotState["k2"])
        assertEquals(emptyList<Any>(), snapshotState["k3"])
    }

    @Test
    fun `createSnapshot resets selection index if selected session was private`() {
        val manager = SessionManager(mock())

        val privateSession = Session("http://www.secret.com", true)
        val regularSession1 = Session("http://www.firefox.com")
        val regularSession2 = Session("http://www.mozilla.org")

        val engine = mock(Engine::class.java)
        `when`(engine.name()).thenReturn("gecko")
        `when`(engine.createSession()).thenReturn(mock(EngineSession::class.java))

        manager.add(regularSession1, false)
        manager.add(regularSession2, false)
        manager.add(privateSession, true)

        val snapshot = manager.createSnapshot()
        assertEquals(2, snapshot!!.sessions.size)
        assertEquals(0, snapshot.selectedSessionIndex)
    }

    @Test
    fun `selectedSession is null with no selection`() {
        val manager = SessionManager(mock())
        assertNull(manager.selectedSession)
    }

    @Test
    fun `selected session will be recalculated when selected session gets removed`() {
        val manager = SessionManager(mock())

        val session1 = Session("https://www.mozilla.org")
        val session2 = Session("https://www.firefox.com")
        val session3 = Session("https://wiki.mozilla.org")
        val session4 = Session("https://github.com/mozilla-mobile/android-components")

        manager.add(session1)
        manager.add(session2)
        manager.add(session3)
        manager.add(session4)

        // (1), 2, 3, 4
        assertEquals(session1, manager.selectedSession)

        // 1, 2, 3, (4)
        manager.select(session4)
        assertEquals(session4, manager.selectedSession)

        // 1, 2, (3)
        manager.remove(session4)
        assertEquals(session3, manager.selectedSession)

        // 2, (3)
        manager.remove(session1)
        assertEquals(session3, manager.selectedSession)

        // (2), 3
        manager.select(session2)
        assertEquals(session2, manager.selectedSession)

        // (2)
        manager.remove(session3)
        assertEquals(session2, manager.selectedSession)

        // -
        manager.remove(session2)
        assertEquals(0, manager.size)
    }

    @Test
    fun `sessions property removes immutable copy`() {
        val manager = SessionManager(mock())

        val session1 = Session("https://www.mozilla.org")
        val session2 = Session("https://www.firefox.com")
        val session3 = Session("https://wiki.mozilla.org")
        val session4 = Session("https://github.com/mozilla-mobile/android-components")

        manager.add(session1)
        manager.add(session2)
        manager.add(session3)
        manager.add(session4)

        val sessions = manager.sessions

        assertEquals(4, sessions.size)
        assertTrue(sessions.contains(session1))
        assertTrue(sessions.contains(session2))
        assertTrue(sessions.contains(session3))
        assertTrue(sessions.contains(session4))

        manager.remove(session1)

        assertEquals(3, manager.size)
        assertEquals(4, sessions.size)
    }

    @Test
    fun `removeAll removes all sessions and notifies observer`() {
        val manager = SessionManager(mock())

        val session1 = Session("https://www.mozilla.org")
        val session2 = Session("https://www.firefox.com")
        val session3 = Session("https://wiki.mozilla.org")
        val session4 = Session("https://github.com/mozilla-mobile/android-components")
        session4.customTabConfig = Mockito.mock(CustomTabConfig::class.java)

        manager.add(session1)
        manager.add(session2)
        manager.add(session3)
        manager.add(session4)

        val observer: SessionManager.Observer = mock()
        manager.register(observer)

        assertEquals(4, manager.size)

        manager.removeAll()

        assertEquals(0, manager.size)

        verify(observer).onAllSessionsRemoved()
        verifyNoMoreInteractions(observer)
    }

    @Test
    fun `findSessionById returns session with same id`() {
        val manager = SessionManager(mock())

        val session1 = Session("https://www.mozilla.org")
        val session2 = Session("https://www.firefox.com")
        val session3 = Session("https://wiki.mozilla.org")
        val session4 = Session("https://github.com/mozilla-mobile/android-components")

        manager.add(session1)
        manager.add(session2)
        manager.add(session3)
        manager.add(session4)

        assertEquals(session1, manager.findSessionById(session1.id))
        assertEquals(session2, manager.findSessionById(session2.id))
        assertEquals(session3, manager.findSessionById(session3.id))
        assertEquals(session4, manager.findSessionById(session4.id))

        assertNull(manager.findSessionById("banana"))
    }

    @Test
    fun `session manager creates and links engine session`() {
        val engine: Engine = mock()

        val actualEngineSession: EngineSession = mock()
        doReturn(actualEngineSession).`when`(engine).createSession(false)
        val privateEngineSession: EngineSession = mock()
        doReturn(privateEngineSession).`when`(engine).createSession(true)

        val sessionManager = SessionManager(engine)

        val session = Session("https://www.mozilla.org")
        sessionManager.add(session)

        assertNull(sessionManager.getEngineSession(session))
        assertEquals(actualEngineSession, sessionManager.getOrCreateEngineSession(session))
        assertEquals(actualEngineSession, sessionManager.getEngineSession(session))
        assertEquals(actualEngineSession, sessionManager.getOrCreateEngineSession(session))

        val privateSession = Session("https://www.mozilla.org", true, Session.Source.NONE)
        sessionManager.add(privateSession)
        assertNull(sessionManager.getEngineSession(privateSession))
        assertEquals(privateEngineSession, sessionManager.getOrCreateEngineSession(privateSession))
    }

    @Test
    fun `removing a session unlinks the engine session`() {
        val engine: Engine = mock()

        val actualEngineSession: EngineSession = mock()
        doReturn(actualEngineSession).`when`(engine).createSession()

        val sessionManager = SessionManager(engine)

        val session = Session("https://www.mozilla.org")
        sessionManager.add(session)

        assertNotNull(sessionManager.getOrCreateEngineSession(session))
        assertNotNull(session.engineSessionHolder.engineSession)
        assertNotNull(session.engineSessionHolder.engineObserver)

        sessionManager.remove(session)

        assertNull(session.engineSessionHolder.engineSession)
        assertNull(session.engineSessionHolder.engineObserver)
    }

    @Test
    fun `add will link an engine session if provided`() {
        val engine: Engine = mock()

        val actualEngineSession: EngineSession = mock()
        val sessionManager = SessionManager(engine)

        val session = Session("https://www.mozilla.org")
        assertNull(session.engineSessionHolder.engineSession)
        assertNull(session.engineSessionHolder.engineObserver)

        sessionManager.add(session, engineSession = actualEngineSession)

        assertNotNull(session.engineSessionHolder.engineSession)
        assertNotNull(session.engineSessionHolder.engineObserver)

        assertEquals(actualEngineSession, sessionManager.getOrCreateEngineSession(session))
        assertEquals(actualEngineSession, sessionManager.getEngineSession(session))
        assertEquals(actualEngineSession, sessionManager.getOrCreateEngineSession(session))
    }

    @Test
    fun `removeSessions retains customtab sessions`() {
        val manager = SessionManager(mock())

        val session1 = Session("https://www.mozilla.org")
        val session2 = Session("https://getPocket.com")
        val session3 = Session("https://www.firefox.com")
        session2.customTabConfig = Mockito.mock(CustomTabConfig::class.java)

        manager.add(session1)
        manager.add(session2)
        manager.add(session3)

        val observer: SessionManager.Observer = mock()
        manager.register(observer)

        assertEquals(3, manager.size)

        manager.removeSessions()

        assertEquals(1, manager.size)
        assertEquals(session2, manager.all[0])

        verify(observer).onAllSessionsRemoved()
        verifyNoMoreInteractions(observer)
    }

    @Test(expected = IllegalStateException::class)
    fun `exception is thrown from selectedSessionOrThrow with no selection`() {
        val manager = SessionManager(mock())
        manager.selectedSessionOrThrow
    }

    @Test
    fun `all not selected sessions should be removed on Low Memory`() {
        val manager = SessionManager(mock())

        val emptyBitmap = spy(Bitmap::class.java)

        val session1 = Session("https://www.mozilla.org")
        session1.thumbnail = emptyBitmap

        val session2 = Session("https://getPocket.com")
        session2.thumbnail = emptyBitmap

        val session3 = Session("https://www.firefox.com")
        session3.thumbnail = emptyBitmap

        manager.add(session1, true)
        manager.add(session2, false)
        manager.add(session3, false)

        val allSessionsMustHaveAThumbnail = manager.all.all { it.thumbnail != null }

        assertTrue(allSessionsMustHaveAThumbnail)

        manager.onLowMemory()

        val onlySelectedSessionMustHaveAThumbnail =
                session1.thumbnail != null && session2.thumbnail == null && session3.thumbnail == null

        assertTrue(onlySelectedSessionMustHaveAThumbnail)
    }

    @Test
    fun `custom tab session will not be selected if it is the first session`() {
        val session = Session("about:blank")
        session.customTabConfig = Mockito.mock(CustomTabConfig::class.java)

        val manager = SessionManager(mock())
        manager.add(session)

        assertNull(manager.selectedSession)
    }

    @Test
    fun `parent will be selected if child is removed and flag is set to true`() {
        val parent = Session("https://www.mozilla.org")

        val session1 = Session("https://www.firefox.com")
        val session2 = Session("https://getpocket.com")
        val child = Session("https://www.mozilla.org/en-US/internet-health/")

        val manager = SessionManager(mock())
        manager.add(parent)
        manager.add(session1)
        manager.add(session2)
        manager.add(child, parent = parent)

        manager.select(child)
        manager.remove(child, selectParentIfExists = true)

        assertEquals(parent, manager.selectedSession)
        assertEquals("https://www.mozilla.org", manager.selectedSessionOrThrow.url)
    }

    @Test
    fun `parent will not be selected if child is removed and flag is set to false`() {
        val parent = Session("https://www.mozilla.org")

        val session1 = Session("https://www.firefox.com")
        val session2 = Session("https://getpocket.com")
        val child1 = Session("https://www.mozilla.org/en-US/internet-health/")
        val child2 = Session("https://www.mozilla.org/en-US/technology/")

        val manager = SessionManager(mock())
        manager.add(parent)
        manager.add(session1)
        manager.add(session2)
        manager.add(child1, parent = parent)
        manager.add(child2, parent = parent)

        manager.select(child1)
        manager.remove(child1, selectParentIfExists = false)

        assertEquals(session1, manager.selectedSession)
        assertEquals("https://www.firefox.com", manager.selectedSessionOrThrow.url)
    }

    @Test
    fun `Setting selectParentIfExists when removing session without parent has no effect`() {
        val session1 = Session("https://www.firefox.com")
        val session2 = Session("https://getpocket.com")
        val session3 = Session("https://www.mozilla.org/en-US/internet-health/")

        val manager = SessionManager(mock())
        manager.add(session1)
        manager.add(session2)
        manager.add(session3)

        manager.select(session3)
        manager.remove(session3, selectParentIfExists = true)

        assertEquals(session2, manager.selectedSession)
        assertEquals("https://getpocket.com", manager.selectedSessionOrThrow.url)
    }

    @Test
    fun `Sessions with parent are added after parent`() {
        val parent01 = Session("https://www.mozilla.org")
        val parent02 = Session("https://getpocket.com")

        val session1 = Session("https://www.firefox.com")
        val session2 = Session("https://developer.mozilla.org/en-US/")
        val child001 = Session("https://www.mozilla.org/en-US/internet-health/")
        val child002 = Session("https://www.mozilla.org/en-US/technology/")
        val child003 = Session("https://getpocket.com/add/")

        val manager = SessionManager(mock())
        manager.add(parent01)
        manager.add(session1)
        manager.add(child001, parent = parent01)
        manager.add(session2)
        manager.add(parent02)
        manager.add(child002, parent = parent01)
        manager.add(child003, parent = parent02)

        assertEquals(parent01, manager.sessions[0]) // ├── parent 1
        assertEquals(child002, manager.sessions[1]) // │   ├── child 2
        assertEquals(child001, manager.sessions[2]) // │   └── child 1
        assertEquals(session1, manager.sessions[3]) // ├──session 1
        assertEquals(session2, manager.sessions[4]) // ├──session 2
        assertEquals(parent02, manager.sessions[5]) // └── parent 2
        assertEquals(child003, manager.sessions[6]) //     └── child 3
    }

    @Test
    fun `SessionManager updates parent id of children after updating parent`() {
        val session1 = Session("https://www.firefox.com")
        val session2 = Session("https://developer.mozilla.org/en-US/")
        val session3 = Session("https://www.mozilla.org/en-US/internet-health/")
        val session4 = Session("https://www.mozilla.org/en-US/technology/")

        val manager = SessionManager(mock())
        manager.add(session1)
        manager.add(session2, parent = session1)
        manager.add(session3, parent = session2)
        manager.add(session4, parent = session3)

        // session 1 <- session2 <- session3 <- session4
        assertNull(session1.parentId)
        assertEquals(session1.id, session2.parentId)
        assertEquals(session2.id, session3.parentId)
        assertEquals(session3.id, session4.parentId)

        manager.remove(session3)

        assertEquals(session1, manager.sessions[0])
        assertEquals(session2, manager.sessions[1])
        assertEquals(session4, manager.sessions[2])

        // session1 <- session2 <- session4
        assertNull(session1.parentId)
        assertEquals(session1.id, session2.parentId)
        assertEquals(session2.id, session4.parentId)

        manager.remove(session1)

        assertEquals(session2, manager.sessions[0])
        assertEquals(session4, manager.sessions[1])

        // session2 <- session4
        assertNull(session2.parentId)
        assertEquals(session2.id, session4.parentId)
    }
}

package com.ncstudio.daw.session.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSettingsSecurityTest {

    @Test
    fun releaseBuildsRequireTlsForRemoteSessions() {
        // A bare host is upgraded to https, not silently sent in clear text.
        val upgraded = ConnectionSettings.fromHost(
            hostInput = "192.168.1.20:8787",
            sessionId = "s",
            role = ConnectionSettings.ROLE_ARTIST,
            token = "t",
            allowCleartext = false,
        )
        assertEquals("https://192.168.1.20:8787/v1", upgraded.baseUrl)
        assertEquals("wss://192.168.1.20:8787/ws", upgraded.wsUrl)

        assertThrows(IllegalArgumentException::class.java) {
            ConnectionSettings.fromHost(
                hostInput = "http://session.example.com",
                sessionId = "s",
                role = ConnectionSettings.ROLE_ENGINEER,
                token = "t",
                allowCleartext = false,
            )
        }

        val secure = ConnectionSettings.fromHost(
            hostInput = "session.example.com",
            sessionId = "s",
            role = ConnectionSettings.ROLE_ENGINEER,
            token = "t",
            allowCleartext = false,
        )
        assertEquals("https://session.example.com/v1", secure.baseUrl)
        assertEquals("wss://session.example.com/ws", secure.wsUrl)
    }

    @Test
    fun debugBuildsMayTalkToALanServer() {
        val local = ConnectionSettings.fromHost(
            hostInput = "192.168.1.20:8787",
            sessionId = "s",
            role = ConnectionSettings.ROLE_ARTIST,
            token = "t",
            allowCleartext = true,
        )
        assertEquals("http://192.168.1.20:8787/v1", local.baseUrl)
        assertEquals("ws://192.168.1.20:8787/ws", local.wsUrl)
    }

    @Test
    fun debugBuildsWarnBeforePlainHttpToAHostTheConfigCannotName() {
        // Loopback, the emulator host and mDNS names are allowed by the debug
        // network security config; a bare LAN IP is not.
        assertNull(ConnectionSettings.cleartextWarning("127.0.0.1:8787"))
        assertNull(ConnectionSettings.cleartextWarning("10.0.2.2:8787"))
        assertNull(ConnectionSettings.cleartextWarning("studio-laptop.local:8787"))
        assertNull(ConnectionSettings.cleartextWarning("https://192.168.1.20:8787"))

        val warning = ConnectionSettings.cleartextWarning("192.168.1.20:8787")
        assertEquals(ConnectionSettings.CLEARTEXT_SESSION_ADVICE, warning)
        assertTrue("the advice leads with TLS", warning!!.contains("HTTPS"))
        assertNotNull(ConnectionSettings.cleartextWarning("http://session.example.com"))
    }
}

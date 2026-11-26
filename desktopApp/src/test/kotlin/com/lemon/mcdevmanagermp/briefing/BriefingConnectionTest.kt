package com.lemon.mcdevmanagermp.briefing

import com.sun.jna.Platform
import org.junit.Assume.assumeTrue
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BriefingConnectionTest {
    @Test
    fun restoresConnectionSettingsWrittenByThePreviousVersion() = withTestPreferences { prefs ->
        prefs.put("baseUrl", "https://api.deepseek.com")
        prefs.put("model", "deepseek-chat")

        val loaded = BriefingStore(prefs = prefs).loadConnection()

        assertEquals("https://api.deepseek.com", loaded.baseUrl)
        assertEquals("deepseek-chat", loaded.model)
        assertFalse(loaded.rememberApiKey)
    }

    @Test
    fun remembersTheKeyOnlyWhileOptedIn() = withTestPreferences { prefs ->
        assumeTrue(Platform.isWindows())
        val key = "sk-synthetic-" + UUID.randomUUID()
        val remembered = AiConnection("https://api.deepseek.com", "deepseek-chat", key, rememberApiKey = true)

        BriefingStore(prefs = prefs).saveConnection(remembered)
        prefs.sync()
        val saved = assertNotNull(prefs.get("connection", null))
        assertFalse(saved.contains(key))
        val restored = BriefingStore(prefs = prefs).loadConnection()
        assertEquals(remembered.baseUrl, restored.baseUrl)
        assertEquals(remembered.model, restored.model)
        assertTrue(restored.rememberApiKey)
        assertTrue(restored.apiKey == key)

        val sessionOnly = AiConnection(restored.baseUrl, restored.model, restored.apiKey)
        BriefingStore(prefs = prefs).saveConnection(sessionOnly)
        assertTrue(sessionOnly.apiKey == key)
        prefs.sync()
        val reopened = BriefingStore(prefs = prefs).loadConnection()
        assertFalse(reopened.rememberApiKey)
        assertFalse(reopened.apiKey == key)
        assertFalse(assertNotNull(prefs.get("connection", null)).contains("protectedApiKey"))
    }

    @Test
    fun keepsModelSettingsWhenSavedKeyCannotBeRead() = withTestPreferences { prefs ->
        prefs.put("connection", """{"baseUrl":"https://api.deepseek.com","model":"deepseek-chat","protectedApiKey":"%invalid%"}""")

        val restored = BriefingStore(prefs = prefs).loadConnection()

        assertEquals("https://api.deepseek.com", restored.baseUrl)
        assertEquals("deepseek-chat", restored.model)
        assertFalse(restored.rememberApiKey)
        assertNotNull(restored.restoreWarning)
    }

    private fun withTestPreferences(action: (Preferences) -> Unit) {
        val prefs = Preferences.userRoot().node("mcbriefing-connection-test-" + UUID.randomUUID())
        try {
            action(prefs)
        } finally {
            prefs.removeNode()
        }
    }
}

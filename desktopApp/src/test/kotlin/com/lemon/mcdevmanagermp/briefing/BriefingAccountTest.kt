package com.lemon.mcdevmanagermp.briefing

import com.lemon.mcdevmanagermp.data.common.NetworkState
import com.lemon.mcdevmanagermp.data.consts.CookiesExpiredException
import com.lemon.mcdevmanagermp.data.vo.netease.user.UserInfoVO
import com.lemon.mcdevmanagermp.domain.account.Account
import com.lemon.mcdevmanagermp.domain.account.AccountRepository
import com.lemon.mcdevmanagermp.domain.account.CookieRepository
import com.lemon.mcdevmanagermp.domain.user.UserRepository
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BriefingAccountTest {
    private val developer = Account(
        id = 17,
        nickname = "测试开发者",
        cookiesJson = """{"session":"test-cookie"}""",
        lastLoginTime = 1L,
        headImg = "https://example.test/avatar.png",
        email = "developer@example.test",
    )

    @Test
    fun returnsToTheRememberedAccountUntilExplicitlyResumed() = withTestPreferences { prefs ->
        val accounts = TestAccounts(developer)
        val cookies = TestCookies()
        val user = TestUser(NetworkState.Success<UserInfoVO>())
        val session = BriefingAccount(accounts, cookies, user, prefs)
        assertEquals(developer.id, session.openOnStart().active?.id)
        assertEquals(1, user.lookups)
        val remembered = assertNotNull(accounts.getLastUsedAccount())

        session.signOut()
        assertEquals(remembered, accounts.getLastUsedAccount())
        assertTrue(cookies.getAllCookiesMap().isEmpty())
        val reopened = BriefingAccount(accounts, cookies, user, prefs)
        assertEquals(BriefingAccountEntry(remembered), reopened.openOnStart())
        assertEquals(1, user.lookups)

        val restored = assertNotNull(reopened.resume())
        assertEquals(remembered.id, restored.id)
        assertEquals(remembered.nickname, restored.nickname)
        assertEquals(remembered.headImg, restored.headImg)
        assertEquals(mapOf("session" to "test-cookie"), cookies.getAllCookiesMap())
        assertEquals(2, user.lookups)
        assertEquals(remembered.id, reopened.openOnStart().active?.id)
    }

    @Test
    fun keepsTheProfileWhenAnOlderVersionClearedTheSession() = withTestPreferences { prefs ->
        val remembered = developer.copy(cookiesJson = "{}")
        val user = TestUser(NetworkState.Success<UserInfoVO>())
        val session = BriefingAccount(TestAccounts(remembered), TestCookies(), user, prefs)

        assertEquals(BriefingAccountEntry(remembered), session.openOnStart())
        assertFalse(remembered.hasSavedSession)
        assertNull(session.resume())
        assertEquals(0, user.lookups)
    }

    @Test
    fun expiredSessionLeavesTheDeveloperVisibleForSignIn() = withTestPreferences { prefs ->
        val cookies = TestCookies()
        val user = TestUser(NetworkState.Error("expired", CookiesExpiredException()))
        val session = BriefingAccount(TestAccounts(developer), cookies, user, prefs)

        assertEquals(BriefingAccountEntry(remembered = developer, needsLogin = true), session.openOnStart())
        assertTrue(cookies.getAllCookiesMap().isEmpty())
        assertEquals(1, user.lookups)
    }

    private fun withTestPreferences(action: suspend (Preferences) -> Unit) = runBlocking {
        val prefs = Preferences.userRoot().node("mcbriefing-account-test-" + UUID.randomUUID())
        try {
            action(prefs)
        } finally {
            prefs.removeNode()
        }
    }

    private class TestCookies : CookieRepository {
        private val values = mutableMapOf<String, String>()

        override fun getAllCookiesMap() = values.toMap()
        override fun addCookie(key: String, value: String) { values[key] = value }
        override fun clearCookies() = values.clear()
    }

    private class TestUser(private val response: NetworkState<UserInfoVO>) : UserRepository {
        var lookups = 0
            private set

        override suspend fun getUserInfo(): NetworkState<UserInfoVO> {
            lookups++
            return response
        }

        override suspend fun getOverview() = error("Unexpected overview lookup")
        override suspend fun getLevelInfo() = error("Unexpected level lookup")
    }

    private class TestAccounts(private var saved: Account) : AccountRepository {
        override suspend fun getLastUsedAccount() = saved
        override suspend fun upsertAccount(account: Account) { saved = account }
        override suspend fun getAllAccounts() = error("Unexpected account lookup")
        override suspend fun getAccountByNickname(nickname: String) = error("Unexpected nickname lookup")
        override suspend fun deleteAccount(id: Long) = error("Unexpected account deletion")
        override suspend fun updateNicknameById(id: Long, nickname: String) = error("Unexpected nickname update")
    }
}

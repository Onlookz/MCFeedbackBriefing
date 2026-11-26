package com.lemon.mcdevmanagermp.briefing

import com.lemon.mcdevmanagermp.data.consts.CookiesNotValidException
import com.lemon.mcdevmanagermp.data.consts.NeteaseLoginException
import com.lemon.mcdevmanagermp.data.consts.neteaseLoginErrorMessage
import com.lemon.mcdevmanagermp.data.repository.AccountRepositoryImpl
import com.lemon.mcdevmanagermp.data.repository.CookieRepositoryImpl
import com.lemon.mcdevmanagermp.data.repository.LoginRepositoryImpl
import com.lemon.mcdevmanagermp.data.repository.UserRepositoryImpl
import com.lemon.mcdevmanagermp.domain.account.Account
import com.lemon.mcdevmanagermp.domain.account.AccountRepository
import com.lemon.mcdevmanagermp.domain.account.AutoLoginUseCase
import com.lemon.mcdevmanagermp.domain.account.CookieRepository
import com.lemon.mcdevmanagermp.domain.account.SaveAccountUseCase
import com.lemon.mcdevmanagermp.domain.login.LoginUseCase
import com.lemon.mcdevmanagermp.domain.user.UserRepository
import kotlinx.coroutines.CancellationException
import java.util.prefs.Preferences

internal data class BriefingAccountEntry(
    val remembered: Account? = null,
    val active: Account? = null,
    val needsLogin: Boolean = false,
)

private const val RETURNED_TO_ACCOUNT_PAGE = "returned-to-account-page"

internal val Account.hasSavedSession: Boolean
    get() = cookiesJson.isNotBlank() && cookiesJson.trim() != "{}"

internal class BriefingAccount(
    private val accounts: AccountRepository = AccountRepositoryImpl.INSTANCE,
    private val cookies: CookieRepository = CookieRepositoryImpl.INSTANCE,
    user: UserRepository = UserRepositoryImpl.INSTANCE,
    private val prefs: Preferences = Preferences.userRoot().node("mc-feedback-briefing/account"),
) {
    private val restoreLogin = AutoLoginUseCase(accounts, user, cookies)
    private val login = LoginUseCase(LoginRepositoryImpl.INSTANCE, user, cookies)
    private val save = SaveAccountUseCase(accounts, user, cookies)

    suspend fun openOnStart(): BriefingAccountEntry {
        val saved = accounts.getLastUsedAccount() ?: return BriefingAccountEntry()
        if (!saved.hasSavedSession || prefs.getBoolean(RETURNED_TO_ACCOUNT_PAGE, false)) {
            return BriefingAccountEntry(remembered = saved)
        }
        val restored = resume()
        return BriefingAccountEntry(restored ?: saved, restored, needsLogin = restored == null)
    }

    suspend fun resume(): Account? {
        val saved = accounts.getLastUsedAccount() ?: return null
        if (!saved.hasSavedSession) return null
        clearSession()
        if (!restoreLogin()) return null
        val restored = accounts.getLastUsedAccount() ?: return null
        setAccountPage(false)
        return restored
    }

    fun signOut() {
        setAccountPage(true)
        clearSession()
    }

    suspend fun signIn(email: String, password: String, session: String): Account {
        clearSession()
        try {
            if (session.isBlank()) {
                login(email = email, password = password)
                save(email = email)
            } else {
                login(cookies = session)
                save()
            }
            val account = accounts.getLastUsedAccount() ?: throw BriefingFailure("登录状态保存失败，请重试。")
            setAccountPage(false)
            return account
        } catch (e: CancellationException) {
            clearSession()
            throw e
        } catch (e: Exception) {
            clearSession()
            val cause = generateSequence<Throwable>(e) { it.cause }
                .firstOrNull { it is NeteaseLoginException || it is CookiesNotValidException }
            val message = when (cause) {
                is NeteaseLoginException -> neteaseLoginErrorMessage(cause.ret, cause.dt)
                is CookiesNotValidException -> "会话凭据无效或已过期，请在网易开发者平台重新登录。"
                else -> "登录失败，请检查账号信息和网络后重试。"
            }
            throw BriefingFailure(message)
        }
    }

    fun clearSession() = cookies.clearCookies()

    private fun setAccountPage(show: Boolean) {
        if (prefs.getBoolean(RETURNED_TO_ACCOUNT_PAGE, false) == show) return
        prefs.putBoolean(RETURNED_TO_ACCOUNT_PAGE, show)
        prefs.flush()
    }
}

package com.lemon.mcdevmanagermp.briefing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lemon.mcdevmanagermp.domain.account.Account
import com.lemon.mcdevmanagermp.ui.components.DeveloperAvatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun BriefingAccountPage(
    session: BriefingAccount,
    entry: BriefingAccountEntry,
    initialError: String,
    onAppearance: () -> Unit,
    onLogin: (Account) -> Unit,
) {
    val saved = entry.remembered
    val scope = rememberCoroutineScope()
    var showLogin by remember(saved?.id) { mutableStateOf(saved == null) }
    var loginEmail by remember(saved?.id) { mutableStateOf("") }
    var needsLogin by remember(entry) { mutableStateOf(entry.needsLogin) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember(initialError) { mutableStateOf(initialError) }

    if (showLogin || saved == null) {
        BriefingLogin(
            session = session,
            initialEmail = loginEmail,
            initialError = error,
            onAppearance = onAppearance,
            onBack = if (saved != null) ({ showLogin = false }) else null,
            onLogin = onLogin,
        )
        return
    }

    val canResume = saved.hasSavedSession && !needsLogin
    val colors = MaterialTheme.colorScheme
    BriefingEntryPanel(maxWidth = 350) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            DeveloperAvatar(saved.headImg, Modifier.size(84.dp), RoundedCornerShape(16.dp))
            Text(
                saved.nickname.ifBlank { saved.email }.ifBlank { "网易开发者" },
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (error.isNotBlank()) {
                BriefingStatusNotice(error, error = true)
            } else {
                Text("网易开发者账号", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    if (!canResume) {
                        loginEmail = saved.email
                        showLogin = true
                    } else {
                        connecting = true
                        error = ""
                        scope.launch {
                            try {
                                val restored = withContext(Dispatchers.IO) { session.resume() }
                                if (restored == null) {
                                    needsLogin = true
                                    error = "网易会话已失效，请重新登录。"
                                } else {
                                    onLogin(restored)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                error = "暂时无法进入简报，请重试。"
                            } finally {
                                connecting = false
                            }
                        }
                    }
                },
                enabled = !connecting,
                shape = controlShape,
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            ) {
                Text(when {
                    connecting -> "正在连接…"
                    canResume -> "进入简报"
                    else -> "重新登录"
                })
            }
            EntryActions(enabled = !connecting, onAppearance = onAppearance, otherLabel = "切换账号") {
                error = ""
                loginEmail = ""
                showLogin = true
            }
        }
    }
}

@Composable
private fun BriefingLogin(
    session: BriefingAccount,
    initialEmail: String,
    initialError: String,
    onAppearance: () -> Unit,
    onBack: (() -> Unit)?,
    onLogin: (Account) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var useSession by remember { mutableStateOf(false) }
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember(initialError) { mutableStateOf(initialError) }

    BriefingEntryPanel(maxWidth = 470) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("登录开发者账号", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (useSession) {
                OutlinedTextField(
                    value = credential,
                    onValueChange = { credential = it },
                    label = { Text("网易开发者平台 Cookie") },
                    supportingText = { Text("在网易开发者平台登录后，填入当前会话凭据。") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = controlShape,
                    singleLine = true,
                    enabled = !loading,
                )
            } else {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("网易账号邮箱") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = controlShape,
                    singleLine = true,
                    enabled = !loading,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = controlShape,
                    singleLine = true,
                    enabled = !loading,
                )
            }
            if (error.isNotBlank()) BriefingStatusNotice(error, error = true)
            Button(
                onClick = {
                    loading = true
                    error = ""
                    scope.launch {
                        try {
                            val account = withContext(Dispatchers.IO) {
                                session.signIn(email.trim(), password, if (useSession) credential.trim() else "")
                            }
                            onLogin(account)
                        } catch (e: BriefingFailure) {
                            error = e.message.orEmpty()
                        } finally {
                            loading = false
                        }
                    }
                },
                shape = controlShape,
                enabled = !loading && if (useSession) credential.isNotBlank() else email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (loading) "登录中…" else "登录") }
            TextButton(
                onClick = {
                    useSession = !useSession
                    error = ""
                    password = ""
                    credential = ""
                },
                enabled = !loading,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                shape = controlShape,
            ) { Text(if (useSession) "使用账号密码登录" else "使用已登录的网易会话") }
            EntryActions(!loading, onAppearance, "返回账号页", onBack)
        }
    }
}

@Composable
private fun BriefingEntryPanel(maxWidth: Int, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth.dp).fillMaxWidth(),
            shape = briefingPanelShape,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
            shadowElevation = 8.dp,
        ) {
            Box(Modifier.padding(horizontal = 23.dp, vertical = 25.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryActions(enabled: Boolean, onAppearance: () -> Unit, otherLabel: String, onOther: (() -> Unit)?) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        if (onOther != null) {
            TextButton(onClick = onOther, enabled = enabled, shape = controlShape) { Text(otherLabel) }
        }
        TextButton(onClick = onAppearance, enabled = enabled, shape = controlShape) { Text("显示设置") }
    }
}

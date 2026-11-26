package com.lemon.mcdevmanagermp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.lemon.mcdevmanagermp.briefing.BriefingAppearance
import com.lemon.mcdevmanagermp.briefing.BriefingAppearanceDialog
import com.lemon.mcdevmanagermp.briefing.BriefingAppearanceStore
import com.lemon.mcdevmanagermp.briefing.FeedbackBriefingApp
import com.lemon.mcdevmanagermp.briefing.LocalBriefingAppearance
import com.lemon.mcdevmanagermp.briefing.LocalBriefingViewport
import com.lemon.mcdevmanagermp.briefing.briefingColors
import com.lemon.mcdevmanagermp.ui.DesktopTitleBar
import com.lemon.mcdevmanagermp.ui.theme.AppColors
import com.lemon.mcdevmanagermp.ui.theme.AppTheme
import com.lemon.mcdevmanagermp.ui.theme.LocalAppColors
import com.lemon.mcdevmanagermp.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

@Composable
fun FrameWindowScope.DesktopRoot(
    windowState: WindowState,
    title: String,
    onClose: () -> Unit,
) {
    val store = remember { BriefingAppearanceStore() }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(store.load()) }
    var draft by remember { mutableStateOf<BriefingAppearance?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var focused by remember(window) { mutableStateOf(window.isFocused) }
    DisposableEffect(window) {
        val listener = object : WindowAdapter() {
            override fun windowGainedFocus(event: WindowEvent) { focused = true }
            override fun windowLostFocus(event: WindowEvent) { focused = false }
        }
        window.addWindowFocusListener(listener)
        onDispose { window.removeWindowFocusListener(listener) }
    }
    val display = draft ?: saved
    val colors = remember(display.background) { briefingColors(display.background) }
    val mode = if (colors.background.luminance() < 0.20f) ThemeMode.DARK else ThemeMode.LIGHT
    val systemDensity = LocalDensity.current
    val textDensity = Density(systemDensity.density, systemDensity.fontScale * display.textPercent / 100f)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = DpSize(maxWidth, maxHeight)
        AppTheme(themeMode = mode) {
            val appColors = AppColors(colors, LocalAppColors.current.extended)
            CompositionLocalProvider(
                LocalDensity provides textDensity,
                LocalAppColors provides appColors,
                LocalBriefingAppearance provides display,
                LocalBriefingViewport provides viewport,
            ) {
                MaterialTheme(colorScheme = colors) {
                    BriefingWindowFrame(
                        modifier = Modifier.fillMaxSize(),
                        floating = windowState.placement != WindowPlacement.Maximized,
                        focused = focused,
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            DesktopTitleBar(title = title, state = windowState, onClose = onClose, focused = focused)
                            HorizontalDivider(color = colors.outlineVariant)
                            FeedbackBriefingApp(animateBackdrop = focused, onAppearance = {
                                error = ""
                                draft = saved
                            })
                        }
                    }

                    val preview = draft
                    if (preview != null) {
                        BriefingAppearanceDialog(
                            draft = preview,
                            onChange = { draft = it },
                            onDismiss = { draft = null },
                            saving = saving,
                            error = error,
                            onSave = {
                                saving = true
                                error = ""
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { store.save(preview) }
                                        saved = preview
                                        draft = null
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        error = "显示设置保存失败，请重试。"
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BriefingWindowFrame(
    modifier: Modifier,
    floating: Boolean,
    focused: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val edge = if (focused) colors.outline else lerp(colors.outlineVariant, colors.outline, 0.45f)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (floating) 9.dp else 0.dp),
        border = if (floating) BorderStroke(1.dp, edge) else null,
        color = colors.background,
        contentColor = colors.onBackground,
        content = content,
    )
}

package com.lemon.mcdevmanagermp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import com.github.panpf.sketch.AsyncImage
import com.github.panpf.sketch.rememberAsyncImageState
import com.github.panpf.sketch.request.ComposableImageOptions
import com.github.panpf.sketch.request.error
import com.github.panpf.sketch.request.fallback
import com.github.panpf.sketch.request.placeholder
import com.lemon.mcdevmanagermp.ui.theme.LocalAppColors
import mcdevmanagermpr.shared.generated.resources.Res
import mcdevmanagermpr.shared.generated.resources.img_avatar

@Composable
fun DeveloperAvatar(headImg: String?, modifier: Modifier, shape: Shape = CircleShape) {
    val color = LocalAppColors.current.primary.copy(alpha = 0.12f)
    Box(modifier.clip(shape).background(color), contentAlignment = Alignment.Center) {
        AsyncImage(
            uri = headImg,
            state = rememberAsyncImageState(ComposableImageOptions {
                placeholder(Res.drawable.img_avatar)
                fallback(Res.drawable.img_avatar)
                error(Res.drawable.img_avatar)
                crossfade()
                sizeMultiplier(2.0f)
            }),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

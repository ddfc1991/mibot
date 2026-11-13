package com.openminis.app.mibot.v7

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * [destiny/v7] Material chrome wrapper around a V7 design screen. Stub: the
 * real wrapper applied the V7 dark palette / scaffold. Keeps the route tree
 * compiling until the real package lands.
 */
@Composable
fun V7MaterialWrap(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) { content() }
}

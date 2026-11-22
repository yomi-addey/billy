package com.example.billy.ui.views

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Circular profile photo, falling back to the first initial (or a person icon). */
@Composable
fun Avatar(photo: ByteArray?, name: String, modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val image = remember(photo) {
        photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name.trim().firstOrNull()?.uppercaseChar()
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = "Profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
            initial != null -> Text(
                initial.toString(),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            else -> Icon(
                Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

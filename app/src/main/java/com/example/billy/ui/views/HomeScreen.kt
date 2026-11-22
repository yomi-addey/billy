package com.example.billy.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.billy.data.Profile
import com.example.billy.ui.theme.BillyTheme

/** Placeholder home: shows the signed-in user's profile. */
@Composable
fun HomeScreen(
    profile: Profile,
    photo: ByteArray?,
    onEditProfile: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Avatar(photo = photo, name = profile.firstName, size = 120.dp)
            Text("Hi, ${profile.firstName}", style = MaterialTheme.typography.headlineMedium)
            if (profile.lastName.isNotBlank()) {
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "${profile.city}, ${profile.region}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profile.bio.isNotBlank()) {
                Text(profile.bio, textAlign = TextAlign.Center)
            }
            Button(onClick = onEditProfile) { Text("Edit profile") }
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    BillyTheme {
        HomeScreen(Profile("Ada", "Lovelace", "Boston", "Massachusetts", "Splits fairly."), null, {}, {})
    }
}

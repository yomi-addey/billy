package com.example.billy.ui.views

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.billy.data.ProfileLimits
import com.example.billy.ui.theme.BillyTheme
import com.example.billy.view_models.LocationStatus
import com.example.billy.view_models.ProfileUiState

/**
 * First-time profile setup, and editing later ([ProfileUiState.isEditing]).
 * Asks only for what bill splitting needs: name, optional photo, city/state, optional bio.
 */
@Composable
fun ProfileSetupScreen(
    state: ProfileUiState,
    onFirstNameChange: (String) -> Unit,
    onLastNameChange: (String) -> Unit,
    onCityChange: (String) -> Unit,
    onRegionChange: (String) -> Unit,
    onBioChange: (String) -> Unit,
    onLocationRequested: (explicit: Boolean) -> Unit,
    onLocationPermissionResult: (Boolean) -> Unit,
    onPhotoPicked: (Uri) -> Unit,
    onRemovePhoto: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        onLocationPermissionResult,
    )
    // System photo picker: no storage permission needed, user picks a single image.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onPhotoPicked)
    }
    val requestLocation = { explicit: Boolean ->
        onLocationRequested(explicit)
        locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val pickPhoto = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // First-time setup: offer to fill in city/state once. The user can decline and type it.
    LaunchedEffect(state.shouldAutoRequestLocation) {
        if (state.shouldAutoRequestLocation) requestLocation(false)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (state.isEditing) "Edit profile" else "Set up your profile",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "Your name, photo, city and state are shown to people you split bills with.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Photo (optional)
            Box(contentAlignment = Alignment.Center) {
                Avatar(
                    photo = state.photo,
                    name = state.firstName,
                    size = 112.dp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            enabled = !state.isPreparingPhoto,
                            role = Role.Button,
                            onClickLabel = "Choose profile photo",
                            onClick = pickPhoto,
                        ),
                )
                if (state.isPreparingPhoto) CircularProgressIndicator()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = pickPhoto, enabled = !state.isPreparingPhoto) {
                    Text(if (state.photo == null) "Add photo" else "Change photo")
                }
                if (state.photo != null) {
                    TextButton(onClick = onRemovePhoto) { Text("Remove") }
                }
            }

            // Name
            OutlinedTextField(
                value = state.firstName,
                onValueChange = onFirstNameChange,
                label = { Text("First name") },
                isError = state.firstNameError,
                supportingText = if (state.firstNameError) {
                    { Text("Enter your first name") }
                } else null,
                singleLine = true,
                keyboardOptions = nameKeyboard,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.lastName,
                onValueChange = onLastNameChange,
                label = { Text("Last name (optional)") },
                singleLine = true,
                keyboardOptions = nameKeyboard,
                modifier = Modifier.fillMaxWidth(),
            )

            // Location
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Location", style = MaterialTheme.typography.titleMedium)
                    val busy = state.locationStatus == LocationStatus.Requesting ||
                        state.locationStatus == LocationStatus.Detecting
                    OutlinedButton(onClick = { requestLocation(true) }, enabled = !busy) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.LocationOn, contentDescription = null, Modifier.size(18.dp))
                        }
                        Text("Use my location", Modifier.padding(start = 8.dp))
                    }
                }
                locationMessage(state.locationStatus)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.city,
                        onValueChange = onCityChange,
                        label = { Text("City") },
                        isError = state.cityError,
                        supportingText = if (state.cityError) {
                            { Text("Required") }
                        } else null,
                        singleLine = true,
                        keyboardOptions = nameKeyboard,
                        modifier = Modifier.weight(3f),
                    )
                    OutlinedTextField(
                        value = state.region,
                        onValueChange = onRegionChange,
                        label = { Text("State") },
                        isError = state.regionError,
                        supportingText = if (state.regionError) {
                            { Text("Required") }
                        } else null,
                        singleLine = true,
                        keyboardOptions = nameKeyboard,
                        modifier = Modifier.weight(2f),
                    )
                }
            }

            // Bio (optional)
            OutlinedTextField(
                value = state.bio,
                onValueChange = onBioChange,
                label = { Text("Bio (optional)") },
                supportingText = { Text("${state.bio.length} / ${ProfileLimits.BIO_MAX}") },
                minLines = 2,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }

            Button(
                onClick = onSave,
                enabled = !state.isSaving && !state.isPreparingPhoto,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (state.isEditing) "Save" else "Continue")
                }
            }
            if (state.isEditing) {
                TextButton(onClick = onCancel, enabled = !state.isSaving) { Text("Cancel") }
            }
        }
    }
}

private val nameKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.Words,
    imeAction = ImeAction.Next,
)

private fun locationMessage(status: LocationStatus): String? = when (status) {
    LocationStatus.Idle, LocationStatus.Requesting -> null
    LocationStatus.Detecting -> "Finding your city…"
    LocationStatus.Detected -> "Filled in from your approximate location. You can change it."
    LocationStatus.Failed -> "Couldn't detect your location. Enter your city and state."
    LocationStatus.PermissionDenied -> "Location access is off. Enter your city and state."
}

@Preview(showBackground = true)
@Composable
private fun ProfileSetupPreview() {
    BillyTheme {
        ProfileSetupScreen(
            state = ProfileUiState(
                firstName = "Ada",
                city = "Boston",
                region = "Massachusetts",
                locationStatus = LocationStatus.Detected,
            ),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

package com.example.billy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.billy.data.AndroidLocationProvider
import com.example.billy.data.HttpAuthRepository
import com.example.billy.data.HttpProfileRepository
import com.example.billy.data.KeystoreTokenStore
import com.example.billy.data.prepareProfilePhoto
import com.example.billy.ui.theme.BillyTheme
import com.example.billy.ui.views.HomeScreen
import com.example.billy.ui.views.LoginScreen
import com.example.billy.ui.views.ProfileSetupScreen
import com.example.billy.view_models.LoginScreenViewModel
import com.example.billy.view_models.ProfileGate
import com.example.billy.view_models.ProfileGateViewModel
import com.example.billy.view_models.ProfileSetupViewModel

private const val SESSION_EXPIRED = "Your session expired. Sign in again."

class MainActivity : ComponentActivity() {
    private val authRepo by lazy { HttpAuthRepository(KeystoreTokenStore(applicationContext)) }
    private val profileRepo by lazy { HttpProfileRepository(authRepo) }
    private val locationProvider by lazy { AndroidLocationProvider(applicationContext) }

    private val vm: LoginScreenViewModel by viewModels { LoginScreenViewModel.Factory(authRepo) }
    private val gateVm: ProfileGateViewModel by viewModels { ProfileGateViewModel.Factory(profileRepo) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On rotation/recreation the same intent is redelivered; the VM ignores a repeated token.
        handleSignInLink(intent)
        setContent {
            BillyTheme {
                val login by vm.state.collectAsStateWithLifecycle()
                val gate by gateVm.state.collectAsStateWithLifecycle()

                LaunchedEffect(login.isSignedIn, login.isCheckingSession) {
                    if (login.isSignedIn) gateVm.loadIfNeeded()
                    else if (!login.isCheckingSession) gateVm.reset()
                }
                LaunchedEffect(gate.sessionExpired) {
                    if (gate.sessionExpired) signOut(SESSION_EXPIRED)
                }

                when {
                    login.isCheckingSession -> Centered { CircularProgressIndicator() }
                    !login.isSignedIn -> LoginScreen(
                        state = login,
                        onCountryChange = vm::onCountryChange,
                        onPhoneChange = vm::onPhoneChange,
                        onLogin = vm::login,
                        onUseDifferentNumber = vm::useDifferentNumber,
                    )
                    else -> SignedIn(gate.gate, gate.isEditing, gate.formSession)
                }
            }
        }
    }

    @Composable
    private fun SignedIn(gate: ProfileGate, isEditing: Boolean, formSession: Int) {
        when (gate) {
            ProfileGate.Idle, ProfileGate.Loading -> Centered { CircularProgressIndicator() }
            ProfileGate.Error -> Centered {
                Text("Couldn't load your profile. Check your connection.")
                Button(onClick = gateVm::retry) { Text("Try again") }
                TextButton(onClick = { signOut() }) { Text("Sign out") }
            }
            ProfileGate.NeedsProfile -> ProfileForm(key = "profile-create-$formSession", gate = gate)
            is ProfileGate.Ready ->
                if (isEditing) {
                    BackHandler(onBack = gateVm::cancelEditing)
                    ProfileForm(key = "profile-edit-$formSession", gate = gate)
                } else {
                    HomeScreen(
                        profile = gate.profile,
                        photo = gate.photo,
                        onEditProfile = gateVm::startEditing,
                        onSignOut = { signOut() },
                    )
                }
        }
    }

    @Composable
    private fun ProfileForm(key: String, gate: ProfileGate) {
        val ready = gate as? ProfileGate.Ready
        val formVm: ProfileSetupViewModel = viewModel(
            key = key,
            factory = ProfileSetupViewModel.Factory(profileRepo, locationProvider, ready?.profile, ready?.photo),
        )
        val state by formVm.state.collectAsStateWithLifecycle()
        val context = LocalContext.current

        LaunchedEffect(state.saved) {
            state.saved?.let { gateVm.onProfileSaved(it, state.photo) }
        }
        LaunchedEffect(state.sessionExpired) {
            if (state.sessionExpired) signOut(SESSION_EXPIRED)
        }

        ProfileSetupScreen(
            state = state,
            onFirstNameChange = formVm::onFirstNameChange,
            onLastNameChange = formVm::onLastNameChange,
            onCityChange = formVm::onCityChange,
            onRegionChange = formVm::onRegionChange,
            onBioChange = formVm::onBioChange,
            onLocationRequested = formVm::onLocationRequested,
            onLocationPermissionResult = formVm::onLocationPermissionResult,
            onPhotoPicked = { uri -> formVm.onPhotoPicked { prepareProfilePhoto(context, uri) } },
            onRemovePhoto = formVm::removePhoto,
            onSave = formVm::save,
            onCancel = gateVm::cancelEditing,
        )
    }

    private fun signOut(message: String? = null) {
        vm.signOut(message)
        gateVm.reset()
    }

    // singleTop: tapping the SMS link while the app is open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSignInLink(intent)
    }

    private fun handleSignInLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.path != "/signin") return
        data.getQueryParameter("token")?.takeIf { it.isNotBlank() }?.let(vm::onSignInLink)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) { content() }
    }
}

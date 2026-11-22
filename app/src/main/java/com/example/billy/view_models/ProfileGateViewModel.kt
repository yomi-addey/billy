package com.example.billy.view_models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.billy.data.AuthException
import com.example.billy.data.Profile
import com.example.billy.data.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a signed-in user should see: profile setup (first time), home, or an error. */
sealed interface ProfileGate {
    data object Idle : ProfileGate
    data object Loading : ProfileGate
    data object NeedsProfile : ProfileGate
    data class Ready(val profile: Profile, val photo: ByteArray?) : ProfileGate
    data object Error : ProfileGate
}

data class ProfileGateUiState(
    val gate: ProfileGate = ProfileGate.Idle,
    val isEditing: Boolean = false,
    /**
     * Increments whenever a new profile form should start from scratch (editing starts, or a
     * different user signs in), so the form's keyed view model is never reused with stale input.
     */
    val formSession: Int = 0,
    val sessionExpired: Boolean = false,
)

class ProfileGateViewModel(private val profiles: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow(ProfileGateUiState())
    val state: StateFlow<ProfileGateUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** Loads the profile after sign-in. No-op if already loaded or loading. */
    fun loadIfNeeded() {
        val gate = _state.value.gate
        if (gate == ProfileGate.Idle || gate == ProfileGate.Error) load()
    }

    fun retry() = load()

    private fun load() {
        loadJob?.cancel()
        _state.update { it.copy(gate = ProfileGate.Loading) }
        loadJob = viewModelScope.launch {
            val result = profiles.getProfile()
            ensureActive() // runCatching swallows cancellation; don't publish results after reset()
            result
                .onSuccess { profile ->
                    val gate = if (profile == null) ProfileGate.NeedsProfile
                    // A missing photo shouldn't block the home screen.
                    else ProfileGate.Ready(profile, if (profile.hasPhoto) profiles.getPhoto().getOrNull() else null)
                    ensureActive()
                    _state.update { it.copy(gate = gate) }
                }
                .onFailure { e ->
                    if (e is AuthException && e.httpCode == 401) _state.update { it.copy(sessionExpired = true) }
                    else _state.update { it.copy(gate = ProfileGate.Error) }
                }
        }
    }

    fun startEditing() {
        if (_state.value.gate !is ProfileGate.Ready) return
        _state.update { it.copy(isEditing = true, formSession = it.formSession + 1) }
    }

    fun cancelEditing() = _state.update { it.copy(isEditing = false) }

    fun onProfileSaved(profile: Profile, photo: ByteArray?) =
        _state.update { it.copy(gate = ProfileGate.Ready(profile, photo), isEditing = false) }

    /** Call on sign-out so the next user starts clean. */
    fun reset() {
        loadJob?.cancel()
        _state.update { ProfileGateUiState(formSession = it.formSession + 1) }
    }

    class Factory(private val profiles: ProfileRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileGateViewModel(profiles) as T
    }
}

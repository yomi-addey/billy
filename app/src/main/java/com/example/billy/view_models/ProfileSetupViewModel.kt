package com.example.billy.view_models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.billy.data.AuthException
import com.example.billy.data.LocationProvider
import com.example.billy.data.Profile
import com.example.billy.data.ProfileLimits
import com.example.billy.data.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LocationStatus {
    Idle,
    /** Permission prompt is showing. */
    Requesting,
    Detecting,
    Detected,
    Failed,
    PermissionDenied,
}

@Suppress("ArrayInDataClass") // photo is compared by reference, which is what we want for state changes
data class ProfileUiState(
    val isEditing: Boolean = false,
    val firstName: String = "",
    val lastName: String = "",
    val city: String = "",
    val region: String = "",
    val bio: String = "",
    /** JPEG ready for upload (or the current photo when editing). */
    val photo: ByteArray? = null,
    val photoChanged: Boolean = false,
    val isPreparingPhoto: Boolean = false,
    val locationStatus: LocationStatus = LocationStatus.Idle,
    /** Once the user types in a location field, detection never overwrites it. */
    val cityEdited: Boolean = false,
    val regionEdited: Boolean = false,
    val showErrors: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    /** Set when saving finished; the caller navigates away. */
    val saved: Profile? = null,
    val sessionExpired: Boolean = false,
) {
    val firstNameError get() = showErrors && firstName.isBlank()
    val cityError get() = showErrors && city.isBlank()
    val regionError get() = showErrors && region.isBlank()
    val canSave get() = firstName.isNotBlank() && city.isNotBlank() && region.isNotBlank() &&
        !isSaving && !isPreparingPhoto
    val shouldAutoRequestLocation get() = !isEditing && locationStatus == LocationStatus.Idle
}

class ProfileSetupViewModel(
    private val profiles: ProfileRepository,
    private val location: LocationProvider,
    existing: Profile? = null,
    existingPhoto: ByteArray? = null,
) : ViewModel() {
    private val hadPhoto = existing?.hasPhoto == true

    private val _state = MutableStateFlow(
        existing?.let {
            ProfileUiState(
                isEditing = true,
                firstName = it.firstName,
                lastName = it.lastName,
                city = it.city,
                region = it.region,
                bio = it.bio,
                photo = existingPhoto,
                cityEdited = true,
                regionEdited = true,
            )
        } ?: ProfileUiState()
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    fun onFirstNameChange(v: String) = _state.update { it.copy(firstName = v.take(ProfileLimits.NAME_MAX), error = null) }
    fun onLastNameChange(v: String) = _state.update { it.copy(lastName = v.take(ProfileLimits.NAME_MAX), error = null) }
    fun onCityChange(v: String) =
        _state.update { it.copy(city = v.take(ProfileLimits.PLACE_MAX), cityEdited = true, error = null) }
    fun onRegionChange(v: String) =
        _state.update { it.copy(region = v.take(ProfileLimits.PLACE_MAX), regionEdited = true, error = null) }
    fun onBioChange(v: String) = _state.update { it.copy(bio = v.take(ProfileLimits.BIO_MAX), error = null) }

    /** True when the user tapped "Use my location", so the result should replace what they typed. */
    private var explicitRequest = false

    /**
     * Call just before launching the permission prompt. [explicit] is true when the user tapped
     * "Use my location"; false for the automatic request when the form first opens, which must
     * not overwrite anything the user already typed.
     */
    fun onLocationRequested(explicit: Boolean) {
        explicitRequest = explicit
        _state.update { it.copy(locationStatus = LocationStatus.Requesting) }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (!granted) {
            _state.update { it.copy(locationStatus = LocationStatus.PermissionDenied) }
            return
        }
        detectLocation()
    }

    private fun detectLocation() {
        _state.update { it.copy(locationStatus = LocationStatus.Detecting) }
        viewModelScope.launch {
            location.detect()
                .onSuccess { found ->
                    val overwrite = explicitRequest
                    _state.update {
                        it.copy(
                            city = if (!overwrite && it.cityEdited && it.city.isNotBlank()) it.city else found.city,
                            region = if (!overwrite && it.regionEdited && it.region.isNotBlank()) it.region else found.region,
                            locationStatus = LocationStatus.Detected,
                        )
                    }
                }
                .onFailure { _state.update { it.copy(locationStatus = LocationStatus.Failed) } }
        }
    }

    /** [prepare] reads, crops and re-encodes the picked image (see `prepareProfilePhoto`). */
    fun onPhotoPicked(prepare: suspend () -> ByteArray) {
        _state.update { it.copy(isPreparingPhoto = true, error = null) }
        viewModelScope.launch {
            runCatching { prepare() }
                .onSuccess { bytes -> _state.update { it.copy(photo = bytes, photoChanged = true, isPreparingPhoto = false) } }
                .onFailure { _state.update { it.copy(isPreparingPhoto = false, error = "Couldn't use that photo. Try another one.") } }
        }
    }

    fun removePhoto() = _state.update { it.copy(photo = null, photoChanged = true) }

    fun save() {
        val s = _state.value
        if (!s.canSave) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        val profile = Profile(
            firstName = s.firstName.trim(),
            lastName = s.lastName.trim(),
            city = s.city.trim(),
            region = s.region.trim(),
            bio = s.bio.trim(),
        )
        _state.update { it.copy(isSaving = true, error = null, showErrors = true) }
        viewModelScope.launch {
            val result = profiles.saveProfile(profile).mapCatching { saved ->
                val photo = s.photo
                when {
                    s.photoChanged && photo != null -> profiles.uploadPhoto(photo).getOrThrow()
                    s.photoChanged && hadPhoto -> profiles.deletePhoto().getOrThrow()
                }
                saved.copy(hasPhoto = photo != null)
            }
            result
                .onSuccess { saved -> _state.update { it.copy(isSaving = false, saved = saved) } }
                .onFailure { e ->
                    if (e is AuthException && e.httpCode == 401) {
                        _state.update { it.copy(isSaving = false, sessionExpired = true) }
                    } else {
                        _state.update {
                            it.copy(isSaving = false, error = "Couldn't save your profile. Check your connection and try again.")
                        }
                    }
                }
        }
    }

    class Factory(
        private val profiles: ProfileRepository,
        private val location: LocationProvider,
        private val existing: Profile? = null,
        private val existingPhoto: ByteArray? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProfileSetupViewModel(profiles, location, existing, existingPhoto) as T
    }
}

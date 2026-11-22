package com.example.billy

import com.example.billy.data.Profile
import com.example.billy.data.ProfileLimits
import com.example.billy.view_models.LocationStatus
import com.example.billy.view_models.ProfileSetupViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ProfileSetupViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeProfileRepository()
    private val location = FakeLocationProvider()
    private fun vm(existing: Profile? = null, photo: ByteArray? = null) =
        ProfileSetupViewModel(repo, location, existing, photo)

    private fun ProfileSetupViewModel.fillRequired() {
        onFirstNameChange("Ada")
        onCityChange("Boston")
        onRegionChange("MA")
    }

    @Test fun `requires first name, city and state but not last name or bio`() {
        val vm = vm()
        assertFalse(vm.state.value.canSave)
        vm.onFirstNameChange("Ada")
        vm.onCityChange("Boston")
        assertFalse(vm.state.value.canSave)
        vm.onRegionChange("MA")
        assertTrue(vm.state.value.canSave)
        vm.onFirstNameChange("   ")
        assertFalse(vm.state.value.canSave)
    }

    @Test fun `saving an incomplete form shows errors and does not call the backend`() {
        val vm = vm()
        vm.onFirstNameChange("Ada")
        vm.save()
        val s = vm.state.value
        assertTrue(s.cityError)
        assertTrue(s.regionError)
        assertFalse(s.firstNameError)
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun `inputs are capped at the backend limits`() {
        val vm = vm()
        vm.onFirstNameChange("x".repeat(100))
        vm.onBioChange("y".repeat(500))
        assertEquals(ProfileLimits.NAME_MAX, vm.state.value.firstName.length)
        assertEquals(ProfileLimits.BIO_MAX, vm.state.value.bio.length)
    }

    @Test fun `auto detection fills empty location fields`() {
        val vm = vm()
        assertTrue(vm.state.value.shouldAutoRequestLocation)
        vm.onLocationRequested(explicit = false)
        assertFalse(vm.state.value.shouldAutoRequestLocation)
        vm.onLocationPermissionResult(true)
        val s = vm.state.value
        assertEquals("Boston", s.city)
        assertEquals("Massachusetts", s.region)
        assertEquals(LocationStatus.Detected, s.locationStatus)
    }

    @Test fun `auto detection never overwrites what the user typed`() {
        location.gate = CompletableDeferred()
        val vm = vm()
        vm.onLocationRequested(explicit = false)
        vm.onLocationPermissionResult(true)
        vm.onCityChange("Cambridge") // typed while detection is running
        location.gate!!.complete(Unit)
        assertEquals("Cambridge", vm.state.value.city)
        assertEquals("Massachusetts", vm.state.value.region)
    }

    @Test fun `tapping Use my location replaces typed values`() {
        val vm = vm()
        vm.onCityChange("Cambridge")
        vm.onLocationRequested(explicit = true)
        vm.onLocationPermissionResult(true)
        assertEquals("Boston", vm.state.value.city)
    }

    @Test fun `denied permission leaves manual entry`() {
        val vm = vm()
        vm.onLocationRequested(explicit = false)
        vm.onLocationPermissionResult(false)
        assertEquals(LocationStatus.PermissionDenied, vm.state.value.locationStatus)
        assertEquals(0, location.calls)
        assertEquals("", vm.state.value.city)
    }

    @Test fun `detection failure is reported`() {
        location.result = Result.failure(IOException())
        val vm = vm()
        vm.onLocationRequested(explicit = true)
        vm.onLocationPermissionResult(true)
        assertEquals(LocationStatus.Failed, vm.state.value.locationStatus)
    }

    @Test fun `save trims fields and uploads a picked photo`() {
        val vm = vm()
        vm.fillRequired()
        vm.onFirstNameChange("  Ada ")
        vm.onBioChange(" hi ")
        val jpeg = byteArrayOf(1, 2, 3)
        vm.onPhotoPicked { jpeg }
        vm.save()

        assertEquals(Profile("Ada", "", "Boston", "MA", "hi"), repo.saved.single())
        assertArrayEquals(jpeg, repo.photo)
        val saved = vm.state.value.saved
        assertNotNull(saved)
        assertTrue(saved!!.hasPhoto)
    }

    @Test fun `photo that fails to process shows an error`() {
        val vm = vm()
        vm.onPhotoPicked { throw IOException("bad image") }
        assertNull(vm.state.value.photo)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.isPreparingPhoto)
    }

    @Test fun `network failure keeps the form and shows an error`() {
        repo.failWith = IOException()
        val vm = vm()
        vm.fillRequired()
        vm.save()
        val s = vm.state.value
        assertNull(s.saved)
        assertNotNull(s.error)
        assertFalse(s.isSaving)
        assertEquals("Ada", s.firstName)
    }

    @Test fun `photo upload failure does not finish setup`() {
        repo.failPhotoWith = IOException()
        val vm = vm()
        vm.fillRequired()
        vm.onPhotoPicked { byteArrayOf(1) }
        vm.save()
        assertNull(vm.state.value.saved)
        assertNotNull(vm.state.value.error)
    }

    @Test fun `401 marks the session as expired`() {
        repo.failWith = unauthorized
        val vm = vm()
        vm.fillRequired()
        vm.save()
        assertTrue(vm.state.value.sessionExpired)
    }

    @Test fun `edit mode pre-fills and does not auto-request location`() {
        val existing = Profile("Ada", "Lovelace", "Boston", "MA", "bio", hasPhoto = true)
        val vm = vm(existing, byteArrayOf(9))
        val s = vm.state.value
        assertTrue(s.isEditing)
        assertEquals("Lovelace", s.lastName)
        assertFalse(s.shouldAutoRequestLocation)
        assertTrue(s.canSave)
    }

    @Test fun `edit without touching the photo does not re-upload it`() {
        repo.photo = byteArrayOf(9)
        val vm = vm(Profile("Ada", city = "Boston", region = "MA", hasPhoto = true), byteArrayOf(9))
        vm.onCityChange("Cambridge")
        vm.save()
        assertEquals(0, repo.uploads)
        assertEquals(0, repo.deletes)
        assertTrue(vm.state.value.saved!!.hasPhoto)
    }

    @Test fun `removing the photo in edit mode deletes it`() {
        repo.photo = byteArrayOf(9)
        val vm = vm(Profile("Ada", city = "Boston", region = "MA", hasPhoto = true), byteArrayOf(9))
        vm.removePhoto()
        vm.save()
        assertEquals(1, repo.deletes)
        assertFalse(vm.state.value.saved!!.hasPhoto)
    }
}

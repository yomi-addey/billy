package com.example.billy

import com.example.billy.data.Profile
import com.example.billy.view_models.LoginScreenViewModel
import com.example.billy.view_models.ProfileGate
import com.example.billy.view_models.ProfileGateViewModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ProfileGateViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ada = Profile("Ada", "Lovelace", "Boston", "MA")

    @Test fun `first-time user needs a profile`() {
        val vm = ProfileGateViewModel(FakeProfileRepository(profile = null))
        vm.loadIfNeeded()
        assertEquals(ProfileGate.NeedsProfile, vm.state.value.gate)
    }

    @Test fun `existing profile goes to home with photo`() {
        val photo = byteArrayOf(1, 2)
        val vm = ProfileGateViewModel(FakeProfileRepository(ada.copy(hasPhoto = true), photo))
        vm.loadIfNeeded()
        val gate = vm.state.value.gate as ProfileGate.Ready
        assertEquals("Ada", gate.profile.firstName)
        assertArrayEquals(photo, gate.photo)
    }

    @Test fun `network error can be retried`() {
        val repo = FakeProfileRepository(ada).apply { failWith = IOException() }
        val vm = ProfileGateViewModel(repo)
        vm.loadIfNeeded()
        assertEquals(ProfileGate.Error, vm.state.value.gate)
        repo.failWith = null
        vm.retry()
        assertTrue(vm.state.value.gate is ProfileGate.Ready)
    }

    @Test fun `401 flags an expired session`() {
        val vm = ProfileGateViewModel(FakeProfileRepository().apply { failWith = unauthorized })
        vm.loadIfNeeded()
        assertTrue(vm.state.value.sessionExpired)
    }

    @Test fun `editing gets a fresh form session and saving returns home`() {
        val vm = ProfileGateViewModel(FakeProfileRepository(ada))
        vm.loadIfNeeded()
        val before = vm.state.value.formSession
        vm.startEditing()
        assertTrue(vm.state.value.isEditing)
        assertEquals(before + 1, vm.state.value.formSession)
        vm.onProfileSaved(ada.copy(city = "Cambridge"), null)
        assertFalse(vm.state.value.isEditing)
        assertEquals("Cambridge", (vm.state.value.gate as ProfileGate.Ready).profile.city)
    }

    @Test fun `reset clears the profile and starts a new form session`() {
        val vm = ProfileGateViewModel(FakeProfileRepository(ada))
        vm.loadIfNeeded()
        val before = vm.state.value.formSession
        vm.reset()
        assertEquals(ProfileGate.Idle, vm.state.value.gate)
        assertTrue(vm.state.value.formSession > before)
    }
}

class LoginScreenViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `stored session means signed in after the check`() {
        val vm = LoginScreenViewModel(FakeAuthRepository(FakeTokenStore("t")))
        assertFalse(vm.state.value.isCheckingSession)
        assertTrue(vm.state.value.isSignedIn)
    }

    @Test fun `no stored session means signed out`() {
        val vm = LoginScreenViewModel(FakeAuthRepository(FakeTokenStore(null)))
        assertFalse(vm.state.value.isSignedIn)
    }

    @Test fun `sign in link stores the session, sign out clears it`() {
        val tokens = FakeTokenStore()
        val vm = LoginScreenViewModel(FakeAuthRepository(tokens))
        vm.onSignInLink("abc")
        assertTrue(vm.state.value.isSignedIn)
        assertEquals("session-abc", tokens.token)
        vm.signOut("bye")
        assertFalse(vm.state.value.isSignedIn)
        assertEquals("bye", vm.state.value.error)
        assertNull(tokens.token)
    }
}

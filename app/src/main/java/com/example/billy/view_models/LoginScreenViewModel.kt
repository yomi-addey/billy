package com.example.billy.view_models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.billy.data.AuthException
import com.example.billy.data.AuthRepository
import com.example.billy.data.Country
import com.example.billy.data.PhoneNumbers
import com.example.billy.data.countries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val country: Country = countries.first(),
    val phone: String = "",
    val phoneTouched: Boolean = false,
    val isSending: Boolean = false,
    /** Set once the SMS has been requested; the number it was sent to. */
    val sentTo: String? = null,
    val isVerifying: Boolean = false,
    val error: String? = null,
    val isSignedIn: Boolean = false,
) {
    val phoneValid get() = PhoneNumbers.isValid(country.dialCode, phone)
    val showPhoneError get() = phoneTouched && !phoneValid
}

class LoginScreenViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState(isSignedIn = auth.isSignedIn()))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var lastHandledToken: String? = null

    fun onCountryChange(country: Country) = _state.update { it.copy(country = country, error = null) }

    fun onPhoneChange(phone: String) =
        _state.update { it.copy(phone = phone, phoneTouched = true, error = null) }

    fun login() {
        val s = _state.value
        if (!s.phoneValid || s.isSending) return
        val e164 = PhoneNumbers.toE164(s.country.dialCode, s.phone)
        _state.update { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            auth.requestSignInLink(e164)
                .onSuccess { _state.update { it.copy(isSending = false, sentTo = e164) } }
                .onFailure {
                    _state.update { it.copy(isSending = false, error = "Couldn't send the text. Check your connection and try again.") }
                }
        }
    }

    /** Lets the user go back and enter a different number. */
    fun useDifferentNumber() = _state.update { it.copy(sentTo = null, error = null) }

    /** Called with the token from the link in the SMS. Tokens are single-use, so ignore repeats. */
    fun onSignInLink(token: String) {
        if (token == lastHandledToken) return
        lastHandledToken = token
        _state.update { it.copy(isVerifying = true, error = null) }
        viewModelScope.launch {
            auth.completeSignIn(token)
                .onSuccess { _state.update { it.copy(isVerifying = false, isSignedIn = true) } }
                .onFailure { e ->
                    val msg = if (e is AuthException && e.httpCode in 400..499)
                        "That sign-in link has expired or was already used. Request a new one."
                    else "Couldn't complete sign in. Check your connection and tap the link again."
                    if (e !is AuthException || e.httpCode !in 400..499) lastHandledToken = null
                    _state.update { it.copy(isVerifying = false, sentTo = null, error = msg) }
                }
        }
    }

    fun signOut() {
        auth.signOut()
        _state.value = LoginUiState()
    }

    class Factory(private val auth: AuthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LoginScreenViewModel(auth) as T
    }
}

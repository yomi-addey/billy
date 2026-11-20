package com.example.billy.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.billy.data.Country
import com.example.billy.data.countries
import com.example.billy.ui.theme.BillyTheme
import com.example.billy.view_models.LoginUiState

@Composable
fun LoginScreen(
    state: LoginUiState,
    onCountryChange: (Country) -> Unit,
    onPhoneChange: (String) -> Unit,
    onLogin: () -> Unit,
    onUseDifferentNumber: () -> Unit,
) {
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically)
        ) {
            when {
                state.isVerifying -> {
                    CircularProgressIndicator()
                    Text("Signing you in…", style = MaterialTheme.typography.titleMedium)
                }

                state.sentTo != null -> {
                    Text("Check your texts", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "We sent a sign-in link to ${state.sentTo}. Tap it to finish signing in.",
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = onUseDifferentNumber) { Text("Use a different number") }
                }

                else -> {
                    Text("Welcome", style = MaterialTheme.typography.headlineLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CountryDropdown(
                            modifier = Modifier.weight(1f),
                            selected = state.country,
                            onSelected = onCountryChange
                        )
                        TextField(
                            modifier = Modifier.weight(2f),
                            placeholder = { Text("Phone number") },
                            value = state.phone,
                            onValueChange = onPhoneChange,
                            shape = RoundedCornerShape(percent = 50),
                            singleLine = true,
                            isError = state.showPhoneError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            colors = fieldColors()
                        )
                    }
                    if (state.showPhoneError) {
                        Text(
                            "Enter a valid phone number",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(onClick = onLogin, enabled = state.phoneValid && !state.isSending) {
                        if (state.isSending) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Text me a sign-in link")
                        }
                    }
                }
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryDropdown(
    modifier: Modifier = Modifier,
    selected: Country,
    onSelected: (Country) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        modifier = modifier,
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        TextField(
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
            readOnly = true,
            value = "${selected.flag} ${selected.dialCode}",
            onValueChange = {},
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = fieldColors(),
            shape = RoundedCornerShape(percent = 50)
        )

        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            countries.forEach { country ->
                DropdownMenuItem(
                    text = { Text("${country.flag} ${country.name} (${country.dialCode})") },
                    onClick = {
                        onSelected(country)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
@Preview
private fun LoginScreenPreview() {
    BillyTheme {
        LoginScreen(LoginUiState(phone = "415 555 0123"), {}, {}, {}, {})
    }
}

@Composable
@Preview
private fun LoginScreenSentPreview() {
    BillyTheme {
        LoginScreen(LoginUiState(sentTo = "+14155550123"), {}, {}, {}, {})
    }
}

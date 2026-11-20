package com.example.billy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.billy.data.HttpAuthRepository
import com.example.billy.ui.theme.BillyTheme
import com.example.billy.ui.views.LoginScreen
import com.example.billy.view_models.LoginScreenViewModel

class MainActivity : ComponentActivity() {
    private val vm: LoginScreenViewModel by viewModels {
        LoginScreenViewModel.Factory(HttpAuthRepository(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On rotation/recreation the same intent is redelivered; the VM ignores a repeated token.
        handleSignInLink(intent)
        setContent {
            BillyTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                if (state.isSignedIn) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                        Column(
                            Modifier.fillMaxSize().padding(padding),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                        ) {
                            Text("You're signed in")
                            Button(onClick = vm::signOut) { Text("Sign out") }
                        }
                    }
                } else {
                    LoginScreen(
                        state = state,
                        onCountryChange = vm::onCountryChange,
                        onPhoneChange = vm::onPhoneChange,
                        onLogin = vm::login,
                        onUseDifferentNumber = vm::useDifferentNumber,
                    )
                }
            }
        }
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

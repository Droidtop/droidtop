package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droidtop.app.ui.DroidtopTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import android.util.Log
import dev.droidtop.library.userFacingErrorMessage
import kotlinx.coroutines.launch
import app.gamenative.service.itch.ItchAuthManager

/**
 * Signing in to itch.io -- build-plan step 6 of the PC surface
 * (docs/SPEC.md 7i). itch.io uses a personal API key rather than
 * OAuth, so this is a simple text input screen that validates the
 * key against itch.io and stores it on success.
 */
class ItchSignInActivity : AppCompatActivity() {

    private var apiKey by mutableStateOf("")
    private var status by mutableStateOf("Enter your itch.io API key from https://itch.io/user/settings/api-keys")
    private var isLoading by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DroidtopTheme(darkTheme = true, gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Sign in to itch.io", style = MaterialTheme.typography.headlineSmall)
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { signIn() },
                        enabled = !isLoading && apiKey.isNotBlank(),
                    ) {
                        Text(if (isLoading) "Signing in..." else "Sign in", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    private fun signIn() {
        isLoading = true
        status = "Validating API key..."
        lifecycleScope.launch {
            // signIn runs on Dispatchers.IO itself.
            val result = ItchAuthManager.signIn(this@ItchSignInActivity, apiKey.trim())
            isLoading = false
            if (result.isSuccess) {
                status = "Signed in to itch.io as ${result.getOrNull()}."
                delay(1500)
                finish()
            } else {
                val exc = result.exceptionOrNull()
                Log.w("droidtop.itch", "Sign-in failed", exc)
                status = userFacingErrorMessage(exc)
            }
        }
    }

    companion object {
        private const val CLASS_NAME = "dev.droidtop.app.ItchSignInActivity"

        fun intent(context: Context): Intent =
            Intent().setClassName(context.packageName, CLASS_NAME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

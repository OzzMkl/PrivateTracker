package org.privatetracker.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.privatetracker.app.navigation.MainScaffold
import org.privatetracker.core.designsystem.theme.PrivateTrackerTheme
import org.privatetracker.core.protocol.v1.PairingUri
import org.privatetracker.feature.onboarding.OnboardingRoute

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** A pairing link from a camera app, waiting until the main screens can show it. */
    private val pairingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity gets back the link it had not shown yet; the intent's was taken the first time.
        if (savedInstanceState == null) takePairingLink(intent) else pairingLink.value = savedInstanceState.getString(PAIRING_LINK)
        enableEdgeToEdge()
        setContent {
            PrivateTrackerTheme {
                val viewModel: AppViewModel = hiltViewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val link by pairingLink.collectAsStateWithLifecycle()
                when (val current = state) {
                    AppUiState.Loading -> Surface(Modifier.fillMaxSize()) {}
                    AppUiState.Onboarding -> OnboardingRoute()
                    is AppUiState.Ready -> MainScaffold(current.mode, link, onPairingLinkHandled = { pairingLink.value = null })
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PAIRING_LINK, pairingLink.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takePairingLink(intent)
    }

    private fun takePairingLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.scheme == PairingUri.SCHEME) pairingLink.value = data.toString()
    }

    private companion object {
        const val PAIRING_LINK = "pairing_link"
    }
}

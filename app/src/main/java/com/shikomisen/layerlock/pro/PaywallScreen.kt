package com.shikomisen.layerlock.pro

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shikomisen.layerlock.data.pro.FREE_LAYER_LIMIT
import com.shikomisen.layerlock.data.pro.ProFeature
import com.shikomisen.layerlock.data.pro.ProStatus

/**
 * The Pro unlock.
 *
 * A one-time purchase, not a subscription (§9): the creative tools have no ongoing cost behind them,
 * and moving a previously one-off unlock to a recurring charge is the specific mistake that cost
 * Widgetsmith goodwill. If cloud sync or a hosted preset marketplace ever ships, *that* is what a
 * subscription would be for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    highlighted: ProFeature?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProViewModel = viewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val price by viewModel.price.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHost.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("LayerLock Pro") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Unlock everything, once",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "One payment. No subscription, no recurring charge, and it stays with your Google " +
                    "account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            highlighted?.let { feature ->
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(feature.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            feature.rationale,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            ProFeature.entries.forEach { feature ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Column {
                        Text(feature.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            feature.rationale,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                "The free version stays fully usable: up to $FREE_LAYER_LIMIT layers per scene, " +
                    "every clock and date format, and no time limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val current = status) {
                is ProStatus.Entitled -> StatusCard("Pro is active on this account.")
                is ProStatus.EntitledUnverified -> StatusCard(
                    "Pro is active. Waiting to confirm with Google Play (${current.reason}).",
                )

                is ProStatus.Unavailable -> StatusCard(
                    "Google Play billing is unavailable here: ${current.reason}",
                )

                ProStatus.Free -> Unit
            }

            if (!status.isPro) {
                Button(
                    onClick = { context.findActivity()?.let(viewModel::purchase) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(price?.let { "Unlock Pro — $it" } ?: "Unlock Pro")
                }
            }

            TextButton(onClick = viewModel::restore, enabled = !busy) {
                Text("Restore a previous purchase")
            }
        }
    }
}

@Composable
private fun StatusCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Billing needs a real Activity, which a Composable only has indirectly. */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

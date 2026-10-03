package com.supernova.networkswitch.presentation.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.supernova.networkswitch.domain.model.CompatibilityState
import com.supernova.networkswitch.domain.model.ControlMethod
import com.supernova.networkswitch.presentation.theme.NetworkSwitchTheme
import com.supernova.networkswitch.presentation.viewmodel.AutoSwitchViewModel
import com.supernova.networkswitch.presentation.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()
    private val autoSwitchViewModel: AutoSwitchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NetworkSwitchTheme {
                SettingsScreen(
                    viewModel = viewModel,
                    autoSwitchViewModel = autoSwitchViewModel,
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    viewModel: SettingsViewModel,
    autoSwitchViewModel: AutoSwitchViewModel,
    onBackClick: () -> Unit
) {
    val controlMethod by viewModel.controlMethod.collectAsState()
    val autoSwitchEnabled by autoSwitchViewModel.enabled.collectAsState()
    val autoSwitchStatus by autoSwitchViewModel.status.collectAsState()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The service runs either way; the permission only makes its notification visible. */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Control Method Selection
            ControlMethodCard(
                selectedMethod = controlMethod,
                onMethodSelected = { viewModel.updateControlMethod(it) },
                rootCompatibility = viewModel.rootCompatibility,
                shizukuCompatibility = viewModel.shizukuCompatibility,
                onRetryClick = { viewModel.retryCompatibilityCheck() }
            )
            
            // VoLTE auto-switch
            AutoSwitchCard(
                enabled = autoSwitchEnabled,
                status = autoSwitchStatus,
                diagnostics = autoSwitchViewModel.diagnostics,
                diagnosticsRunning = autoSwitchViewModel.diagnosticsRunning,
                onEnabledChange = { enable ->
                    if (enable &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    autoSwitchViewModel.setEnabled(enable)
                },
                onDiagnosticsClick = { autoSwitchViewModel.runDiagnostics() }
            )

            // About Section
            AboutCard()
        }
    }
}

@Composable
private fun AutoSwitchCard(
    enabled: Boolean,
    status: String,
    diagnostics: String?,
    diagnosticsRunning: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDiagnosticsClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "4G only while VoLTE is available",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Switches to 4G only once VoLTE registers and gives the previous network mode back when VoLTE is gone. It reacts to IMS registration events instead of polling. Needs the control method above to work. For reliable background operation, exclude this app from battery optimization.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (enabled && status.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onDiagnosticsClick,
                enabled = !diagnosticsRunning
            ) {
                Text(if (diagnosticsRunning) "Checking..." else "Check VoLTE detection")
            }

            if (diagnostics != null) {
                Spacer(modifier = Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        text = diagnostics,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlMethodCard(
    selectedMethod: ControlMethod,
    onMethodSelected: (ControlMethod) -> Unit,
    rootCompatibility: CompatibilityState,
    shizukuCompatibility: CompatibilityState,
    onRetryClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Control Method",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(onClick = onRetryClick) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh compatibility"
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Choose how the app should control network settings. Root method requires a rooted device, while Shizuku method works with non-rooted devices that have Shizuku installed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Root Method Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedMethod == ControlMethod.ROOT,
                        onClick = { onMethodSelected(ControlMethod.ROOT) }
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedMethod == ControlMethod.ROOT,
                    onClick = { onMethodSelected(ControlMethod.ROOT) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Root Method",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Requires rooted device with root access granted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Compatibility status indicator
                when (rootCompatibility) {
                    is CompatibilityState.Pending -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    is CompatibilityState.Compatible -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Compatible",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    is CompatibilityState.PermissionDenied -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Permission denied",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    is CompatibilityState.Incompatible -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            
            // Shizuku Method Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selectedMethod == ControlMethod.SHIZUKU,
                        onClick = { onMethodSelected(ControlMethod.SHIZUKU) }
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selectedMethod == ControlMethod.SHIZUKU,
                    onClick = { onMethodSelected(ControlMethod.SHIZUKU) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Shizuku Method",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Works with non-rooted devices using Shizuku service",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Compatibility status indicator
                when (shizukuCompatibility) {
                    is CompatibilityState.Pending -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    is CompatibilityState.Compatible -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Compatible",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    is CompatibilityState.PermissionDenied -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Permission denied",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    is CompatibilityState.Incompatible -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Not available",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Source Code",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            LinkItem(
                title = "NetworkSwitch",
                subtitle = "https://github.com/aunchagaonkar/NetworkSwitch",
                link = "https://github.com/aunchagaonkar/NetworkSwitch"
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Open Source Licenses",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            LinkItem(
                title = "Shizuku",
                subtitle = "Apache License 2.0\nhttps://github.com/RikkaApps/Shizuku",
                link = "https://github.com/RikkaApps/Shizuku"
            )
            
            LinkItem(
                title = "libsu",
                subtitle = "Apache License 2.0\nhttps://github.com/topjohnwu/libsu",
                link = "https://github.com/topjohnwu/libsu"
            )
            
            LinkItem(
                title = "Android Jetpack",
                subtitle = "Apache License 2.0\nhttps://android.googlesource.com/platform/frameworks/support",
                link = "https://android.googlesource.com/platform/frameworks/support"
            )
            
            LinkItem(
                title = "Kotlin",
                subtitle = "Apache License 2.0\nhttps://github.com/JetBrains/kotlin",
                link = "https://github.com/JetBrains/kotlin"
            )
        }
    }
}

@Composable
private fun LinkItem(
    title: String,
    subtitle: String,
    link: String
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            }
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

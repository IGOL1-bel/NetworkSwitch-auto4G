package com.supernova.networkswitch.presentation.ui.activity

import kotlinx.coroutines.launch
import com.supernova.networkswitch.util.LogExporter
import com.supernova.networkswitch.util.AppLog
import com.supernova.networkswitch.presentation.ui.composable.ModeDropdown
import com.supernova.networkswitch.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import com.supernova.networkswitch.autoswitch.AutoSwitchPreferences
import com.supernova.networkswitch.autoswitch.DetectionMode
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
    val detectionMode by autoSwitchViewModel.detectionMode.collectAsState()
    val pollIntervalSec by autoSwitchViewModel.pollIntervalSec.collectAsState()
    val probeEnabled by autoSwitchViewModel.probeEnabled.collectAsState()
    val lastCheck by autoSwitchViewModel.lastCheck.collectAsState()
    val restoreMode by autoSwitchViewModel.restoreMode.collectAsState()
    val actionModeA by autoSwitchViewModel.actionModeA.collectAsState()
    val actionModeB by autoSwitchViewModel.actionModeB.collectAsState()
    val probeIntervalMin by autoSwitchViewModel.probeIntervalMin.collectAsState()
    val quietEnabled by autoSwitchViewModel.quietEnabled.collectAsState()
    val quietStartMin by autoSwitchViewModel.quietStartMin.collectAsState()
    val quietEndMin by autoSwitchViewModel.quietEndMin.collectAsState()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The service runs either way; the permission only makes its notification visible. */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
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
                lastCheck = lastCheck,
                restoreMode = restoreMode,
                actionModeA = actionModeA,
                actionModeB = actionModeB,
                onRestoreModeChange = { autoSwitchViewModel.setRestoreMode(it) },
                onActionModeAChange = { autoSwitchViewModel.setActionModeA(it) },
                onActionModeBChange = { autoSwitchViewModel.setActionModeB(it) },
                detectionMode = detectionMode,
                pollIntervalSec = pollIntervalSec,
                onDetectionModeChange = { autoSwitchViewModel.setDetectionMode(it) },
                onPollIntervalChange = { autoSwitchViewModel.setPollIntervalSec(it) },
                probeEnabled = probeEnabled,
                probeIntervalMin = probeIntervalMin,
                onProbeEnabledChange = { autoSwitchViewModel.setProbeEnabled(it) },
                onProbeIntervalChange = { autoSwitchViewModel.setProbeIntervalMin(it) },
                quietEnabled = quietEnabled,
                quietStartMin = quietStartMin,
                quietEndMin = quietEndMin,
                onQuietEnabledChange = { autoSwitchViewModel.setQuietEnabled(it) },
                onQuietStartChange = { autoSwitchViewModel.setQuietStartMin(it) },
                onQuietEndChange = { autoSwitchViewModel.setQuietEndMin(it) },
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

            LogCard()

            // About Section
            AboutCard()
        }
    }
}

@Composable
private fun AutoSwitchCard(
    enabled: Boolean,
    status: String,
    lastCheck: Long,
    restoreMode: Int,
    actionModeA: Int,
    actionModeB: Int,
    onRestoreModeChange: (Int) -> Unit,
    onActionModeAChange: (Int) -> Unit,
    onActionModeBChange: (Int) -> Unit,
    detectionMode: DetectionMode,
    pollIntervalSec: Int,
    onDetectionModeChange: (DetectionMode) -> Unit,
    onPollIntervalChange: (Int) -> Unit,
    probeEnabled: Boolean,
    probeIntervalMin: Int,
    onProbeEnabledChange: (Boolean) -> Unit,
    onProbeIntervalChange: (Int) -> Unit,
    quietEnabled: Boolean,
    quietStartMin: Int,
    quietEndMin: Int,
    onQuietEnabledChange: (Boolean) -> Unit,
    onQuietStartChange: (Int) -> Unit,
    onQuietEndChange: (Int) -> Unit,
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
                    text = stringResource(R.string.auto_switch_card_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.auto_switch_card_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            ModeDropdown(
                label = stringResource(R.string.restore_target_title),
                selectedValue = restoreMode,
                onSelected = onRestoreModeChange,
                noneLabel = stringResource(R.string.restore_target_previous)
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.notification_buttons_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.notification_buttons_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            ModeDropdown(
                label = stringResource(R.string.notification_button_1),
                selectedValue = actionModeA,
                onSelected = onActionModeAChange
            )
            Spacer(modifier = Modifier.height(8.dp))
            ModeDropdown(
                label = stringResource(R.string.notification_button_2),
                selectedValue = actionModeB,
                onSelected = onActionModeBChange
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.detect_how),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            DetectionModeOption(
                selected = detectionMode == DetectionMode.EVENTS,
                title = stringResource(R.string.detect_events_title),
                description = stringResource(R.string.detect_events_desc),
                onClick = { onDetectionModeChange(DetectionMode.EVENTS) }
            )
            DetectionModeOption(
                selected = detectionMode == DetectionMode.POLLING,
                title = stringResource(R.string.detect_polling_title),
                description = stringResource(R.string.detect_polling_desc),
                onClick = { onDetectionModeChange(DetectionMode.POLLING) }
            )
            if (detectionMode == DetectionMode.POLLING) {
                var sliderValue by remember(pollIntervalSec) { mutableFloatStateOf(pollIntervalSec.toFloat()) }
                Text(
                    text = stringResource(R.string.poll_every, sliderValue.toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onPollIntervalChange(sliderValue.toInt()) },
                    valueRange = AutoSwitchPreferences.MIN_POLL_SEC.toFloat()..AutoSwitchPreferences.MAX_POLL_SEC.toFloat()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.probe_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.probe_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(checked = probeEnabled, onCheckedChange = onProbeEnabledChange)
            }
            if (probeEnabled) {
                var probeValue by remember(probeIntervalMin) { mutableFloatStateOf(probeIntervalMin.toFloat()) }
                Text(
                    text = stringResource(R.string.probe_every, probeValue.toInt()),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = probeValue,
                    onValueChange = { probeValue = it },
                    onValueChangeFinished = { onProbeIntervalChange(probeValue.toInt()) },
                    valueRange = AutoSwitchPreferences.MIN_PROBE_MIN.toFloat()..AutoSwitchPreferences.MAX_PROBE_MIN.toFloat()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.quiet_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.quiet_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(checked = quietEnabled, onCheckedChange = onQuietEnabledChange)
            }
            if (quietEnabled) {
                val quietContext = LocalContext.current
                fun pickTime(current: Int, onPicked: (Int) -> Unit) {
                    android.app.TimePickerDialog(
                        quietContext,
                        { _, hour, minute -> onPicked(hour * 60 + minute) },
                        current / 60,
                        current % 60,
                        true
                    ).show()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime(quietStartMin, onQuietStartChange) }) {
                        Text(stringResource(R.string.quiet_from, formatMinutes(quietStartMin)))
                    }
                    OutlinedButton(onClick = { pickTime(quietEndMin, onQuietEndChange) }) {
                        Text(stringResource(R.string.quiet_to, formatMinutes(quietEndMin)))
                    }
                }
            }

            val context = LocalContext.current
            val powerManager = context.getSystemService(PowerManager::class.java)
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                ) {
                    Text(stringResource(R.string.battery_exclude))
                }
            }

            if (enabled && status.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (lastCheck > 0L) {
                    Text(
                        text = stringResource(
                            R.string.last_check,
                            java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM)
                                .format(java.util.Date(lastCheck))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onDiagnosticsClick,
                enabled = !diagnosticsRunning
            ) {
                Text(stringResource(if (diagnosticsRunning) R.string.diag_checking else R.string.diag_check))
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
private fun LogCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizeKb by remember { mutableStateOf(AppLog.sizeBytes() / 1024) }

    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
    val savedText = stringResource(R.string.log_saved)
    val saveFailedText = stringResource(R.string.log_save_failed)
    val clearedText = stringResource(R.string.log_cleared)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.log_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.log_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.log_size, sizeKb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { LogExporter.share(context) }
                }) { Text(stringResource(R.string.log_share)) }
                OutlinedButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val name = LogExporter.saveToDownloads(context)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            toast(if (name != null) savedText.format(name) else saveFailedText)
                        }
                    }
                }) { Text(stringResource(R.string.log_save)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = {
                AppLog.clear()
                sizeKb = 0
                toast(clearedText)
            }) { Text(stringResource(R.string.log_clear)) }
        }
    }
}

@Composable
private fun DetectionModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                    text = stringResource(R.string.control_method),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                IconButton(onClick = onRetryClick) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh_compat)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.control_method_desc),
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
                        text = stringResource(R.string.root_method),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.root_method_desc),
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
                            contentDescription = stringResource(R.string.cd_compatible),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    is CompatibilityState.PermissionDenied -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = stringResource(R.string.cd_permission_denied),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    is CompatibilityState.Incompatible -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = stringResource(R.string.cd_error),
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
                        text = stringResource(R.string.shizuku_method),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.shizuku_method_desc),
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
                            contentDescription = stringResource(R.string.cd_compatible),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    is CompatibilityState.PermissionDenied -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = stringResource(R.string.cd_permission_denied),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    is CompatibilityState.Incompatible -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = stringResource(R.string.cd_not_available),
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
                text = stringResource(R.string.about),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = stringResource(R.string.source_code),
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
                text = stringResource(R.string.open_source_licenses),
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


private fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

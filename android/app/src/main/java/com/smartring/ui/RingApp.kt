package com.smartring.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartring.core.RingEvent
import com.smartring.ui.components.ConnectionPill
import com.smartring.ui.components.ConsoleSheet
import com.smartring.ui.components.DevPanel
import com.smartring.ui.components.FirmwareTools
import com.smartring.ui.components.PowerOffButton
import com.smartring.ui.components.ResetRingButton
import com.smartring.ui.logic.DevCommands
import com.smartring.ui.logic.eventLabel
import com.smartring.ui.logic.eventRows
import com.smartring.ui.logic.formatClock
import com.smartring.ui.logic.formatTime
import com.smartring.ui.logic.statusDump
import com.smartring.ui.screens.DisplayScreen
import com.smartring.ui.screens.GesturesScreen
import com.smartring.ui.screens.HealthScreen
import com.smartring.ui.screens.MouseScreen
import com.smartring.ui.screens.RingScreen
import com.smartring.ui.vm.RingAppViewModel
import java.time.ZoneId
import java.util.Locale

private enum class AppTab(val label: String, val icon: ImageVector) {
    Ring("Ring", Icons.Filled.Watch),
    Display("Display", Icons.Filled.Tv),
    Gestures("Gestures", Icons.Filled.BackHand),
    Mouse("Mouse", Icons.Filled.Mouse),
    Health("Health", Icons.Filled.Favorite),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingApp(
    vm: RingAppViewModel,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.gestureMessage.collectAsStateWithLifecycle()
    val mouse by vm.mouseSettings.collectAsStateWithLifecycle()
    val devMode by vm.devMode.collectAsStateWithLifecycle()
    val saved by vm.savedMessages.collectAsStateWithLifecycle()
    val events by vm.recentEvents.collectAsStateWithLifecycle()
    val gestures by vm.gestures.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val training by vm.training.collectAsStateWithLifecycle()
    val feed by vm.activity.collectAsStateWithLifecycle()
    val apiSettings by vm.apiSettings.collectAsStateWithLifecycle()
    val apiStatus by vm.apiStatus.collectAsStateWithLifecycle()
    val bpm by vm.liveBpm.collectAsStateWithLifecycle()
    val pulse by vm.heartPulse.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val recordLabel by vm.recordLabel.collectAsStateWithLifecycle()
    val ota by vm.ota.collectAsStateWithLifecycle()
    val consoleLines by vm.consoleLines.collectAsStateWithLifecycle()
    val logFilter by vm.logFilter.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(AppTab.Ring) }
    var console by rememberSaveable { mutableStateOf(false) }
    val snackbar = androidx.compose.runtime.remember { SnackbarHostState() }
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()

    LaunchedEffect(vm) {
        vm.notices.collect { snackbar.showSnackbar(it) }
    }

    val rows = eventRows(events, zone, locale)
    val lastGesture = events.firstOrNull { it is RingEvent.GestureFired || it is RingEvent.GestureArmed }?.let { eventLabel(it) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(tab.label) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    actions = {
                        if (devMode) {
                            IconButton(onClick = { console = true }) {
                                Icon(Icons.Filled.Terminal, contentDescription = "Console")
                            }
                        }
                    },
                )
                ConnectionPill(
                    state = connection.state,
                    onConnect = onConnect,
                    onStop = { vm.disconnect() },
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val extras: @Composable () -> Unit = {
            if (devMode) {
                DevPanel(
                    dump = statusDump(connection, status),
                    recording = recording,
                    recordLabel = recordLabel,
                    events = rows,
                    onRecordLabel = vm::setRecordLabel,
                    onToggleRecording = vm::toggleRecording,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tab == AppTab.Ring) {
                    FirmwareTools(
                        images = vm.firmwareImages(),
                        ota = ota,
                        onUpdate = { image, confirm -> vm.startFirmwareUpdate(image, confirm) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PowerOffButton(onPowerOff = { vm.powerOff() }, modifier = Modifier.fillMaxWidth())
                    ResetRingButton(
                        caution = DevCommands.reset.caution.orEmpty(),
                        onReset = { vm.runPreset(DevCommands.reset) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        val screenModifier = Modifier.padding(padding)
        when (tab) {
            AppTab.Ring -> RingScreen(
                connection = connection,
                status = status,
                clockText = formatClock(status.clockSyncedAtMs, zone, locale),
                devMode = devMode,
                onConnectionClick = { vm.disconnect() },
                onMode = vm::setMode,
                onSyncClock = vm::syncClock,
                onBrightness = vm::setBrightness,
                onTimeout = vm::setScreenTimeout,
                onFind = vm::findRing,
                onDevMode = { on ->
                    vm.setDevMode(on)
                    if (!on) console = false
                },
                modifier = screenModifier,
                extras = extras,
            )
            AppTab.Display -> DisplayScreen(
                message = message,
                status = status,
                saved = saved,
                lastGesture = lastGesture,
                onMessage = vm::setGestureMessage,
                onPlay = vm::playGestureMessage,
                onDetector = vm::setGestureDetector,
                onShowNow = vm::showText,
                onSave = vm::saveQuickMessage,
                onDeleteSaved = vm::removeQuickMessage,
                modifier = screenModifier,
                extras = extras,
            )
            AppTab.Gestures -> GesturesScreen(
                gestures = gestures,
                listening = listening,
                training = training,
                feed = feed,
                feedTimes = feed.map { formatTime(it.atMs, zone, locale) },
                apiSettings = apiSettings,
                apiStatus = apiStatus,
                onListening = vm::setListening,
                onCreate = { vm.createGesture(it) },
                onRename = vm::renameGesture,
                onDelete = vm::deleteGesture,
                onEnabled = vm::setEnabled,
                onStrictness = vm::setStrictness,
                onAction = vm::setAction,
                onTest = vm::testAction,
                onTrain = vm::startTraining,
                onStopTraining = vm::stopTraining,
                onClearExamples = vm::clearExamples,
                onApi = vm::updateApi,
                onRegenerateToken = vm::regenerateToken,
                modifier = screenModifier,
                extras = extras,
            )
            AppTab.Mouse -> MouseScreen(
                settings = mouse,
                onChange = vm::setMouseSettings,
                onRecenter = vm::recenterMouse,
                modifier = screenModifier,
                extras = extras,
            )
            AppTab.Health -> HealthScreen(
                mode = status.mode,
                bpm = bpm ?: status.heartBpm,
                pulse = pulse,
                onHeart = vm::setHeart,
                modifier = screenModifier,
                extras = extras,
            )
        }
    }
    if (console && devMode) {
        ConsoleSheet(
            lines = consoleLines,
            filter = logFilter,
            onFilter = vm::setLogFilter,
            onClear = vm::clearConsole,
            onSend = vm::sendRaw,
            onPreset = vm::runPreset,
            onDismiss = { console = false },
        )
    }
}

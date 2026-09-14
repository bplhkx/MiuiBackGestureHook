// SPDX-License-Identifier: Apache-2.0
package dev.codex.miuibackgesturehook.activity

import android.annotation.SuppressLint
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.codex.miuibackgesturehook.BuildConfig
import dev.codex.miuibackgesturehook.ModuleApplication
import dev.codex.miuibackgesturehook.PredictiveBackPreferences
import dev.codex.miuibackgesturehook.R
import dev.codex.miuibackgesturehook.NativeHookStatusKind
import dev.codex.miuibackgesturehook.NativeHookStatusProtocol
import dev.codex.miuibackgesturehook.NativeHookStatusUiState
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import dev.codex.miuibackgesturehook.util.miuixBlurEffect
import dev.codex.miuibackgesturehook.util.rememberMiuixBlurBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class PredictiveBackSettingsActivity :
    ComponentActivity(),
    ModuleApplication.ServiceStateListener {
    private var xposedService: XposedService? by mutableStateOf(null)
    private var serviceStateObserved by mutableStateOf(false)
    private var nativeHookStatus by mutableStateOf(
        NativeHookStatusUiState.checking(Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL),
    )
    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusNonce = 0L
    private var systemUiResponseReceived = false
    private var systemUiReadyReported = false
    private var statusReceiverRegistered = false
    private val statusTimeout = Runnable {
        if (statusNonce != 0L) {
            statusNonce = 0L
            nativeHookStatus = if (!systemUiResponseReceived) {
                NativeHookStatusUiState.noResponse()
            } else if (!systemUiReadyReported) {
                NativeHookStatusUiState(NativeHookStatusKind.SystemUiNotReady)
            } else {
                NativeHookStatusUiState(
                    kind = NativeHookStatusKind.NativeNoResponse,
                    legacyMode = Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL,
                )
            }
        }
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != NativeHookStatusProtocol.ACTION_REPLY) {
                return
            }
            val senderUid = sentFromUid
            val senderPackage = sentFromPackage
            if (senderUid == Process.INVALID_UID
                || senderPackage != NativeHookStatusProtocol.SYSTEM_UI_PACKAGE
                || !isUidOwner(senderUid, NativeHookStatusProtocol.SYSTEM_UI_PACKAGE)
            ) {
                return
            }
            val nonce = intent.getLongExtra(NativeHookStatusProtocol.EXTRA_NONCE, 0L)
            if (nonce <= 0L || nonce != statusNonce) {
                return
            }
            nativeHookStatus = NativeHookStatusUiState.fromReply(intent)
            val nativeResponse = intent.getBooleanExtra(
                NativeHookStatusProtocol.EXTRA_NATIVE_RESPONSE,
                false,
            )
            if (!nativeResponse) {
                systemUiResponseReceived = true
                systemUiReadyReported = intent.getBooleanExtra(
                    NativeHookStatusProtocol.EXTRA_SYSTEMUI_READY,
                    false,
                )
            } else {
                statusHandler.removeCallbacks(statusTimeout)
                statusNonce = 0L
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MiuixTheme(colors = colors) {
                PredictiveBackSettingsScreen(
                    service = xposedService,
                    serviceStateObserved = serviceStateObserved,
                    nativeHookStatus = nativeHookStatus,
                    onRefreshNativeHookStatus = ::requestNativeHookStatus,
                    onClose = { finish() },
                    onOpenGestureTriggerSettings = {
                        startActivity(
                            Intent(
                                this,
                                GestureTriggerSettingsActivity::class.java,
                            ),
                        )
                    },
                    onOpenAppList = {
                        startActivity(
                            Intent(
                                this,
                                PredictiveBackAppListActivity::class.java,
                            ),
                        )
                    },
                    onOpenContextualSearchSettings = {
                        startActivity(
                            Intent(
                                this,
                                ContextualSearchSettingsActivity::class.java,
                            ),
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ModuleApplication.addServiceStateListener(this, notifyImmediately = true)
        registerStatusReceiver()
        requestNativeHookStatus()
    }

    override fun onStop() {
        unregisterStatusReceiver()
        ModuleApplication.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        xposedService = service
        serviceStateObserved = true
    }

    private fun registerStatusReceiver() {
        if (statusReceiverRegistered) {
            return
        }
        val filter = IntentFilter(NativeHookStatusProtocol.ACTION_REPLY)
        registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        statusReceiverRegistered = true
    }

    private fun unregisterStatusReceiver() {
        if (!statusReceiverRegistered) {
            return
        }
        statusHandler.removeCallbacks(statusTimeout)
        statusNonce = 0L
        systemUiResponseReceived = false
        systemUiReadyReported = false
        unregisterReceiver(statusReceiver)
        statusReceiverRegistered = false
    }

    private fun requestNativeHookStatus() {
        if (!statusReceiverRegistered) {
            return
        }
        val nonce = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        statusNonce = nonce
        systemUiResponseReceived = false
        systemUiReadyReported = false
        nativeHookStatus = NativeHookStatusUiState.checking(
            Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL,
        )
        statusHandler.removeCallbacks(statusTimeout)
        statusHandler.postDelayed(statusTimeout, STATUS_TIMEOUT_MS)
        try {
            val query = Intent(NativeHookStatusProtocol.ACTION_QUERY)
                .setPackage(NativeHookStatusProtocol.SYSTEM_UI_PACKAGE)
                .putExtra(NativeHookStatusProtocol.EXTRA_NONCE, nonce)
                .putExtra("sender_uid", Process.myUid())
            val options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle()
            sendBroadcast(query, null, options)
        } catch (_: Throwable) {
            statusHandler.removeCallbacks(statusTimeout)
            statusNonce = 0L
            nativeHookStatus = NativeHookStatusUiState.noResponse()
        }
    }

    private fun isUidOwner(uid: Int, packageName: String): Boolean {
        return try {
            packageManager.getPackagesForUid(uid)?.contains(packageName) == true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val STATUS_TIMEOUT_MS = 2500L
        private const val ANDROID_17_API_LEVEL = 37
    }
}

@Composable
private fun NativeHookRuntimeStatusCard(
    state: NativeHookStatusUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state.kind == NativeHookStatusKind.Ready
    val warning = state.kind == NativeHookStatusKind.Checking
        || state.kind == NativeHookStatusKind.WaitingForNative
        || state.kind == NativeHookStatusKind.SystemUiNotReady
        || state.kind == NativeHookStatusKind.NativeNotReady
        || state.kind == NativeHookStatusKind.LegacyNotReady
    val title = when (state.kind) {
        NativeHookStatusKind.Checking -> stringResource(
            if (state.legacyMode) {
                R.string.native_hook_status_legacy_checking_title
            } else {
                R.string.native_hook_status_checking_title
            },
        )
        NativeHookStatusKind.WaitingForNative -> stringResource(
            if (state.legacyMode) {
                R.string.native_hook_status_legacy_waiting_title
            } else {
                R.string.native_hook_status_waiting_title
            },
        )
        NativeHookStatusKind.Ready -> stringResource(
            if (state.legacyMode) {
                R.string.native_hook_status_ready_legacy_title
            } else {
                R.string.native_hook_status_ready_title
            },
        )
        NativeHookStatusKind.SystemUiNotReady ->
            stringResource(R.string.native_hook_status_systemui_not_ready_title)
        NativeHookStatusKind.NativeNotReady ->
            stringResource(R.string.native_hook_status_native_not_ready_title)
        NativeHookStatusKind.LegacyNotReady ->
            stringResource(R.string.native_hook_status_legacy_not_ready_title)
        NativeHookStatusKind.NativeNoResponse ->
            stringResource(
                if (state.legacyMode) {
                    R.string.native_hook_status_legacy_no_response_title
                } else {
                    R.string.native_hook_status_native_no_response_title
                },
            )
        NativeHookStatusKind.ProfileRejected ->
            stringResource(R.string.native_hook_status_profile_rejected_title)
        NativeHookStatusKind.NoResponse -> stringResource(R.string.native_hook_status_no_response_title)
        NativeHookStatusKind.LsPosedUnavailable ->
            stringResource(R.string.native_hook_status_lsposed_unavailable_title)
    }
    val summary = when (state.kind) {
        NativeHookStatusKind.Checking -> stringResource(
            if (state.legacyMode) {
                R.string.native_hook_status_legacy_checking_summary
            } else {
                R.string.native_hook_status_checking_summary
            },
        )
        NativeHookStatusKind.WaitingForNative ->
            stringResource(
                if (state.legacyMode) {
                    R.string.native_hook_status_legacy_waiting_summary
                } else {
                    R.string.native_hook_status_waiting_summary
                },
            )
        NativeHookStatusKind.Ready -> stringResource(
            if (state.legacyMode) {
                R.string.native_hook_status_ready_legacy_summary
            } else if (state.profileDynamic) {
                R.string.native_hook_status_ready_runtime_summary
            } else {
                R.string.native_hook_status_ready_static_summary
            },
        )
        NativeHookStatusKind.SystemUiNotReady ->
            stringResource(R.string.native_hook_status_systemui_not_ready_summary)
        NativeHookStatusKind.NativeNotReady ->
            stringResource(R.string.native_hook_status_native_not_ready_summary)
        NativeHookStatusKind.LegacyNotReady ->
            stringResource(R.string.native_hook_status_legacy_not_ready_summary)
        NativeHookStatusKind.NativeNoResponse ->
            stringResource(
                if (state.legacyMode) {
                    R.string.native_hook_status_legacy_no_response_summary
                } else {
                    R.string.native_hook_status_native_no_response_summary
                },
            )
        NativeHookStatusKind.ProfileRejected ->
            stringResource(R.string.native_hook_status_profile_rejected_summary)
        NativeHookStatusKind.NoResponse -> stringResource(R.string.native_hook_status_no_response_summary)
        NativeHookStatusKind.LsPosedUnavailable ->
            stringResource(R.string.native_hook_status_lsposed_unavailable_summary)
    }
    val mode: String? = if (state.kind == NativeHookStatusKind.Ready) {
        when {
            state.legacyMode -> "LSPOSED"
            state.profileDynamic -> stringResource(R.string.native_hook_status_mode_runtime_profile)
            else -> stringResource(R.string.native_hook_status_mode_builtin_profile)
        }
    } else {
        null
    }
    val cardColor = when {
        ready && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        ready && isSystemInDarkTheme() -> Color(0xFF1A3825)
        ready -> Color(0xFFDFFAE4)
        warning && isSystemInDarkTheme() -> Color(0xFF3D3215)
        warning -> Color(0xFFFFF3CD)
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        isSystemInDarkTheme() -> Color(0xFF3A1E22)
        else -> Color(0xFFFFE4E1)
    }
    val iconTint = when {
        ready && MiuixTheme.isDynamicColor ->
            MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
        ready -> Color(0xFF36D167)
        warning && isSystemInDarkTheme() -> Color(0xFFFFC107)
        warning -> Color(0xFFFFB300)
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
        isSystemInDarkTheme() -> Color(0xFFFF8A80)
        else -> Color(0xFFD32F2F)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = cardColor),
            onClick = onRefresh,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        imageVector = when {
                            ready -> Icons.Rounded.CheckCircleOutline
                            warning -> Icons.Rounded.WarningAmber
                            else -> Icons.Rounded.ErrorOutline
                        },
                        tint = iconTint,
                        contentDescription = null,
                    )
                }
                if (mode != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp, 10.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(
                            text = mode,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text = title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = summary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private data class SettingsStatusCardMessage(
    val text: String,
    val severity: SettingsCardSeverity,
)

private enum class SettingsCardSeverity {
    Info,
    Error,
}

@Composable
@SuppressLint("ApplySharedPref")
private fun PredictiveBackSettingsScreen(
    service: XposedService?,
    serviceStateObserved: Boolean,
    nativeHookStatus: NativeHookStatusUiState,
    onRefreshNativeHookStatus: () -> Unit,
    onClose: () -> Unit,
    onOpenGestureTriggerSettings: () -> Unit,
    onOpenAppList: () -> Unit,
    onOpenContextualSearchSettings: () -> Unit,
) {
    val configurationErrorMessage = stringResource(R.string.predictive_back_config_error)
    val saveErrorMessage = stringResource(R.string.predictive_back_save_error)
    val serviceLoadingMessage = stringResource(R.string.predictive_back_service_loading)
    val serviceUnavailableMessage =
        stringResource(R.string.predictive_back_service_unavailable)
    val scope = rememberCoroutineScope()
    var preferences by remember { mutableStateOf<SharedPreferences?>(null) }
    var configurationLoading by remember { mutableStateOf(true) }
    var configurationError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var aospBackGestureRestoration by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_AOSP_BACK_GESTURE_RESTORATION)
    }
    var confirmedAospBackGestureRestoration by remember {
        mutableStateOf(PredictiveBackPreferences.DEFAULT_AOSP_BACK_GESTURE_RESTORATION)
    }
    var hyperOsIndicator by remember { mutableStateOf(false) }
    var confirmedHyperOsIndicator by remember { mutableStateOf(false) }
    var hyperOsHaptics by remember { mutableStateOf(false) }
    var confirmedHyperOsHaptics by remember { mutableStateOf(false) }
    var hyperOsHapticsEnhanced by remember { mutableStateOf(false) }
    var confirmedHyperOsHapticsEnhanced by remember { mutableStateOf(false) }
    var hyperOsSlideAnimation by remember { mutableStateOf(false) }
    var confirmedHyperOsSlideAnimation by remember { mutableStateOf(false) }
    var oneUiCrossTaskAnimation by remember { mutableStateOf(false) }
    var confirmedOneUiCrossTaskAnimation by remember { mutableStateOf(false) }
    var moduleLogging by remember { mutableStateOf(true) }
    var confirmedModuleLogging by remember { mutableStateOf(true) }
    val writeMutex = remember(preferences) { Mutex() }
    val lazyListState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val horizontalSafeInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    val topBarBackdrop = rememberMiuixBlurBackdrop()

    LaunchedEffect(service, serviceStateObserved, configurationErrorMessage) {
        preferences = null
        configurationError = null
        saveError = null
        aospBackGestureRestoration =
            PredictiveBackPreferences.DEFAULT_AOSP_BACK_GESTURE_RESTORATION
        confirmedAospBackGestureRestoration =
            PredictiveBackPreferences.DEFAULT_AOSP_BACK_GESTURE_RESTORATION
        hyperOsIndicator = false
        confirmedHyperOsIndicator = false
        hyperOsHaptics = false
        confirmedHyperOsHaptics = false
        hyperOsHapticsEnhanced = false
        confirmedHyperOsHapticsEnhanced = false
        hyperOsSlideAnimation = false
        confirmedHyperOsSlideAnimation = false
        oneUiCrossTaskAnimation = false
        confirmedOneUiCrossTaskAnimation = false
        moduleLogging = PredictiveBackPreferences.DEFAULT_MODULE_LOGGING
        confirmedModuleLogging = PredictiveBackPreferences.DEFAULT_MODULE_LOGGING
        if (!serviceStateObserved) {
            configurationLoading = true
            return@LaunchedEffect
        }
        if (service == null) {
            configurationLoading = false
            return@LaunchedEffect
        }
        configurationLoading = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                val remotePreferences =
                    service.getRemotePreferences(PredictiveBackPreferences.GROUP)
                val storedHyperOsHaptics = remotePreferences.getBoolean(
                    PredictiveBackPreferences.KEY_HYPEROS_HAPTICS,
                    PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS,
                )
                val legacyAospHaptics = remotePreferences.getBoolean(
                    PredictiveBackPreferences.LEGACY_KEY_AOSP_HYPEROS_HAPTICS,
                    false,
                )
                val unifiedHyperOsHaptics = if (!legacyAospHaptics) {
                    storedHyperOsHaptics
                } else {
                    try {
                        var migrated = false
                        remotePreferences.edit(commit = true) {
                            putBoolean(PredictiveBackPreferences.KEY_HYPEROS_HAPTICS, true)
                            remove(PredictiveBackPreferences.LEGACY_KEY_AOSP_HYPEROS_HAPTICS)
                            migrated = commit()
                        }
                        if (migrated) true else storedHyperOsHaptics
                    } catch (_: Throwable) {
                        storedHyperOsHaptics
                    }
                }
                val flags = booleanArrayOf(
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_AOSP_BACK_GESTURE_RESTORATION,
                        PredictiveBackPreferences.DEFAULT_AOSP_BACK_GESTURE_RESTORATION,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_INDICATOR,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR,
                    ),
                    unifiedHyperOsHaptics,
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_HAPTICS_ENHANCED,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS_ENHANCED,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_SLIDE_ANIMATION,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_SLIDE_ANIMATION,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_ONEUI_CROSS_TASK_ANIMATION,
                        PredictiveBackPreferences.DEFAULT_ONEUI_CROSS_TASK_ANIMATION,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_MODULE_LOGGING,
                        PredictiveBackPreferences.DEFAULT_MODULE_LOGGING,
                    ),
                )
                remotePreferences to flags
            }
            preferences = loaded.first
            aospBackGestureRestoration = loaded.second[0]
            confirmedAospBackGestureRestoration = loaded.second[0]
            hyperOsIndicator = loaded.second[1]
            confirmedHyperOsIndicator = loaded.second[1]
            hyperOsHaptics = loaded.second[2]
            confirmedHyperOsHaptics = loaded.second[2]
            hyperOsHapticsEnhanced = loaded.second[3]
            confirmedHyperOsHapticsEnhanced = loaded.second[3]
            hyperOsSlideAnimation = loaded.second[4]
            confirmedHyperOsSlideAnimation = loaded.second[4]
            oneUiCrossTaskAnimation = loaded.second[5]
            confirmedOneUiCrossTaskAnimation = loaded.second[5]
            moduleLogging = loaded.second[6]
            confirmedModuleLogging = loaded.second[6]
        } catch (_: Throwable) {
            configurationError = configurationErrorMessage
        } finally {
            configurationLoading = false
        }
    }

    val persistBooleanPreference: (
        String,
        Boolean,
        (Boolean) -> Unit,
        () -> Boolean,
        (Boolean) -> Unit,
    ) -> Unit = { key, requestedEnabled, setLocal, getConfirmed, setConfirmed ->
        val activePreferences = preferences
        if (activePreferences != null) {
            setLocal(requestedEnabled)
            saveError = null
            scope.launch {
                val saved = writeMutex.withLock {
                    val fallbackEnabled = getConfirmed()
                    val commitSucceeded = withContext(Dispatchers.IO) {
                        var succeeded = false
                        try {
                            activePreferences.edit(commit = true) {
                                putBoolean(key, requestedEnabled)
                                succeeded = commit()
                            }
                        } catch (_: Throwable) {
                            succeeded = false
                        }
                        if (!succeeded) {
                            try {
                                activePreferences.edit(commit = true) {
                                    putBoolean(key, fallbackEnabled)
                                    commit()
                                }
                            } catch (_: Throwable) {
                                // Restore the RemotePreferences cache where possible.
                            }
                        }
                        succeeded
                    }
                    if (preferences === activePreferences && commitSucceeded) {
                        setConfirmed(requestedEnabled)
                    }
                    commitSucceeded
                }
                if (preferences === activePreferences && !saved) {
                    setLocal(getConfirmed())
                    saveError = saveErrorMessage
                }
            }
        }
    }
    val persistAospBackGestureRestoration: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_AOSP_BACK_GESTURE_RESTORATION,
            requestedEnabled,
            { aospBackGestureRestoration = it },
            { confirmedAospBackGestureRestoration },
            { confirmedAospBackGestureRestoration = it },
        )
    }
    val persistHyperOsIndicator: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_INDICATOR,
            requestedEnabled,
            { hyperOsIndicator = it },
            { confirmedHyperOsIndicator },
            { confirmedHyperOsIndicator = it },
        )
    }
    val persistHyperOsHaptics: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_HAPTICS,
            requestedEnabled,
            { hyperOsHaptics = it },
            { confirmedHyperOsHaptics },
            { confirmedHyperOsHaptics = it },
        )
    }
    val persistHyperOsHapticsEnhanced: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_HAPTICS_ENHANCED,
            requestedEnabled,
            { hyperOsHapticsEnhanced = it },
            { confirmedHyperOsHapticsEnhanced },
            { confirmedHyperOsHapticsEnhanced = it },
        )
    }
    val persistHyperOsSlideAnimation: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_SLIDE_ANIMATION,
            requestedEnabled,
            { hyperOsSlideAnimation = it },
            { confirmedHyperOsSlideAnimation },
            { confirmedHyperOsSlideAnimation = it },
        )
    }
    val persistOneUiCrossTaskAnimation: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_ONEUI_CROSS_TASK_ANIMATION,
            requestedEnabled,
            { oneUiCrossTaskAnimation = it },
            { confirmedOneUiCrossTaskAnimation },
            { confirmedOneUiCrossTaskAnimation = it },
        )
    }
    val persistModuleLogging: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_MODULE_LOGGING,
            requestedEnabled,
            { moduleLogging = it },
            { confirmedModuleLogging },
            { confirmedModuleLogging = it },
        )
    }
    val statusMessage = when {
        configurationLoading -> SettingsStatusCardMessage(
            text = serviceLoadingMessage,
            severity = SettingsCardSeverity.Info,
        )

        configurationError != null -> SettingsStatusCardMessage(
            text = configurationError.orEmpty(),
            severity = SettingsCardSeverity.Error,
        )

        saveError != null -> SettingsStatusCardMessage(
            text = saveError.orEmpty(),
            severity = SettingsCardSeverity.Error,
        )

        serviceStateObserved && service == null -> SettingsStatusCardMessage(
            text = serviceUnavailableMessage,
            severity = SettingsCardSeverity.Error,
        )

        else -> null
    }
    val configurationEnabled = preferences != null

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier
                    .miuixBlurEffect(topBarBackdrop)
                    .background(Color.Transparent),
                color = Color.Transparent,
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.predictive_back_title),
                subtitle = "v${BuildConfig.VERSION_NAME}",
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Close,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(topBarBackdrop)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            state = lazyListState,
            contentPadding = PaddingValues(
                start = horizontalSafeInsets.calculateLeftPadding(layoutDirection),
                top = paddingValues.calculateTopPadding() + 8.dp,
                end = horizontalSafeInsets.calculateRightPadding(layoutDirection),
                bottom = paddingValues.calculateBottomPadding(),
            ),
            overscrollEffect = null,
        ) {
            item(key = "native_hook_runtime_status") {
                NativeHookRuntimeStatusCard(
                    state = if (serviceStateObserved && service == null) {
                        NativeHookStatusUiState(NativeHookStatusKind.LsPosedUnavailable)
                    } else {
                        nativeHookStatus
                    },
                    onRefresh = onRefreshNativeHookStatus,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "aosp_back_gesture_restoration") {
                AospBackGestureRestorationCard(
                    aospBackGestureRestoration = aospBackGestureRestoration,
                    configurationEnabled = configurationEnabled,
                    onToggle = persistAospBackGestureRestoration,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "hyperos_switches") {
                HyperOsSwitchGroupCard(
                    aospBackGestureRestoration = aospBackGestureRestoration,
                    hyperOsIndicator = hyperOsIndicator,
                    hyperOsHaptics = hyperOsHaptics,
                    hyperOsHapticsEnhanced = hyperOsHapticsEnhanced,
                    hyperOsSlideAnimation = hyperOsSlideAnimation,
                    oneUiCrossTaskAnimation = oneUiCrossTaskAnimation,
                    configurationEnabled = configurationEnabled,
                    onHyperOsIndicatorToggle = persistHyperOsIndicator,
                    onHyperOsHapticsToggle = persistHyperOsHaptics,
                    onHyperOsHapticsEnhancedToggle = persistHyperOsHapticsEnhanced,
                    onHyperOsSlideAnimationToggle = persistHyperOsSlideAnimation,
                    onOneUiCrossTaskAnimationToggle = persistOneUiCrossTaskAnimation,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "module_logging") {
                ModuleLoggingCard(
                    moduleLogging = moduleLogging,
                    configurationEnabled = configurationEnabled,
                    onModuleLoggingToggle = persistModuleLogging,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "contextual_search") {
                ContextualSearchNavigationCard(
                    onClick = onOpenContextualSearchSettings,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "gesture_trigger_navigation") {
                GestureTriggerNavigationCard(
                    onClick = onOpenGestureTriggerSettings,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "app_list_navigation") {
                AppListNavigationCard(
                    onClick = onOpenAppList,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            if (statusMessage != null) {
                item(key = "configuration_status") {
                    StatusCard(
                        message = statusMessage.text,
                        severity = statusMessage.severity,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                    )
                }
            }
            item(key = "navigation_bar_spacer") {
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun StatusCard(
    message: String,
    severity: SettingsCardSeverity,
    modifier: Modifier = Modifier,
) {
    val accentColor = cardAccentColor(severity)
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(
            color = accentColor.copy(alpha = 0.2f),
            contentColor = accentColor,
        ),
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
        )
    }
}

@Composable
private fun cardAccentColor(severity: SettingsCardSeverity): Color = when (severity) {
    SettingsCardSeverity.Info -> MiuixTheme.colorScheme.primary
    SettingsCardSeverity.Error -> MiuixTheme.colorScheme.error
}

@Composable
private fun AospBackGestureRestorationCard(
    aospBackGestureRestoration: Boolean,
    configurationEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.aosp_gesture_restoration_title),
            summary = stringResource(R.string.aosp_gesture_restoration_summary),
            checked = aospBackGestureRestoration,
            enabled = configurationEnabled,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun HyperOsSwitchGroupCard(
    aospBackGestureRestoration: Boolean,
    hyperOsIndicator: Boolean,
    hyperOsHaptics: Boolean,
    hyperOsHapticsEnhanced: Boolean,
    hyperOsSlideAnimation: Boolean,
    oneUiCrossTaskAnimation: Boolean,
    configurationEnabled: Boolean,
    onHyperOsIndicatorToggle: (Boolean) -> Unit,
    onHyperOsHapticsToggle: (Boolean) -> Unit,
    onHyperOsHapticsEnhancedToggle: (Boolean) -> Unit,
    onHyperOsSlideAnimationToggle: (Boolean) -> Unit,
    onOneUiCrossTaskAnimationToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        WindowSpinnerPreference(
            items = listOf(
                DropdownItem(
                    text = stringResource(R.string.back_indicator_style_aosp),
                    summary = stringResource(R.string.back_indicator_style_aosp_summary),
                ),
                DropdownItem(
                    text = stringResource(R.string.back_indicator_style_hyperos),
                    summary = stringResource(R.string.back_indicator_style_hyperos_summary),
                ),
            ),
            selectedIndex = if (hyperOsIndicator) 1 else 0,
            title = stringResource(R.string.back_indicator_style_title),
            summary = stringResource(
                if (hyperOsIndicator) {
                    R.string.back_indicator_style_hyperos_summary
                } else {
                    R.string.back_indicator_style_aosp_summary
                },
            ),
            enabled = configurationEnabled && aospBackGestureRestoration,
            onSelectedIndexChange = { selectedIndex ->
                onHyperOsIndicatorToggle(selectedIndex == 1)
            },
        )
        WindowSpinnerPreference(
            items = listOf(
                DropdownItem(
                    text = stringResource(R.string.haptic_feedback_effect_aosp),
                    summary = stringResource(R.string.haptic_feedback_effect_aosp_summary),
                ),
                DropdownItem(
                    text = stringResource(R.string.haptic_feedback_effect_hyperos),
                    summary = stringResource(R.string.haptic_feedback_effect_hyperos_summary),
                ),
            ),
            selectedIndex = if (hyperOsHaptics) 1 else 0,
            title = stringResource(R.string.haptic_feedback_effect_title),
            summary = stringResource(
                if (hyperOsHaptics) {
                    R.string.haptic_feedback_effect_hyperos_summary
                } else {
                    R.string.haptic_feedback_effect_aosp_summary
                },
            ),
            enabled = configurationEnabled && aospBackGestureRestoration,
            onSelectedIndexChange = { selectedIndex ->
                onHyperOsHapticsToggle(selectedIndex == 1)
            },
        )
        SwitchPreference(
            title = stringResource(R.string.hyperos_haptics_enhanced_title),
            summary = stringResource(
                if (!aospBackGestureRestoration) {
                    R.string.aosp_gesture_restoration_disabled_hyperos_summary
                } else if (configurationEnabled && !hyperOsHaptics) {
                    R.string.hyperos_haptics_enhanced_disabled_summary
                } else {
                    R.string.hyperos_haptics_enhanced_summary
                },
            ),
            checked = hyperOsHapticsEnhanced,
            enabled = configurationEnabled && aospBackGestureRestoration && hyperOsHaptics,
            onCheckedChange = onHyperOsHapticsEnhancedToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.hyperos_slide_animation_title),
            summary = stringResource(
                if (!aospBackGestureRestoration) {
                    R.string.aosp_gesture_restoration_disabled_hyperos_summary
                } else {
                    R.string.hyperos_slide_animation_summary
                },
            ),
            checked = hyperOsSlideAnimation,
            enabled = configurationEnabled && aospBackGestureRestoration,
            onCheckedChange = onHyperOsSlideAnimationToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.oneui_cross_task_animation_title),
            summary = stringResource(
                if (!aospBackGestureRestoration) {
                    R.string.aosp_gesture_restoration_disabled_hyperos_summary
                } else {
                    R.string.oneui_cross_task_animation_summary
                },
            ),
            checked = oneUiCrossTaskAnimation,
            enabled = configurationEnabled && aospBackGestureRestoration,
            onCheckedChange = onOneUiCrossTaskAnimationToggle,
        )
    }
}

@Composable
private fun AppListNavigationCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.predictive_back_apps_entry_title),
            summary = stringResource(R.string.predictive_back_apps_entry_summary),
            onClick = onClick,
        )
    }
}

@Composable
private fun GestureTriggerNavigationCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.gesture_trigger_entry_title),
            summary = stringResource(R.string.gesture_trigger_entry_summary),
            onClick = onClick,
        )
    }
}

@Composable
private fun ModuleLoggingCard(
    moduleLogging: Boolean,
    configurationEnabled: Boolean,
    onModuleLoggingToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.module_logging_title),
            summary = stringResource(R.string.module_logging_summary),
            checked = moduleLogging,
            enabled = configurationEnabled,
            onCheckedChange = onModuleLoggingToggle,
        )
    }
}

@Composable
private fun ContextualSearchNavigationCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.contextual_search_entry_title),
            summary = stringResource(R.string.contextual_search_entry_summary),
            onClick = onClick,
        )
    }
}

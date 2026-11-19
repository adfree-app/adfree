package git.shin.adfree.ui.screens

import git.shin.adfree.l10n.Strings
import git.shin.adfree.l10n.L10n
import git.shin.adfree.platform.OnResumeTick
import git.shin.adfree.platform.currentPlatformHost
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import git.shin.adfree.model.CountFormat
import git.shin.adfree.model.ProtectionStats
import git.shin.adfree.ui.AdGuardIntent
import git.shin.adfree.ui.AdGuardTab
import git.shin.adfree.ui.AdGuardUiState
import git.shin.adfree.ui.AdGuardViewModel
import git.shin.adfree.ui.ProtectionSection
import git.shin.adfree.ui.theme.AdGuardBlueDns
import git.shin.adfree.ui.theme.AdGuardDarkBg
import git.shin.adfree.ui.theme.AdGuardDarkOutline
import git.shin.adfree.ui.theme.AdGuardDarkSurface
import git.shin.adfree.ui.theme.AdGuardGreenLight
import git.shin.adfree.ui.theme.AdGuardGreenPrimary
import git.shin.adfree.ui.theme.AdGuardOrangeWarning
import git.shin.adfree.ui.theme.AdGuardPurpleAI
import git.shin.adfree.ui.theme.AdGuardRedBlocked
import git.shin.adfree.ui.theme.AdGuardTextMuted
import git.shin.adfree.ui.theme.AdGuardTextPrimary
import git.shin.adfree.ui.theme.AdGuardTextSecondary
import git.shin.adfree.ui.theme.UiSize
import git.shin.adfree.ui.theme.isDesktopLayout

/**
 * The home tab: the power switch, a compact row of module switches, and the
 * numbers the tunnel produced.
 *
 * Deliberately short — the per-module options live behind the Protection tab,
 * the way the reference client lays it out, so this screen never turns into a
 * long settings list.
 */
@Composable
fun HomeScreen(
    viewModel: AdGuardViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val privateDnsOn by viewModel.privateDnsOn.collectAsState()
    val platform = currentPlatformHost()
    val scope = rememberCoroutineScope()

    // Both of these are windows on a desktop and neither is one on a phone, so
    // the host is asked first and the fallback is a surface over this screen:
    // the same screens, reached the only way the platform has.
    var quick by remember { mutableStateOf(QuickSurface.NONE) }

    // The switch lives in the system settings, so re-read it on every return;
    // the same call re-pushes the effective DoT policy to the core.
    OnResumeTick { viewModel.refreshPrivateDnsState() }

    val quickRow: @Composable () -> Unit = {
        QuickOpenRow(
            onOpenLog = { if (!platform.openFilteringLog()) quick = QuickSurface.LOG },
            onOpenInspector = {
                if (!platform.openRequestInspector()) quick = QuickSurface.INSPECTOR
            },
        )
    }

    if (isDesktopLayout) {
        DesktopHome(
            state = state,
            privateDnsOn = privateDnsOn,
            modifier = modifier,
            onToggle = { viewModel.onIntent(AdGuardIntent.ToggleProtection) },
            onOpenStatistics = {
                viewModel.onIntent(AdGuardIntent.SelectTab(AdGuardTab.STATISTICS))
            },
            onOpenPrivateDns = { platform.openPrivateDnsSettings() },
            onOpenScripts = {
                viewModel.onIntent(
                    AdGuardIntent.OpenProtectionSection(ProtectionSection.EXTENSIONS)
                )
            },
            quickOpenRow = quickRow,
            quickModuleRow = {
                QuickModuleRow(
                    adBlocking = state.settings.isAdBlockingEnabled,
                    tracking = state.settings.isTrackingProtectionEnabled,
                    dns = state.settings.isDnsProtectionEnabled,
                    https = state.settings.isHttpsFilteringEnabled,
                    firewall = state.settings.isFirewallEnabled,
                    eco = state.settings.isEcoBatteryModeEnabled,
                    onAdBlocking = { viewModel.onIntent(AdGuardIntent.ToggleAdBlocking) },
                    onTracking = { viewModel.onIntent(AdGuardIntent.ToggleTracking) },
                    onDns = { viewModel.onIntent(AdGuardIntent.ToggleDns) },
                    onHttps = { viewModel.onIntent(AdGuardIntent.ToggleHttps) },
                    onFirewall = { viewModel.onIntent(AdGuardIntent.ToggleFirewall) },
                    onEco = { viewModel.onIntent(AdGuardIntent.ToggleEcoMode) },
                )
            },
        )
    } else {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AdGuardDarkBg),
    ) {
        HomeHeader(
            isActive = state.isVpnActive,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            QuickModuleRow(
                adBlocking = state.settings.isAdBlockingEnabled,
                tracking = state.settings.isTrackingProtectionEnabled,
                dns = state.settings.isDnsProtectionEnabled,
                https = state.settings.isHttpsFilteringEnabled,
                firewall = state.settings.isFirewallEnabled,
                eco = state.settings.isEcoBatteryModeEnabled,
                onAdBlocking = { viewModel.onIntent(AdGuardIntent.ToggleAdBlocking) },
                onTracking = { viewModel.onIntent(AdGuardIntent.ToggleTracking) },
                onDns = { viewModel.onIntent(AdGuardIntent.ToggleDns) },
                onHttps = { viewModel.onIntent(AdGuardIntent.ToggleHttps) },
                onFirewall = { viewModel.onIntent(AdGuardIntent.ToggleFirewall) },
                onEco = { viewModel.onIntent(AdGuardIntent.ToggleEcoMode) },
            )

            Spacer(modifier = Modifier.height(10.dp))

            quickRow()

            Spacer(modifier = Modifier.height(6.dp))

            AdGuardShieldHero(
                isActive = state.isVpnActive,
                onToggle = { viewModel.onIntent(AdGuardIntent.ToggleProtection) }
            )

            Spacer(modifier = Modifier.height(14.dp))

            HomeSystemStatusRow()

            if (privateDnsOn) {
                Spacer(modifier = Modifier.height(16.dp))
                PrivateDnsWarningCard(
                    onOpenSettings = { platform.openPrivateDnsSettings() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StatsSection(
                stats = state.stats,
                scriptsInstalled = state.userscripts.size,
                scriptsEnabled = state.userscripts.count { it.isEnabled },
                scriptsMasterOn = state.scriptsMasterEnabled,
                onOpenStatistics = {
                    viewModel.onIntent(AdGuardIntent.SelectTab(AdGuardTab.STATISTICS))
                },
                onOpenScripts = {
                    viewModel.onIntent(
                        AdGuardIntent.OpenProtectionSection(ProtectionSection.EXTENSIONS)
                    )
                },
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    }

    if (quick != QuickSurface.NONE) {
        QuickSurfacePopup(
            surface = quick,
            viewModel = viewModel,
            onClose = { quick = QuickSurface.NONE },
        )
    }
}

/** Which of the two windows the home screen can also raise itself. */
private enum class QuickSurface { NONE, LOG, INSPECTOR }

/**
 * The log and the inspector, on a screen that has no second window.
 *
 * Both already exist as windows on a desktop and as tabs nowhere; this is the
 * surface that makes them reachable from the home screen on a phone, where the
 * app is one window and the only thing it can open is a dialog.
 */
@Composable
private fun QuickSurfacePopup(
    surface: QuickSurface,
    viewModel: AdGuardViewModel,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(AdGuardDarkBg),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = L10n.get(
                        if (surface == QuickSurface.LOG) Strings.home_quick_log
                        else Strings.inspector_title
                    ),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AdGuardTextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = AdGuardTextMuted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onClose)
                        .padding(8.dp)
                        .size(18.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (surface) {
                    QuickSurface.LOG -> FilteringLogScreen(viewModel = viewModel)
                    QuickSurface.INSPECTOR -> RequestInspectorScreen()
                    QuickSurface.NONE -> Unit
                }
            }
        }
    }
}

/**
 * The app's two visible surfaces, as two buttons on the home screen.
 *
 * AdGuard puts these in its own window chrome; here the app is a single window,
 * so they sit where the user already is.
 */
@Composable
private fun QuickOpenRow(onOpenLog: () -> Unit, onOpenInspector: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        QuickOpenButton(Icons.Filled.List, L10n.get(Strings.home_quick_log), onOpenLog)
        Spacer(modifier = Modifier.width(10.dp))
        QuickOpenButton(
            Icons.Filled.SwapVert,
            L10n.get(Strings.inspector_title),
            onOpenInspector,
        )
    }
}

@Composable
private fun QuickOpenButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1C2733))
            .border(1.dp, AdGuardDarkOutline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AdGuardGreenPrimary, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(7.dp))
        Text(label, fontSize = 12.sp, color = AdGuardTextPrimary, maxLines = 1)
    }
}

/** App identity plus the live on/off state. No build or rule counts. */
@Composable
private fun HomeHeader(isActive: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = AdGuardGreenPrimary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = L10n.get(Strings.app_name),
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                color = AdGuardTextPrimary
            )
        }

        StatusChip(isActive = isActive)
    }
}

@Composable
private fun StatusChip(isActive: Boolean) {
    val tint = if (isActive) AdGuardGreenPrimary else AdGuardRedBlocked
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = if (isActive) L10n.get(Strings.home_status_filtering)
            else L10n.get(Strings.home_status_paused),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = tint
        )
    }
}

/**
 * The two system-level checks AdGuard surfaces on its dashboard: whether the
 * app may post notifications (the ongoing protection status is invisible
 * without them) and whether the OS battery optimizer is exempting the tunnel.
 * Each chip opens the system screen that changes the answer.
 */
@Composable
private fun HomeSystemStatusRow() {
    val platform = currentPlatformHost()
    val scope = rememberCoroutineScope()
    val showNotifications = platform.supportsNotifications()
    val showBattery = platform.supportsBatteryOptimization()
    var notificationsOn by remember { mutableStateOf(platform.notificationsEnabled()) }
    var batteryOptimized by remember { mutableStateOf(!platform.ignoringBatteryOptimizations()) }

    fun refresh() {
        notificationsOn = platform.notificationsEnabled()
        batteryOptimized = !platform.ignoringBatteryOptimizations()
    }

    OnResumeTick {
        refresh()
        // MIUI and other OEMs persist their per-app power policy slightly
        // after the settings activity closes, so the read taken on resume can
        // still see the old value. Re-read while the chip is complaining.
        if (batteryOptimized) {
            scope.launch {
                repeat(3) {
                    delay(1_000)
                    batteryOptimized = !platform.ignoringBatteryOptimizations()
                    if (!batteryOptimized) return@launch
                }
            }
        }
    }

    if (!showNotifications && !showBattery) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showNotifications) {
            SystemStatusChip(
                icon = if (notificationsOn) Icons.Default.NotificationsActive
                else Icons.Default.NotificationsOff,
                title = L10n.get(Strings.home_health_notifications),
                value = L10n.get(if (notificationsOn) Strings.home_health_notifications_on
                    else Strings.home_health_notifications_off
                ),
                ok = notificationsOn,
                onClick = { platform.requestNotificationPermission() },
                modifier = Modifier
                    .weight(1f)
                    .testTag("home_status_notifications"),
            )
        }
        if (showBattery) {
            SystemStatusChip(
                icon = Icons.Default.BatteryChargingFull,
                title = L10n.get(Strings.home_health_battery),
                value = L10n.get(if (batteryOptimized) Strings.home_health_battery_on
                    else Strings.home_health_battery_ok
                ),
                ok = !batteryOptimized,
                onClick = {
                    if (batteryOptimized) platform.requestIgnoreBatteryOptimizations()
                    else platform.openBatterySettings()
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("home_status_battery"),
            )
        }
    }
}

@Composable
private fun SystemStatusChip(
    icon: ImageVector,
    title: String,
    value: String,
    ok: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (ok) AdGuardGreenPrimary else AdGuardOrangeWarning
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AdGuardDarkSurface)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.15f)),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                fontSize = 11.sp,
                color = AdGuardTextPrimary,
                maxLines = 1,
            )
            Text(
                text = value,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
            )
        }
    }
}


/**
 * Shown while Android's Private DNS is on. The core stops resetting port 853
 * in that mode (otherwise strict mode loses all resolution), which means DNS
 * filtering cannot see those lookups; the user gets the trade and a shortcut
 * to the system switch instead of a silently weaker app.
 */
@Composable
private fun PrivateDnsWarningCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = AdGuardOrangeWarning.copy(alpha = 0.12f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AdGuardOrangeWarning,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = L10n.get(Strings.home_private_dns_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AdGuardTextPrimary,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = L10n.get(Strings.home_private_dns_body),
                fontSize = 11.sp,
                color = AdGuardTextSecondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(L10n.get(Strings.home_private_dns_open), fontSize = 11.sp)
            }
        }
    }
}

/**
 * One row, one tap per module, the way the reference client shows them: the
 * icon carries the state, so six switches fit above the power button.
 */
@Composable
private fun QuickModuleRow(
    adBlocking: Boolean,
    tracking: Boolean,
    dns: Boolean,
    https: Boolean,
    firewall: Boolean,
    eco: Boolean,
    onAdBlocking: () -> Unit,
    onTracking: () -> Unit,
    onDns: () -> Unit,
    onHttps: () -> Unit,
    onFirewall: () -> Unit,
    onEco: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuickModuleToggle(
            icon = Icons.Default.Block,
            label = L10n.get(Strings.home_quick_ads),
            active = adBlocking,
            accent = AdGuardGreenPrimary,
            onClick = onAdBlocking,
            modifier = Modifier.weight(1f),
        )
        QuickModuleToggle(
            icon = Icons.Default.TrackChanges,
            label = L10n.get(Strings.home_quick_tracking),
            active = tracking,
            accent = AdGuardOrangeWarning,
            onClick = onTracking,
            modifier = Modifier.weight(1f),
        )
        QuickModuleToggle(
            icon = Icons.Default.Dns,
            label = L10n.get(Strings.home_quick_dns),
            active = dns,
            accent = AdGuardBlueDns,
            onClick = onDns,
            modifier = Modifier.weight(1f),
        )
        QuickModuleToggle(
            icon = Icons.Default.Lock,
            label = L10n.get(Strings.home_quick_https),
            active = https,
            accent = Color(0xFFB48CFF),
            onClick = onHttps,
            modifier = Modifier.weight(1f),
        )
        QuickModuleToggle(
            icon = Icons.Default.Shield,
            label = L10n.get(Strings.home_quick_firewall),
            active = firewall,
            accent = AdGuardGreenPrimary,
            onClick = onFirewall,
            modifier = Modifier.weight(1f),
        )
        QuickModuleToggle(
            icon = Icons.Default.BatteryChargingFull,
            label = L10n.get(Strings.home_quick_eco),
            active = eco,
            accent = AdGuardGreenLight,
            onClick = onEco,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickModuleToggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag("quick_$label"),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    if (active) accent.copy(alpha = 0.16f) else AdGuardDarkSurface
                ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) accent else AdGuardTextMuted,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) AdGuardTextPrimary else AdGuardTextMuted,
            maxLines = 1,
        )
    }
}

/** The power button. Doubles as the protection switch. */
@Composable
fun AdGuardShieldHero(
    isActive: Boolean,
    onToggle: () -> Unit
) {
    val shieldBgColor by animateColorAsState(
        targetValue = if (isActive) AdGuardGreenPrimary else Color(0xFF232D37),
        label = "shieldColor"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(108.dp)
                .clickable { onToggle() }
                .testTag("shield_power_toggle")
        ) {
            // The rings are drawn well inside the box: the older version scaled
            // them past the box edge, and everything outside was simply clipped
            // away by the layout bounds.
            if (isActive) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val base = size.minDimension * 0.40f
                    val radius = base + size.minDimension * 0.055f
                    drawCircle(color = Color(0x1A00D06C), radius = radius)
                    drawCircle(color = Color(0x2800D06C), radius = radius * 0.88f)
                }
            }

            // The button itself, always drawn: it used to sit inside the
            // `isActive` block, so it vanished from the screen whenever the
            // protection was off and left only the caption behind.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = if (isActive) listOf(
                                Color(0xFF00FF85),
                                AdGuardGreenPrimary,
                                Color(0xFF008040)
                            ) else listOf(
                                Color(0xFF374653),
                                Color(0xFF232D37),
                                Color(0xFF141A20)
                            )
                        )
                    )
                    .border(
                        width = 2.dp,
                        color = if (isActive) Color(0x88FFFFFF) else AdGuardDarkOutline,
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = L10n.get(Strings.home_shield_toggle),
                    tint = if (isActive) Color.Black else Color(0xFF8B9DAF),
                    modifier = Modifier.size(44.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = if (isActive) L10n.get(Strings.home_shield_protected)
            else L10n.get(Strings.home_shield_off),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isActive) AdGuardGreenLight else AdGuardTextMuted
        )
    }
}

/** One number, its icon, and where tapping it goes. */
private data class StatTile(
    val value: String,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val onClick: () -> Unit,
)

/**
 * The counters, as their own cards in a row that scrolls sideways.
 *
 * They used to be a two-by-two grid on a phone and a bare strip of numbers on a
 * window — two layouts for one set of facts, neither with room for a fifth
 * number. A single scrolling row fixes that, and each number keeps a card of
 * its own so its colour and outline still say which counter it is; a shared
 * card would have made five equal cells of five different things.
 *
 * When the window is wide enough for every card, they share the width evenly
 * and nothing scrolls. Both branches are the same cards, so the count of
 * layouts stays one.
 */
@Composable
private fun StatsSection(
    stats: ProtectionStats,
    scriptsInstalled: Int,
    scriptsEnabled: Int,
    scriptsMasterOn: Boolean,
    onOpenStatistics: () -> Unit,
    onOpenScripts: () -> Unit,
) {
    // A master switch that is off means nothing runs, whatever the per-script
    // flags still say; showing "4/6" there would be the one line on this screen
    // that lies.
    val running = if (scriptsMasterOn) scriptsEnabled else 0
    val tiles = listOf(
        StatTile(
            value = CountFormat.compact(stats.adsBlocked),
            label = L10n.get(Strings.home_stat_ads),
            icon = Icons.Default.Block,
            accent = AdGuardGreenPrimary,
            onClick = onOpenStatistics,
        ),
        StatTile(
            value = CountFormat.compact(stats.trackersBlocked),
            label = L10n.get(Strings.home_stat_trackers),
            icon = Icons.Default.TrackChanges,
            accent = AdGuardOrangeWarning,
            onClick = onOpenStatistics,
        ),
        StatTile(
            value = L10n.format(Strings.home_stat_data_value, CountFormat.data(stats.dataSavedBytes)),
            label = L10n.get(Strings.home_stat_data),
            icon = Icons.Default.CloudDownload,
            accent = AdGuardBlueDns,
            onClick = onOpenStatistics,
        ),
        StatTile(
            value = L10n.format(
                Strings.home_stat_battery_value,
                stats.estimatedBatterySavedPercent,
            ),
            label = L10n.get(Strings.home_stat_battery),
            icon = Icons.Default.BatteryChargingFull,
            accent = AdGuardGreenLight,
            onClick = onOpenStatistics,
        ),
        StatTile(
            value = "$running/$scriptsInstalled",
            label = L10n.get(Strings.home_scripts_title),
            icon = Icons.Default.Extension,
            accent = if (running > 0) AdGuardPurpleAI else AdGuardTextMuted,
            onClick = onOpenScripts,
        ),
    )

    BoxWithConstraints(modifier = Modifier.testTag("home_stats_row")) {
        // Room for every card at the width below, or the row scrolls.
        val fits = maxWidth >= TileWidth * tiles.size + TileGap * (tiles.size - 1)
        Row(
            modifier = if (fits) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.horizontalScroll(rememberScrollState())
            },
            horizontalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            tiles.forEach { tile ->
                StatTileCell(
                    tile = tile,
                    modifier = if (fits) Modifier.weight(1f) else Modifier.width(TileWidth),
                )
            }
        }
    }
}

private val TileWidth = 108.dp
private val TileGap = 10.dp

/** One card of [StatsSection]: its icon, its number, and where it leads. */
@Composable
private fun StatTileCell(tile: StatTile, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = tile.onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AdGuardDarkSurface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.linearGradient(
                listOf(AdGuardDarkOutline, tile.accent.copy(alpha = 0.22f))
            )
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            Icon(
                imageVector = tile.icon,
                contentDescription = null,
                tint = tile.accent,
                modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = tile.value,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = AdGuardTextPrimary,
                maxLines = 1,
            )
            Text(
                text = tile.label,
                fontSize = 11.sp,
                color = AdGuardTextMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The desktop home, drawn the way AdGuard for Windows draws its own: one
 * centred state, said once in a big line, with the switch right under it.
 *
 * The phone body stacks the counters under the power button because a 400dp
 * screen has nowhere else to put them. On a window this wide the same row is
 * still the right shape — it simply fits without scrolling.
 */
@Composable
private fun DesktopHome(
    state: AdGuardUiState,
    privateDnsOn: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenPrivateDns: () -> Unit,
    onOpenScripts: () -> Unit,
    quickOpenRow: @Composable () -> Unit,
    quickModuleRow: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AdGuardDarkBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UiSize.gutter.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(18.dp))

        // Six switches spread over 1090dp read as six islands. AdGuard keeps
        // this row as one cluster under the status, so it gets a width.
        Box(modifier = Modifier.widthIn(max = 560.dp)) {
            quickModuleRow()
        }

        Spacer(modifier = Modifier.height(26.dp))

        Text(
            text = if (state.isVpnActive) L10n.get(Strings.home_shield_protected)
            else L10n.get(Strings.home_shield_off),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = if (state.isVpnActive) AdGuardTextPrimary else AdGuardTextMuted,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = L10n.get(
                if (state.isVpnActive) Strings.home_state_subtitle_on
                else Strings.home_state_subtitle_off
            ),
            fontSize = 12.5.sp,
            color = AdGuardTextMuted,
        )

        Spacer(modifier = Modifier.height(18.dp))

        ProtectionSwitch(isActive = state.isVpnActive, onToggle = onToggle)

        Spacer(modifier = Modifier.height(22.dp))

        Box(modifier = Modifier.widthIn(max = 560.dp)) {
            quickOpenRow()
        }

        Spacer(modifier = Modifier.height(30.dp))

        StatsSection(
            stats = state.stats,
            scriptsInstalled = state.userscripts.size,
            scriptsEnabled = state.userscripts.count { it.isEnabled },
            scriptsMasterOn = state.scriptsMasterEnabled,
            onOpenStatistics = onOpenStatistics,
            onOpenScripts = onOpenScripts,
        )

        if (privateDnsOn) {
            Spacer(modifier = Modifier.height(UiSize.sectionGap.dp))
            PrivateDnsWarningCard(onOpenSettings = onOpenPrivateDns)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** The pill switch AdGuard for Windows puts under the state line. */
@Composable
private fun ProtectionSwitch(isActive: Boolean, onToggle: () -> Unit) {
    val track by animateColorAsState(
        targetValue = if (isActive) AdGuardGreenPrimary else Color(0xFF2C3A46),
        label = "switchTrack",
    )
    val knobX by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        label = "switchKnob",
    )
    Box(
        modifier = Modifier
            .width(62.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(track)
            .border(
                1.dp,
                if (isActive) Color(0x55FFFFFF) else AdGuardDarkOutline,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onToggle)
            .testTag("shield_power_toggle"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 3.dp)
                .offset(x = 30.dp * knobX)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}


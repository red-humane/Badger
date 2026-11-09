package com.humnspace.integral

import android.accessibilityservice.AccessibilityServiceInfo
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import com.humnspace.integral.accessibility.BadgerMotionEngine
import com.humnspace.integral.accessibility.ButtonPressIndicatorOverlayView
import com.humnspace.integral.accessibility.ReminderManager
import com.humnspace.integral.accessibility.SavedCropsManager
import androidx.core.accessibilityservice.AccessibilityServiceInfoCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.humnspace.integral.accessibility.DetectionState
import com.humnspace.integral.accessibility.EssentialKeyService
import com.humnspace.integral.accessibility.DetectedKeyEvent
import com.humnspace.integral.accessibility.ScreenAnalysisOverlay
import com.humnspace.integral.accessibility.NothingGlyphController
import com.humnspace.integral.ui.badgerSpringClick
import com.humnspace.integral.ui.connectedSpringButton
import com.humnspace.integral.ui.rememberConnectedSpringState
import com.humnspace.integral.accessibility.IntegralAcoustics
import com.humnspace.integral.accessibility.VoiceRecordingManager
import com.humnspace.integral.accessibility.VoiceRecording
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Satoshi font family loaded from res/font/
val SatoshiFontFamily = FontFamily(
    Font(R.font.satoshi_regular, FontWeight.Normal),
    Font(R.font.satoshi_medium, FontWeight.Medium),
    Font(R.font.satoshi_bold, FontWeight.Bold),
    Font(R.font.satoshi_black, FontWeight.Black)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold, fontSize = 38.sp),
    headlineMedium = TextStyle(fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge = TextStyle(fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        ensureServiceEnabled(this)
        NothingGlyphController.init(this)
        ReminderManager.initNotificationChannel(this)
        ReminderManager.syncActiveNotifications(this)
        setContent { BadgerApp() }
    }
}

private val Ink = Color(0xFF111111)
private val Paper = Color(0xFFF4F3F0)
private val Muted = Color(0xFF888892)
private val Accent = Color(0xFFFFB300) // Badger Amber #FFB300
private val SurfaceDark = Color(0xFF09090B)
private val CardSurface = Color(0xFF141416)
private val CardBorder = Color(0xFF222228)

@Composable
private fun BadgerApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE) }
    var isOnboardingCompleted by remember {
        mutableStateOf(prefs.getBoolean(EssentialKeyService.ONBOARDING_COMPLETED, false))
    }
    var pages by remember {
        mutableStateOf(if (isOnboardingCompleted) listOf(AppPage.HOME) else listOf(AppPage.ONBOARDING))
    }
    fun open(page: AppPage) { pages = pages + page }
    fun back() { if (pages.size > 1) pages = pages.dropLast(1) }

    BackHandler(
        enabled = ScreenAnalysisOverlay.isRingerOverlayShowing ||
            ScreenAnalysisOverlay.isVoiceOverlayShowing ||
            ScreenAnalysisOverlay.isVoiceRecordingOverlayShowing
    ) {
        if (ScreenAnalysisOverlay.isRingerOverlayShowing) ScreenAnalysisOverlay.dismissRingerOverlay()
        if (ScreenAnalysisOverlay.isVoiceOverlayShowing) ScreenAnalysisOverlay.dismissVoiceSearchOverlay()
        if (ScreenAnalysisOverlay.isVoiceRecordingOverlayShowing) ScreenAnalysisOverlay.dismissVoiceRecordingOverlay()
    }
    BackHandler(
        enabled = !ScreenAnalysisOverlay.isRingerOverlayShowing &&
            !ScreenAnalysisOverlay.isVoiceOverlayShowing &&
            !ScreenAnalysisOverlay.isVoiceRecordingOverlayShowing &&
            pages.size > 1,
        onBack = { back() }
    )

    MaterialTheme(
        typography = AppTypography,
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF121214),
            background = SurfaceDark,
            surface = CardSurface,
            onBackground = Color.White,
            onSurface = Color.White,
            secondary = Accent
        )
    ) {
        when (pages.last()) {
            AppPage.ONBOARDING -> OnboardingScreen(
                onComplete = {
                    prefs.edit().putBoolean(EssentialKeyService.ONBOARDING_COMPLETED, true).apply()
                    isOnboardingCompleted = true
                    pages = listOf(AppPage.HOME)
                },
                canGoBack = pages.size > 1,
                onBack = { back() }
            )
            AppPage.ACCESSIBILITY_SETUP -> AccessibilitySetupScreen(onBack = { back() }, onComplete = { back() })
            AppPage.DETECTION -> DetectionScreen(onBack = { back() }, onSetup = { open(AppPage.ACCESSIBILITY_SETUP) })
            AppPage.LOCATION -> PressLocationScreen(onBack = { back() })
            AppPage.GLYPHS -> GlyphsSettingsScreen(onBack = { back() })
            AppPage.VOICE_NOTES -> VoiceNotesScreen(onBack = { back() })
            AppPage.REMINDERS -> RemindersScreen(onBack = { back() })
            AppPage.SAVED_CROPS -> SavedCropsScreen(onBack = { back() })
            AppPage.HOME -> HomeScreen(
                onTest = { open(AppPage.DETECTION) },
                onLocation = { open(AppPage.LOCATION) },
                onSetup = { open(AppPage.ACCESSIBILITY_SETUP) },
                onGlyphs = { open(AppPage.GLYPHS) },
                onVoiceNotes = { open(AppPage.VOICE_NOTES) },
                onReminders = { open(AppPage.REMINDERS) },
                onSavedCrops = { open(AppPage.SAVED_CROPS) },
                onOnboarding = { open(AppPage.ONBOARDING) }
            )
        }
    }
}

private enum class AppPage { HOME, ONBOARDING, DETECTION, LOCATION, ACCESSIBILITY_SETUP, GLYPHS, VOICE_NOTES, REMINDERS, SAVED_CROPS }

private data class GestureConfig(
    val key: String,
    val name: String,
    val defaultActionId: String,
    val allowedActionIds: List<String>
)

@Composable
private fun HomeScreen(
    onTest: () -> Unit,
    onLocation: () -> Unit,
    onSetup: () -> Unit,
    onGlyphs: () -> Unit,
    onVoiceNotes: () -> Unit,
    onReminders: () -> Unit,
    onSavedCrops: () -> Unit,
    onOnboarding: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE) }
    var serviceEnabled by remember { mutableStateOf(isServiceEnabled(context)) }
    var wasServiceEnabled by remember { mutableStateOf(serviceEnabled) }
    var listening by remember { mutableStateOf(prefs.getBoolean(EssentialKeyService.MAPPING_ENABLED, true)) }
    val actions = remember { badgerActions() }

    val gestures = remember {
        listOf(
            GestureConfig(
                key = EssentialKeyService.ACTION_SINGLE,
                name = "Single Press",
                defaultActionId = EssentialKeyService.ACTION_RINGER,
                allowedActionIds = listOf(
                    EssentialKeyService.ACTION_RINGER,
                    EssentialKeyService.ACTION_CONTENTS
                )
            ),
            GestureConfig(
                key = EssentialKeyService.ACTION_DOUBLE,
                name = "Double Press",
                defaultActionId = EssentialKeyService.ACTION_CONTENTS,
                allowedActionIds = listOf(
                    EssentialKeyService.ACTION_CONTENTS,
                    EssentialKeyService.ACTION_VOICE_RECORDING
                )
            ),
            GestureConfig(
                key = EssentialKeyService.ACTION_TRIPLE,
                name = "Triple Press",
                defaultActionId = EssentialKeyService.ACTION_BUBBLE_HUB,
                allowedActionIds = listOf(
                    EssentialKeyService.ACTION_BUBBLE_HUB
                )
            ),
            GestureConfig(
                key = EssentialKeyService.ACTION_LONG,
                name = "Long Press",
                defaultActionId = EssentialKeyService.ACTION_VOICE_SEARCH,
                allowedActionIds = listOf(
                    EssentialKeyService.ACTION_VOICE_SEARCH,
                    EssentialKeyService.ACTION_VOICE_RECORDING
                )
            )
        )
    }

    var selectedGestureIndex by remember { mutableIntStateOf(0) }
    val activeGesture = gestures[selectedGestureIndex]
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }

    LaunchedEffect(activeGesture.key) {
        dragOffset.snapTo(0f)
    }

    var voiceDestination by remember {
        mutableStateOf(prefs.getString("voice_destination_memory", "web") ?: "web")
    }

    // Audio Permission Launcher
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            listening = true
            prefs.edit().putBoolean(EssentialKeyService.MAPPING_ENABLED, true).apply()
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabledNow = isServiceEnabled(context)
                if (enabledNow && !wasServiceEnabled) {
                    listening = true
                    prefs.edit().putBoolean(EssentialKeyService.MAPPING_ENABLED, true).apply()
                } else {
                    listening = prefs.getBoolean(EssentialKeyService.MAPPING_ENABLED, true)
                }
                wasServiceEnabled = enabledNow
                serviceEnabled = enabledNow
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // Reactive Map of gesture -> assigned action ID
    var gestureActionMap by remember {
        mutableStateOf(
            gestures.associate { gesture ->
                val saved = prefs.getString(gesture.key, gesture.defaultActionId) ?: gesture.defaultActionId
                val valid = if (gesture.allowedActionIds.contains(saved)) saved else gesture.defaultActionId
                gesture.key to valid
            }
        )
    }

    var swipeDirection by remember { mutableIntStateOf(1) } // 1 for next (swipe left), -1 for prev (swipe right)

    val currentActionId = gestureActionMap[activeGesture.key] ?: activeGesture.defaultActionId
    val selectedAction = actions.firstOrNull { it.id == currentActionId } ?: actions[0]

    fun selectActionForGesture(gestureKey: String, actionId: String) {
        gestureActionMap = gestureActionMap + (gestureKey to actionId)
        prefs.edit().putString(gestureKey, actionId).apply()
        if (gestureKey == EssentialKeyService.ACTION_SINGLE) {
            prefs.edit().putString(EssentialKeyService.MAPPED_ACTION, actionId).apply()
        }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

        if ((actionId == EssentialKeyService.ACTION_VOICE_SEARCH || actionId == EssentialKeyService.ACTION_VOICE_RECORDING) &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun cycleGestureAction(direction: Int) {
        val allowed = activeGesture.allowedActionIds
        if (allowed.size <= 1) return
        val current = gestureActionMap[activeGesture.key] ?: activeGesture.defaultActionId
        val currentIndex = allowed.indexOf(current).coerceAtLeast(0)
        val nextIndex = (currentIndex + direction + allowed.size) % allowed.size
        swipeDirection = direction
        selectActionForGesture(activeGesture.key, allowed[nextIndex])
    }

    fun triggerPreview() {
        ScreenAnalysisOverlay.showButtonPress(context, ButtonPressIndicatorOverlayView.PressType.DOWN)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ScreenAnalysisOverlay.showButtonPress(context, ButtonPressIndicatorOverlayView.PressType.UP)
        }, 220L)

        when (selectedAction.id) {
            EssentialKeyService.ACTION_RINGER -> {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val targetMode = if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) "ring" else "vibrate"
                ScreenAnalysisOverlay.showRingerOverlay(context, targetMode, null)
            }
            EssentialKeyService.ACTION_VOICE_SEARCH -> {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    ScreenAnalysisOverlay.showVoiceSearchOverlay(context)
                } else {
                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            EssentialKeyService.ACTION_VOICE_RECORDING -> {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    ScreenAnalysisOverlay.showVoiceRecordingOverlay(context)
                } else {
                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            EssentialKeyService.ACTION_CONTENTS -> {
                (context as? MainActivity)?.let {
                    Toast.makeText(context, "Press the Badge button to trigger Get Content on screen", Toast.LENGTH_SHORT).show()
                }
            }
            EssentialKeyService.ACTION_BUBBLE_HUB -> {
                ScreenAnalysisOverlay.showFeatureBubbles(
                    context = context,
                    onSelectRinger = {
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val targetMode = if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) "ring" else "vibrate"
                        ScreenAnalysisOverlay.showRingerOverlay(context, targetMode, null)
                    },
                    onSelectGetContent = {
                        ScreenAnalysisOverlay.showVoiceRecordingOverlay(context)
                    },
                    onSelectVoice = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            ScreenAnalysisOverlay.showVoiceSearchOverlay(context)
                        } else {
                            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }
            EssentialKeyService.ACTION_QR_SCAN -> ScreenAnalysisOverlay.showQrLens(context, null)
            EssentialKeyService.ACTION_SUMMARIZE -> ScreenAnalysisOverlay.showScreenSummarizer(context, null)
            else -> Toast.makeText(context, "${selectedAction.title} selected for Badge button", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF08080A))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. TOP BAR: [ Badge Icon ] BADGER               ( Diagnostics )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF141416))
                    .border(1.dp, Color(0xFF26262C), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painterResource(R.drawable.integral_logo_mark),
                    contentDescription = "Badger Logo",
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Text(
                "BADGER",
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = SatoshiFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.4.sp,
                modifier = Modifier.weight(1f)
            )

            // Circular Diagnostics Button (Ref 3 & 5 style)
            IconButton(
                onClick = onTest,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF16161A))
                    .border(1.dp, Color(0xFF282830), CircleShape)
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Key Diagnostics",
                    tint = Color(0xFFE0E0E6),
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Onboarding & Setup Guide Button
            IconButton(
                onClick = onOnboarding,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF16161A))
                    .border(1.dp, Color(0xFF282830), CircleShape)
            ) {
                Icon(
                    Icons.Default.HelpOutline,
                    contentDescription = "Setup Guide & Onboarding",
                    tint = Color(0xFFE0E0E6),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 2. HERO EDITORIAL HEADLINE: hello again on single line
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "hello ",
                color = Color.White,
                fontSize = 36.sp,
                fontFamily = SatoshiFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp
            )
            Text(
                "again",
                color = Accent,
                fontSize = 36.sp,
                fontFamily = SatoshiFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp
            )
        }
        Text(
            "Configure actions for your button.",
            color = Color(0xFF8E8E98),
            fontSize = 13.5.sp,
            fontFamily = SatoshiFontFamily,
            fontWeight = FontWeight.Normal,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp)
        )

        // 3. HORIZONTAL SEGMENTED CHIPS (Ref 3 & 5 style)
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val gestureLabels = listOf("Single", "Double", "Triple", "Hold")
            gestures.forEachIndexed { idx, gesture ->
                val isSelected = (idx == selectedGestureIndex)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(21.dp))
                        .background(if (isSelected) Color.White else Color(0xFF141418))
                        .border(
                            1.dp,
                            if (isSelected) Color.White else Color(0xFF26262E),
                            RoundedCornerShape(21.dp)
                        )
                        .clickable {
                            swipeDirection = if (idx > selectedGestureIndex) 1 else -1
                            selectedGestureIndex = idx
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        gestureLabels.getOrElse(idx) { gesture.name.split(" ").first() },
                        color = if (isSelected) Color(0xFF0A0A0C) else Color(0xFF8E8E98),
                        fontSize = 12.5.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // 4. FEATURED HERO BENTO CARD IN SOLID AMBER - CAROUSEL SWIPE TO CHANGE ACTION
        val allowedActionIds = activeGesture.allowedActionIds
        val currentActionIndex = allowedActionIds.indexOf(currentActionId).coerceAtLeast(0)
        val totalActions = allowedActionIds.size

        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Accent), // Solid vibrant Amber #FFB300
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(activeGesture.key, currentActionId, totalActions) {
                    if (totalActions <= 1) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { /* Drag started */ },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                val currentVal = dragOffset.value
                                val damped = dragAmount * 0.85f
                                dragOffset.snapTo((currentVal + damped).coerceIn(-360f, 360f))
                            }
                        },
                        onDragEnd = {
                            coroutineScope.launch {
                                val currentVal = dragOffset.value
                                val threshold = 65f
                                if (currentVal < -threshold) {
                                    // Fling left -> Next action
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    dragOffset.animateTo(-420f, tween(110))
                                    cycleGestureAction(1)
                                    dragOffset.snapTo(320f)
                                    dragOffset.animateTo(0f, spring(dampingRatio = 0.68f, stiffness = 450f))
                                } else if (currentVal > threshold) {
                                    // Fling right -> Previous action
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    dragOffset.animateTo(420f, tween(110))
                                    cycleGestureAction(-1)
                                    dragOffset.snapTo(-320f)
                                    dragOffset.animateTo(0f, spring(dampingRatio = 0.68f, stiffness = 450f))
                                } else {
                                    // Return to center spring
                                    dragOffset.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 500f))
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                dragOffset.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 500f))
                            }
                        }
                    )
                }
        ) {
            Column(
                Modifier
                    .padding(22.dp)
                    .graphicsLayer {
                        translationX = dragOffset.value
                        rotationZ = (dragOffset.value / 360f) * 3.5f
                        val absVal = kotlin.math.abs(dragOffset.value)
                        val scale = 1f - (absVal / 1800f).coerceIn(0f, 0.06f)
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (selectedGestureIndex) {
                                0 -> Icons.Default.TouchApp
                                1 -> Icons.Default.Fingerprint
                                2 -> Icons.Default.Widgets
                                else -> Icons.Default.KeyboardVoice
                            },
                            contentDescription = null,
                            tint = Color(0xFF101012),
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            activeGesture.name.uppercase(),
                            color = Color(0xFF101012),
                            fontSize = 11.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp
                        )
                    }

                    // Carousel Pagination Dot Indicators (Replaces previous preview button)
                    if (totalActions > 1) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x18000000))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            allowedActionIds.forEachIndexed { idx, _ ->
                                val isCurrent = idx == currentActionIndex
                                val dotWidth by androidx.compose.animation.core.animateDpAsState(
                                    targetValue = if (isCurrent) 18.dp else 6.dp,
                                    animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
                                    label = "dotWidth"
                                )
                                Box(
                                    Modifier
                                        .size(width = dotWidth, height = 6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (isCurrent) Color(0xFF101012) else Color(0x38000000))
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                AnimatedContent(
                    targetState = selectedAction,
                    transitionSpec = {
                        if (swipeDirection > 0) {
                            (slideInHorizontally { width -> width / 3 } + fadeIn(tween(180)))
                                .togetherWith(slideOutHorizontally { width -> -width / 3 } + fadeOut(tween(140)))
                        } else {
                            (slideInHorizontally { width -> -width / 3 } + fadeIn(tween(180)))
                                .togetherWith(slideOutHorizontally { width -> width / 3 } + fadeOut(tween(140)))
                        }
                    },
                    label = "CarouselActionContent"
                ) { action ->
                    Column {
                        Text(
                            action.title,
                            color = Color(0xFF0C0C0E),
                            fontSize = 28.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.6).sp
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            action.description,
                            color = Color(0xFF2C2C30),
                            fontSize = 13.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x24000000))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (totalActions > 1) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                    tint = Color(0xFF121214).copy(alpha = 0.55f),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    "Swipe to switch action",
                                    color = Color(0xFF121214),
                                    fontSize = 11.5.sp,
                                    fontFamily = SatoshiFontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = Color(0xFF121214).copy(alpha = 0.55f),
                                    modifier = Modifier.size(12.dp)
                                )
                            } else {
                                Text(
                                    "Assigned Mapping",
                                    color = Color(0xFF121214),
                                    fontSize = 11.5.sp,
                                    fontFamily = SatoshiFontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (totalActions > 1) {
                        Text(
                            "${currentActionIndex + 1} / $totalActions",
                            color = Color(0xFF121214).copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 5. 2-COLUMN BENTO STAT GRID: HARDWARE KEY + SAVED CROPS
        val savedCrops = remember { SavedCropsManager.getSavedCrops(context) }
        val reminders = remember { ReminderManager.getReminders(context) }
        val isHardwareActive = (listening && serviceEnabled)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Left Bento: Hardware Key (White if Active, Gray if Offline, NO green circle)
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isHardwareActive) Color(0xFFF4F4F6) else Color(0xFF1C1C22)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(128.dp)
                    .then(
                        if (!isHardwareActive) Modifier.border(1.dp, Color(0xFF282832), RoundedCornerShape(24.dp))
                        else Modifier
                    )
                    .clickable {
                        if (!serviceEnabled) {
                            onSetup()
                        } else {
                            listening = !listening
                            prefs.edit().putBoolean(EssentialKeyService.MAPPING_ENABLED, listening).apply()
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Hardware Key",
                        color = if (isHardwareActive) Color(0xFF6E6E78) else Color(0xFF888892),
                        fontSize = 11.5.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                    Column {
                        Text(
                            if (!serviceEnabled) "Setup" else if (listening) "Active" else "Offline",
                            color = if (isHardwareActive) Color(0xFF101014) else Color(0xFF888892),
                            fontSize = 22.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (isHardwareActive) "${gestures.size} gestures" else "Tap to enable",
                            color = if (isHardwareActive) Color(0xFF888892) else Color(0xFF666670),
                            fontSize = 11.sp,
                            fontFamily = SatoshiFontFamily
                        )
                    }
                }
            }

            // Right Bento: Saved Circle-to-Crop Images
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16161A)),
                modifier = Modifier
                    .weight(1f)
                    .height(128.dp)
                    .border(1.dp, Color(0xFF282830), RoundedCornerShape(24.dp))
                    .clickable { onSavedCrops() }
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Saved Crops",
                            color = Color(0xFF8E8E98),
                            fontSize = 11.5.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            Icons.Default.Crop,
                            contentDescription = "Crops",
                            tint = Accent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            "${savedCrops.size} Images",
                            color = Accent,
                            fontSize = 20.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Tap to view",
                            color = Color(0xFF787882),
                            fontSize = 11.sp,
                            fontFamily = SatoshiFontFamily
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 6. TACTILE FOCAL ORB & INTERACTIVE PREVIEW CARD
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121216)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF222228), RoundedCornerShape(26.dp))
                .pointerInput(activeGesture.key, currentActionId) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = {
                            if (totalDrag < -25f) cycleGestureAction(1)
                            else if (totalDrag > 25f) cycleGestureAction(-1)
                        },
                        onHorizontalDrag = { change, drag -> totalDrag += drag; change.consume() }
                    )
                }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "ACTION PREVIEW",
                        color = Color(0xFF7E7E88),
                        fontSize = 11.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.3.sp
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Accent.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "Interactive",
                            color = Accent,
                            fontSize = 10.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // Big Glowing Yellow Focal Orb (Ref 1 style)
                Box(
                    Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .background(Accent)
                        .clickable { triggerPreview() },
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = selectedAction,
                        transitionSpec = {
                            if (swipeDirection >= 0) {
                                (slideInHorizontally(tween(220)) { it } + fadeIn(tween(180))) togetherWith
                                    (slideOutHorizontally(tween(200)) { -it } + fadeOut(tween(160)))
                            } else {
                                (slideInHorizontally(tween(220)) { -it } + fadeIn(tween(180))) togetherWith
                                    (slideOutHorizontally(tween(200)) { it } + fadeOut(tween(160)))
                            }
                        },
                        label = "focalOrbIcon"
                    ) { action ->
                        val heroIcon = when (action.id) {
                            EssentialKeyService.ACTION_RINGER -> Icons.AutoMirrored.Filled.VolumeUp
                            EssentialKeyService.ACTION_BUBBLE_HUB -> Icons.Default.Widgets
                            else -> action.icon
                        }
                        Icon(
                            heroIcon,
                            contentDescription = action.title,
                            tint = Color(0xFF0E0E10),
                            modifier = Modifier.size(54.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    selectedAction.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontFamily = SatoshiFontFamily,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    "Swipe horizontally to switch • Tap orb to test",
                    color = Color(0xFF888892),
                    fontSize = 11.5.sp,
                    fontFamily = SatoshiFontFamily
                )

                Spacer(Modifier.height(18.dp))

                // Action Pill Button (Ref 4 & 5 style)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF1B1B20))
                        .border(1.dp, Color(0xFF282832), RoundedCornerShape(24.dp))
                        .badgerSpringClick { triggerPreview() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Preview overlay animation",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }


        Spacer(Modifier.height(14.dp))

        // 8. SOUND EFFECTS (SFX) CARD
        var sfxEnabled by remember {
            mutableStateOf(IntegralAcoustics.isEnabled(context))
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF222228), RoundedCornerShape(22.dp))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Sound Effects",
                    tint = if (sfxEnabled) Accent else Color(0xFF888890),
                    modifier = Modifier.size(22.dp)
                )

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "Sound Effects",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (sfxEnabled) "Nothing micro-clicks, radar scans & chimes" else "Disabled • Silent overlay transitions",
                        color = Color(0xFF888890),
                        fontSize = 11.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Normal
                    )
                }

                Switch(
                    checked = sfxEnabled,
                    onCheckedChange = {
                        sfxEnabled = it
                        IntegralAcoustics.setEnabled(context, it)
                        if (it) {
                            IntegralAcoustics.playBadgePress(context)
                        }
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF121214),
                        checkedTrackColor = Accent,
                        uncheckedThumbColor = Color(0xFF888890),
                        uncheckedTrackColor = Color(0xFF222228)
                    )
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 9. USE GLYPHS SETTINGS CARD
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .border(1.dp, Color(0xFF222228), RoundedCornerShape(22.dp))
                .badgerSpringClick { onGlyphs() }
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Flare,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp)
                )

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "Glyph Light Language",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Directional scan sweeps, recording spectrum & confirm pulses",
                        color = Color(0xFF888890),
                        fontSize = 11.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Normal
                    )
                }

                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open",
                    tint = Color(0xFF888890),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 9. VOICE NOTES CARD (Separated from Reminders)
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .border(1.dp, Color(0xFF222228), RoundedCornerShape(22.dp))
                .badgerSpringClick { onVoiceNotes() }
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp)
                )

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "Voice Notes",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Review, listen, rename and share your audio clips",
                        color = Color(0xFF888890),
                        fontSize = 11.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Normal
                    )
                }

                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open",
                    tint = Color(0xFF888890),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 10. REMINDERS CARD (Separated from Voice Notes)
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .border(1.dp, Color(0xFF222228), RoundedCornerShape(22.dp))
                .badgerSpringClick { onReminders() }
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Alarm,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(22.dp)
                )

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "Reminders",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${reminders.size} scheduled • Created via Say it",
                        color = Color(0xFF888890),
                        fontSize = 11.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Normal
                    )
                }

                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open",
                    tint = Color(0xFF888890),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // 11. MASTER TOGGLE
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .border(1.dp, Color(0xFF222228), RoundedCornerShape(22.dp))
                .clickable {
                    if (!serviceEnabled) {
                        onSetup()
                    } else {
                        listening = !listening
                        prefs.edit().putBoolean(EssentialKeyService.MAPPING_ENABLED, listening).apply()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
        ) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (!serviceEnabled) "Enable service access" else if (listening) "Turn mapping off" else "Turn mapping on",
                    color = if (listening) Color.White else Accent,
                    fontSize = 14.sp,
                    fontFamily = SatoshiFontFamily,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 12. BOTTOM LINKS
        Text(
            "Adjust press animation location",
            color = Color(0xFF888890),
            fontSize = 13.sp,
            fontFamily = SatoshiFontFamily,
            fontWeight = FontWeight.Normal,
            modifier = Modifier
                .clickable(onClick = onLocation)
                .padding(vertical = 6.dp)
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "Test button detection",
            color = Color(0xFF888890),
            fontSize = 13.sp,
            fontFamily = SatoshiFontFamily,
            fontWeight = FontWeight.Normal,
            modifier = Modifier
                .clickable(onClick = onTest)
                .padding(vertical = 6.dp)
        )

        Spacer(Modifier.height(28.dp))
    }
}

private data class BadgerAction(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)

private fun badgerActions() = listOf(
    BadgerAction(
        EssentialKeyService.ACTION_RINGER,
        "Dynamic Ringer",
        "Switch smoothly between Ring and Vibrate.",
        Icons.AutoMirrored.Filled.VolumeUp
    ),
    BadgerAction(
        EssentialKeyService.ACTION_CONTENTS,
        "Get Content",
        "Select and copy text, links, or images from your screen.",
        Icons.Default.ContentCopy
    ),
    BadgerAction(
        EssentialKeyService.ACTION_VOICE_SEARCH,
        "Say it",
        "Speak to search, open apps, or set reminders.",
        Icons.Default.KeyboardVoice
    ),
    BadgerAction(
        EssentialKeyService.ACTION_VOICE_RECORDING,
        "Voice Notes",
        "Record and save audio notes instantly.",
        Icons.Default.Mic
    ),
    BadgerAction(
        EssentialKeyService.ACTION_BUBBLE_HUB,
        "Feature Tray",
        "Quick dock with your favorite actions.",
        Icons.Default.Widgets
    ),
    BadgerAction(
        EssentialKeyService.ACTION_QR_SCAN,
        "QR Scanner",
        "Scan QR codes and barcodes on your screen.",
        Icons.Default.QrCodeScanner
    ),
    BadgerAction(
        EssentialKeyService.ACTION_SUMMARIZE,
        "Screen Summary",
        "Quick summary of what is on your screen.",
        Icons.Default.AutoAwesome
    ),
    BadgerAction(
        EssentialKeyService.ACTION_NONE,
        "No Action",
        "Leave this button press unmapped.",
        Icons.Default.Block
    )
)


@Composable
private fun GlyphsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE) }
    val haptics = LocalHapticFeedback.current

    var masterUseGlyphs by remember { mutableStateOf(prefs.getBoolean(EssentialKeyService.USE_GLYPHS, true)) }
    var glyphsProgress by remember { mutableStateOf(prefs.getBoolean(EssentialKeyService.GLYPHS_PROGRESS, true)) }
    var glyphsAppFlash by remember { mutableStateOf(prefs.getBoolean(EssentialKeyService.GLYPHS_APP_FLASH, true)) }
    var glyphsVoiceReactivity by remember { mutableStateOf(prefs.getBoolean(EssentialKeyService.GLYPHS_VOICE_REACTIVITY, true)) }

    var isTestingProgress by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF070709))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                "Use Glyphs",
                color = Color.White,
                fontSize = 21.sp,
                fontFamily = SatoshiFontFamily,
                fontWeight = FontWeight.Bold
            )
        }

        // Master Switch Card
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (masterUseGlyphs) Accent.copy(alpha = 0.35f) else Color(0xFF222228), RoundedCornerShape(22.dp))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Glyph Interface",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enable Nothing Phone (3a) Glyph lighting for Badger features",
                        color = Color(0xFF888890),
                        fontSize = 12.sp,
                        fontFamily = SatoshiFontFamily
                    )
                }
                Switch(
                    checked = masterUseGlyphs,
                    onCheckedChange = {
                        masterUseGlyphs = it
                        prefs.edit().putBoolean(EssentialKeyService.USE_GLYPHS, it).apply()
                        if (!it) com.humnspace.integral.accessibility.NothingGlyphController.turnOffAll()
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF121214),
                        checkedTrackColor = Accent,
                        uncheckedThumbColor = Color(0xFF888890),
                        uncheckedTrackColor = Color(0xFF222228)
                    )
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        if (masterUseGlyphs) {
            Text(
                "GLYPH FEATURES",
                color = Accent,
                fontSize = 12.sp,
                fontFamily = SatoshiFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(Modifier.height(12.dp))

            // 1. Progress indicators Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF222228), RoundedCornerShape(20.dp))
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Progress indicators",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontFamily = SatoshiFontFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "When downloading a file from Chrome or Play Store, shows live download progress in the C glyph lights.",
                                color = Color(0xFF888890),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontFamily = SatoshiFontFamily
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = glyphsProgress,
                            onCheckedChange = {
                                glyphsProgress = it
                                prefs.edit().putBoolean(EssentialKeyService.GLYPHS_PROGRESS, it).apply()
                                if (!it) com.humnspace.integral.accessibility.NothingGlyphController.clearDownloadProgress()
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF121214),
                                checkedTrackColor = Accent,
                                uncheckedThumbColor = Color(0xFF888890),
                                uncheckedTrackColor = Color(0xFF222228)
                            )
                        )
                    }

                                        if (glyphsProgress && !isNotificationAccessGranted(context)) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF221F10)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                .badgerSpringClick {
                                    context.startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Notification Access Required", color = Accent, fontSize = 12.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                    Text("Tap to grant permission so Badger can mirror Play Store and Chrome download progress to your C glyph lights.", color = Color(0xFFCCC5A0), fontSize = 11.sp, fontFamily = SatoshiFontFamily)
                                }
                            }
                        }
                    }

                    if (glyphsProgress) {
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = {
                                if (isTestingProgress) return@OutlinedButton
                                isTestingProgress = true
                                Thread {
                                    for (p in 0..20) {
                                        com.humnspace.integral.accessibility.NothingGlyphController.setDownloadProgress(p / 20f)
                                        Thread.sleep(100)
                                    }
                                    com.humnspace.integral.accessibility.NothingGlyphController.completeDownloadProgress()
                                    isTestingProgress = false
                                }.start()
                            },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333338))
                        ) {
                            Text(
                                if (isTestingProgress) "Testing C lights progress..." else "Test C lights progress (0% → 100%)",
                                color = Accent,
                                fontSize = 12.sp,
                                fontFamily = SatoshiFontFamily,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 2. App launch & close flashes Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF222228), RoundedCornerShape(20.dp))
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "App opening & closing flashes",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontFamily = SatoshiFontFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Flashes B lights from bottom to top (B1 to B5) segment-style on app open, and reverse (B5 to B1) on app close. Ignored during app switching.",
                                color = Color(0xFF888890),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontFamily = SatoshiFontFamily
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = glyphsAppFlash,
                            onCheckedChange = {
                                glyphsAppFlash = it
                                prefs.edit().putBoolean(EssentialKeyService.GLYPHS_APP_FLASH, it).apply()
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF121214),
                                checkedTrackColor = Accent,
                                uncheckedThumbColor = Color(0xFF888890),
                                uncheckedTrackColor = Color(0xFF222228)
                            )
                        )
                    }

                    if (glyphsAppFlash) {
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    com.humnspace.integral.accessibility.NothingGlyphController.playAppOpenFlash()
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333338))
                            ) {
                                Text("Test Open (B1→B5)", color = Accent, fontSize = 11.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Medium)
                            }
                            OutlinedButton(
                                onClick = {
                                    com.humnspace.integral.accessibility.NothingGlyphController.playAppCloseFlash()
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                modifier = Modifier.weight(1f).height(42.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333338))
                            ) {
                                Text("Test Close (B5→B1)", color = Accent, fontSize = 11.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 3. Voice reactivity Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF222228), RoundedCornerShape(20.dp))
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Voice reactivity",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The glyph pulses in real-time with your speech while you record a voice note.",
                            color = Color(0xFF888890),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontFamily = SatoshiFontFamily
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = glyphsVoiceReactivity,
                        onCheckedChange = {
                            glyphsVoiceReactivity = it
                            prefs.edit().putBoolean(EssentialKeyService.GLYPHS_VOICE_REACTIVITY, it).apply()
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF121214),
                            checkedTrackColor = Accent,
                            uncheckedThumbColor = Color(0xFF888890),
                            uncheckedTrackColor = Color(0xFF222228)
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun OnboardingScreen(
    onComplete: () -> Unit,
    canGoBack: Boolean = false,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val prefs = remember { context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE) }

    var currentStep by remember { mutableIntStateOf(0) }

    var hasAccessibility by remember { mutableStateOf(isServiceEnabled(context)) }
    var hasMicPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasMicPermission = granted
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotifPermission = granted
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccessibility = isServiceEnabled(context)
                hasMicPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasNotifPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = true) {
        if (currentStep > 0) {
            currentStep--
        } else if (canGoBack) {
            onBack()
        } else {
            (context as? ComponentActivity)?.finish()
        }
    }

    // Step 3 (Key Listening) state
    val events by DetectionState.events.collectAsState()
    var capturedKey by remember { mutableStateOf<DetectedKeyEvent?>(null) }

    LaunchedEffect(currentStep) {
        if (currentStep == 3) {
            capturedKey = null
            prefs.edit()
                .putBoolean(EssentialKeyService.DETECTION_ENABLED, true)
                .putBoolean(EssentialKeyService.MAPPING_ENABLED, true)
                .apply()
        } else {
            prefs.edit().putBoolean(EssentialKeyService.DETECTION_ENABLED, false).apply()
        }
    }

    LaunchedEffect(events.firstOrNull()?.eventTime) {
        if (currentStep == 3 && capturedKey == null) {
            val latest = events.firstOrNull()
            if (latest?.action == "DOWN") {
                capturedKey = latest
                val sig = keySignature(latest)
                prefs.edit()
                    .putString(EssentialKeyService.TARGET_KEY_SIGNATURE, sig)
                    .putString("last_assigned_key_name", latest.keyName)
                    .putString("last_assigned_device", latest.deviceName)
                    .putInt("last_assigned_keycode", latest.keyCode)
                    .putInt("last_assigned_scancode", latest.scanCode)
                    .putBoolean(EssentialKeyService.DETECTION_ENABLED, false)
                    .apply()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                IntegralAcoustics.playOverlayOpen(context)
                kotlinx.coroutines.delay(1200L)
                onComplete()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF070709))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Bar
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentStep > 0 || canGoBack) {
                    IconButton(
                        onClick = {
                            if (currentStep > 0) currentStep-- else onBack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }

                Text(
                    when (currentStep) {
                        0 -> "WELCOME"
                        1 -> "STEP 1 OF 3: PERMISSIONS"
                        2 -> "STEP 2 OF 3: OPTIMIZE"
                        else -> "STEP 3 OF 3: BUTTON SETUP"
                    },
                    color = Accent,
                    fontSize = 11.sp,
                    fontFamily = SatoshiFontFamily,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.4.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )

                if (canGoBack) {
                    IconButton(onClick = onComplete) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                } else {
                    Spacer(Modifier.width(48.dp))
                }
            }

            // Step Content
            when (currentStep) {
                0 -> {
                    // Step 0: Welcome
                    Spacer(Modifier.height(18.dp))
                    Box(
                        Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF141416))
                            .border(1.5.dp, Brush.radialGradient(listOf(Accent.copy(alpha = 0.8f), Color(0xFF26262C))), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painterResource(R.drawable.integral_logo_mark),
                            contentDescription = "Badger Logo",
                            modifier = Modifier.size(62.dp)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "BADGER",
                        color = Accent,
                        fontSize = 13.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Supercharge Your Button",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use the physical button on the side of your phone for quick everyday tasks: switch ringer modes, speak to search or set reminders, and copy text from your screen.",
                        color = Color(0xFFAAAAB2),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        fontFamily = SatoshiFontFamily,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(26.dp))

                    // Feature highlights
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF141416))
                            .border(1.dp, Color(0xFF222228), RoundedCornerShape(20.dp))
                            .padding(18.dp)
                    ) {
                        OnboardingFeatureRow(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            title = "Dynamic Ringer",
                            desc = "Quickly switch between Ring and Vibrate right from the edge of your screen."
                        )
                        Spacer(Modifier.height(14.dp))
                        OnboardingFeatureRow(
                            icon = Icons.Default.Mic,
                            title = "Say It & Voice Notes",
                            desc = "Speak to search the web, open apps, set reminders, or record quick voice notes."
                        )
                        Spacer(Modifier.height(14.dp))
                        OnboardingFeatureRow(
                            icon = Icons.Default.CropFree,
                            title = "Get Content",
                            desc = "Select and copy text, links, or images directly from any app on your screen."
                        )
                    }

                    Spacer(Modifier.height(30.dp))
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentStep = 1
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Text(
                            "Get Started",
                            color = Color(0xFF121214),
                            fontSize = 15.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color(0xFF121214), modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                }

                1 -> {
                    // Step 1: Permissions
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Permissions & Access",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Badger operates 100% locally on your phone. No accounts, no analytics, no cloud tracking.",
                        color = Color(0xFFAAAAB2),
                        fontSize = 13.sp,
                        fontFamily = SatoshiFontFamily,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(22.dp))

                    // Accessibility Card (Required)
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                        modifier = Modifier.fillMaxWidth().border(1.dp, if (hasAccessibility) Color(0xFF2E7D32) else Color(0xFF33333C), RoundedCornerShape(20.dp))
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(if (hasAccessibility) Color(0xFF1B5E20) else Color(0xFF22222A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (hasAccessibility) Icons.Default.Check else Icons.Default.AccessibilityNew,
                                        contentDescription = null,
                                        tint = if (hasAccessibility) Color(0xFF81C784) else Accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Badger Button Service", color = Color.White, fontSize = 14.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(6.dp))
                                        Text("REQUIRED", color = Accent, fontSize = 9.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                    }
                                    Text("Allows detecting your button presses and showing on-screen shortcuts.", color = Color(0xFF888890), fontSize = 11.sp, fontFamily = SatoshiFontFamily)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            if (hasAccessibility) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Service Enabled & Active", color = Color(0xFF81C784), fontSize = 12.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(42.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                                ) {
                                    Text("Enable in Android Settings", color = Color(0xFF121214), fontSize = 13.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Microphone Card (Recommended)
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF222228), RoundedCornerShape(20.dp))
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape).background(if (hasMicPermission) Color(0xFF1B5E20) else Color(0xFF22222A)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (hasMicPermission) Icons.Default.Check else Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = if (hasMicPermission) Color(0xFF81C784) else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Microphone", color = Color.White, fontSize = 14.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                Text("Used to search by voice, create reminders, and take voice notes.", color = Color(0xFF888890), fontSize = 11.sp, fontFamily = SatoshiFontFamily)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (hasMicPermission) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                            } else {
                                OutlinedButton(
                                    onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Grant", fontSize = 12.sp, color = Accent, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Notification Card (Recommended)
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF222228), RoundedCornerShape(20.dp))
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(38.dp).clip(CircleShape).background(if (hasNotifPermission) Color(0xFF1B5E20) else Color(0xFF22222A)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (hasNotifPermission) Icons.Default.Check else Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = if (hasNotifPermission) Color(0xFF81C784) else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Notifications", color = Color.White, fontSize = 14.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                Text("Used to alert you when your reminders are due.", color = Color(0xFF888890), fontSize = 11.sp, fontFamily = SatoshiFontFamily)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (hasNotifPermission) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Grant", fontSize = 12.sp, color = Accent, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = {
                            if (hasAccessibility) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentStep = 2
                            } else {
                                Toast.makeText(context, "Please enable Badger Button Service to continue", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = hasAccessibility,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            disabledContainerColor = Color(0xFF24242A)
                        )
                    ) {
                        Text(
                            if (hasAccessibility) "Continue to Button Setup" else "Enable Button Service First",
                            color = if (hasAccessibility) Color(0xFF121214) else Color(0xFF707078),
                            fontSize = 14.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        if (hasAccessibility) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color(0xFF121214), modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }

                2 -> {
                    // Step 2: Disable stock Ketchum / Essential Space via ADB
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Prevent Button Conflicts",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "By default, your phone opens Essential Space when pressing this button. Disabling that app lets Badger work smoothly without conflicts or double-presses.",
                        color = Color(0xFFAAAAB2),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        fontFamily = SatoshiFontFamily,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(22.dp))

                    // ADB Tool Card
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF282834), RoundedCornerShape(20.dp))
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF202028)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Terminal, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Disable via Computer or ADB (Recommended)", color = Color.White, fontSize = 14.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                    Text("1 quick command. Safe and easy to revert.", color = Color(0xFF888890), fontSize = 11.sp, fontFamily = SatoshiFontFamily)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Run this command on your computer via USB debugging, or directly on your phone with Shizuku or LADB:",
                                color = Color(0xFFB0B0B8),
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                fontFamily = SatoshiFontFamily
                            )
                            Spacer(Modifier.height(10.dp))

                            // Code block
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF0C0C0E))
                                    .border(1.dp, Color(0xFF26262E), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    "adb shell pm disable-user --user 0 com.nothing.ketchum",
                                    color = Accent,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString("adb shell pm disable-user --user 0 com.nothing.ketchum"))
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    Toast.makeText(context, "ADB command copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22222A))
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Copy ADB Command", color = Color.White, fontSize = 12.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "To undo at any time: adb shell pm enable com.nothing.ketchum",
                                color = Color(0xFF666670),
                                fontSize = 10.sp,
                                fontFamily = SatoshiFontFamily
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Settings Fallback Card
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF222228), RoundedCornerShape(20.dp))
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF202028)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFFE0E0E6), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("Alternative: Phone Settings", color = Color.White, fontSize = 14.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                                    Text("If you don't have a computer or ADB right now", color = Color(0xFF888890), fontSize = 11.sp, fontFamily = SatoshiFontFamily)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "1. Open Android Settings → Special features / System.\n2. Tap Essential Key.\n3. Change action to 'None' or disable Essential Space.",
                                color = Color(0xFFB0B0B8),
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                fontFamily = SatoshiFontFamily
                            )
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentStep = 3
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Text(
                            "Continue to Button Test",
                            color = Color(0xFF121214),
                            fontSize = 15.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color(0xFF121214), modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                }

                3 -> {
                    // Step 3: Hardware Key Calibration / Listening
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Test Your Button",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Press the physical button on the side of your phone once to make sure everything is working.",
                        color = Color(0xFFAAAAB2),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        fontFamily = SatoshiFontFamily,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(36.dp))

                    // Radar pulse circle
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.94f,
                        targetValue = 1.14f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                        ),
                        label = "scale"
                    )
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 0.65f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )

                    Box(
                        Modifier
                            .size(180.dp)
                            .graphicsLayer {
                                scaleX = if (capturedKey == null) pulseScale else 1f
                                scaleY = if (capturedKey == null) pulseScale else 1f
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Halo ring
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(
                                    if (capturedKey != null) Color(0xFF4CAF50).copy(alpha = 0.2f)
                                    else Accent.copy(alpha = pulseAlpha)
                                )
                        )
                        // Inner circle
                        Box(
                            Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF141418))
                                .border(2.dp, if (capturedKey != null) Color(0xFF4CAF50) else Accent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (capturedKey != null) Icons.Default.CheckCircle else Icons.Default.Radio,
                                contentDescription = null,
                                tint = if (capturedKey != null) Color(0xFF4CAF50) else Accent,
                                modifier = Modifier.size(54.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(26.dp))

                    Text(
                        if (capturedKey != null) "BUTTON DETECTED!" else "Press your button...",
                        color = if (capturedKey != null) Color(0xFF4CAF50) else Accent,
                        fontSize = 18.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (capturedKey != null)
                            "Ready! Your button is connected and working."
                        else
                            "Press the physical button on the side of your phone once",
                        color = if (capturedKey != null) Color.White else Color(0xFF888890),
                        fontSize = 13.sp,
                        fontFamily = SatoshiFontFamily
                    )

                    Spacer(Modifier.height(36.dp))

                    if (capturedKey != null) {
                        Button(
                            onClick = onComplete,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Text(
                                "Go to Badger Home",
                                color = Color(0xFF121214),
                                fontSize = 15.sp,
                                fontFamily = SatoshiFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color(0xFF121214), modifier = Modifier.size(18.dp))
                        }
                    } else {
                        TextButton(
                            onClick = {
                                prefs.edit()
                                    .putString("last_assigned_key_name", "Nothing Essential Key")
                                    .putInt("last_assigned_scancode", 250)
                                    .putBoolean(EssentialKeyService.DETECTION_ENABLED, false)
                                    .apply()
                                onComplete()
                            }
                        ) {
                            Text("Skip or use default Nothing Key", color = Color(0xFF888890), fontSize = 13.sp, fontFamily = SatoshiFontFamily)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun OnboardingFeatureRow(
    icon: ImageVector,
    title: String,
    desc: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF202028)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = Color(0xFF888890), fontSize = 12.sp, lineHeight = 16.sp, fontFamily = SatoshiFontFamily)
        }
    }
}

@Composable
private fun AccessibilitySetupScreen(onBack: () -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && isServiceEnabled(context)) {
                context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(EssentialKeyService.MAPPING_ENABLED, true).apply()
                onComplete()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF070709))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("Set up Badger", color = Color.White, fontSize = 21.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.size(74.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF222228)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.integral_logo_mark), contentDescription = "Badger", modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("One quick Android setting", color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Turn on Badger’s key service once. We’ll bring you straight back here when you press Back.", color = Color(0xFFB7B7BC), fontSize = 14.sp, lineHeight = 20.sp, fontFamily = SatoshiFontFamily, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        SetupInstruction("1", "Find Badger", "On the Accessibility page, tap ‘Badger key detection’ under Downloaded apps or Installed services.")
        Spacer(Modifier.height(10.dp))
        SetupInstruction("2", "Allow the service", "Turn on “Use ‘Badger key detection’ and review Android’s accessibility warning, then tap Allow.")
        Spacer(Modifier.height(10.dp))
        SetupInstruction("3", "Return here", "Use your phone’s Back gesture or Back button. Badger will detect access and finish setup automatically.")
        Spacer(Modifier.height(16.dp))
        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF141416))) {
            Column(Modifier.fillMaxWidth().padding(17.dp)) {
                Text("Why this access?", color = Color.White, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text("Badger uses this service to listen for button presses. When you use Get Content, it lets you select text on your screen to copy or search. Your data stays 100% on your device and is never shared.", color = Color(0xFFB7B7BC), fontSize = 12.sp, lineHeight = 17.sp, fontFamily = SatoshiFontFamily)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: android.content.ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF121214))
        ) {
            Text("Open Accessibility Settings", fontSize = 15.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onBack) { Text("I’ll do this later", color = Color(0xFFAAAAB0), fontFamily = SatoshiFontFamily) }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SetupInstruction(number: String, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF141416))
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF2C2C34)), contentAlignment = Alignment.Center) {
            Text(number, color = Accent, fontSize = 13.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(13.dp))
        Column {
            Text(title, color = Color.White, fontSize = 14.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = Color(0xFFB7B7BC), fontSize = 12.sp, lineHeight = 17.sp, fontFamily = SatoshiFontFamily)
        }
    }
}

@Composable
private fun PressLocationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE) }
    var originX by remember { mutableFloatStateOf(prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_X, EssentialKeyService.DEFAULT_ORIGIN_X)) }
    var originY by remember { mutableFloatStateOf(prefs.getFloat(EssentialKeyService.ANIMATION_ORIGIN_Y, EssentialKeyService.DEFAULT_ORIGIN_Y)) }

    fun update(x: Float, y: Float) {
        originX = x.coerceIn(0.02f, 0.98f)
        originY = y.coerceIn(0.02f, 0.98f)
        prefs.edit().putFloat(EssentialKeyService.ANIMATION_ORIGIN_X, originX)
            .putFloat(EssentialKeyService.ANIMATION_ORIGIN_Y, originY).apply()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF070709))
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("Press Location", color = Color.White, fontSize = 21.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
        }
        Text("Place animation origin", color = Color.White, fontSize = 24.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(7.dp))
        Text("Drag the amber marker to the Badge button position on your phone.", color = Color(0xFFAAAAB0), fontSize = 13.sp, lineHeight = 19.sp, fontFamily = SatoshiFontFamily, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(410.dp), contentAlignment = Alignment.Center) {
            val frameWidth = 224.dp
            val frameHeight = 394.dp
            Box(
                Modifier
                    .width(frameWidth)
                    .height(frameHeight)
                    .clip(RoundedCornerShape(36.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF202024), Color(0xFF0E0E10))))
                    .border(2.dp, Color(0xFF44444C), RoundedCornerShape(36.dp))
            ) {
                Column(Modifier.align(Alignment.TopCenter).padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DISPLAY PREVIEW", color = Color(0xFF77777F), fontSize = 9.sp, fontFamily = SatoshiFontFamily, letterSpacing = 1.7.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(30.dp).height(4.dp).clip(CircleShape).background(Color(0xFF424248)))
                }
                Box(Modifier.fillMaxSize().pointerInput(frameWidth, frameHeight) {
                    detectDragGestures(onDragStart = { point -> update(point.x / size.width, point.y / size.height) }, onDrag = { change, _ ->
                        update(change.position.x / size.width, change.position.y / size.height)
                        change.consume()
                    })
                })
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Box(Modifier.offset(x = maxWidth * originX - 9.dp, y = maxHeight * originY - 9.dp).size(18.dp).background(Accent, CircleShape).border(2.dp, Color(0xFF141416), CircleShape))
                }
                Text("DRAG TO REPOSITION", color = Color(0xFF77777F), fontSize = 9.sp, fontFamily = SatoshiFontFamily, letterSpacing = 1.3.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 19.dp))
            }
        }
        Text("HORIZONTAL  ·  ${(originX * 100).toInt()}%", color = Color(0xFFBDBDC3), fontSize = 11.sp, fontFamily = SatoshiFontFamily, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.Start))
        Slider(value = originX, onValueChange = { update(it, originY) }, valueRange = 0.02f..0.98f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0xFF38383E)))
        Text("VERTICAL  ·  ${(originY * 100).toInt()}%", color = Color(0xFFBDBDC3), fontSize = 11.sp, fontFamily = SatoshiFontFamily, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.Start))
        Slider(value = originY, onValueChange = { update(originX, it) }, valueRange = 0.02f..0.98f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0xFF38383E)))
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(17.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF161618))
        ) {
            Text("Save Location", fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = { update(EssentialKeyService.DEFAULT_ORIGIN_X, EssentialKeyService.DEFAULT_ORIGIN_Y) }) {
            Text("Reset to default", color = Color(0xFFAAAAB0), fontFamily = SatoshiFontFamily)
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun DetectionScreen(onBack: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val events by DetectionState.events.collectAsState()
    val prefs = context.getSharedPreferences(EssentialKeyService.PREFS, Context.MODE_PRIVATE)
    var selectedSignature by remember {
        mutableStateOf(prefs.getString(EssentialKeyService.TARGET_KEY_SIGNATURE, null))
    }
    fun isBadgerKey(event: DetectedKeyEvent) = event.isNothingEssentialKey || selectedSignature == keySignature(event)
    val latestDown = events.firstOrNull { it.action == "DOWN" && isBadgerKey(it) }
    var serviceEnabled by remember { mutableStateOf(isServiceEnabled(context)) }
    var listening by remember { mutableStateOf(prefs.getBoolean(EssentialKeyService.DETECTION_ENABLED, false)) }
    var captureComplete by remember { mutableStateOf(false) }

    LaunchedEffect(serviceEnabled) {
        if (serviceEnabled && selectedSignature == null) {
            captureComplete = false
            listening = true
            prefs.edit().putBoolean(EssentialKeyService.DETECTION_ENABLED, true).apply()
        }
    }

    LaunchedEffect(events.firstOrNull()?.eventTime, events.firstOrNull()?.action) {
        val newestEvent = events.firstOrNull()
        if (newestEvent?.action == "DOWN") {
            val sig = keySignature(newestEvent)
            selectedSignature = sig
            prefs.edit()
                .putString(EssentialKeyService.TARGET_KEY_SIGNATURE, sig)
                .putString("last_assigned_key_name", newestEvent.keyName)
                .putString("last_assigned_device", newestEvent.deviceName)
                .putInt("last_assigned_keycode", newestEvent.keyCode)
                .putInt("last_assigned_scancode", newestEvent.scanCode)
                .putBoolean(EssentialKeyService.DETECTION_ENABLED, false)
                .apply()
            listening = false
            captureComplete = true
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = isServiceEnabled(context)
                listening = prefs.getBoolean(EssentialKeyService.DETECTION_ENABLED, false)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val assignedKeyName = latestDown?.keyName ?: prefs.getString("last_assigned_key_name", "Nothing Essential Key") ?: "Nothing Essential Key"
    val assignedDevice = latestDown?.deviceName ?: prefs.getString("last_assigned_device", "gpio_keys / Nothing Button") ?: "gpio_keys"
    val assignedKeyCode = latestDown?.keyCode ?: prefs.getInt("last_assigned_keycode", 0)
    val assignedScanCode = latestDown?.scanCode ?: prefs.getInt("last_assigned_scancode", 766)

    Box(Modifier.fillMaxSize().background(Color(0xFF070709))) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                Text("Key Detection", color = Color.White, fontSize = 21.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
            }

            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                modifier = Modifier.border(1.dp, Color(0xFF24242A), RoundedCornerShape(22.dp))
            ) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            captureComplete -> "BUTTON CONNECTED"
                            listening -> "LISTENING FOR BUTTON PRESS"
                            else -> "READY TO TEST"
                        },
                        color = Accent,
                        fontSize = 11.sp,
                        fontFamily = SatoshiFontFamily,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(18.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(Color(0xFF1C1C22), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            KeyPressAnimation(eventTime = latestDown?.eventTime)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (listening) "Press any physical key now" else assignedKeyName,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontFamily = SatoshiFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (listening) "Badger will lock onto this key" else "Scan code $assignedScanCode · Bound to Badger",
                                color = Color(0xFF888892),
                                fontSize = 12.sp,
                                fontFamily = SatoshiFontFamily
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (!serviceEnabled) onSetup()
                                else {
                                    DetectionState.clear()
                                    captureComplete = false
                                    listening = true
                                    prefs.edit().putBoolean(EssentialKeyService.DETECTION_ENABLED, true).apply()
                                }
                            },
                            enabled = !listening,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF121214))
                        ) {
                            Text(if (!serviceEnabled) "Enable access" else if (listening) "Listening..." else "Calibrate Key", fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                        }
                        if (listening) {
                            OutlinedButton(
                                onClick = {
                                    listening = false
                                    prefs.edit().putBoolean(EssentialKeyService.DETECTION_ENABLED, false).apply()
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Stop", color = Color(0xFFAAAAB0), fontFamily = SatoshiFontFamily)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "ASSIGNED HARDWARE KEY",
                color = Color(0xFF888892),
                fontSize = 11.sp,
                fontFamily = SatoshiFontFamily,
                letterSpacing = 1.3.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF222228), RoundedCornerShape(20.dp))
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Accent.copy(alpha = 0.15f))
                                .border(1.dp, Accent.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.RadioButtonChecked,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                assignedKeyName,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontFamily = SatoshiFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Hardware Button · $assignedDevice",
                                color = Color(0xFF888890),
                                fontSize = 12.sp,
                                fontFamily = SatoshiFontFamily
                            )
                        }

                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Accent.copy(alpha = 0.15f))
                                .border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "ACTIVE",
                                color = Accent,
                                fontSize = 10.sp,
                                fontFamily = SatoshiFontFamily,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    HorizontalDivider(color = Color(0xFF222228), thickness = 1.dp)

                    Spacer(Modifier.height(14.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Key Code", color = Color(0xFF666670), fontSize = 11.sp, fontFamily = SatoshiFontFamily)
                            Spacer(Modifier.height(2.dp))
                            Text(assignedKeyCode.toString(), color = Color.White, fontSize = 13.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Medium)
                        }
                        Column {
                            Text("Scan Code", color = Color(0xFF666670), fontSize = 11.sp, fontFamily = SatoshiFontFamily)
                            Spacer(Modifier.height(2.dp))
                            Text(assignedScanCode.toString(), color = Accent, fontSize = 13.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Medium)
                        }
                        Column {
                            Text("Status", color = Color(0xFF666670), fontSize = 11.sp, fontFamily = SatoshiFontFamily)
                            Spacer(Modifier.height(2.dp))
                            Text("Bound", color = Color(0xFF4CAF50), fontSize = 13.sp, fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Medium)
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        "Badger binds directly to this physical button. When pressed, Badger executes your active gesture without retaining click logs or event histories.",
                        color = Color(0xFF888890),
                        fontSize = 12.sp,
                        fontFamily = SatoshiFontFamily,
                        lineHeight = 17.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    if (selectedSignature != null) {
                        TextButton(
                            onClick = {
                                selectedSignature = null
                                prefs.edit()
                                    .remove(EssentialKeyService.TARGET_KEY_SIGNATURE)
                                    .remove("last_assigned_key_name")
                                    .remove("last_assigned_device")
                                    .remove("last_assigned_keycode")
                                    .remove("last_assigned_scancode")
                                    .apply()
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                "Reset to Default (Nothing Essential Key)",
                                color = Accent,
                                fontSize = 12.sp,
                                fontFamily = SatoshiFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyPressAnimation(eventTime: Long?) {
    val scale = remember { Animatable(1f) }
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(eventTime) {
        if (eventTime != null) {
            ripple.snapTo(0.6f)
            scale.animateTo(0.88f, tween(85))
            scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 480f))
            ripple.animateTo(0f, tween(480))
        }
    }
    Box(Modifier.size(94.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(48.dp, 66.dp).graphicsLayer(scaleX = scale.value, scaleY = scale.value).background(Color(0xFF24242A), RoundedCornerShape(18.dp)).border(1.dp, Color(0xFF333338), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).background(Accent, CircleShape))
        }
    }
}

private fun keySignature(event: DetectedKeyEvent): String = "${event.deviceDescriptor}|${event.keyCode}:${event.scanCode}"

private fun ensureServiceEnabled(context: Context) {
    try {
        val cr = context.contentResolver
        val fullComponent = "${context.packageName}/${EssentialKeyService::class.java.name}"
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val services = current.split(':').filter { it.isNotBlank() }.toMutableSet()
        val isAlreadyInList = services.any { str ->
            val cn = ComponentName.unflattenFromString(str)
            cn?.packageName == context.packageName
        }
        if (!isAlreadyInList) {
            services.add(fullComponent)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
        }
        Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
    } catch (_: Exception) {}
}

private fun isServiceEnabled(context: Context): Boolean {
    ensureServiceEnabled(context)

    // 1. Check live active AccessibilityManager service list
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (am != null) {
        val running = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (running.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }) {
            return true
        }
    }

    // 2. Check secure settings with unflattenFromString (matches full & short forms)
    val myPackage = context.packageName
    val myClass = EssentialKeyService::class.java.name
    val enabledSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    val isEnabledInSettings = enabledSetting.split(':').any { str ->
        val cn = ComponentName.unflattenFromString(str)
        cn != null && cn.packageName == myPackage && (cn.className == myClass || cn.className == ".accessibility.EssentialKeyService")
    }

    if (isEnabledInSettings) {
        val masterSwitch = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        if (masterSwitch == 1) return true
    }
    return isEnabledInSettings
}


private fun isNotificationAccessGranted(context: Context): Boolean {
    val listeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return listeners?.contains(context.packageName) == true
}

@Composable
private fun VoiceNotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var recordings by remember { mutableStateOf(VoiceRecordingManager.getRecordings(context)) }
    var playingFile by remember { mutableStateOf<java.io.File?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    val mediaPlayer = remember { MediaPlayer() }
    val haptics = LocalHapticFeedback.current

    // Playback progress loop
    LaunchedEffect(isPlaying, playingFile) {
        while (isPlaying && playingFile != null) {
            try {
                currentPositionMs = mediaPlayer.currentPosition.toLong()
                totalDurationMs = mediaPlayer.duration.toLong().coerceAtLeast(1L)
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(100L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.release()
            } catch (_: Exception) {}
        }
    }

    fun playPause(recording: VoiceRecording) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        try {
            if (playingFile?.absolutePath == recording.file.absolutePath) {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                    isPlaying = false
                } else {
                    mediaPlayer.start()
                    isPlaying = true
                }
            } else {
                mediaPlayer.reset()
                mediaPlayer.setDataSource(recording.file.absolutePath)
                mediaPlayer.prepare()
                mediaPlayer.start()
                playingFile = recording.file
                isPlaying = true
                totalDurationMs = recording.durationMs
                mediaPlayer.setOnCompletionListener {
                    isPlaying = false
                    currentPositionMs = 0L
                }
            }
        } catch (_: Exception) {
            Toast.makeText(context, "Could not play recording", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareRecording(recording: VoiceRecording) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "com.humnspace.integral.fileprovider",
                recording.file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Voice Note"))
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    var recordingToExport by remember { mutableStateOf<VoiceRecording?>(null) }
    var exportCustomName by remember { mutableStateOf("") }
    var recordingToRename by remember { mutableStateOf<VoiceRecording?>(null) }
    var renameCustomName by remember { mutableStateOf("") }

    fun deleteRecording(recording: VoiceRecording) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (playingFile?.absolutePath == recording.file.absolutePath) {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.reset()
            } catch (_: Exception) {}
            playingFile = null
            isPlaying = false
        }
        VoiceRecordingManager.deleteRecording(recording.file)
        recordings = VoiceRecordingManager.getRecordings(context)
        Toast.makeText(context, "Voice note deleted", Toast.LENGTH_SHORT).show()
    }

    if (recordingToExport != null) {
        val rec = recordingToExport!!
        AlertDialog(
            onDismissRequest = { recordingToExport = null },
            title = {
                Text(
                    "Export Voice Note",
                    color = Color.White,
                    fontFamily = SatoshiFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        "Export this clip to your Downloads folder with an optional custom name.",
                        color = Color(0xFFB0B0B8),
                        fontFamily = SatoshiFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = exportCustomName,
                        onValueChange = { exportCustomName = it },
                        placeholder = { Text("Custom name (optional)", color = Color(0xFF666670), fontSize = 13.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Color(0xFF2A2A32),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Accent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val savedName = VoiceRecordingManager.exportToDownloads(context, rec, exportCustomName)
                        if (savedName != null) {
                            Toast.makeText(context, "Saved to Downloads: $savedName", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Failed to export recording", Toast.LENGTH_SHORT).show()
                        }
                        recordingToExport = null
                        exportCustomName = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF121214)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Export", fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        recordingToExport = null
                        exportCustomName = ""
                    }
                ) {
                    Text("Cancel", color = Color(0xFF888890), fontFamily = SatoshiFontFamily)
                }
            },
            containerColor = Color(0xFF18181C),
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (recordingToRename != null) {
        val rec = recordingToRename!!
        AlertDialog(
            onDismissRequest = { recordingToRename = null },
            title = {
                Text(
                    "Rename Voice Note",
                    color = Color.White,
                    fontFamily = SatoshiFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column {
                    Text(
                        "Enter a new name for this recording clip.",
                        color = Color(0xFFB0B0B8),
                        fontFamily = SatoshiFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = renameCustomName,
                        onValueChange = { renameCustomName = it },
                        placeholder = { Text("New name", color = Color(0xFF666670), fontSize = 13.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Color(0xFF2A2A32),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Accent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val renamed = VoiceRecordingManager.renameRecording(rec.file, renameCustomName)
                        if (renamed != null) {
                            recordings = VoiceRecordingManager.getRecordings(context)
                            Toast.makeText(context, "Renamed to: ${renamed.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to rename", Toast.LENGTH_SHORT).show()
                        }
                        recordingToRename = null
                        renameCustomName = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF121214)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save", fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        recordingToRename = null
                        renameCustomName = ""
                    }
                ) {
                    Text("Cancel", color = Color(0xFF888890), fontFamily = SatoshiFontFamily)
                }
            },
            containerColor = Color(0xFF18181C),
            shape = RoundedCornerShape(20.dp)
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(14.dp))

        // Top Bar
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF18181C))
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Spacer(Modifier.width(16.dp))

            Column {
                Text(
                    "Voice Notes",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontFamily = SatoshiFontFamily,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (recordings.isEmpty()) "0 voice notes" else "${recordings.size} saved voice note${if (recordings.size > 1) "s" else ""}",
                    color = Color(0xFF888890),
                    fontSize = 12.sp,
                    fontFamily = SatoshiFontFamily,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        if (recordings.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF18181C))
                            .border(1.dp, Color(0xFF282830), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    Text(
                        "No Voice Notes Yet",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "Voice notes recorded using the Voice Recorder action or Feature Tray will automatically appear here.",
                        color = Color(0xFF888890),
                        fontSize = 13.sp,
                        fontFamily = SatoshiFontFamily,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 19.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(recordings, key = { it.file.absolutePath }) { rec ->
                    val isCurrentTrack = playingFile?.absolutePath == rec.file.absolutePath
                    val isCurrentPlaying = isCurrentTrack && isPlaying

                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                if (isCurrentTrack) Accent.copy(alpha = 0.5f) else Color(0xFF222228),
                                RoundedCornerShape(22.dp)
                            )
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(18.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Play / Pause button
                                Box(
                                    Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(if (isCurrentPlaying) Accent else Color(0xFF222228))
                                        .clickable { playPause(rec) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isCurrentPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isCurrentPlaying) "Pause" else "Play",
                                        tint = if (isCurrentPlaying) Color(0xFF121214) else Accent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(Modifier.width(14.dp))

                                Column(Modifier.weight(1f)) {
                                    Text(
                                        rec.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontFamily = SatoshiFontFamily,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        "${VoiceRecordingManager.formatDuration(rec.durationMs)} · ${String.format(java.util.Locale.US, "%.1f", rec.sizeBytes / 1024f / 1024f)} MB",
                                        color = Color(0xFF888890),
                                        fontSize = 12.sp,
                                        fontFamily = SatoshiFontFamily
                                    )
                                }

                                // Rename button
                                IconButton(
                                    onClick = {
                                        renameCustomName = rec.file.nameWithoutExtension
                                        recordingToRename = rec
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Rename",
                                        tint = Color(0xFFB0B0B8),
                                        modifier = Modifier.size(17.dp)
                                    )
                                }

                                // Export button
                                IconButton(
                                    onClick = {
                                        exportCustomName = ""
                                        recordingToExport = rec
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.FileDownload,
                                        contentDescription = "Export to Downloads",
                                        tint = Color(0xFF888890),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                // Share button
                                IconButton(
                                    onClick = { shareRecording(rec) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Share",
                                        tint = Color(0xFF888890),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                // Delete button
                                IconButton(
                                    onClick = { deleteRecording(rec) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color(0xFF888890),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            // Active Playback Progress Bar
                            if (isCurrentTrack) {
                                Spacer(Modifier.height(14.dp))
                                val progress = if (totalDurationMs > 0) (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f) else 0f
                                Column(Modifier.fillMaxWidth()) {
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = Accent,
                                        trackColor = Color(0xFF24242A)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            VoiceRecordingManager.formatDuration(currentPositionMs),
                                            color = Accent,
                                            fontSize = 11.sp,
                                            fontFamily = SatoshiFontFamily
                                        )
                                        Text(
                                            VoiceRecordingManager.formatDuration(rec.durationMs),
                                            color = Color(0xFF888890),
                                            fontSize = 11.sp,
                                            fontFamily = SatoshiFontFamily
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemindersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var reminders by remember { mutableStateOf(ReminderManager.getReminders(context)) }

    fun refresh() {
        reminders = ReminderManager.getReminders(context)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF070709))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Reminders",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${reminders.size} active",
                        color = Color(0xFF888890),
                        fontSize = 12.sp,
                        fontFamily = SatoshiFontFamily
                    )
                }
                if (reminders.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            reminders.forEach { ReminderManager.deleteReminder(context, it.id) }
                            refresh()
                        }
                    ) {
                        Text(
                            "Clear All",
                            color = Accent,
                            fontSize = 13.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (reminders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF141416))
                                .border(1.dp, Color(0xFF24242A), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Alarm,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(Modifier.height(18.dp))

                        Text(
                            "No Reminders Yet",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "Reminders created with 'Say it' (like 'remind me to call John at 5pm') will appear here and notify you at the right time.",
                            color = Color(0xFF888890),
                            fontSize = 13.sp,
                            fontFamily = SatoshiFontFamily,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 19.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(reminders, key = { it.id }) { reminder ->
                        val timeStr = remember(reminder.triggerTimestamp) {
                            SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(reminder.triggerTimestamp))
                        }
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFF222228), RoundedCornerShape(20.dp))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Done / Complete check button
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1C1C22))
                                        .border(1.dp, Accent.copy(alpha = 0.5f), CircleShape)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            ReminderManager.deleteReminder(context, reminder.id)
                                            refresh()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Done",
                                        tint = Accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        reminder.title,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontFamily = SatoshiFontFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.AccessTime,
                                            contentDescription = null,
                                            tint = Accent,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            timeStr,
                                            color = Color(0xFF888890),
                                            fontSize = 12.sp,
                                            fontFamily = SatoshiFontFamily
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        ReminderManager.deleteReminder(context, reminder.id)
                                        refresh()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Delete",
                                        tint = Color(0xFF888890),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedCropsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var crops by remember { mutableStateOf(SavedCropsManager.getSavedCrops(context)) }
    var viewingFile by remember { mutableStateOf<java.io.File?>(null) }

    fun refresh() {
        crops = SavedCropsManager.getSavedCrops(context)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF070709))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Saved Crops",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${crops.size} images",
                        color = Color(0xFF888890),
                        fontSize = 12.sp,
                        fontFamily = SatoshiFontFamily
                    )
                }
            }

            if (crops.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF141416))
                                .border(1.dp, Color(0xFF24242A), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Crop,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(Modifier.height(18.dp))

                        Text(
                            "No Saved Crops Yet",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontFamily = SatoshiFontFamily,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "When you use Circle to Crop in Get-Content, tap Save to keep extracts organized right here in Badger.",
                            color = Color(0xFF888890),
                            fontSize = 13.sp,
                            fontFamily = SatoshiFontFamily,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 19.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(crops, key = { it.absolutePath }) { file ->
                        val bitmap = remember(file.absolutePath, file.lastModified()) {
                            try {
                                BitmapFactory.decodeFile(file.absolutePath)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        val dateStr = remember(file.lastModified()) {
                            SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(file.lastModified()))
                        }
                        val sizeStr = remember(file.length()) {
                            String.format(Locale.US, "%.1f KB", file.length() / 1024f)
                        }

                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF141416)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFF222228), RoundedCornerShape(20.dp))
                                .clickable { viewingFile = file }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Thumbnail
                                Box(
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF0C0C0E))
                                        .border(1.dp, Color(0xFF24242A), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Crop thumbnail",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Image,
                                            contentDescription = null,
                                            tint = Color(0xFF555560)
                                        )
                                    }
                                }

                                Spacer(Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        file.name,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontFamily = SatoshiFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        "$dateStr · $sizeStr",
                                        color = Color(0xFF888890),
                                        fontSize = 11.sp,
                                        fontFamily = SatoshiFontFamily
                                    )
                                }

                                // Share button
                                IconButton(
                                    onClick = { SavedCropsManager.shareCrop(context, file) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Share",
                                        tint = Color(0xFF888890),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }

                                // Delete button
                                IconButton(
                                    onClick = {
                                        SavedCropsManager.deleteCrop(file)
                                        refresh()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color(0xFF888890),
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Full Screen Viewer Dialog
        viewingFile?.let { file ->
            val fullBitmap = remember(file.absolutePath) {
                try {
                    BitmapFactory.decodeFile(file.absolutePath)
                } catch (_: Exception) {
                    null
                }
            }
            Dialog(onDismissRequest = { viewingFile = null }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF141416))
                        .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                        .padding(16.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Crop Preview",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontFamily = SatoshiFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = { viewingFile = null },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        if (fullBitmap != null) {
                            Image(
                                bitmap = fullBitmap.asImageBitmap(),
                                contentDescription = "Full Preview",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    SavedCropsManager.shareCrop(context, file)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF121214))
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Share", fontFamily = SatoshiFontFamily, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    SavedCropsManager.deleteCrop(file)
                                    viewingFile = null
                                    refresh()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333338))
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFE57373), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Delete", color = Color(0xFFE57373), fontFamily = SatoshiFontFamily)
                            }
                        }
                    }
                }
            }
        }
    }
}

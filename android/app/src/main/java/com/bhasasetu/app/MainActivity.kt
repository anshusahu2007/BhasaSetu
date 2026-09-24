package com.bhasasetu.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bhasasetu.app.data.TranslationLoader
import com.bhasasetu.app.data.TranslationRepository
import com.bhasasetu.app.data.local.AppDatabase
import com.bhasasetu.app.data.remote.RetrofitClient
import com.bhasasetu.app.ui.screens.HistoryScreen
import com.bhasasetu.app.ui.theme.BhasaSetuTheme
import com.bhasasetu.app.ui.viewmodel.TranslationViewModel
import com.bhasasetu.app.ui.viewmodel.TranslationViewModelFactory
import java.util.Locale

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(this)
        val repository = TranslationRepository(
            context = this,
            translationDao = database.translationDao(),
            translationHistoryDao = database.translationHistoryDao(),
            translationApi = RetrofitClient.translationApi
        )
        val loader = TranslationLoader(this)
        val viewModelFactory = TranslationViewModelFactory(repository, loader)

        setContent {
            BhasaSetuTheme(darkTheme = false, dynamicColor = false) {
                val viewModel: TranslationViewModel = viewModel(
                    factory = viewModelFactory
                )
                var currentScreen by remember { mutableStateOf("main") }

                if (currentScreen == "history") {
                    BackHandler { currentScreen = "main" }
                    
                    val context = LocalContext.current
                    var tts: TextToSpeech? by remember { mutableStateOf(null) }
                    DisposableEffect(context) {
                        val ttsInstance = TextToSpeech(context) { }
                        tts = ttsInstance
                        onDispose {
                            ttsInstance.stop()
                            ttsInstance.shutdown()
                        }
                    }

                    val speak = { text: String, language: String ->
                        tts?.let {
                            val locale = when (language) {
                                "Hindi" -> Locale.forLanguageTag("hi-IN")
                                "Santali" -> Locale.forLanguageTag("sat-IN")
                                "Ho" -> Locale.forLanguageTag("hoc-IN")
                                "Mundari" -> Locale.forLanguageTag("unr-IN")
                                else -> Locale.getDefault()
                            }
                            it.setLanguage(locale)
                            it.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    }

                    HistoryScreen(
                        viewModel = viewModel,
                        onBack = { currentScreen = "main" },
                        onItemClick = { text, lang ->
                            if (lang == "Hindi") {
                                viewModel.onTabSelected(0)
                                viewModel.onHindiTextChanged(text)
                            } else {
                                viewModel.onTabSelected(1)
                                viewModel.onLanguageSelected(lang)
                                viewModel.onStudentTextChanged(text)
                            }
                            currentScreen = "main"
                        },
                        onSpeak = { text, lang -> speak(text, lang) }
                    )
                } else {
                    BhasaSetuMainContent(viewModel, onOpenHistory = { currentScreen = "history" })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BhasaSetuMainContent(viewModel: TranslationViewModel, onOpenHistory: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var isListening by remember { mutableStateOf(false) }
    var pendingRecognition by remember { mutableStateOf(false) }
    var currentSpeechModeIsStudent by remember { mutableStateOf(false) }

    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    DisposableEffect(context) {
        val ttsInstance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) Log.d("TTS", "Initialized")
        }
        tts = ttsInstance
        onDispose {
            ttsInstance.stop()
            ttsInstance.shutdown()
        }
    }

    val speak = { text: String, language: String ->
        tts?.let {
            val locale = when (language) {
                "Hindi" -> Locale.forLanguageTag("hi-IN")
                "Santali" -> Locale.forLanguageTag("sat-IN")
                "Ho" -> Locale.forLanguageTag("hoc-IN")
                "Mundari" -> Locale.forLanguageTag("unr-IN")
                else -> Locale.getDefault()
            }
            it.setLanguage(locale)
            it.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    // Hybrid Speech Recognizer Logic
    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val actType = connectivityManager.getNetworkCapabilities(network) ?: return false
        return actType.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    val speechRecognizer = remember {
        val isOffline = !isNetworkAvailable()
        Log.d("VOICE", "Internet available: ${!isOffline}")
        
        val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            Log.d("VOICE", "On-device recognizer available = true")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            Log.d("VOICE", "On-device recognizer available = false")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer
    }

    val recognitionListener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { 
                Log.d("SpeechRecog", "Ready for speech")
                isListening = true 
            }
            override fun onEndOfSpeech() { 
                Log.d("SpeechRecog", "End of speech")
                isListening = false 
            }
            override fun onError(error: Int) {
                Log.e("VOICE", "Speech recognition error: $error")
                isListening = false
                val isOffline = !isNetworkAvailable()
                val message = when (error) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        if (isOffline) {
                            Log.w("VOICE", "Offline speech model unavailable or network required")
                            "Offline Hindi voice recognition is not available on this device. Please download Hindi offline speech recognition from your phone's Speech Services settings or enter text manually."
                        } else {
                            "Network error. Please check your internet connection."
                        }
                    }
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized. Please try again."
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone error. Please check access."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Voice input not supported for this language."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service is busy. Please wait."
                    else -> "Speech recognition error. Please try again."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d("SpeechRecog", "Result: ${matches[0]}")
                    if (currentSpeechModeIsStudent) {
                        viewModel.onStudentSpeechRecognized(matches[0])
                    } else {
                        viewModel.onHindiTextChanged(matches[0], source = "voice")
                    }
                }
            }
            override fun onBeginningOfSpeech() { Log.d("SpeechRecog", "Beginning of speech") }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    DisposableEffect(Unit) {
        speechRecognizer.setRecognitionListener(recognitionListener)
        onDispose { speechRecognizer.destroy() }
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted && pendingRecognition) {
            pendingRecognition = false
            // The LaunchedEffect below will trigger startSpeech
        }
    }

    val startSpeech = { isStudent: Boolean ->
        currentSpeechModeIsStudent = isStudent
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val languageCode = if (isStudent) {
                when (uiState.selectedLanguage) {
                    "Santali" -> "sat-IN"
                    "Ho" -> "hoc-IN"
                    "Mundari" -> "unr-IN"
                    else -> "hi-IN"
                }
            } else {
                "hi-IN"
            }
            
            val isOffline = !isNetworkAvailable()
            Log.d("VOICE", "Starting recognition. Lang: $languageCode, Offline Mode: $isOffline")
            if (isOffline) {
                Log.d("VOICE", "Using on-device recognizer (EXTRA_PREFER_OFFLINE = true)")
            } else {
                Log.d("VOICE", "Using online recognizer")
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                if (isOffline) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }
            speechRecognizer.startListening(intent)
        } else {
            pendingRecognition = true
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(pendingRecognition) {
        if (pendingRecognition && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingRecognition = false
            startSpeech(currentSpeechModeIsStudent)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { BhasaSetuHeader(onOpenHistory) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            OfflineStatusBanner()

            ModeSelector(
                selectedTab = uiState.selectedTab,
                onTabSelected = { viewModel.onTabSelected(it) }
            )

            if (uiState.selectedTab == 0) {
                TeacherModeContent(
                    viewModel = viewModel,
                    isListening = isListening && !currentSpeechModeIsStudent,
                    onStartSpeech = { startSpeech(false) },
                    onSpeakResult = { speak(uiState.translation, uiState.selectedLanguage) }
                )
            } else {
                StudentModeContent(
                    viewModel = viewModel,
                    isListening = isListening && currentSpeechModeIsStudent,
                    onStartSpeech = { startSpeech(true) },
                    onSpeakResult = { speak(uiState.studentHindiResult, "Hindi") }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BhasaSetuHeader(onOpenHistory: () -> Unit) {
    CenterAlignedTopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32), 
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Bhasa Setu",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Classroom Language Bridge",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = { /* Menu */ }) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }
        },
        actions = {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Default.History, contentDescription = "History")
            }
            IconButton(onClick = { /* Settings */ }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
fun OfflineStatusBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFE8F5E9),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = Color(0xFF2E7D32)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Offline dictionary available • 100+ classroom phrases",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF2E7D32),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ModeSelector(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.secondary),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModeTab(
            label = "Teacher Mode",
            icon = Icons.Default.School,
            isSelected = selectedTab == 0,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(0) }
        )
        ModeTab(
            label = "Student Mode",
            icon = Icons.Default.School, 
            isSelected = selectedTab == 1,
            modifier = Modifier.weight(1f),
            onClick = { onTabSelected(1) }
        )
    }
}

@Composable
fun ModeTab(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "tabBackground"
    )
    val contentColor by animateColorAsState(
        if (isSelected) Color.White else MaterialTheme.colorScheme.primary,
        label = "tabContent"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(32.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

@Composable
fun TeacherModeContent(
    viewModel: TranslationViewModel,
    isListening: Boolean,
    onStartSpeech: () -> Unit,
    onSpeakResult: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Teacher Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("Speak or type in Hindi", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    IconButton(onClick = { /* Help */ }, modifier = Modifier.size(32.dp)) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Text("Target Language", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                
                LanguageSelector(
                    selected = uiState.selectedLanguage,
                    onSelected = { viewModel.onLanguageSelected(it) }
                )

                Text("Hindi Input", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                
                Box {
                    OutlinedTextField(
                        value = uiState.hindiText,
                        onValueChange = { viewModel.onHindiTextChanged(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Type or speak in Hindi...") },
                        minLines = 4,
                        shape = RoundedCornerShape(16.dp),
                        trailingIcon = {
                            Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                if (uiState.hindiText.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.onHindiTextChanged("") }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                                IconButton(onClick = onStartSpeech) {
                                    Icon(Icons.Default.Mic, contentDescription = "Speak", tint = if (isListening) Color.Red else MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    )
                    Text(
                        "${uiState.hindiText.length}/500",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }

                QuickPhraseChips(onChipClick = { 
                    viewModel.onHindiTextChanged(it)
                    viewModel.translate()
                })

                TranslateButton(
                    isLoading = uiState.isLoading,
                    onClick = { viewModel.translate() }
                )
            }
        }

        AnimatedVisibility(visible = uiState.translation.isNotEmpty(), enter = fadeIn() + expandVertically()) {
            TranslationResultCard(
                result = uiState.translation,
                subtitle = "${uiState.selectedLanguage} (Vernacular Output)",
                status = uiState.translationStatus,
                phonetic = uiState.phonetic,
                onSpeak = onSpeakResult
            )
        }
    }
}

@Composable
fun StudentModeContent(
    viewModel: TranslationViewModel,
    isListening: Boolean,
    onStartSpeech: () -> Unit,
    onSpeakResult: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Student Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    Text("Communicate back in your language", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }

                Text("Your Language", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                
                LanguageSelector(
                    selected = uiState.selectedLanguage,
                    onSelected = { viewModel.onLanguageSelected(it) }
                )

                if (uiState.selectedLanguage == "Mundari" || uiState.selectedLanguage == "Ho") {
                    Text(
                        "${uiState.selectedLanguage} voice input is not supported on this device. Please enter text manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                OutlinedTextField(
                    value = uiState.studentRecognizedText,
                    onValueChange = { viewModel.onStudentTextChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${uiState.selectedLanguage} Input") },
                    minLines = 3,
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        IconButton(onClick = onStartSpeech) {
                            Icon(Icons.Default.Mic, contentDescription = "Speak", tint = if (isListening) Color.Red else MaterialTheme.colorScheme.secondary)
                        }
                    }
                )

                Button(
                    onClick = { viewModel.translateToHindi() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isStudentLoading && uiState.studentRecognizedText.isNotEmpty(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (uiState.isStudentLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                    } else {
                        Text("Translate to Hindi")
                    }
                }
            }
        }

        AnimatedVisibility(visible = uiState.studentHindiResult.isNotEmpty(), enter = fadeIn() + expandVertically()) {
            TranslationResultCard(
                result = uiState.studentHindiResult,
                subtitle = "Hindi Translation",
                status = uiState.studentTranslationStatus,
                phonetic = null,
                onSpeak = onSpeakResult
            )
        }
    }
}

@Composable
fun LanguageSelector(selected: String, onSelected: (String) -> Unit) {
    val languages = listOf("Santali", "Mundari", "Ho")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        languages.forEach { lang ->
            val isSelected = selected == lang
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { onSelected(lang) }
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = lang,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
fun QuickPhraseChips(onChipClick: (String) -> Unit) {
    val phrases = listOf("नमस्ते", "बैठ जाओ", "ध्यान से सुनो", "दोहराओ")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        phrases.forEach { phrase ->
            Surface(
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onChipClick(phrase) },
                color = MaterialTheme.colorScheme.secondary,
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = phrase,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun TranslateButton(isLoading: Boolean, onClick: () -> Unit) {
    val gradient = Brush.horizontalGradient(listOf(Color(0xFF6C3FE8), Color(0xFF8E63FF)))
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(gradient)
            .clickable(enabled = !isLoading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Translate, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Translate", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun TranslationResultCard(
    result: String,
    subtitle: String,
    status: String,
    phonetic: String?,
    onSpeak: () -> Unit
) {
    val clipboardManager = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column {
                    Text("Translation Result", style = MaterialTheme.typography.labelLarge, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Row {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("translation", result)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onSpeak) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speak", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Text(
                text = result,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start
            )

            if (phonetic != null && phonetic.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondary,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Pronunciation", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(phonetic, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (status.contains("offline")) Color(0xFFE8F5E9) else Color(0xFFE3F2FD))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (status.contains("offline")) "Offline • Room Database" else "Online • AI Translation",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.contains("offline")) Color(0xFF2E7D32) else Color(0xFF1976D2),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

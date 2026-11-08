package com.humnspace.integral

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

class VoiceTypingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VoiceTypingScreen() }
    }
}

@androidx.compose.runtime.Composable
private fun VoiceTypingScreen() {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        delay(220)
        focusRequester.requestFocus()
        keyboard?.show()
    }
    MaterialTheme {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF0D0D0F)).padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Voice typing", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text("Use the microphone on your default keyboard to dictate. Badger does not record audio.", color = Color(0xFFB7B7BC), fontSize = 14.sp, lineHeight = 20.sp)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(focusRequester),
                placeholder = { Text("Tap the keyboard microphone to start") },
                shape = RoundedCornerShape(18.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (text.isNotBlank()) {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Voice typing", text))
                    }
                    (context as? ComponentActivity)?.finish()
                }, modifier = Modifier.weight(1f)) { Text("Copy and close") }
                Button(onClick = { (context as? ComponentActivity)?.finish() }, modifier = Modifier.weight(1f)) { Text("Close") }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

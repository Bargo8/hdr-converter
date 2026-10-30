package com.jaccksallas.hdrconverter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { HdrScreen() }
            }
        }
    }
}

@Composable
fun HdrScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var inputName by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("pilih video dulu") }
    var output by remember { mutableStateOf<File?>(null) }
    var sessionId by remember { mutableStateOf<Long?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            inputUri = uri
            inputName = displayName(context, uri)
            output = null
            status = "siap dikonversi"
        }
    }

    fun start() {
        val uri = inputUri ?: return
        running = true
        cancelled = false
        progress = 0f
        output = null
        status = "nyiapin file..."

        scope.launch {
            var input: File? = null
            try {
                input = withContext(Dispatchers.IO) { copyToCache(context, uri) }
                status = "konversi..."
                val inputFile = input
                val durationMs = withContext(Dispatchers.IO) {
                    probeDurationMs(inputFile.absolutePath)
                }
                val out = outputFileFor(context, inputName)
                val ok = runHdr(
                    input = inputFile.absolutePath,
                    output = out.absolutePath,
                    durationMs = durationMs,
                    onProgress = { p -> scope.launch(Dispatchers.Main) { progress = p } },
                    onSession = { id -> sessionId = id },
                )
                status = when {
                    ok -> { output = out; "selesai: ${out.name}" }
                    cancelled -> "dibatalin"
                    else -> "gagal. cek konversi di log"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status = "error: ${e.message}"
            } finally {
                input?.delete()
                running = false
                sessionId = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("HDR Converter", style = MaterialTheme.typography.headlineMedium)
        Text(
            inputName ?: "belum ada video",
            style = MaterialTheme.typography.bodyLarge,
        )

        Button(
            onClick = { picker.launch("video/*") },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("pilih video") }

        Button(
            onClick = { start() },
            enabled = inputUri != null && !running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("konversi ke HDR") }

        if (running) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${(progress * 100).toInt()}%")
            OutlinedButton(
                onClick = {
                    cancelled = true
                    sessionId?.let { FFmpegKit.cancel(it) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("batalin") }
        }

        Text(status, style = MaterialTheme.typography.bodyMedium)

        output?.let { file ->
            Button(
                onClick = {
                    val fileUri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "share video"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("share") }
        }
    }
}

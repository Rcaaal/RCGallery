package com.example.rcgallery.ui.screen

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

enum class MediaImportPlatform {
    DOUYIN,
    BILIBILI,
    YOUTUBE,
    X,
}

data class MediaImportRoute(
    val platform: MediaImportPlatform,
    val input: String,
)

private val sharedUrlPattern = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
private val bilibiliIdPattern = Regex("\\b(?:bv[0-9a-z]+|av\\d+)\\b", RegexOption.IGNORE_CASE)

fun detectMediaImportRoute(input: String): MediaImportRoute? {
    val normalizedInput = input.trim()
    if (normalizedInput.isBlank()) return null

    val urls = sharedUrlPattern.findAll(normalizedInput)
        .map { it.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}', '。', '，', '！', '？') }

    for (url in urls) {
        val host = url.lowercase(Locale.ROOT)
        val platform = when {
            "douyin.com" in host -> MediaImportPlatform.DOUYIN
            "bilibili.com" in host || "b23.tv" in host -> MediaImportPlatform.BILIBILI
            "youtube.com" in host || "youtu.be" in host -> MediaImportPlatform.YOUTUBE
            "x.com" in host || "twitter.com" in host -> MediaImportPlatform.X
            else -> null
        }
        if (platform != null) return MediaImportRoute(platform, normalizedInput)
    }

    return if (bilibiliIdPattern.containsMatchIn(normalizedInput)) {
        MediaImportRoute(MediaImportPlatform.BILIBILI, normalizedInput)
    } else {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaImportScreen(
    onDismiss: () -> Unit,
    onRouteDetected: (MediaImportRoute) -> Unit,
    onShowHistory: () -> Unit,
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun startImport() {
        val route = detectMediaImportRoute(input)
        if (route == null) {
            error = "未识别到抖音、哔哩哔哩或 YouTube 链接"
        } else {
            error = null
            onRouteDetected(route)
        }
    }

    BackHandler(onBack = onDismiss)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("媒体导入") },
                navigationIcon = { TextButton(onClick = onDismiss) { Text("返回") } },
                actions = { TextButton(onClick = onShowHistory) { Text("历史") } },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "粘贴抖音、哔哩哔哩或 YouTube 分享链接，应用会自动选择对应导入器。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("媒体分享链接") },
                minLines = 3,
                maxLines = 5,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        input = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        error = null
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("粘贴") }
                Button(
                    onClick = ::startImport,
                    modifier = Modifier.weight(1f),
                ) { Text("开始识别") }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

package com.example.rcgallery.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * 添加 SMB 设备对话框 — 输入 IP 地址或主机名。
 * MVP 阶段固定匿名访问。
 */
@Composable
fun SmbConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (host: String) -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null
) {
    var host by remember { mutableStateOf("") }
    var hostError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("添加网络设备") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "输入 PC 的局域网 IP 地址，例如 192.168.1.5",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        hostError = null
                    },
                    label = { Text("IP 地址") },
                    placeholder = { Text("192.168.1.5") },
                    singleLine = true,
                    isError = hostError != null,
                    supportingText = hostError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            if (host.isNotBlank()) {
                                onConnect(host.trim())
                            }
                        }
                    ),
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isLoading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "正在连接 $host ...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = host.trim()
                    if (trimmed.isEmpty()) {
                        hostError = "请输入 IP 地址"
                        return@Button
                    }
                    onConnect(trimmed)
                },
                enabled = !isLoading && host.isNotBlank()
            ) {
                Text("连接")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("取消")
            }
        }
    )
}

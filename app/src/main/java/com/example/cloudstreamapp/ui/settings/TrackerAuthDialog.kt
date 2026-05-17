package com.example.cloudstreamapp.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource

@Composable
fun TrackerAuthDialog(
    source: TorrentSource,
    loginInProgress: Boolean,
    loginError: String?,
    onLogin: (username: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!loginInProgress) onDismiss() },
        title = { Text("Вход в ${source.displayName}") },
        text = {
            Column {
                Text("Введите данные аккаунта ${source.displayName}. Учётные данные хранятся только на устройстве.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Имя пользователя") },
                    singleLine = true,
                    enabled = !loginInProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    enabled = !loginInProgress,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loginError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(loginError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (loginInProgress) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onLogin(username, password) },
                enabled = username.isNotBlank() && password.isNotBlank() && !loginInProgress,
            ) { Text("Войти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loginInProgress) { Text("Отмена") }
        },
    )
}

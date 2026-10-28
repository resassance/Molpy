package app.musicplayer.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.musicplayer.model.*
import app.musicplayer.ui.components.ScreenHeader
import app.musicplayer.ui.present

@Composable
private fun NetworkSettingsFields(
    draft: NetworkDraft,
    hasCredential: Boolean,
    applying: Boolean,
    message: String?,
    error: AppError?,
    onEdit: (NetworkDraft) -> Unit,
    onApply: (CharArray?) -> Unit,
) {
    // Transient field only: never saveable, never read from the secret store, cleared on
    // submit/disposal.
    var password by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { password = "" } }
    LaunchedEffect(draft.mode, draft.authentication) {
        if (draft.mode != NetworkMode.HTTP_CONNECT || !draft.authentication) password = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Подключение", style = MaterialTheme.typography.titleMedium)
        NetworkMode.entries.forEach { mode ->
            Row {
                RadioButton(
                    selected = draft.mode == mode,
                    enabled = !applying,
                    onClick = { onEdit(draft.copy(mode = mode)) },
                )
                Text(
                    when (mode) {
                        NetworkMode.SYSTEM -> "Системное подключение / VPN"
                        NetworkMode.HTTP_CONNECT -> "HTTP CONNECT"
                        NetworkMode.SOCKS5 -> "SOCKS5 без авторизации"
                    }
                )
            }
        }
        if (draft.mode != NetworkMode.SYSTEM) {
            OutlinedTextField(
                draft.host,
                { onEdit(draft.copy(host = it)) },
                Modifier.fillMaxWidth(),
                enabled = !applying,
                label = { Text("Имя узла без схемы и пути") },
                singleLine = true,
            )
            OutlinedTextField(
                draft.port,
                { onEdit(draft.copy(port = it)) },
                enabled = !applying,
                label = { Text("Порт 1–65535") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        if (draft.mode == NetworkMode.HTTP_CONNECT) {
            Row {
                Checkbox(
                    draft.authentication,
                    { onEdit(draft.copy(authentication = it)) },
                    enabled = !applying,
                )
                Text("HTTP Basic авторизация")
            }
            if (draft.authentication) {
                OutlinedTextField(
                    draft.username,
                    { onEdit(draft.copy(username = it)) },
                    enabled = !applying,
                    label = { Text("Имя пользователя") },
                    singleLine = true,
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    enabled = !applying,
                    label = { Text("Новый пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (hasCredential)
                    Text(
                        "Сохранённый пароль скрыт. Оставьте поля авторизации без изменений, чтобы использовать его снова."
                    )
            }
        }
        Button(
            enabled = !applying,
            onClick = {
                val submitted = password.takeIf { it.isNotEmpty() }?.toCharArray()
                password = ""
                onApply(submitted)
            },
        ) {
            Text(if (applying) "Применение…" else "Применить")
        }
        message?.let { Text(it) }
        error?.let { Text(present(it).message) }
    }
}

@Composable
fun NetworkSettingsScreen(
    model: NetworkSettingsModel,
    onBack: () -> Unit,
    playback: PlaybackSettingsModel? = null,
    onAppearance: (() -> Unit)? = null,
    onShareDiagnostics: (() -> Unit)? = null,
    onApply: (CharArray?) -> Unit,
) {
    val state by model.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Настройки", onBack)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        ) {
            onAppearance?.let { open ->
                item {
                    TextButton(
                        onClick = open,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text("Оформление", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            playback?.let { item { PlaybackSettingsSection(it) } }
            onShareDiagnostics?.let { share ->
                item {
                    TextButton(share, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Поделиться диагностикой", Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                NetworkSettingsFields(
                    state.draft,
                    state.hasCredential,
                    state.applying,
                    state.message,
                    state.error,
                    model::edit,
                    onApply,
                )
            }
        }
    }
}

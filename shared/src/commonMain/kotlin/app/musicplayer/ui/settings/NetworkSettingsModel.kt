package app.musicplayer.ui.settings

import app.musicplayer.library.LibraryRepository
import app.musicplayer.model.*
import app.musicplayer.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class NetworkSettingsModel(
    private val network: NetworkController,
    private val library: LibraryRepository,
    private val secrets: SecretStore,
    ownerScope: CoroutineScope,
) {
    private val scope =
        CoroutineScope(
            ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job])
        )
    private val stateMutable = MutableStateFlow(NetworkSettingsState())
    val state: StateFlow<NetworkSettingsState> = stateMutable.asStateFlow()
    private var closed = false
    private var networkEditRevision = 0L
    private var credentialRef: SecretId? = null
    private var credentialUsername: String? = null
    private val credentialRefsForCleanup = mutableSetOf<SecretId>()

    init {
        scope.launch {
            val revision =
                0L // Initial restore must not overwrite edits made before this job starts.
            try {
                val config = library.getNetworkConfig()
                config.credentialRef?.let(credentialRefsForCleanup::add)
                if (revision == networkEditRevision && !closed) {
                    credentialRef = config.credentialRef
                    stateMutable.update {
                        it.copy(
                            draft =
                                NetworkDraft(
                                    config.mode,
                                    config.endpoint?.host.orEmpty(),
                                    config.endpoint?.port?.toString().orEmpty(),
                                    config.credentialRef != null,
                                ),
                            hasCredential = config.credentialRef != null,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                stateMutable.update {
                    it.copy(error = AppError(ErrorCode.DATABASE, "settings.load", true))
                }
            }
        }
    }

    fun edit(draft: NetworkDraft) {
        if (closed || stateMutable.value.applying) return
        ++networkEditRevision
        stateMutable.update { it.copy(draft = draft, message = null, error = null) }
    }

    suspend fun apply(password: CharArray?) {
        var ownsApply = false
        try {
            if (closed || stateMutable.value.applying) return
            ++networkEditRevision
            val draft = stateMutable.value.draft
            val host = draft.host.trim()
            val port = draft.port.trim().toIntOrNull()
            if (
                draft.mode != NetworkMode.SYSTEM &&
                    (!validHost(host) || port == null || port !in 1..65535)
            ) {
                stateMutable.update {
                    it.copy(message = "Укажите имя узла без схемы и пути и порт от 1 до 65535.")
                }
                return
            }
            val auth = draft.mode == NetworkMode.HTTP_CONNECT && draft.authentication
            if (
                auth &&
                    ((password?.isNotEmpty() == true && draft.username.isBlank()) ||
                        ((password == null || password.isEmpty()) &&
                            (credentialRef == null ||
                                (draft.username.isNotBlank() &&
                                    draft.username != credentialUsername))))
            ) {
                stateMutable.update {
                    it.copy(message = "Укажите имя пользователя и новый пароль прокси.")
                }
                return
            }
            ownsApply = true
            stateMutable.update { it.copy(applying = true, message = null, error = null) }
            credentialRef?.let(credentialRefsForCleanup::add)
            val newRef =
                if (!auth) null
                else if (password?.isNotEmpty() == true) secrets.put(draft.username, password)
                else credentialRef
            newRef?.let(credentialRefsForCleanup::add)
            // Keep a created reference even if apply fails after persisting it. Never delete a
            // referenced secret.
            if (auth) {
                credentialRef = newRef
                if (password?.isNotEmpty() == true) credentialUsername = draft.username
            }
            val config =
                NetworkConfig(
                    draft.mode,
                    if (draft.mode == NetworkMode.SYSTEM) null else ProxyEndpoint(host, port!!),
                    newRef,
                )
            when (val result = network.apply(config)) {
                is AppResult.Success -> {
                    credentialRef = newRef
                    stateMutable.update {
                        it.copy(
                            hasCredential = newRef != null,
                            message = "Настройки сети применены.",
                        )
                    }
                    // Cleanup is deferred until persistence confirms this apply. Failed rotations
                    // remain known.
                    val savedRef = library.getNetworkConfig().credentialRef
                    if (savedRef == newRef) {
                        val activeRef = network.context.value.config.credentialRef
                        credentialRefsForCleanup
                            .toList()
                            .filter { it != savedRef && it != activeRef }
                            .forEach { obsolete ->
                                secrets.remove(obsolete)
                                credentialRefsForCleanup.remove(obsolete)
                            }
                    }
                }
                is AppResult.Failure ->
                    stateMutable.update {
                        it.copy(error = result.error, hasCredential = credentialRef != null)
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppFailureException) {
            stateMutable.update { it.copy(error = e.error) }
        } catch (_: Exception) {
            stateMutable.update {
                it.copy(error = AppError(ErrorCode.NETWORK, "settings.apply", true))
            }
        } finally {
            password?.fill('\u0000')
            if (ownsApply) stateMutable.update { it.copy(applying = false) }
        }
    }

    private fun validHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (':' in host)
            return host.count { it == ':' } >= 2 && host.matches(Regex("[0-9a-fA-F:]+"))
        return host.split('.').all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    fun close() {
        closed = true
        scope.cancel()
    }
}

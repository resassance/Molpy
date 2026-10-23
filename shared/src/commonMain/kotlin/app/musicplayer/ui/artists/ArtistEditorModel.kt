package app.musicplayer.ui.artists

import app.musicplayer.artists.*
import app.musicplayer.model.*
import app.musicplayer.ui.components.LoadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ArtistEditorState(
    val track: Track? = null,
    val choices: List<ArtistChoice> = emptyList(),
    val expected: List<ArtistId>? = null,
    val query: String = "",
    val results: LoadState<List<ArtistCatalogHit>> = LoadState(emptyList()),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: AppError? = null,
)

class ArtistEditorModel(
    private val repository: ArtistRepository,
    private val search: ArtistSearchPort,
    ownerScope: CoroutineScope,
) {
    private val job = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + job)
    private val mutable = MutableStateFlow(ArtistEditorState())
    val state: StateFlow<ArtistEditorState> = mutable.asStateFlow()
    private var identities = ArtistDirectory()
    private var generation = 0L
    private var queryGeneration = 0L
    private var request: Job? = null
    private var queryRequest: Job? = null

    fun open(track: Track) {
        closeDraft()
        val selected = generation
        mutable.value = ArtistEditorState(track = track, saving = true)
        request = scope.launch {
            try {
                repository.ensure(track).required()
                val directory = repository.directory.first().required()
                val presentation = ArtistResolver.present(track, directory)
                ensureActive()
                if (selected == generation) {
                    identities = directory
                    mutable.value =
                        ArtistEditorState(
                            track,
                            presentation.artists.map {
                                it.id?.let(ArtistChoice::Existing) ?: ArtistChoice.Name(it.name)
                            },
                            directory.overrides[track.id],
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (selected == generation)
                    mutable.update { it.copy(error = error(e, "artists.editor.open")) }
            } finally {
                if (selected == generation) mutable.update { it.copy(saving = false) }
            }
        }
    }

    fun setQuery(query: String) {
        if (state.value.track == null) return
        queryRequest?.cancel()
        val selected = generation
        val serial = ++queryGeneration
        mutable.update { it.copy(query = query, results = LoadState(emptyList())) }
        if (query.trim().length < 2) return
        queryRequest = scope.launch {
            delay(300)
            if (selected != generation || serial != queryGeneration) return@launch
            mutable.update { it.copy(results = LoadState(loading = true)) }
            try {
                val result = search.search(query.trim())
                ensureActive()
                if (selected == generation && serial == queryGeneration)
                    mutable.update {
                        it.copy(
                            results =
                                when (result) {
                                    is AppResult.Success -> LoadState(result.value)
                                    is AppResult.Failure -> LoadState(error = result.error)
                                }
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (selected == generation && serial == queryGeneration)
                    mutable.update {
                        it.copy(results = LoadState(error = error(e, "artists.editor.search")))
                    }
            }
        }
    }

    fun select(index: Int, choice: ArtistChoice) = edit { choices ->
        if (index !in choices.indices) choices
        else choices.toMutableList().apply { this[index] = choice }
    }

    fun add(choice: ArtistChoice) = edit { it + choice }

    fun remove(index: Int) = edit { choices -> choices.filterIndexed { i, _ -> i != index } }

    private fun edit(action: (List<ArtistChoice>) -> List<ArtistChoice>) {
        if (state.value.saving || state.value.track == null) return
        mutable.update {
            it.copy(
                choices = action(it.choices).distinctBy(::choiceKey),
                error = null,
                saved = false,
            )
        }
    }

    private fun choiceKey(choice: ArtistChoice): Pair<String, String> =
        when (choice) {
            is ArtistChoice.Name -> "name" to choice.name
            is ArtistChoice.Existing ->
                "local" to
                    (choice.id
                            .takeIf { it in identities.profiles }
                            ?.let { ArtistResolver.root(it, identities) } ?: choice.id)
                        .value
            is ArtistChoice.Catalog -> {
                val source = choice.profile.source
                val id = identities.aliases[ArtistAliasKey(ArtistResolver.sourceKey(source))]
                if (id != null) "local" to ArtistResolver.root(id, identities).value
                else "source" to ArtistResolver.sourceKey(source)
            }
        }

    fun save() = write(false)

    fun reset() = write(true)

    private fun write(reset: Boolean) {
        val draft = state.value
        val track = draft.track ?: return
        if (draft.saving) return
        if (!reset && draft.choices.isEmpty()) {
            mutable.update {
                it.copy(error = AppError(ErrorCode.INVALID_CONFIG, "artists.editor.empty", false))
            }
            return
        }
        val selected = generation
        mutable.update { it.copy(saving = true, error = null) }
        request = scope.launch {
            try {
                val choices =
                    if (reset) draft.choices
                    else
                        draft.choices.map {
                            if (it is ArtistChoice.Catalog)
                                ArtistChoice.Catalog(search.validate(it.profile).required())
                            else it
                        }
                ensureActive()
                val result =
                    if (reset) repository.resetOverride(track.id, draft.expected)
                    else repository.saveOverride(track, choices, draft.expected)
                ensureActive()
                if (selected != generation) return@launch
                when (result.required()) {
                    ArtistWriteResult.APPLIED ->
                        mutable.update { it.copy(choices = choices, saved = true) }
                    ArtistWriteResult.STALE -> {
                        val latest = repository.directory.first().required()
                        ensureActive()
                        if (selected == generation)
                            mutable.update {
                                it.copy(
                                    expected = latest.overrides[track.id],
                                    error =
                                        AppError(
                                            ErrorCode.INVALID_CONFIG,
                                            "artists.editor.stale",
                                            false,
                                        ),
                                )
                            }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (selected == generation)
                    mutable.update { it.copy(error = error(e, "artists.editor.save")) }
            } finally {
                if (selected == generation) mutable.update { it.copy(saving = false) }
            }
        }
    }

    fun networkInvalidated() {
        queryGeneration++
        queryRequest?.cancel()
        if (state.value.track != null)
            mutable.update {
                it.copy(
                    results =
                        LoadState(error = AppError(ErrorCode.NETWORK, "artists.editor.route", true))
                )
            }
    }

    fun closeDraft() {
        identities = ArtistDirectory()
        generation++
        queryGeneration++
        request?.cancel()
        queryRequest?.cancel()
        mutable.value = ArtistEditorState()
    }

    fun close() {
        closeDraft()
        job.cancel()
    }

    private fun error(e: Exception, operation: String) =
        if (e is AppFailureException) e.error else AppError(ErrorCode.UNKNOWN, operation, true)

    private fun <T> AppResult<T>.required(): T =
        when (this) {
            is AppResult.Success -> value
            is AppResult.Failure -> throw AppFailureException(error)
        }
}

package com.joohn.baixavideos.ui

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.joohn.baixavideos.data.Prefs
import com.joohn.baixavideos.engine.DownloadMode
import com.joohn.baixavideos.engine.EngineUnavailableException
import com.joohn.baixavideos.engine.SearchException
import com.joohn.baixavideos.engine.SearchResult
import com.joohn.baixavideos.engine.Trim
import com.joohn.baixavideos.engine.VideoInfo
import com.joohn.baixavideos.engine.VideoInfoException
import com.joohn.baixavideos.engine.VideoInfoFetcher
import com.joohn.baixavideos.engine.VideoQuality
import com.joohn.baixavideos.engine.YouTubeSearch
import com.joohn.baixavideos.util.Links
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil

sealed interface InfoState {
    data object Empty : InfoState

    /** Lendo o link; quando ele veio da busca, já mostra título e miniatura do resultado. */
    data class Loading(val hint: SearchResult? = null) : InfoState
    data class Loaded(val info: VideoInfo) : InfoState
    data class Failed(val message: String) : InfoState
}

sealed interface SearchState {
    data object Idle : SearchState
    data class Loading(val query: String) : SearchState
    data class Results(
        val query: String,
        val items: List<SearchResult>,
        val canLoadMore: Boolean,
        val loadingMore: Boolean = false,
    ) : SearchState

    data class Failed(val query: String, val message: String) : SearchState
}

/**
 * Rascunho de um download: link, formato, qualidade e corte. O Início e a janela de
 * compartilhar usam o mesmo rascunho, então as opções se comportam igual nos dois lugares.
 */
@Stable
class DownloadDraft(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private var fetchJob: Job? = null
    private var searchJob: Job? = null
    private var pendingHint: SearchResult? = null

    var text by mutableStateOf("")
        private set
    var link by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<InfoState>(InfoState.Empty)
        private set
    var mode by mutableStateOf(Prefs.settings.value.mode)
        private set
    var quality by mutableStateOf(Prefs.settings.value.quality)
        private set
    var trimOn by mutableStateOf(false)
        private set
    var trimStart by mutableLongStateOf(0L)
        private set
    var trimEnd by mutableLongStateOf(0L)
        private set

    var search by mutableStateOf<SearchState>(SearchState.Idle)
        private set

    /** Texto digitado que não é link: vira busca no YouTube. */
    val searchQuery: String? get() = text.trim().takeIf { link == null && it.length >= 2 }

    /** Duração descoberta pelo player da prévia, para sites que não informam a duração. */
    private var playerDurationSec by mutableStateOf<Double?>(null)

    val loadedInfo: VideoInfo? get() = (info as? InfoState.Loaded)?.info

    /** Duração em segundos inteiros, quando conhecida. */
    val durationSec: Long?
        get() = (loadedInfo?.durationSec ?: playerDurationSec)?.let { ceil(it).toLong() }?.takeIf { it > 0 }

    fun updateText(value: String) {
        text = value
        if (value.isBlank()) {
            searchJob?.cancel()
            search = SearchState.Idle
        }
        val next = Links.extractUrl(value)
        if (next == link) return
        link = next
        load(next)
    }

    fun retryInfo() = load(link)

    private fun load(next: String?) {
        fetchJob?.cancel()
        trimOn = false
        playerDurationSec = null
        if (next == null) {
            info = InfoState.Empty
            return
        }
        VideoInfoFetcher.cached(next)?.let {
            info = InfoState.Loaded(it)
            return
        }
        info = InfoState.Loading(pendingHint?.takeIf { it.url == next })
        pendingHint = null
        fetchJob = scope.launch {
            delay(350) // deixa a colagem ou a digitação terminar antes de abrir o motor
            info = try {
                val loaded = withTimeoutOrNull(INFO_TIMEOUT_MS) { VideoInfoFetcher.fetch(app, next) }
                if (loaded != null) {
                    InfoState.Loaded(loaded)
                } else {
                    InfoState.Failed("O site demorou demais para responder.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: VideoInfoException) {
                InfoState.Failed(e.message ?: "Não consegui ler esse link.")
            } catch (_: EngineUnavailableException) {
                InfoState.Failed("O motor de download não iniciou. Abra os Ajustes para tentar de novo.")
            } catch (_: Exception) {
                InfoState.Failed("Não consegui ler esse link agora.")
            }
        }
    }

    /** Busca no YouTube o texto digitado (primeira página). */
    fun runSearch() {
        val query = searchQuery ?: return
        searchJob?.cancel()
        search = SearchState.Loading(query)
        searchJob = scope.launch {
            search = fetchPage(query, emptyList())
        }
    }

    fun loadMoreResults() {
        val current = search as? SearchState.Results ?: return
        if (current.loadingMore || !current.canLoadMore) return
        search = current.copy(loadingMore = true)
        searchJob = scope.launch {
            search = fetchPage(current.query, current.items)
        }
    }

    private suspend fun fetchPage(query: String, previous: List<SearchResult>): SearchState = try {
        val from = previous.size + 1
        val to = previous.size + SEARCH_PAGE
        val page = withTimeoutOrNull(INFO_TIMEOUT_MS) { YouTubeSearch.search(app, query, from, to) }
            ?: throw SearchException("A busca demorou demais para responder.")
        val items = (previous + page).distinctBy { it.id }
        SearchState.Results(query, items, canLoadMore = page.size >= SEARCH_PAGE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: SearchException) {
        if (previous.isEmpty()) SearchState.Failed(query, e.message ?: "A busca falhou.")
        else SearchState.Results(query, previous, canLoadMore = false)
    } catch (_: EngineUnavailableException) {
        SearchState.Failed(query, "O motor de download não iniciou. Abra os Ajustes para tentar de novo.")
    } catch (_: Exception) {
        if (previous.isEmpty()) SearchState.Failed(query, "Não consegui buscar agora.")
        else SearchState.Results(query, previous, canLoadMore = false)
    }

    /** Escolhe um resultado da busca: ele vira o link do download. */
    fun pick(result: SearchResult) {
        pendingHint = result
        updateText(result.url)
    }

    /** Volta para a lista da última busca. */
    fun backToResults() {
        val query = (search as? SearchState.Results)?.query ?: return
        updateText(query)
    }

    fun selectMode(value: DownloadMode) {
        mode = value
        Prefs.update { it.copy(mode = value) }
    }

    fun selectQuality(value: VideoQuality) {
        quality = value
        Prefs.update { it.copy(quality = value) }
    }

    fun setTrimEnabled(enabled: Boolean) {
        trimOn = enabled
        if (enabled) {
            trimStart = 0L
            trimEnd = durationSec ?: 0L
        }
    }

    fun setTrimRange(start: Long, end: Long) {
        val max = durationSec
        trimStart = start.coerceAtLeast(0L).let { if (max != null) it.coerceAtMost(max) else it }
        trimEnd = end.coerceAtLeast(0L).let { if (max != null) it.coerceAtMost(max) else it }
    }

    fun onPlayerDuration(seconds: Double) {
        if (seconds <= 0 || loadedInfo?.durationSec != null || playerDurationSec != null) return
        playerDurationSec = seconds
        if (trimOn && trimEnd == 0L) trimEnd = durationSec ?: 0L
    }

    /** Trecho para baixar; null com o corte desligado ou quando ele cobre o vídeo inteiro. */
    val trim: Trim?
        get() {
            if (!trimOn || trimEnd <= trimStart) return null
            val max = durationSec
            if (max != null && trimStart == 0L && trimEnd >= max) return null
            return Trim(trimStart, trimEnd)
        }

    val trimProblem: String?
        get() = when {
            !trimOn -> null
            durationSec == null && trimEnd == 0L -> "Toque em Fim e digite onde o trecho termina."
            trimEnd <= trimStart -> "O fim do trecho precisa vir depois do início."
            else -> null
        }

    val canDownload: Boolean get() = link != null && trimProblem == null

    fun toStart(): PendingStart? = link?.let { PendingStart(it, mode, quality, trim, loadedInfo) }

    fun reset() = updateText("")

    private companion object {
        const val INFO_TIMEOUT_MS = 120_000L
        const val SEARCH_PAGE = 10
    }
}

package com.mlayfer.iptv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mlayfer.iptv.data.Channel
import com.mlayfer.iptv.data.ChannelKind
import com.mlayfer.iptv.data.Playlist
import com.mlayfer.iptv.data.Programme
import com.mlayfer.iptv.data.RecentEntry
import com.mlayfer.iptv.data.Repository
import com.mlayfer.iptv.data.Series
import com.mlayfer.iptv.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ListView { ALL, FAVORITES, RECENT }

/** What the list is showing: everything, live TV, films, or series. */
enum class Catalog { ALL, LIVE, MOVIES, SERIES }

enum class Screen { CHANNELS, SOURCES }

data class UiState(
    val playlists: List<Playlist> = emptyList(),
    val activeId: String? = null,
    val channels: List<Channel> = emptyList(),
    val series: List<Series> = emptyList(),
    /** What the portal refused or cut short — shown instead of silently omitted. */
    val notes: List<String> = emptyList(),
    val epg: Map<String, List<Programme>> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val recent: List<RecentEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val group: String? = null,
    val catalog: Catalog = Catalog.ALL,
    val view: ListView = ListView.ALL,
    /** Non-null while an individual series is open on its episode list. */
    val openSeries: Series? = null,
    val episodes: List<Channel> = emptyList(),
    val episodesLoading: Boolean = false,
    val episodesError: String? = null,
    val selectedId: String? = null,
    val screen: Screen = Screen.CHANNELS,
    val addBusy: Boolean = false,
    val addError: String? = null,
) {
    val activePlaylist: Playlist? get() = playlists.firstOrNull { it.id == activeId }

    val kind: ChannelKind?
        get() = when (catalog) {
            Catalog.LIVE -> ChannelKind.LIVE
            Catalog.MOVIES -> ChannelKind.VOD
            else -> null
        }

    /** Episodes are playable too, so a selection can come from either list. */
    val selectedChannel: Channel?
        get() = channels.firstOrNull { it.id == selectedId }
            ?: episodes.firstOrNull { it.id == selectedId }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = Store(application)
    private val repository = Repository()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val playlists = store.playlists
        val active = store.activeId?.takeIf { id -> playlists.any { it.id == id } }
            ?: playlists.firstOrNull()?.id
        _state.value = _state.value.copy(
            playlists = playlists,
            activeId = active,
            favorites = store.favorites,
            recent = store.recent,
            screen = if (playlists.isEmpty()) Screen.SOURCES else Screen.CHANNELS,
        )
        active?.let { selectPlaylist(it) }
    }

    fun selectPlaylist(id: String) {
        val playlist = _state.value.playlists.firstOrNull { it.id == id } ?: return
        store.activeId = id
        _state.value = _state.value.copy(
            activeId = id,
            selectedId = null,
            channels = emptyList(),
            series = emptyList(),
            notes = emptyList(),
            openSeries = null,
            episodes = emptyList(),
            epg = emptyMap(),
            group = null,
            error = null,
        )
        load(playlist, force = false)
    }

    fun refresh() {
        val playlist = _state.value.activePlaylist ?: return
        repository.forget(playlist.id)
        load(playlist, force = true)
    }

    private fun load(playlist: Playlist, force: Boolean) {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { repository.loadPlaylist(playlist, force) }
                _state.value = _state.value.copy(
                    channels = parsed.channels,
                    series = parsed.series,
                    notes = parsed.notes,
                    loading = false,
                    error = null,
                )
                val guide = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: parsed.epgUrl
                if (guide != null) loadEpg(guide, force)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    channels = emptyList(),
                    series = emptyList(),
                    notes = emptyList(),
                    loading = false,
                    error = e.message ?: "טעינת הרשימה נכשלה",
                )
            }
        }
    }

    private fun loadEpg(url: String, force: Boolean) {
        viewModelScope.launch {
            try {
                val guide = withContext(Dispatchers.IO) { repository.loadEpg(url, force) }
                _state.value = _state.value.copy(epg = guide)
            } catch (e: Exception) {
                // The guide is a bonus; playback must never depend on it.
                _state.value = _state.value.copy(epg = emptyMap())
            }
        }
    }

    /** Loads the playlist first: a source that doesn't work never gets saved. */
    fun addPlaylist(playlist: Playlist) {
        _state.value = _state.value.copy(addBusy = true, addError = null)
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) { repository.loadPlaylist(playlist) }
                val playlists = _state.value.playlists + playlist
                store.playlists = playlists
                store.activeId = playlist.id
                _state.value = _state.value.copy(
                    playlists = playlists,
                    activeId = playlist.id,
                    channels = parsed.channels,
                    series = parsed.series,
                    notes = parsed.notes,
                    selectedId = null,
                    group = null,
                    epg = emptyMap(),
                    addBusy = false,
                    addError = null,
                    error = null,
                    screen = Screen.CHANNELS,
                )
                val guide = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: parsed.epgUrl
                if (guide != null) loadEpg(guide, false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    addBusy = false,
                    addError = e.message ?: "טעינת הרשימה נכשלה",
                )
            }
        }
    }

    fun removePlaylist(id: String) {
        repository.forget(id)
        val playlists = _state.value.playlists.filterNot { it.id == id }
        store.playlists = playlists
        _state.value = _state.value.copy(playlists = playlists)

        // Deleting a playlist the user isn't watching must not disturb playback.
        if (_state.value.activeId != id) return

        val next = playlists.firstOrNull()
        if (next != null) {
            selectPlaylist(next.id)
        } else {
            store.activeId = null
            _state.value = _state.value.copy(
                activeId = null,
                channels = emptyList(),
                selectedId = null,
                epg = emptyMap(),
                screen = Screen.SOURCES,
            )
        }
    }

    fun select(channel: Channel) {
        val entry = RecentEntry(channel.id, _state.value.activeId.orEmpty(), System.currentTimeMillis())
        val recent = (listOf(entry) + _state.value.recent.filterNot { it.channelId == channel.id })
            .take(MAX_RECENT)
        store.recent = recent
        _state.value = _state.value.copy(selectedId = channel.id, recent = recent)
    }

    fun toggleFavorite(channel: Channel) {
        val favorites = _state.value.favorites.toMutableSet()
        if (!favorites.add(channel.id)) favorites.remove(channel.id)
        store.favorites = favorites
        _state.value = _state.value.copy(favorites = favorites)
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun setGroup(group: String?) {
        _state.value = _state.value.copy(group = group)
    }

    fun setCatalog(catalog: Catalog) {
        _state.value = _state.value.copy(
            catalog = catalog,
            group = null,
            openSeries = null,
            episodes = emptyList(),
            episodesError = null,
        )
    }

    fun openSeries(series: Series) {
        val playlist = _state.value.activePlaylist ?: return
        _state.value = _state.value.copy(
            openSeries = series,
            episodes = emptyList(),
            episodesLoading = true,
            episodesError = null,
        )
        viewModelScope.launch {
            try {
                val episodes = withContext(Dispatchers.IO) {
                    repository.loadEpisodes(playlist, series.id)
                }
                _state.value = _state.value.copy(
                    episodes = episodes,
                    episodesLoading = false,
                    episodesError = if (episodes.isEmpty()) "לא נמצאו פרקים לסדרה הזו" else null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    episodesLoading = false,
                    episodesError = e.message ?: "טעינת הפרקים נכשלה",
                )
            }
        }
    }

    fun closeSeries() {
        _state.value = _state.value.copy(
            openSeries = null,
            episodes = emptyList(),
            episodesError = null,
        )
    }

    fun setView(view: ListView) {
        _state.value = _state.value.copy(view = view)
    }

    fun setScreen(screen: Screen) {
        _state.value = _state.value.copy(screen = screen, addError = null)
    }

    fun clearAddError() {
        _state.value = _state.value.copy(addError = null)
    }

    private companion object {
        const val MAX_RECENT = 24
    }
}

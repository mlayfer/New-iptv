package com.mlayfer.iptv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mlayfer.iptv.data.Channel
import com.mlayfer.iptv.data.ChannelKind
import com.mlayfer.iptv.data.HomeRows
import com.mlayfer.iptv.data.Playlist
import com.mlayfer.iptv.data.PlaylistSource
import com.mlayfer.iptv.data.Programme
import com.mlayfer.iptv.data.RecentEntry
import com.mlayfer.iptv.data.Repository
import com.mlayfer.iptv.data.Series
import com.mlayfer.iptv.data.Store
import com.mlayfer.iptv.data.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ListView { ALL, FAVORITES, RECENT }

/**
 * How the live channels are laid out. A wall of tiles is the fastest way to
 * find a logo you know; a list is the only way to read what is actually on,
 * because a schedule needs a line of its own.
 */
enum class GuideLayout { GRID, LIST }

/** What the list is showing: everything, live TV, films, or series. */
enum class Catalog { ALL, LIVE, MOVIES, SERIES }

/**
 * Television and the library are two different products behind one door, and
 * the app opens on the choice. The same shape as the Tizen build.
 */
enum class Screen { CHOOSE, HOME, CHANNELS, TITLE, SOURCES }

data class UiState(
    val playlists: List<Playlist> = emptyList(),
    val activeId: String? = null,
    val channels: List<Channel> = emptyList(),
    val series: List<Series> = emptyList(),
    /** What the portal refused or cut short — shown instead of silently omitted. */
    val notes: List<String> = emptyList(),
    val epg: Map<String, List<Programme>> = emptyMap(),
    /**
     * The guide as the portal tells it, a channel at a time, keyed by channel
     * id. An XMLTV file is optional and this subscription has none; the panel
     * answers for every stream, so the guide is fetched for what is on screen.
     */
    val guide: Map<String, List<Programme>> = emptyMap(),
    /** Channel tiles, or one channel per line with its schedule beside it. */
    val guideLayout: GuideLayout = GuideLayout.GRID,
    /**
     * Something being played that is in no list: a stretch of a channel's
     * archive. It is made on the spot out of a channel and a time, so there is
     * nowhere else for it to live.
     */
    val adHoc: Channel? = null,
    val favorites: Set<String> = emptySet(),
    val recent: List<RecentEntry> = emptyList(),
    /** Ticked off by hand; newest last, so the order says which was last. */
    val watched: List<String> = emptyList(),
    /** Prepared answers for the search, filled in once the catalogue lands. */
    val searchIndex: List<HomeRows.IndexRow> = emptyList(),
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
    val screen: Screen = Screen.CHOOSE,
    val addBusy: Boolean = false,
    /** Which part of the catalogue is on its way, while adding a source. */
    val addStage: String? = null,
    val addError: String? = null,
) {
    val activePlaylist: Playlist? get() = playlists.firstOrNull { it.id == activeId }

    /**
     * One answer to "what is on this channel", wherever it came from: the
     * portal's own guide if it has answered for this channel, otherwise the
     * XMLTV file if the source came with one.
     */
    fun programmes(channel: Channel?): List<Programme>? {
        if (channel == null) return null
        guide[channel.id]?.let { if (it.isNotEmpty()) return it }
        return channel.tvgId?.let { epg[it] }?.takeIf { it.isNotEmpty() }
    }

    val kind: ChannelKind?
        get() = when (catalog) {
            Catalog.LIVE -> ChannelKind.LIVE
            Catalog.MOVIES -> ChannelKind.VOD
            else -> null
        }

    /** Episodes are playable too, so a selection can come from either list. */
    val selectedChannel: Channel?
        get() = adHoc?.takeIf { it.id == selectedId }
            ?: channels.firstOrNull { it.id == selectedId }
            ?: episodes.firstOrNull { it.id == selectedId }

    /**
     * The home screen, decided by the rules in HomeRows — the same ones the
     * Tizen app runs, so both open on the same thing.
     */
    val homeRows: List<HomeRows.Row> by lazy {
        HomeRows.build(
            // Channels have a guide of their own; the library's home is its own.
            items = homeCards.filter { it.kind != "LIVE" },
            history = historyEntries,
            // Most recently watched favourites first; a Set has no order of its own.
            favorites = (recent.map { it.channelId } + favorites).distinct()
                .filter { it in favorites },
            marks = watched,
            now = System.currentTimeMillis(),
        )
    }

    /**
     * The catalogue plus anything remembered that is no longer in it.
     *
     * Worked out once for each state rather than on every read: a getter looks
     * free at the call site and is twenty-three thousand map insertions behind
     * it, and the screen reads this more than once per frame.
     */
    val homeCards: List<HomeRows.Card> by lazy {
        run {
            val cards = LinkedHashMap<String, HomeRows.Card>()
            for (channel in channels) cards[channel.id] = channel.toCard()
            for (item in series) {
                cards[seriesCardId(item.id)] = HomeRows.Card(
                    id = seriesCardId(item.id),
                    name = item.name,
                    group = item.group ?: "סדרות",
                    kind = "VOD",
                    contentType = "SERIES",
                    logo = item.logo,
                    alias = item.alias,
                )
            }
            for (entry in recent) {
                if (entry.name.isBlank() || cards.containsKey(entry.channelId)) continue
                cards[entry.channelId] = HomeRows.Card(
                    id = entry.channelId,
                    name = entry.name,
                    group = entry.group,
                    kind = entry.kind.ifBlank { "VOD" },
                    contentType = entry.contentType.ifBlank { "MOVIE" },
                    logo = entry.logo,
                )
            }
            cards.values.toList()
        }
    }

    /**
     * One answer to "have I seen this" — a hand-tick, or something played to
     * the end. Derived rather than stored, so the two can never disagree.
     */
    val seen: Set<String> by lazy { HomeRows.watchedSet(historyEntries, watched) }

    private val historyEntries: List<HomeRows.Entry> by lazy {
        recent.map { HomeRows.Entry(it.channelId, it.at, it.position, it.duration) }
    }

    fun resumeFor(id: String): Long {
        val entry = recent.firstOrNull { it.channelId == id } ?: return 0
        return if (HomeRows.isResumable(
                HomeRows.Entry(entry.channelId, entry.at, entry.position, entry.duration)
            )
        ) entry.position else 0
    }
}

/** Series live in their own list, so their cards carry a prefix of their own. */
fun seriesCardId(id: String): String = "series:" + id

fun Channel.toCard(): HomeRows.Card = HomeRows.Card(
    id = id,
    name = name,
    group = group ?: if (kind == ChannelKind.LIVE) "ערוצים" else "סרטים",
    kind = if (kind == ChannelKind.LIVE) "LIVE" else "VOD",
    contentType = if (kind == ChannelKind.LIVE) "LIVE" else "MOVIE",
    logo = logo,
    alias = alias,
)

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
            watched = store.watched,
            guideLayout = runCatching { GuideLayout.valueOf(store.guideLayout) }
                .getOrDefault(GuideLayout.GRID),
            screen = if (playlists.isEmpty()) Screen.SOURCES else Screen.CHOOSE,
        )
        active?.let { selectPlaylist(it) }
    }

    fun selectPlaylist(id: String) {
        val playlist = _state.value.playlists.firstOrNull { it.id == id } ?: return
        store.activeId = id
        guideAsked.clear()
        _state.value = _state.value.copy(
            activeId = id,
            selectedId = null,
            channels = emptyList(),
            series = emptyList(),
            notes = emptyList(),
            openSeries = null,
            episodes = emptyList(),
            epg = emptyMap(),
            guide = emptyMap(),
            adHoc = null,
            group = null,
            error = null,
        )
        load(playlist, force = false)
    }

    fun refresh() {
        val playlist = _state.value.activePlaylist ?: return
        repository.forget(playlist.id)
        guideAsked.clear()
        load(playlist, force = true)
    }

    /**
     * Prepare the search once, in the background.
     *
     * Every keystroke would otherwise redo the same alphabet arithmetic for
     * every item in the catalogue. It costs a fraction of a second to work out
     * and nothing to reuse, and until it is ready the search simply walks the
     * catalogue as it always did.
     */
    private fun indexSearchSoon() {
        viewModelScope.launch {
            val cards = _state.value.homeCards
            val index = withContext(Dispatchers.Default) { HomeRows.buildSearchIndex(cards) }
            // The catalogue may have been replaced while this was being built.
            if (_state.value.homeCards === cards) {
                _state.value = _state.value.copy(searchIndex = index)
            }
        }
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
                    searchIndex = emptyList(),
                )
                indexSearchSoon()
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

    /**
     * Channels whose guide has been asked for, answered or not.
     *
     * Held outside the state because it is bookkeeping, not something a screen
     * draws — and because a list scrolling past asks for the same channel every
     * time it comes back into view. A portal that has nothing for a channel
     * stays asked: an empty answer is an answer.
     */
    private val guideAsked = HashSet<String>()

    /**
     * Fetch the schedule for one channel, if it has not been fetched already.
     *
     * Called by whatever is on screen rather than for the whole catalogue: a
     * subscription here is thirteen thousand channels, and asking the portal
     * thirteen thousand times to fill a screen that shows eight of them is not
     * a guide, it is a denial of service.
     */
    fun loadGuide(channel: Channel) {
        if (channel.kind != ChannelKind.LIVE) return
        val streamId = channel.streamId ?: return
        val source = _state.value.activePlaylist?.source as? PlaylistSource.Xtream ?: return
        if (!guideAsked.add(channel.id)) return

        viewModelScope.launch {
            val programmes = withContext(Dispatchers.IO) {
                try {
                    XtreamClient.shortEpg(source, streamId)
                } catch (e: Exception) {
                    // A guide is a bonus; a portal that will not answer for one
                    // channel must not stop the rest of the screen.
                    emptyList()
                }
            }
            if (programmes.isEmpty()) return@launch
            _state.value = _state.value.copy(
                guide = _state.value.guide + (channel.id to programmes)
            )
        }
    }

    fun setGuideLayout(layout: GuideLayout) {
        store.guideLayout = layout.name
        _state.value = _state.value.copy(guideLayout = layout)
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
        _state.value = _state.value.copy(addBusy = true, addStage = null, addError = null)
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    repository.loadPlaylist(playlist) { stage ->
                        _state.value = _state.value.copy(addStage = stage)
                    }
                }
                val playlists = _state.value.playlists + playlist
                guideAsked.clear()
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
                    guide = emptyMap(),
                    addBusy = false,
                    addStage = null,
                    addError = null,
                    error = null,
                    // Connecting a portal ends at the door, the same place a
                    // launch ends at — not halfway inside one of the worlds.
                    screen = Screen.CHOOSE,
                    searchIndex = emptyList(),
                )
                indexSearchSoon()
                val guide = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: parsed.epgUrl
                if (guide != null) loadEpg(guide, false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    addBusy = false,
                    addStage = null,
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
        remember(channel, position = 0, duration = 0)
        // Choosing anything from a list leaves the archive behind: what is on
        // the screen is the thing that was chosen.
        _state.value = _state.value.copy(selectedId = channel.id, adHoc = null)
    }

    /**
     * Play a stretch of a channel's archive.
     *
     * A live channel the portal keeps can be wound back, and what comes out is
     * not a channel: it is a finite, seekable recording with a beginning and an
     * end. So it is made into one — VOD rather than LIVE, which is what gives it
     * a scrubber and keeps the channel keys from carrying you out of it.
     *
     * @param minutes how much of the archive to ask for, from [startMillis].
     */
    fun playCatchUp(channel: Channel, title: String, startMillis: Long, minutes: Int) {
        val source = _state.value.activePlaylist?.source as? PlaylistSource.Xtream ?: return
        val streamId = channel.streamId ?: return
        val url = XtreamClient
            .catchupVariants(source, streamId, startMillis, minutes)
            .firstOrNull() ?: return

        val item = channel.copy(
            id = "catchup:${channel.id}:$startMillis",
            name = if (title.isBlank()) channel.name else "$title · ${channel.name}",
            url = url,
            kind = ChannelKind.VOD,
        )
        remember(item, position = 0, duration = 0)
        _state.value = _state.value.copy(selectedId = item.id, adHoc = item)
    }

    /**
     * Where playback got to. Called while watching, not only when leaving, so a
     * set-top box losing power does not lose the position too.
     */
    fun noteProgress(channel: Channel, position: Long, duration: Long) {
        if (channel.kind == ChannelKind.LIVE) return
        val known = _state.value.recent.firstOrNull { it.channelId == channel.id }
        if (known != null && known.position == position) return
        remember(channel, position, duration)
    }

    private fun remember(channel: Channel, position: Long, duration: Long) {
        val entry = RecentEntry(
            channelId = channel.id,
            playlistId = _state.value.activeId.orEmpty(),
            at = System.currentTimeMillis(),
            position = position,
            duration = duration,
            name = channel.name,
            group = channel.group.orEmpty(),
            kind = if (channel.kind == ChannelKind.LIVE) "LIVE" else "VOD",
            contentType = if (channel.kind == ChannelKind.LIVE) "LIVE" else "MOVIE",
            logo = channel.logo,
        )
        val recent = (listOf(entry) + _state.value.recent.filterNot { it.channelId == channel.id })
            .take(MAX_RECENT)
        store.recent = recent
        _state.value = _state.value.copy(recent = recent)
    }

    /** Seconds to start from for an item that was left part way through. */
    fun resumeFor(id: String): Long = _state.value.resumeFor(id)

    /** A home card is either a channel, a film, or a series to open. */
    fun openCard(card: HomeRows.Card) {
        if (card.contentType == "SERIES") {
            val id = card.id.removePrefix("series:")
            val match = _state.value.series.firstOrNull { it.id == id } ?: return
            _state.value = _state.value.copy(
                screen = Screen.TITLE,
                catalog = Catalog.SERIES,
                view = ListView.ALL,
                query = "",
                group = null,
            )
            openSeries(match)
            return
        }
        val channel = _state.value.channels.firstOrNull { it.id == card.id }
            ?: _state.value.episodes.firstOrNull { it.id == card.id }
            ?: return
        val live = channel.kind == ChannelKind.LIVE
        _state.value = _state.value.copy(
            // A channel is a place you arrive at; a film is a thing you decide
            // about first, so it gets its page rather than the guide.
            screen = if (live) Screen.CHANNELS else Screen.TITLE,
            catalog = if (live) Catalog.LIVE else Catalog.MOVIES,
            view = ListView.ALL,
            query = "",
            group = null,
            openSeries = null,
            episodes = emptyList(),
        )
        select(channel)
    }

    fun toggleFavoriteId(id: String) {
        val favorites = _state.value.favorites.toMutableSet()
        if (!favorites.add(id)) favorites.remove(id)
        store.favorites = favorites
        _state.value = _state.value.copy(favorites = favorites)
    }

    fun toggleFavorite(channel: Channel) {
        val favorites = _state.value.favorites.toMutableSet()
        if (!favorites.add(channel.id)) favorites.remove(channel.id)
        store.favorites = favorites
        _state.value = _state.value.copy(favorites = favorites)
    }

    /**
     * Ticking something off by hand. Unticking also clears a finished position,
     * because playing to the end counts as seen too — otherwise the tick comes
     * straight back.
     */
    fun toggleWatched(id: String) {
        val marks = _state.value.watched.toMutableList()
        val had = marks.remove(id)
        if (!had) marks.add(id)
        store.watched = marks

        var recent = _state.value.recent
        if (had) {
            recent = recent.map { if (it.channelId == id) it.copy(position = 0) else it }
            store.recent = recent
        }
        _state.value = _state.value.copy(watched = marks, recent = recent)
    }

    /** A season is ticked off as one gesture: all of it, or none of it. */
    fun toggleWatchedAll(ids: List<String>) {
        if (ids.isEmpty()) return
        val seen = _state.value.seen
        val done = ids.all { it in seen }
        val marks = _state.value.watched.toMutableList()
        ids.forEach { id ->
            marks.remove(id)
            if (!done) marks.add(id)
        }
        store.watched = marks

        var recent = _state.value.recent
        if (done) {
            recent = recent.map { if (it.channelId in ids) it.copy(position = 0) else it }
            store.recent = recent
        }
        _state.value = _state.value.copy(watched = marks, recent = recent)
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
                    repository.loadEpisodes(playlist, series.id, series.name)
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

    /** Entering a world shows only what belongs to it. */
    fun enterWorld(catalog: Catalog) {
        setCatalog(catalog)
        setScreen(if (catalog == Catalog.LIVE) Screen.CHANNELS else Screen.HOME)
    }

    /**
     * Back retraces the way in: out of a catalogue to the library's home, and
     * out of live television or the library to the door.
     */
    fun back() {
        val current = _state.value
        val target = when {
            current.screen == Screen.CHANNELS && current.catalog == Catalog.LIVE -> Screen.CHOOSE
            current.screen == Screen.CHANNELS -> Screen.HOME
            current.screen == Screen.TITLE -> Screen.HOME
            else -> Screen.CHOOSE
        }
        setScreen(target)
    }

    fun clearAddError() {
        _state.value = _state.value.copy(addError = null)
    }

    private companion object {
        const val MAX_RECENT = 24
    }
}

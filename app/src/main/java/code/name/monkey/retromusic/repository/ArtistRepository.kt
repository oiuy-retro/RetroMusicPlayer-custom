/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

package code.name.monkey.retromusic.repository

import android.provider.MediaStore.Audio.AudioColumns
import code.name.monkey.retromusic.ALBUM_ARTIST
import code.name.monkey.retromusic.helper.SortOrder
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.ArtistSeparator
import code.name.monkey.retromusic.util.PreferenceUtil
import java.text.Collator

interface ArtistRepository {
    fun artists(): List<Artist>

    fun artists(albums: List<Album>, albumArtistsOnly: Boolean): List<Artist>

    fun albumArtists(): List<Artist>

    fun albumArtists(query: String): List<Artist>

    fun artists(query: String): List<Artist>

    fun artist(artistId: Long): Artist

    fun albumArtist(artistName: String): Artist
}

class RealArtistRepository(
    private val songRepository: RealSongRepository,
    private val albumRepository: RealAlbumRepository
) : ArtistRepository {

    private fun getSongLoaderSortOrder(): String {
        return PreferenceUtil.artistSortOrder + ", " +
                PreferenceUtil.artistAlbumSortOrder + ", " +
                PreferenceUtil.artistSongSortOrder
    }

    override fun artist(artistId: Long): Artist {
        if (artistId == Artist.VARIOUS_ARTISTS_ID) {
            // Get Various Artists
            val songs = songRepository.songs(
                songRepository.makeSongCursor(
                    null,
                    null,
                    getSongLoaderSortOrder()
                )
            )
            val albums = albumRepository.splitIntoAlbums(songs)
                .filter { it.albumArtist == Artist.VARIOUS_ARTISTS_DISPLAY_NAME }
            return Artist(Artist.VARIOUS_ARTISTS_ID, albums)
        }

        if (ArtistSeparator.isSyntheticArtistId(artistId)) {
            return splitArtist(artistId)
        }

        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                AudioColumns.ARTIST_ID + "=?",
                arrayOf(artistId.toString()),
                getSongLoaderSortOrder()
            )
        )
        val artistName = songs.firstOrNull()?.artistName
        // Songs tagged "This Artist; Other Artist" belong to this artist as well
        val extraSongs = multiArtistSongsFor(artistName, AudioColumns.ARTIST) { it.artistName }
            .filter { it.artistId != artistId }
        if (extraSongs.isEmpty()) {
            return Artist(artistId, albumRepository.splitIntoAlbums(songs))
        }
        return Artist(
            artistId,
            albumRepository.splitIntoAlbums(songs + extraSongs),
            displayName = artistName
        )
    }

    /**
     * Artist that only exists inside multi-artist tags ("Artist A; Artist B"), addressed by
     * [ArtistSeparator.syntheticArtistId]. Includes single-artist songs with the same name too.
     */
    private fun splitArtist(artistId: Long): Artist {
        val allSongs = songRepository.songs(
            songRepository.makeSongCursor(null, null, getSongLoaderSortOrder())
        )
        val artistName = allSongs.asSequence()
            .flatMap { ArtistSeparator.splitLibrary(it.artistName).asSequence() }
            .firstOrNull { ArtistSeparator.syntheticArtistId(it) == artistId }
            ?: return Artist(artistId, emptyList())
        val songs = allSongs.filter { ArtistSeparator.containsArtist(it.artistName, artistName) }
        return Artist(artistId, albumRepository.splitIntoAlbums(songs), displayName = artistName)
    }

    /**
     * Songs whose [column] tag lists several artists separated by ";" and includes [artistName].
     */
    private fun multiArtistSongsFor(
        artistName: String?,
        column: String,
        tag: (Song) -> String?
    ): List<Song> {
        if (artistName.isNullOrBlank() || ArtistSeparator.isMultiArtist(artistName)) {
            return emptyList()
        }
        return songRepository.songs(
            songRepository.makeSongCursor(
                "$column LIKE ? AND $column LIKE ?",
                arrayOf("%$artistName%", "%;%"),
                getSongLoaderSortOrder()
            )
        ).filter {
            val value = tag(it)
            ArtistSeparator.isMultiArtist(value) && ArtistSeparator.containsArtist(value, artistName)
        }
    }

    override fun albumArtist(artistName: String): Artist {
        if (artistName == Artist.VARIOUS_ARTISTS_DISPLAY_NAME) {
            // Get Various Artists
            val songs = songRepository.songs(
                songRepository.makeSongCursor(
                    null,
                    null,
                    getSongLoaderSortOrder()
                )
            )
            val albums = albumRepository.splitIntoAlbums(songs)
                .filter { it.albumArtist == Artist.VARIOUS_ARTISTS_DISPLAY_NAME }
            return Artist(Artist.VARIOUS_ARTISTS_ID, albums, true)
        }

        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                "album_artist" + "=?",
                arrayOf(artistName),
                getSongLoaderSortOrder()
            )
        )
        // Albums whose album artist is "This Artist; Other Artist" belong to this artist as well
        val extraSongs = multiArtistSongsFor(artistName, "album_artist") { it.albumArtist }
        if (extraSongs.isEmpty()) {
            return Artist(artistName, albumRepository.splitIntoAlbums(songs), true)
        }
        val allSongs = songs + extraSongs
        return Artist(
            allSongs.first().artistId,
            albumRepository.splitIntoAlbums(allSongs),
            true,
            displayName = artistName
        )
    }

    override fun artists(): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                null, null,
                getSongLoaderSortOrder()
            )
        )
        val artists = splitIntoArtists(albumRepository.splitIntoAlbums(songs))
        return sortArtists(artists)
    }

    override fun artists(albums: List<Album>, albumArtistsOnly: Boolean): List<Artist> {
        val artists = if (albumArtistsOnly) {
            splitIntoAlbumArtists(albums)
        } else {
            splitIntoArtists(albums)
        }
        return sortArtists(artists)
    }

    override fun albumArtists(): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                null,
                null,
                "lower($ALBUM_ARTIST)" +
                        if (PreferenceUtil.artistSortOrder == SortOrder.ArtistSortOrder.ARTIST_A_Z) "" else " DESC"
            )
        )
        val artists = splitIntoAlbumArtists(albumRepository.splitIntoAlbums(songs))
        return sortArtists(artists)
    }

    override fun albumArtists(query: String): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                "album_artist" + " LIKE ?",
                arrayOf("%$query%"),
                getSongLoaderSortOrder()
            )
        )
        val artists = splitIntoAlbumArtists(albumRepository.splitIntoAlbums(songs))
            // Drop co-artists that were only pulled in by a matching "A; B" album artist
            .filter { artist ->
                artist.albums.any { !ArtistSeparator.isMultiArtist(it.albumArtist) } ||
                        artist.name.contains(query, ignoreCase = true)
            }
        return sortArtists(artists)
    }

    override fun artists(query: String): List<Artist> {
        val songs = songRepository.songs(
            songRepository.makeSongCursor(
                AudioColumns.ARTIST + " LIKE ?",
                arrayOf("%$query%"),
                getSongLoaderSortOrder()
            )
        )
        val artists = splitIntoArtists(albumRepository.splitIntoAlbums(songs))
            // Drop co-artists that were only pulled in by a matching "A; B" tag
            .filter {
                !ArtistSeparator.isSyntheticArtistId(it.id) ||
                        it.name.contains(query, ignoreCase = true)
            }
        return sortArtists(artists)
    }


    private fun splitIntoAlbumArtists(albums: List<Album>): List<Artist> {
        if (albums.any { ArtistSeparator.isMultiArtist(it.albumArtist) }) {
            return splitIntoMultiAlbumArtists(albums)
        }
        return albums.groupBy { it.albumArtist }
            .filter {
                !it.key.isNullOrEmpty()
            }
            .map {
                val currentAlbums = it.value
                if (currentAlbums.isNotEmpty()) {
                    if (currentAlbums[0].albumArtist == Artist.VARIOUS_ARTISTS_DISPLAY_NAME) {
                        Artist(Artist.VARIOUS_ARTISTS_ID, currentAlbums, true)
                    } else {
                        Artist(currentAlbums[0].artistId, currentAlbums, true)
                    }
                } else {
                    Artist.empty
                }
            }
    }


    /**
     * Album artists when some album artist tags list several artists ("A; B"): such albums
     * are listed under each of those artists. Other albums are grouped exactly as before.
     */
    private fun splitIntoMultiAlbumArtists(albums: List<Album>): List<Artist> {
        val groups = LinkedHashMap<String, MutableList<Album>>()
        val keyByName = HashMap<String, String>()
        val multiAlbums = ArrayList<Album>()
        for (album in albums) {
            val albumArtist = album.albumArtist
            if (ArtistSeparator.isMultiArtist(albumArtist)) {
                multiAlbums.add(album)
                continue
            }
            if (albumArtist.isNullOrEmpty()) continue
            groups.getOrPut(albumArtist) { mutableListOf() }.add(album)
            keyByName.putIfAbsent(ArtistSeparator.key(albumArtist), albumArtist)
        }
        for (album in multiAlbums) {
            for (name in ArtistSeparator.splitLibrary(album.albumArtist)) {
                val key = keyByName.getOrPut(ArtistSeparator.key(name)) { name }
                groups.getOrPut(key) { mutableListOf() }.add(album)
            }
        }
        return groups.map { (name, currentAlbums) ->
            if (name == Artist.VARIOUS_ARTISTS_DISPLAY_NAME) {
                Artist(Artist.VARIOUS_ARTISTS_ID, currentAlbums, true)
            } else {
                Artist(currentAlbums[0].artistId, currentAlbums, true, displayName = name)
            }
        }
    }

    fun splitIntoArtists(albums: List<Album>): List<Artist> {
        if (albums.none { album -> album.songs.any { ArtistSeparator.isMultiArtist(it.artistName) } }) {
            return albums.groupBy { it.artistId }
                .map { Artist(it.key, it.value) }
        }
        // Some songs are tagged "Artist A; Artist B": list them under each artist.
        // Songs with a single artist are grouped exactly as before.
        val groups = LinkedHashMap<Long, MutableList<Song>>()
        val names = HashMap<Long, String>()
        val idByName = HashMap<String, Long>()
        val multiArtistSongs = ArrayList<Song>()
        for (album in albums) {
            val (multi, single) = album.songs.partition { ArtistSeparator.isMultiArtist(it.artistName) }
            multiArtistSongs.addAll(multi)
            if (single.isEmpty()) continue
            val id = single.first().artistId
            groups.getOrPut(id) { mutableListOf() }.addAll(single)
            names.putIfAbsent(id, single.first().artistName)
            single.forEach { idByName.putIfAbsent(ArtistSeparator.key(it.artistName), it.artistId) }
        }
        for (song in multiArtistSongs) {
            for (name in ArtistSeparator.splitLibrary(song.artistName)) {
                val id = idByName[ArtistSeparator.key(name)] ?: ArtistSeparator.syntheticArtistId(name)
                groups.getOrPut(id) { mutableListOf() }.add(song)
                names.putIfAbsent(id, name)
            }
        }
        return groups.map { (id, songs) ->
            Artist(id, albumRepository.splitIntoAlbums(songs, false), displayName = names[id])
        }
    }

    private fun sortArtists(artists: List<Artist>): List<Artist> {
        val collator = Collator.getInstance()
        return when (PreferenceUtil.artistSortOrder) {
            SortOrder.ArtistSortOrder.ARTIST_A_Z -> {
                artists.sortedWith { a1, a2 -> collator.compare(a1.name, a2.name) }
            }
            SortOrder.ArtistSortOrder.ARTIST_Z_A -> {
                artists.sortedWith { a1, a2 -> collator.compare(a2.name, a1.name) }
            }
            else -> artists
        }
    }
}

package com.blissplayer.music

import com.blissplayer.music.data.MusicBrainzLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginalReleaseTest {
    private fun album(groups: String, releases: String = "[]"): String? {
        val json = """{"recordings":[{"title":"Song","artist-credit":[{"name":"Artist"}],"release-groups":$groups,"releases":$releases}]}"""
        return MusicBrainzLookup.parse(json).single().album
    }

    @Test fun prefersEarliestStudioAlbumOverCompilationsAndLaterAlbums() {
        val groups = """[
            {"title":"Red Rocks","primary-type":"Album","secondary-types":["Live"],"first-release-date":"1990"},
            {"title":"Famous Songs","primary-type":"Album","secondary-types":["Compilation"],"first-release-date":"1991"},
            {"title":"Later Album","primary-type":"Album","secondary-types":[],"first-release-date":"2005"},
            {"title":"First Album","primary-type":"Album","secondary-types":[],"first-release-date":"1995"}
        ]"""
        assertEquals("First Album", album(groups))
    }

    @Test fun usesSingleWhenOnlyAlbumsAreLiveOrCompilations() {
        val groups = """[
            {"title":"Famous Songs","primary-type":"Album","secondary-types":["Compilation"]},
            {"title":"Song","primary-type":"Single","secondary-types":[]}
        ]"""
        assertEquals("Song", album(groups))
    }

    @Test fun doesNotFallBackToKnownLiveRelease() {
        val groups = """[{"title":"Red Rocks","primary-type":"Album","secondary-types":["Live"]}]"""
        assertNull(album(groups, """[{"title":"Red Rocks","status":"Official"}]"""))
    }
}

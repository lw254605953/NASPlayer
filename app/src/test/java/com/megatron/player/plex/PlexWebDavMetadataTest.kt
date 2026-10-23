package com.megatron.player.plex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PlexWebDavMetadataTest {
    @Test fun originalFilePathSurvivesMetadataAndDiskCache() {
        val movie = PlexMetadataParser.parseMovie(JSONObject("""
            {"ratingKey":"123","title":"电影","Media":[{"Part":[{
              "key":"/library/parts/9/1/file.mkv","file":"/movies/中文 空格/100%25.mkv"
            }]}]}
        """))!!
        val stored = PlexMovieCodec.toJson(movie)
        assertEquals("/movies/中文 空格/100%25.mkv", stored.getJSONArray("parts").getJSONObject(0).optString("filePath"))
        assertEquals(stored.toString(), PlexMovieCodec.toJson(PlexMovieCodec.fromJson(stored)!!).toString())
    }
}

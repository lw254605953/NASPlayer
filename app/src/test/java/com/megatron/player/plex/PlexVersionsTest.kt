package com.megatron.player.plex

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlexVersionsTest {
    @Test fun allCompleteVersionsSurviveCacheAndSegmentedMediaIsExcluded() {
        val movie = PlexMetadataParser.parseMovie(JSONObject("""{
            "ratingKey":"101","title":"电影","Media":[
              {"container":"mkv","videoCodec":"hevc","audioCodec":"eac3","width":3840,"height":2160,
               "Part":[{"key":"/library/parts/1/file.mkv","file":"/Films/电影.4k.mkv","size":12000000000}]},
              {"container":"mp4","videoCodec":"h264","height":1080,
               "Part":[{"key":"/library/parts/2/file.mp4","file":"/Films/电影.mp4","size":2000000000}]},
              {"Part":[{"key":"/library/parts/3/a"},{"key":"/library/parts/4/b"}]},
              {"Part":[{"key":"https://evil.example/file"}]}
            ]} """))!!
        assertEquals(listOf(2160, 1080), movie.parts.map { it.height })
        assertEquals(12000000000L, movie.parts.first().sizeBytes)
        assertEquals(movie.parts, PlexMovieCodec.fromJson(PlexMovieCodec.toJson(movie))!!.parts)
        val response = JSONObject().put("MediaContainer", JSONObject().put("Metadata", org.json.JSONArray().put(JSONObject("""{
            "ratingKey":"101","Media":[{"Part":[{"key":"/library/parts/1/file.mkv","file":"/4k.mkv"}]},
            {"Part":[{"key":"/library/parts/2/file.mp4","file":"/1080.mp4"}]}]}"""))))
        val credentials = PlexClient("test")
        assertEquals("/1080.mp4", credentials.selectFilePath(response, "101", movie.parts[1].key))
    }
}

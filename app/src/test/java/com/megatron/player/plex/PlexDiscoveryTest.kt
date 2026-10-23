package com.megatron.player.plex

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PlexDiscoveryTest {
    private val client = PlexClient("discovery-regression")

    @Test fun accountResourcesUseClientIdentifier() {
        val response = JSONArray("""[
          {"name":"My NAS","product":"Plex Media Server","provides":"server",
           "clientIdentifier":"nas-machine-id","accessToken":"fake-server-token",
           "connections":[{"uri":"http://nas.example.test:32400","local":true,"relay":false}]}
        ]""")
        val servers = client.parseServerResources(response)
        assertEquals("Must retain a valid server returned by Plex account resources", 1, servers.size)
        assertEquals("nas-machine-id", servers.single().machineIdentifier)
        assertEquals("fake-server-token", servers.single().token)
        assertEquals("http://nas.example.test:32400", servers.single().connections.single().address)
    }

    @Test fun playerDevicesMustNotAppearAsServers() {
        val response = JSONArray("""[
          {"name":"Android Player","provides":"player","clientIdentifier":"phone-id",
           "accessToken":"fake-player-token","connections":[]}
        ]""")
        assertTrue(client.parseServerResources(response).isEmpty())
    }

    @Test fun olderMachineIdentifierPayloadRemainsReadable() {
        val response = JSONArray("""[
          {"name":"Legacy server","provides":"server","machineIdentifier":"older-id",
           "accessToken":"fake-token","connections":[]}
        ]""")
        assertEquals("older-id", client.parseServerResources(response).single().machineIdentifier)
    }
}

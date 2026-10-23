package com.megatron.player.backup

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class BackupArchiveTest {
    @Test fun passwordFreeBackupIncludesCredentialsAndRestoresWithoutPassword() {
        val payload = JSONObject().put("nasPassword", "example-nas-secret").put("plexToken", "example-plex-token")
        val bytes = BackupArchive.encode(payload, charArrayOf())
        assertTrue(String(bytes).contains("example-nas-secret"))
        val decoded = BackupArchive.decodeContents(bytes, charArrayOf())
        assertFalse(decoded.passwordProtected)
        assertEquals(payload.toString(), decoded.payload.toString())
    }

    @Test fun passwordFreeArchiveChecksCorruptionAndEncryptedArchiveStillRequiresPassword() {
        val plain = JSONObject(String(BackupArchive.encode(JSONObject().put("x", 1), charArrayOf())))
        plain.put("data", "{\"x\":2}")
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.decode(plain.toString().toByteArray(), charArrayOf()) }
        val encrypted = BackupArchive.encode(JSONObject(), "password123".toCharArray())
        assertThrows(IllegalArgumentException::class.java) { BackupArchive.decode(encrypted, charArrayOf()) }
        assertTrue(BackupArchive.decodeContents(encrypted, "password123".toCharArray()).passwordProtected)
    }
    @Test fun archiveIsPortableAuthenticatedAndDoesNotExposeCredentials() {
        val pass = "备份密码123456".toCharArray()
        val payload = JSONObject().put("token", "secret-token-example").put("artist", "中文")
        val a = BackupArchive.encode(payload, pass)
        val b = BackupArchive.encode(payload, pass)
        assertFalse(a.contentEquals(b))
        assertFalse(String(a).contains("secret-token-example"))
        assertEquals(payload.toString(), BackupArchive.decode(a, pass).toString())
    }
    @Test fun wrongPasswordAndModifiedCiphertextAreRejected() {
        val original = BackupArchive.encode(JSONObject().put("server", "nas"), "password123".toCharArray())
        try { BackupArchive.decode(original, "wrong-pass".toCharArray()); fail("Wrong password accepted") } catch (_: Exception) { }
        val envelope = JSONObject(String(original)); val data = envelope.getString("data"); envelope.put("data", (if (data[0] == 'A') "B" else "A") + data.drop(1))
        try { BackupArchive.decode(envelope.toString().toByteArray(), "password123".toCharArray()); fail("Tampered ciphertext accepted") } catch (_: Exception) { }
    }
    @Test fun unsupportedKdfAndVersionAreRejectedBeforeDecryption() {
        val bytes = BackupArchive.encode(JSONObject(), "password123".toCharArray())
        val envelope = JSONObject(String(bytes)).put("iterations", Int.MAX_VALUE)
        try { BackupArchive.decode(envelope.toString().toByteArray(), "password123".toCharArray()); fail("Unbounded KDF accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun retentionOnlyTouchesThisInstallationAndKeepsFiveNewest() {
        val owner = "a".repeat(32); val other = "b".repeat(32)
        fun file(who: String, i: Int) = "mp-backup-$who-${1700000000000L+i}-abcdef01.mpbackup"
        val names = (0..6).map { file(owner, it) } + file(other, 0) + "photo.jpg" + "mp-backup-invalid.mpbackup"
        assertEquals(listOf(file(owner, 1), file(owner, 0)), BackupNames.obsolete(names, owner))
        assertTrue(BackupNames.valid(file(other, 0)))
    }
}

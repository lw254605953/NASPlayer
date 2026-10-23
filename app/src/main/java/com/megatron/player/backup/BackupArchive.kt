package com.megatron.player.backup

import org.json.JSONObject
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable archives. An explicitly password-free archive includes credentials as plaintext. */
internal object BackupArchive {
    const val MAX_BYTES = 432 * 1024 * 1024
    private const val ITERATIONS = 210_000
    private const val AAD = "MegatronPlayer.Backup.v1"
    private const val PLAIN = "MegatronPlayer.Backup.plain.v1"
    private val random = SecureRandom()
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    fun encode(payload: JSONObject, password: CharArray): ByteArray {
        require(password.isEmpty() || password.size in 8..128) { "备份密码需为8至128位" }
        val plain = payload.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= 32 * 1024 * 1024) { "备份资料过大" }
        if (password.isEmpty()) return JSONObject().put("format", PLAIN).put("version", 1)
            .put("data", String(plain, Charsets.UTF_8)).put("sha256", digest(plain)).toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray())
        val encoder = Base64.getEncoder()
        return JSONObject().put("format", AAD).put("version", 1).put("iterations", ITERATIONS)
            .put("salt", encoder.encodeToString(salt)).put("iv", encoder.encodeToString(iv))
            .put("data", encoder.encodeToString(cipher.doFinal(plain))).toString().toByteArray(Charsets.UTF_8)
    }
    fun decode(bytes: ByteArray, password: CharArray): JSONObject = decodeContents(bytes, password).payload

    fun decodeContents(bytes: ByteArray, password: CharArray): BackupContents {
        require(bytes.size <= MAX_BYTES && (password.isEmpty() || password.size in 8..128)) { "备份文件或密码无效" }
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        if (envelope.getString("format") == PLAIN) {
            require(envelope.getInt("version") == 1) { "不支持这个备份版本" }
            val plain = envelope.getString("data").toByteArray(Charsets.UTF_8)
            require(plain.size <= 32 * 1024 * 1024 && digest(plain) == envelope.getString("sha256")) { "备份完整性校验失败" }
            return BackupContents(JSONObject(String(plain, Charsets.UTF_8)), passwordProtected = false)
        }
        require(password.size in 8..128) { "请输入备份密码" }
        require(envelope.getString("format") == AAD && envelope.getInt("version") == 1 && envelope.getInt("iterations") == ITERATIONS) { "不支持这个备份版本" }
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(envelope.getString("salt")); val iv = decoder.decode(envelope.getString("iv"))
        require(salt.size == 16 && iv.size == 12) { "备份文件损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray())
        val plain = cipher.doFinal(decoder.decode(envelope.getString("data")))
        require(plain.size <= 32 * 1024 * 1024) { "备份资料过大" }
        return BackupContents(JSONObject(String(plain, Charsets.UTF_8)), passwordProtected = true)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal data class BackupContents(val payload: JSONObject, val passwordProtected: Boolean)

internal object BackupNames {
    private val pattern = Regex("mp-backup-([a-f0-9]{32})-([0-9]{13})-([a-f0-9]{8})\\.mpbackup")
    fun valid(name: String) = pattern.matches(name)
    fun owner(name: String): String? = pattern.matchEntire(name)?.groupValues?.get(1)
    fun obsolete(names: List<String>, owner: String): List<String> = names.filter { owner(it) == owner }.sortedDescending().drop(5)
}

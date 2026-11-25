package app.muse.player.data

import android.util.Base64
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 网易云网页端(weapi)与客户端(eapi)的请求加密，算法与开源社区实现一致。 */
object NeteaseCrypto {
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val RSA_MODULUS =
        "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val RSA_EXPONENT = "010001"
    private val random = SecureRandom()

    private fun aesCbc(data: ByteArray, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(), "AES"),
            IvParameterSpec(IV.toByteArray()),
        )
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }

    /** 返回 weapi 表单：params + encSecKey */
    fun weapi(json: String): Map<String, String> {
        val secret = buildString { repeat(16) { append(BASE62[random.nextInt(BASE62.length)]) } }
        val params = aesCbc(aesCbc(json.toByteArray(), PRESET_KEY).toByteArray(), secret)
        val rsa = BigInteger(1, secret.reversed().toByteArray())
            .modPow(BigInteger(RSA_EXPONENT, 16), BigInteger(RSA_MODULUS, 16))
        return mapOf(
            "params" to params,
            "encSecKey" to rsa.toString(16).padStart(256, '0'),
        )
    }

    /** eapi：path 形如 /api/song/enhance/player/url/v1，返回大写十六进制 params */
    fun eapi(path: String, json: String): String {
        val digest = md5Hex("nobody${path}use${json}md5forencrypt")
        val message = "$path-36cd479b6b5-$json-36cd479b6b5-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(), "AES"))
        return cipher.doFinal(message.toByteArray()).joinToString("") { "%02X".format(it) }
    }

    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

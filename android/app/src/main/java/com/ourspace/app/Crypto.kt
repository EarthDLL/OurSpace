package com.ourspace.app

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 应用层信封加密，严格实现 docs/API.md 第 1 节：
 *
 *   key    = SHA-256(UTF-8(payloadSecret))            -> 32 字节（AES-256）
 *   算法   = AES/GCM/NoPadding
 *   iv     = 12 字节随机
 *   tag    = 128 位（16 字节）
 *   信封   = {"e":"<base64(iv || ciphertext || tag)>", "v":1}
 *
 * 请求体与响应体统一使用该信封；只有 GET /api/v1/health 是明文。
 */
object Crypto {

    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val TAG_LEN = 16
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private val random = SecureRandom()

    /** key = SHA-256(UTF-8(payloadSecret)) */
    fun deriveKey(payloadSecret: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(payloadSecret.toByteArray(Charsets.UTF_8))

    /** 加密并包成信封 JSON：{"e":"base64(iv||ct||tag)"} */
    fun encrypt(payloadSecret: String, plain: String): String {
        val key = deriveKey(payloadSecret)
        val iv = ByteArray(IV_LEN)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

        // JCE 在 AES/GCM 下把 tag 附在密文末尾，所以 iv || out 天然就是 iv||ciphertext||tag
        val all = ByteArray(iv.size + out.size)
        System.arraycopy(iv, 0, all, 0, iv.size)
        System.arraycopy(out, 0, all, iv.size, out.size)

        // 只为兼容契约，信封里只有 e 一个字段
        val env = JSONObject()
        env.put("e", Base64.encodeToString(all, Base64.NO_WRAP))
        return env.toString()
    }

    /**
     * 解开信封 JSON 得到明文。
     * @throws CryptoException 密钥不匹配 / 数据损坏 / 不是合法信封
     */
    fun decrypt(payloadSecret: String, envelopeJson: String): String {
        val e = try {
            JSONObject(envelopeJson).optString("e", "")
        } catch (t: Throwable) {
            ""
        }
        if (e.isEmpty()) throw CryptoException("响应不是合法的加密信封")

        val all = try {
            Base64.decode(e, Base64.DEFAULT)
        } catch (t: Throwable) {
            throw CryptoException("base64 解码失败")
        }
        if (all.size < IV_LEN + TAG_LEN) throw CryptoException("信封数据过短")

        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)

        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(payloadSecret), "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (t: Throwable) {
            // 最常见的原因：两端 payloadSecret 不一致
            throw CryptoException("解密失败，请检查配对密钥是否与后端 payloadSecret 一致")
        }
    }

    /** 工具：把任意 JSONObject 加密成信封字符串 */
    fun encryptJson(payloadSecret: String, obj: JSONObject): String =
        encrypt(payloadSecret, obj.toString())

    /** 工具：把信封字符串解密成 JSONObject */
    fun decryptJson(payloadSecret: String, envelopeJson: String): JSONObject =
        JSONObject(decrypt(payloadSecret, envelopeJson))
}

class CryptoException(message: String) : Exception(message)

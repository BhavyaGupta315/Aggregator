package com.example.aggregator

import android.util.Base64
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    // 1. My Private Key (Who am I?)
    private const val MY_PRIVATE_KEY_STR = "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCZmU+zaiH+BBDSjEGrl9Fqlihnp/WgvbVRD54VaLqymC8XWsUr75zJ+CVswMu+RX3hJBva+ZvAYvWo0HrC9vw7aSc2KI1TB4apXlRCXU19v4qkjWC+DAzu1c/XCD8Gax/Rijewl55gLBj74SKAeS+/oex3Uk/UXRiGAsX2t6q7ZuSGV3/r7Z8nhl8bnH5tJcAejX8bC2Ft/S18xYNDQY80lb+uTKvMAs/YRkcrEcMUi7dmAmXpVHklpEpSEsP3sWB3xBUT72DHp4PEnqngaaeNzv+xGnC+KSV9IZeBAmEdfsu+hay2eJGpBY9HnnRSai9M6GBbpHPA9m+e7cn8KhunAgMBAAECggEADhhRhgdRQzwgRxHz3Mk7wGozHv/8vFlsUBn8yb4ok8b/W0dLHLMKUkZhOM+gYr+Bw61KmReawVbTcEBhY47CqrkFjiS+g0YHh3dOiCCDwexXzq3imM4GScx5HCR3lCE0dEgYwC3JrM2fJU4NmniEdzNmvbRh+vzoCTQr8m38TuIHvy9wsCAHEw5yTeLZ7hwvuCtd+EsUH3q503ZXEovnCndCCT+TUyp9SnUV8oSQ7mMF3giiUHz4eyBRyc18YXGPHXkeFFl4dk7kJh5pJAI27vE6z+fn2rPN+tBi/58hqQwUKf15nQEZmeYQs+iuJ694LKxiLnkz1pRArF6SnXhH4QKBgQDZKlj6vE2nOHahvBUe609a9dKcrGXQsE0Ps3A8qmG8LXVUMvBYPKy2Aknrny1uZYJCDvLf+yRhPvjFLKeT2n98dt7OoEL/cjp2H95tjEE0CkQ/QBw+tCuLxbXr059NLxQXzjJxoACxdr5cYDQ5V5pn+SItGiRGMQxtcA9x5Ck4HwKBgQC1EPCmEQfzt1GTeQUf9K3g2DtC/Umv9i5mkZAUwXEqs20wodup2tvT/DH5qI95l3TjNDgeGn0DjLvTuGRejtotmd9rghSM7pxeZLSwWXa8K2uwId9NQZkczC2HwAam84k29Uc+Yzq5KYYArPQlVwdK2rBejY1cT3aZA+hZHRLLeQKBgDsbyahFhKVVOwT+mokV5z2M10yJqBTLR85UOuJoRb3gaaUHUF/T8/Z+XPxjEQyRWIj+ZKEOTHKjnzab1ujpefW4rSB0gofg4YSxW+tZV44AlV7Z5lYy1/9tSvzrVtq9S6zHFPfUYxvqhBnEnbJV49MU/nZkPSdVxlorPCj1vzplAoGAJ6CAemfJzL33HYZj5719/HJ2bc/PO7JuL0Z2OQSpBsZkBDu4PnFGoRtVuKT6WYsbKsp36aa0a312cfyuAr/S4h4F9ppucvWZxVLW9K1vvfjmxZJ0M41CvDm3UTlme01bX2rI38+Jv5Jl4Gn5uN5WGzzHUro6ENTXSN/BDxe80EkCgYAc5uKPUBERISY9mYeX8IFpnI3u3rdkzYDKm15dyG1Yuzj4Qhq8Wdt7RGv53eybNrkVnfZZCCHqetl6QHZfX23Et43f49W3IUWHoBuf7yjlwU162Er3FOMs8Msf2Q4tTbD048O0KPQBeHrFb/luSta8zKGIX6lfTjccgrNGTqoz/g=="

    // 2. Their Public Key (Who do I trust?)
    private const val OTHER_PUBLIC_KEY_STR = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1bJwfkyh4nhvg/JnkpdENAHSbgBPLsgqjocR8KX56ALYo7X7yrmC1+Irjb7T75H+su6Q443rJF5elvgqzY9OXN6h9e9Fa2vYk5AqEHh5WGCCcKoRQ8pNJwBzKOlOO/zO7OfnzMLoVhkr8tkY2TNodv8L8yNBozEEb3V9yXj9CMwO2hRwhzObVVsb1lcG/0MEvJFp4a/ly7Dy6N2nNxeWCUvaLOzDBhgN0jQBZ7iuADWpfO8LlY1IBlKhATUNIb62Xjbnsl+hkU+49nvYEwfNf+6oz/g0yGyb18A27spf0y5+IYgDbD7HzJsihG98fblZYBMgwwb78uMiSn3thHiqtwIDAQAB"

    // --- APDU Commands for 2-Step Auth ---
    val CMD_AUTH_SEND_KEY = "AUTH_KEY".toByteArray(Charsets.UTF_8) // Step 1: Send Encrypted Session Key
    val CMD_AUTH_SEND_SIG = "AUTH_SIG".toByteArray(Charsets.UTF_8) // Step 2: Send Signature

    // --- RSA Helpers ---

    fun getMyPrivateKey(): PrivateKey {
        val keyBytes = Base64.decode(MY_PRIVATE_KEY_STR, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    fun getOtherPublicKey(): PublicKey {
        val keyBytes = Base64.decode(OTHER_PUBLIC_KEY_STR, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    // --- Operations ---

    // Generate a fresh AES Session Key (16 bytes / 128 bit)
    fun generateSessionKey(): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(128)
        return keyGen.generateKey().encoded
    }

    // RSA Encrypt (Confidentiality: Only THEY can read it)
    fun rsaEncrypt(data: ByteArray, publicKey: PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }

    // RSA Decrypt (Confidentiality: Only I can read it)
    fun rsaDecrypt(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(data)
    }

    // RSA Sign (Authentication: Prove I sent it)
    fun rsaSign(data: ByteArray, privateKey: PrivateKey): ByteArray {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    // RSA Verify (Authentication: Prove THEY sent it)
    fun rsaVerify(data: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(data)
        return verifier.verify(signature)
    }

    // XOR Encryption for File Data (Using the Session Key)
    fun xorEncryptDecrypt(data: ByteArray, sessionKey: ByteArray): ByteArray {
        val output = ByteArray(data.size)
        for (i in data.indices) {
            output[i] = (data[i].toInt() xor sessionKey[i % sessionKey.size].toInt()).toByte()
        }
        return output
    }
}
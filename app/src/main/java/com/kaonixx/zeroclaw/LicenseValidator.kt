package com.kaonixx.zeroclaw

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * License validation for ZeroClaw Android.
 *
 * V1: Simple baked-in ed25519 verification.
 * License keys are signed messages: "ZCLAW-{version}:{email}:{expiry}"
 * No network required for verification.
 */
object LicenseValidator {

    private const val PREFS_NAME = "zeroclaw_license"
    private const val KEY_LICENSE = "license_key"
    private const val KEY_SIGNATURE = "license_signature"
    private const val KEY_EXPIRY = "license_expiry"

    // ed25519 public key (DER SPKI format, base64-encoded)
    private const val PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAdiz2HLYTOBmE5Fwy1Kpn8LmrRuOutg5u/2qPvNDjhAo="

    /**
     * Check if the current installation has a valid Pro license.
     */
    fun isPro(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val licenseKey = prefs.getString(KEY_LICENSE, null) ?: return false
        val signature = prefs.getString(KEY_SIGNATURE, null) ?: return false
        val expiry = prefs.getLong(KEY_EXPIRY, 0L)

        // Check expiry (0 = never expires)
        if (expiry > 0 && System.currentTimeMillis() > expiry) return false

        return verifySignature(licenseKey, signature)
    }

    /**
     * Activate a license key.
     * Key format: "ZCLAW-{version}:{email}:{expiry_timestamp}"
     * Signature is base64-encoded ed25519 signature.
     */
    fun activate(context: Context, licenseKey: String, signatureB64: String, expiryMs: Long = 0L): Boolean {
        if (!verifySignature(licenseKey, signatureB64)) return false

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LICENSE, licenseKey)
            .putString(KEY_SIGNATURE, signatureB64)
            .putLong(KEY_EXPIRY, expiryMs)
            .apply()
        return true
    }

    /**
     * Deactivate license (revert to Free).
     */
    fun deactivate(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun verifySignature(message: String, signatureB64: String): Boolean {
        return try {
            val keyBytes = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val publicKey: PublicKey = keyFactory.generatePublic(keySpec)

            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            sig.update(message.toByteArray(Charsets.UTF_8))
            sig.verify(Base64.decode(signatureB64, Base64.DEFAULT))
        } catch (e: Exception) {
            android.util.Log.e("ZeroClaw", "License verify failed", e)
            false
        }
    }

    fun getLicenseKey(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LICENSE, null)
    }

    fun getExpiryTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_EXPIRY, 0L)
    }
}

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
 * V1: Simple baked-in public key verification.
 * License keys are ed25519-signed messages: "ZCLAW-{version}:{email}:{expiry_timestamp}"
 * App verifies the signature against the baked-in public key.
 * No network required. Key generated offline on the dev machine.
 */
object LicenseValidator {

    private const val PREFS_NAME = "zeroclaw_license"
    private const val KEY_LICENSE = "license_key"
    private const val KEY_SIGNATURE = "license_signature"
    private const val KEY_EXPIRY = "license_expiry"

    // ed25519 public key (base64-encoded, 32 bytes)
    // TODO: Replace with actual generated keypair
    private const val PUBLIC_KEY_B64 = "REPLACE_ME_WITH_REAL_PUBLIC_KEY"

    /**
     * Check if the current installation has a valid Pro license.
     * If no license or signature invalid → Free tier.
     */
    fun isPro(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val licenseKey = prefs.getString(KEY_LICENSE, null) ?: return false
        val signature = prefs.getString(KEY_SIGNATURE, null) ?: return false
        val expiry = prefs.getLong(KEY_EXPIRY, 0L)

        // Check expiry
        if (expiry > 0 && System.currentTimeMillis() > expiry) return false

        // Verify signature
        return verifySignature(licenseKey, signature)
    }

    /**
     * Activate a license key. Key format: "ZCLAW-{version}:{email}:{expiry_timestamp}"
     * Signature is generated on the server side using the private key.
     *
     * Returns true if the key is valid.
     */
    fun activate(context: Context, licenseKey: String, signatureB64: String, expiryMs: Long = 0L): Boolean {
        if (!verifySignature(licenseKey, signatureB64)) return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_LICENSE, licenseKey)
            putString(KEY_SIGNATURE, signatureB64)
            putLong(KEY_EXPIRY, expiryMs)
            apply()
        }
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
            sig.update(message.toByteArray())
            sig.verify(Base64.decode(signatureB64, Base64.DEFAULT))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Generate a license key offline (run on dev machine, not in app):
     *
     * ```bash
     * # Generate keypair
     * openssl genpkey -algorithm ed25519 -out private.pem
     * openssl pkey -in private.pem -pubout -out public.pem
     * base64 -w0 public.pem  # → put in PUBLIC_KEY_B64 above
     *
     * # Sign a license
     * echo -n "ZCLAW-1:kaos@example.com:1893456000000" | openssl pkeyutl -sign -inkey private.pem | base64 -w0
     * ```
     */

    fun getLicenseKey(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LICENSE, null)
    }

    fun getExpiryTime(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_EXPIRY, 0L)
    }
}

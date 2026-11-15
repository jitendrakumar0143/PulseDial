package com.example.telecom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

object DialerHelper {

    fun isDefaultDialer(context: Context): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return telecom.defaultDialerPackage == context.packageName
    }

    fun placeCall(context: Context, rawNumber: String): Boolean {
        val number = rawNumber.trim()
        if (number.isEmpty()) return false
        val uri = Uri.fromParts("tel", number, null)

        val hasCallPhone = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCallPhone) {
            val telecom = context.getSystemService(TelecomManager::class.java)
            if (telecom != null && isDefaultDialer(context)) {
                return try {
                    telecom.placeCall(uri, Bundle())
                    true
                } catch (_: SecurityException) {
                    launchActionDial(context, uri)
                }
            }
            return try {
                val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                true
            } catch (_: SecurityException) {
                launchActionDial(context, uri)
            }
        }
        return launchActionDial(context, uri)
    }

    private fun launchActionDial(context: Context, uri: Uri): Boolean {
        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

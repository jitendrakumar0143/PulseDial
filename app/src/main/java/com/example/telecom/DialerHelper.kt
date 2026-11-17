package com.example.telecom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.DialerPreferences
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DialerHelper {

    fun isDefaultDialer(context: Context): Boolean {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return telecom.defaultDialerPackage == context.packageName
    }

    /**
     * Initiates an outgoing call honoring explicit SIM selection, user's saved Default Calling SIM,
     * or prompting with a Dual SIM chooser dialog when set to "Always Ask".
     */
    fun initiateCall(
        context: Context,
        rawNumber: String,
        explicitAccountHandle: PhoneAccountHandle? = null
    ): Boolean {
        val number = rawNumber.trim()
        if (number.isEmpty()) return false
        if (explicitAccountHandle != null) {
            return placeCall(context, number, explicitAccountHandle)
        }

        val sims = DualSimManager.getAvailableSims(context)
        if (sims.size <= 1) {
            return placeCall(context, number, sims.firstOrNull()?.accountHandle)
        }

        val prefs = DialerPreferences(context)
        val preferredSim = DualSimManager.resolvePreferredSim(sims, prefs.preferredSimAccountId)
        if (preferredSim != null) {
            return placeCall(context, number, preferredSim.accountHandle)
        }

        val activity = DualSimManager.findActivity(context) ?: return placeCall(context, number, null)
        showSimChooserDialog(activity, number, sims, prefs)
        return true
    }

    private fun showSimChooserDialog(
        context: Context,
        number: String,
        sims: List<SimCardInfo>,
        prefs: DialerPreferences
    ) {
        val density = context.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padTop = (12 * density).toInt()
        val spacing = (10 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padTop, padH, 0)
        }

        val rememberCheckBox = MaterialCheckBox(context).apply {
            text = context.getString(R.string.sim_chooser_remember_default)
            minHeight = (48 * density).toInt()
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.sim_chooser_dialog_title, number))
            .setView(container)
            .setNegativeButton(R.string.sim_chooser_cancel, null)
            .create()

        sims.forEachIndexed { index, sim ->
            val btn = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = sim.fullDescription()
                contentDescription = sim.fullDescription()
                setIconResource(R.drawable.ic_sim_card)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = (52 * density).toInt()
                cornerRadius = (16 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = spacing
                }
                setOnClickListener {
                    if (rememberCheckBox.isChecked) {
                        prefs.preferredSimAccountId = sim.accountId
                    }
                    dialog.dismiss()
                    placeCall(context, number, sim.accountHandle)
                }
            }
            container.addView(btn)
        }

        rememberCheckBox.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = spacing
        }
        container.addView(rememberCheckBox)

        dialog.show()
    }

    fun placeCall(
        context: Context,
        rawNumber: String,
        phoneAccountHandle: PhoneAccountHandle? = null
    ): Boolean {
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
                val extras = Bundle().apply {
                    if (phoneAccountHandle != null) {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                    }
                }
                return try {
                    telecom.placeCall(uri, extras)
                    true
                } catch (_: SecurityException) {
                    launchActionDial(context, uri)
                }
            }
            return try {
                val callIntent = Intent(Intent.ACTION_CALL, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (phoneAccountHandle != null) {
                        putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                    }
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

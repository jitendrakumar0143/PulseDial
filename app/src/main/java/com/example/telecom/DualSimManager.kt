package com.example.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

data class SimCardInfo(
    val accountHandle: PhoneAccountHandle?,
    val accountId: String,
    val slotIndex: Int,
    val label: String,
    val carrierName: String,
    val number: String = ""
) {
    fun shortLabel(): String {
        return if (slotIndex > 0) "SIM $slotIndex" else label.ifBlank { "SIM" }
    }

    fun displayLabel(): String {
        val cleanSlot = shortLabel()
        val carrier = carrierName.trim().ifBlank { label.trim() }
        return if (carrier.isNotBlank() && !carrier.equals(cleanSlot, ignoreCase = true)) {
            "$cleanSlot • $carrier"
        } else {
            cleanSlot
        }
    }

    fun fullDescription(): String {
        val base = displayLabel()
        val cleanNum = number.trim()
        return if (cleanNum.isNotBlank()) "$base ($cleanNum)" else base
    }
}

object DualSimManager {

    @SuppressLint("MissingPermission")
    fun getAvailableSims(context: Context): List<SimCardInfo> {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val handles: List<PhoneAccountHandle> = try {
            telecom.callCapablePhoneAccounts.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (handles.isEmpty()) return emptyList()

        val activeSubs: List<SubscriptionInfo> = if (hasPhoneState) {
            try {
                val subMgr = context.getSystemService(SubscriptionManager::class.java)
                subMgr?.activeSubscriptionInfoList.orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val sims = mutableListOf<SimCardInfo>()
        val seenIds = HashSet<String>()

        handles.forEachIndexed { index, handle ->
            val accountId = runCatching { handle.id }.getOrNull().orEmpty()
            if (accountId.isBlank() || !seenIds.add(accountId)) return@forEachIndexed

            val phoneAccount: PhoneAccount? = runCatching { telecom.getPhoneAccount(handle) }.getOrNull()
            val matchedSub = matchSubscription(handle, phoneAccount, activeSubs)

            val slot = when {
                matchedSub != null && matchedSub.simSlotIndex >= 0 -> matchedSub.simSlotIndex + 1
                else -> index + 1
            }
            val label = phoneAccount?.label?.toString()?.trim().orEmpty()
                .ifBlank { "SIM $slot" }
            val carrier = matchedSub?.carrierName?.toString()?.trim().orEmpty()
                .ifBlank { phoneAccount?.shortDescription?.toString()?.trim().orEmpty() }
                .ifBlank { label }
            val number = runCatching { matchedSub?.number?.trim().orEmpty() }.getOrDefault("")

            sims.add(
                SimCardInfo(
                    accountHandle = handle,
                    accountId = accountId,
                    slotIndex = slot,
                    label = label,
                    carrierName = carrier,
                    number = number
                )
            )
        }

        return sims.sortedBy { it.slotIndex }
    }

    private fun matchSubscription(
        handle: PhoneAccountHandle,
        account: PhoneAccount?,
        subs: List<SubscriptionInfo>
    ): SubscriptionInfo? {
        if (subs.isEmpty()) return null
        val handleId = runCatching { handle.id }.getOrNull().orEmpty()
        val accountLabel = account?.label?.toString()?.trim().orEmpty()
        return subs.firstOrNull { sub ->
            val subIdStr = sub.subscriptionId.toString()
            val iccId = sub.iccId.orEmpty()
            (handleId.isNotBlank() && (
                handleId == subIdStr ||
                    (iccId.isNotBlank() && handleId.contains(iccId, ignoreCase = true))
                )) || (accountLabel.isNotBlank() &&
                sub.carrierName?.toString()?.equals(accountLabel, ignoreCase = true) == true)
        }
    }

    fun findSimByAccountId(sims: List<SimCardInfo>, accountId: String?): SimCardInfo? {
        val cleanId = accountId?.trim().orEmpty()
        if (cleanId.isEmpty()) return null
        return sims.firstOrNull { it.accountId == cleanId }
    }

    fun resolvePreferredSim(sims: List<SimCardInfo>, preferredAccountId: String?): SimCardInfo? {
        val cleanId = preferredAccountId?.trim().orEmpty()
        if (cleanId.isEmpty()) return null
        return findSimByAccountId(sims, cleanId)
    }

    fun resolveAlternateSim(sims: List<SimCardInfo>, currentAccountId: String?): SimCardInfo? {
        if (sims.size < 2) return null
        val cleanCurrent = currentAccountId?.trim().orEmpty()
        if (cleanCurrent.isNotEmpty()) {
            sims.firstOrNull { it.accountId != cleanCurrent }?.let { return it }
        }
        return sims.getOrNull(1)
    }

    fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) {
                return if (!current.isFinishing && !current.isDestroyed) current else null
            }
            current = current.baseContext
        }
        return (current as? Activity)?.takeIf { !it.isFinishing && !it.isDestroyed }
    }
}

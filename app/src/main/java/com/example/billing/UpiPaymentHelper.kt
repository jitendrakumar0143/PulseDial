package com.example.billing

import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class UpiTxnStatus {
    SUCCESS,
    SUBMITTED,
    FAILURE,
    CANCELLED,
    UNKNOWN
}

data class UpiParsedResponse(
    val status: UpiTxnStatus,
    val txnId: String,
    val txnRef: String,
    val approvalRefNo: String,
    val responseCode: String
)

object UpiPaymentHelper {

    const val UPI_VPA = "elx@ptaxis"
    const val UPI_PAYEE_NAME = "PulseDial Premium"
    const val UPI_AMOUNT_INR = "299.00"
    const val UPI_AMOUNT_LONG = 299L
    const val UPI_CURRENCY = "INR"
    const val UPI_PLAN_ID = "upi_monthly_299"

    const val WHATSAPP_SUPPORT_LOCAL = "9012634797"
    const val WHATSAPP_SUPPORT_E164 = "919012634797"
    const val ADMIN_EMAIL = "jitendrakumar888126@gmail.com"

    fun isAdminEmail(email: String?): Boolean {
        return email?.trim()?.equals(ADMIN_EMAIL, ignoreCase = true) == true
    }

    fun generateTxnRef(): String = "PD${System.currentTimeMillis()}"

    fun formatAmountInr(amountInr: Long): String = "${amountInr.coerceAtLeast(1L)}.00"

    fun formatWhatsappE164(rawNumber: String): String {
        val digits = rawNumber.filter { it.isDigit() }
        return when {
            digits.isBlank() -> WHATSAPP_SUPPORT_E164
            digits.length == 10 -> "91$digits"
            else -> digits
        }
    }

    fun buildUpiPaymentUri(
        txnRef: String,
        note: String = "PulseDial 1 Month Subscription",
        upiVpa: String = UPI_VPA,
        amountInr: Long = UPI_AMOUNT_LONG
    ): Uri {
        return Uri.Builder()
            .scheme("upi")
            .authority("pay")
            .appendQueryParameter("pa", upiVpa.ifBlank { UPI_VPA })
            .appendQueryParameter("pn", UPI_PAYEE_NAME)
            .appendQueryParameter("tr", txnRef)
            .appendQueryParameter("tn", note)
            .appendQueryParameter("am", formatAmountInr(amountInr))
            .appendQueryParameter("cu", UPI_CURRENCY)
            .build()
    }

    fun buildUpiPayIntent(
        txnRef: String,
        upiVpa: String = UPI_VPA,
        amountInr: Long = UPI_AMOUNT_LONG
    ): Intent {
        return Intent(
            Intent.ACTION_VIEW,
            buildUpiPaymentUri(txnRef = txnRef, upiVpa = upiVpa, amountInr = amountInr)
        )
    }

    fun buildUpiChooserIntent(
        txnRef: String,
        chooserTitle: String,
        upiVpa: String = UPI_VPA,
        amountInr: Long = UPI_AMOUNT_LONG
    ): Intent {
        return Intent.createChooser(
            buildUpiPayIntent(txnRef = txnRef, upiVpa = upiVpa, amountInr = amountInr),
            chooserTitle
        )
    }

    fun buildWhatsAppSupportIntent(
        userName: String,
        userEmail: String,
        deviceTag: String,
        pendingTxnRef: String? = null,
        supportNumberRaw: String = WHATSAPP_SUPPORT_E164
    ): Intent {
        val message = buildString {
            append("Hello PulseDial Support, I need assistance with my account.")
            append("\nName: ").append(userName.ifBlank { "PulseDial User" })
            if (userEmail.isNotBlank()) {
                append("\nEmail: ").append(userEmail)
            }
            if (deviceTag.isNotBlank()) {
                append("\nDevice ID: ").append(deviceTag)
            }
            if (!pendingTxnRef.isNullOrBlank()) {
                append("\nPayment Ref: ").append(pendingTxnRef)
            }
        }
        val e164 = formatWhatsappE164(supportNumberRaw)
        val encoded = URLEncoder.encode(message, StandardCharsets.UTF_8.name())
        val uri = Uri.parse("https://wa.me/$e164?text=$encoded")
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /**
     * Parses NPCI UPI Intent response strings returned via ActivityResult (e.g.,
     * "txnId=UPI123&responseCode=00&Status=SUCCESS&txnRef=PD1700&ApprovalRefNo=456789").
     */
    fun parseUpiResponse(
        rawResponse: String?,
        fallbackStatus: String? = null,
        defaultTxnRef: String = ""
    ): UpiParsedResponse {
        val params = mutableMapOf<String, String>()
        if (!rawResponse.isNullOrBlank()) {
            rawResponse.split("&").forEach { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val key = pair.substring(0, idx).trim().lowercase(Locale.US)
                    val value = runCatching {
                        Uri.decode(pair.substring(idx + 1).trim())
                    }.getOrDefault(pair.substring(idx + 1).trim())
                    params[key] = value
                }
            }
        }

        val statusRaw = (params["status"] ?: fallbackStatus).orEmpty().trim().uppercase(Locale.US)
        val responseCode = params["responsecode"].orEmpty().trim()
        val txnId = params["txnid"].orEmpty().trim()
        val txnRef = params["txnref"].orEmpty().ifBlank { defaultTxnRef }.trim()
        val approvalRefNo = params["approvalrefno"].orEmpty().trim()

        val status = when {
            statusRaw == "SUCCESS" -> UpiTxnStatus.SUCCESS
            statusRaw == "SUBMITTED" || statusRaw == "PENDING" -> UpiTxnStatus.SUBMITTED
            statusRaw == "FAILURE" || statusRaw == "FAILED" -> UpiTxnStatus.FAILURE
            statusRaw == "CANCELLED" || statusRaw == "CANCELED" -> UpiTxnStatus.CANCELLED
            responseCode == "00" && statusRaw.isEmpty() -> UpiTxnStatus.SUCCESS
            else -> UpiTxnStatus.UNKNOWN
        }

        return UpiParsedResponse(
            status = status,
            txnId = txnId,
            txnRef = txnRef,
            approvalRefNo = approvalRefNo,
            responseCode = responseCode
        )
    }
}

package com.example.recording

import android.os.Environment

/**
 * Supported call recording sources, each saved into a dedicated separate folder
 * visible in the device File Manager (and intentionally not shown inside the PulseDial app).
 */
enum class CallRecordingSource(
    val folderName: String,
    val filePrefix: String,
    val displayLabel: String
) {
    CELLULAR(
        folderName = "Cellular Call Recordings",
        filePrefix = "",
        displayLabel = "Cellular Call"
    ),
    WHATSAPP(
        folderName = "WhatsApp Call Recordings",
        filePrefix = "WhatsApp",
        displayLabel = "WhatsApp Call"
    ),
    WHATSAPP_BUSINESS(
        folderName = "WhatsApp Business Call Recordings",
        filePrefix = "WhatsApp_Business",
        displayLabel = "WhatsApp Business Call"
    ),
    GOOGLE_MEET(
        folderName = "Meet Call Recordings",
        filePrefix = "Meet",
        displayLabel = "Google Meet Call"
    ),
    TELEGRAM(
        folderName = "Telegram Call Recordings",
        filePrefix = "Telegram",
        displayLabel = "Telegram Call"
    ),
    INSTAGRAM(
        folderName = "Instagram Call Recordings",
        filePrefix = "Instagram",
        displayLabel = "Instagram Call"
    );

    fun relativePublicPath(): String = "${Environment.DIRECTORY_MUSIC}/$ROOT_FOLDER_NAME/$folderName"

    companion object {
        const val ROOT_FOLDER_NAME = "PulseDial"

        private val PACKAGE_TO_SOURCE = mapOf(
            "com.whatsapp" to WHATSAPP,
            "com.whatsapp.w4b" to WHATSAPP_BUSINESS,
            "com.google.android.apps.meetings" to GOOGLE_MEET,
            "com.google.android.apps.tachyon" to GOOGLE_MEET,
            "org.telegram.messenger" to TELEGRAM,
            "org.telegram.messenger.web" to TELEGRAM,
            "org.telegram.plus" to TELEGRAM,
            "tw.nekomimi.nekogram" to TELEGRAM,
            "com.instagram.android" to INSTAGRAM,
            "com.instagram.lite" to INSTAGRAM
        )

        fun fromPackageName(packageName: String?): CallRecordingSource? {
            if (packageName.isNullOrBlank()) return null
            return PACKAGE_TO_SOURCE[packageName.trim()]
        }
    }
}

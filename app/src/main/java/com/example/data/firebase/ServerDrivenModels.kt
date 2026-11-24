package com.example.data.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class NavigationTabItem(
    val id: String = "",
    val title: String = "",
    val iconKey: String = ICON_HISTORY,
    @get:PropertyName("enabled")
    val enabled: Boolean = true,
    val order: Int = 0,
    val type: String = TYPE_BUILTIN_HISTORY,
    val customTitle: String = "",
    val customBody: String = "",
    val customActionUrl: String = ""
) {
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "id" to id,
        "title" to title,
        "iconKey" to iconKey,
        "enabled" to enabled,
        "order" to order,
        "type" to type,
        "customTitle" to customTitle,
        "customBody" to customBody,
        "customActionUrl" to customActionUrl
    )

    companion object {
        const val ID_HISTORY = "history"
        const val ID_FAVORITES = "favorites"
        const val ID_CONTACTS = "contacts"
        const val ID_SETTINGS = "settings"
        const val ID_ACCOUNT = "account"

        const val TYPE_BUILTIN_HISTORY = "builtin_history"
        const val TYPE_BUILTIN_FAVORITES = "builtin_favorites"
        const val TYPE_BUILTIN_CONTACTS = "builtin_contacts"
        const val TYPE_BUILTIN_SETTINGS = "builtin_settings"
        const val TYPE_BUILTIN_ACCOUNT = "builtin_account"
        const val TYPE_CUSTOM_BANNER = "custom_banner"

        const val ICON_HISTORY = "history"
        const val ICON_FAVORITES = "favorites"
        const val ICON_CONTACTS = "contacts"
        const val ICON_SETTINGS = "settings"
        const val ICON_ACCOUNT = "account"
        const val ICON_ANNOUNCEMENT = "announcement"

        fun defaultTabs(): List<NavigationTabItem> = listOf(
            NavigationTabItem(
                id = ID_HISTORY,
                title = "History",
                iconKey = ICON_HISTORY,
                enabled = true,
                order = 0,
                type = TYPE_BUILTIN_HISTORY
            ),
            NavigationTabItem(
                id = ID_FAVORITES,
                title = "Favorites",
                iconKey = ICON_FAVORITES,
                enabled = true,
                order = 1,
                type = TYPE_BUILTIN_FAVORITES
            ),
            NavigationTabItem(
                id = ID_CONTACTS,
                title = "Contacts",
                iconKey = ICON_CONTACTS,
                enabled = true,
                order = 2,
                type = TYPE_BUILTIN_CONTACTS
            ),
            NavigationTabItem(
                id = ID_SETTINGS,
                title = "Settings",
                iconKey = ICON_SETTINGS,
                enabled = true,
                order = 3,
                type = TYPE_BUILTIN_SETTINGS
            ),
            NavigationTabItem(
                id = ID_ACCOUNT,
                title = "Account",
                iconKey = ICON_ACCOUNT,
                enabled = true,
                order = 4,
                type = TYPE_BUILTIN_ACCOUNT
            )
        )
    }
}

@IgnoreExtraProperties
data class NavigationTabsConfig(
    @DocumentId val id: String = DOC_NAVIGATION_TABS,
    val tabs: List<NavigationTabItem> = NavigationTabItem.defaultTabs(),
    val updatedByUid: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        const val DOC_NAVIGATION_TABS = "navigation_tabs"
    }
}

@IgnoreExtraProperties
data class DynamicSettingItem(
    val key: String = "",
    val title: String = "",
    val description: String = "",
    val iconKey: String = ICON_TOGGLE,
    val type: String = TYPE_TOGGLE,
    @get:PropertyName("enabled")
    val enabled: Boolean = true,
    @get:PropertyName("lockedByAdmin")
    val lockedByAdmin: Boolean = false,
    @get:PropertyName("defaultValue")
    val defaultValue: Boolean = false,
    val order: Int = 0,
    val actionUrl: String = ""
) {
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "key" to key,
        "title" to title,
        "description" to description,
        "iconKey" to iconKey,
        "type" to type,
        "enabled" to enabled,
        "lockedByAdmin" to lockedByAdmin,
        "defaultValue" to defaultValue,
        "order" to order,
        "actionUrl" to actionUrl
    )

    companion object {
        const val KEY_AUTO_RECORDING = "auto_recording"
        const val KEY_RECORDING_FOLDER = "recording_folder"
        const val KEY_ACCESSIBILITY_STATUS = "accessibility_status"
        const val KEY_BLOCKED_NUMBERS = "blocked_numbers"
        const val KEY_DISPLAY_THEME = "display_theme"
        const val KEY_SOUNDS_VIBRATION = "sounds_vibration"
        const val KEY_QUICK_DECLINE = "quick_decline"

        const val TYPE_TOGGLE = "toggle"
        const val TYPE_ACTION = "action"

        const val ICON_RECORD = "record"
        const val ICON_FOLDER = "folder"
        const val ICON_ACCESSIBILITY = "accessibility"
        const val ICON_BLOCK = "block"
        const val ICON_PALETTE = "palette"
        const val ICON_VOLUME = "volume"
        const val ICON_REPLY = "reply"
        const val ICON_TOGGLE = "toggle"
        const val ICON_INFO = "info"

        fun defaultItems(): List<DynamicSettingItem> = listOf(
            DynamicSettingItem(
                key = KEY_AUTO_RECORDING,
                title = "Auto Call Recording",
                description = "Automatically record calls from the first ring (.m4a)",
                iconKey = ICON_RECORD,
                type = TYPE_TOGGLE,
                enabled = true,
                lockedByAdmin = false,
                defaultValue = false,
                order = 0
            ),
            DynamicSettingItem(
                key = KEY_RECORDING_FOLDER,
                title = "Recording Storage Folder",
                description = "Default: Music / PulseDial",
                iconKey = ICON_FOLDER,
                type = TYPE_ACTION,
                enabled = true,
                lockedByAdmin = false,
                order = 1
            ),
            DynamicSettingItem(
                key = KEY_ACCESSIBILITY_STATUS,
                title = "Call Audio & Default Dialer",
                description = "Use PulseDial as Default Dialer for Android 10–16+ calls",
                iconKey = ICON_ACCESSIBILITY,
                type = TYPE_ACTION,
                enabled = true,
                lockedByAdmin = false,
                order = 2
            ),
            DynamicSettingItem(
                key = KEY_BLOCKED_NUMBERS,
                title = "Blocked Numbers",
                description = "Reject incoming calls from blocked numbers",
                iconKey = ICON_BLOCK,
                type = TYPE_ACTION,
                enabled = true,
                lockedByAdmin = false,
                order = 3
            ),
            DynamicSettingItem(
                key = KEY_DISPLAY_THEME,
                title = "Display Theme",
                description = "Light or Dark Mode",
                iconKey = ICON_PALETTE,
                type = TYPE_ACTION,
                enabled = true,
                lockedByAdmin = false,
                order = 4
            ),
            DynamicSettingItem(
                key = KEY_SOUNDS_VIBRATION,
                title = "Sounds & Vibration",
                description = "Keypad dial tones & haptic feedback",
                iconKey = ICON_VOLUME,
                type = TYPE_TOGGLE,
                enabled = true,
                lockedByAdmin = false,
                defaultValue = true,
                order = 5
            )
        )
    }
}

@IgnoreExtraProperties
data class SettingsItemsConfig(
    @DocumentId val id: String = DOC_SETTINGS_ITEMS,
    val items: List<DynamicSettingItem> = DynamicSettingItem.defaultItems(),
    val updatedByUid: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    companion object {
        const val DOC_SETTINGS_ITEMS = "settings_items"
    }
}

@IgnoreExtraProperties
data class LiveBroadcast(
    @DocumentId val id: String = DOC_LIVE_BROADCAST,
    @get:PropertyName("showPopup")
    val showPopup: Boolean = false,
    val popupId: String = "",
    val popupType: String = TYPE_UPDATE,
    val version: String = "",
    @get:PropertyName("forceUpdate")
    val forceUpdate: Boolean = false,
    @get:PropertyName("showOnce")
    val showOnce: Boolean = true,
    val title: String = "",
    val message: String = "",
    val imageUrl: String = "",
    val buttonText: String = "Open",
    val actionUrl: String = "",
    @get:PropertyName("isCancelable")
    val isCancelable: Boolean = true,
    @get:PropertyName("showBannerAd")
    val showBannerAd: Boolean = false,
    val bannerText: String = "",
    val bannerImageUrl: String = "",
    val bannerActionUrl: String = "",
    val updatedByUid: String = "",
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun normalizedPopupType(): String {
        val clean = popupType.trim().lowercase()
        return when (clean) {
            TYPE_AD -> TYPE_AD
            TYPE_UPDATE -> TYPE_UPDATE
            else -> if (version.isNotBlank() || forceUpdate || !isCancelable) TYPE_UPDATE else TYPE_AD
        }
    }

    fun isUpdatePopup(): Boolean = normalizedPopupType() == TYPE_UPDATE

    fun isAdPopup(): Boolean = normalizedPopupType() == TYPE_AD

    /**
     * Force Update applies only to App Update popups. When enabled, the close (x) button is
     * hidden and the popup keeps appearing until the user updates or the Admin disables it.
     */
    fun isForceUpdateActive(): Boolean = isUpdatePopup() && (forceUpdate || !isCancelable)

    fun hasRenderablePopupContent(): Boolean {
        return title.isNotBlank() || message.isNotBlank() || imageUrl.isNotBlank()
    }

    fun effectivePopupId(): String {
        val explicit = popupId.trim()
        if (explicit.isNotEmpty()) return explicit
        return listOf(normalizedPopupType(), version.trim(), title.trim(), message.trim(), imageUrl.trim())
            .joinToString("|")
    }

    /**
     * Strict "One-Time Delivery" gate:
     * - Must be active (`showPopup == true`) and contain renderable content.
     * - If Force Update is enabled on an Update popup, keep showing even if seen previously.
     * - Otherwise, if `showOnce == true` and `effectivePopupId()` is already in `seenPopupIds`,
     *   do NOT show it again until the Admin pushes a new popup with a new `popupId`.
     */
    fun shouldShowToUser(seenPopupIds: Set<String>): Boolean {
        if (!showPopup || !hasRenderablePopupContent()) return false
        if (isForceUpdateActive()) return true
        if (showOnce && seenPopupIds.contains(effectivePopupId())) return false
        return true
    }

    companion object {
        const val DOC_LIVE_BROADCAST = "live_broadcast"
        const val TYPE_UPDATE = "update"
        const val TYPE_AD = "ad"

        fun generatePopupId(nowMs: Long = System.currentTimeMillis()): String = "popup_$nowMs"
    }
}

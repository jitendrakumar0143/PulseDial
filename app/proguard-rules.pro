# ProGuard / R8 Rules for PulseDial

# Keep Firebase POJOs & RTDB/Firestore serialized model classes
-keepclassmembers class com.example.data.firebase.** {
    *;
}
-keep class com.example.data.firebase.** { *; }

# Keep Google Play Billing & Credential Manager classes
-keep class com.android.billingclient.api.** { *; }
-dontwarn com.android.billingclient.api.**
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Keep Firebase classes & annotations
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.firebase.database.PropertyName *;
    @com.google.firebase.firestore.PropertyName *;
    @com.google.firebase.firestore.ServerTimestamp *;
}

# Preserve line numbers for Crash Logger diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

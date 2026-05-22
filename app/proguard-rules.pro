# ============================================================
# 崩溃堆栈保留行号（方便排查线上问题）
# ============================================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================
# 本项目数据模型（Gson 序列化 / Room 实体）
# ============================================================
-keep class ai.cjym.agentclaw.domain.model.** { *; }
-keep class ai.cjym.agentclaw.data.local.db.** { *; }
-keep class ai.cjym.agentclaw.data.remote.api.** { *; }
-keep class ai.inmo.core_common.** { *; }

# ============================================================
# Gson：保留泛型、注解，防止序列化字段被改名
# ============================================================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============================================================
# Retrofit + OkHttp
# ============================================================
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-keepattributes Exceptions
-keepclassmembernames interface * {
    @retrofit2.http.* <methods>;
}
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ============================================================
# Room（实体、DAO、数据库）
# ============================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

# ============================================================
# MMKV
# ============================================================
-keep class com.tencent.mmkv.** { *; }

# ============================================================
# Bouncy Castle（加密库）
# ============================================================
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ============================================================
# Firebase（Crashlytics + Analytics）
# ============================================================
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ============================================================
# Termux 终端库
# ============================================================
-keep class com.termux.** { *; }
-dontwarn com.termux.**

# ============================================================
# Commons Compress / XZ（rootfs 解压）
# ============================================================
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**

# ============================================================
# USB Serial（串口通信）
# ============================================================
-keep class com.felhr.** { *; }
-dontwarn com.felhr.**

# ============================================================
# Markwon（Markdown 渲染）
# ============================================================
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# ============================================================
# WebView JavaScript 接口（如有）
# ============================================================
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ============================================================
# Parcelable / Serializable
# ============================================================
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================
# Kotlin 协程 / Reflection
# ============================================================
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ============================================================
# 抑制常见无害警告
# ============================================================
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe
-dontwarn java.lang.invoke.*

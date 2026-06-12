# Règles ProGuard pour EmuLex GBC
-keepattributes SourceFile,LineNumberTable
-keep class com.emulex.gbc.core.** { *; }
-keep class com.emulex.gbc.cartridge.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

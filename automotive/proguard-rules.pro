# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# 시연 시나리오가 실패할 때 원인을 찾을 수 있도록 줄 번호를 남긴다
-keepattributes SourceFile,LineNumberTable

# Pleos SDK: 내부에서 AIDL·리플렉션·JNA로 클래스를 찾으므로 통째로 남긴다
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }

# JNA: Pleos universal-access가 네이티브 라이브러리(libuacore) 호출에 쓴다
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Gson·Retrofit: SDK 내부 통신이 제네릭 시그니처와 어노테이션을 읽는다
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

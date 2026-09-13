# JoeyOS release shrinking (R8). The libraries used ship their own keep rules; these cover the rest.

# Line numbers in crash logs (joeyos.log), mapped back with the build's mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The app's own classes keep their names, so the stack traces in joeyos.log stay readable
# without the mapping file (unused code is still removed).
-keepnames class com.joeyos.app.** { *; }

# Activities, receivers and providers named in the manifest are kept by AAPT's rules already.

# Tink (under the encrypted RetroAchievements login) refers to compile-time-only annotations.
-dontwarn com.google.errorprone.annotations.**

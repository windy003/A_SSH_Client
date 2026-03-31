# JSch
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

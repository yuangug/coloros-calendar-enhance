# 开 R8/minify 时必须保留 API 102 入口，否则框架找不到类
-keep class com.lcodexs.coursealarm.CourseAlarmEntry { *; }
-keepclassmembers class * implements io.github.libxposed.api.XposedModule { *; }
-keep class io.github.libxposed.api.** { *; }
-dontwarn io.github.libxposed.api.**

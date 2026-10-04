# Shizuku 相关类不能被混淆（如果用 release + minify 一定要保留）
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# 反射调用到的方法名
-keepclassmembers class rikka.shizuku.Shizuku {
    public static *** newProcess(...);
}

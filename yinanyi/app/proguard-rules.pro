# sherpa-onnx 的 JNI 库会按名字回调这些 Java 类的构造函数和字段，不能被混淆或裁剪
-keep class com.k2fsa.sherpa.onnx.** { *; }

# 我们自己的 JNI 入口：函数名要和 translator.cpp 里的 Java_com_yinanyi_app_engine_LlamaNative_* 对得上
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.yinanyi.app.engine.LlamaNative { *; }

# OkHttp 的可选依赖，没有也没关系
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

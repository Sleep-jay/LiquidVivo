# Protobuf
-shrinkunusedprotofields

# Commons-compress
-dontwarn com.github.luben.zstd.**

# === Xposed 模块入口：仅 java_init.list 按类名反射加载，只保入口类与构造函数 ===
# 其余 xposed/hook 代码全部参与咕嘎混淆（引用均为直接引用，无按名反射）
-keep class com.LiquidVivo.xposed.HookEntry { <init>(); }
-keep class io.github.libxposed.** { *; }

# === 咕嘎重度混淆：类/包/成员名全部替换为「咕」「嘎」组合 ===
-allowaccessmodification
-classobfuscationdictionary obfuscation/class-dict.txt
-packageobfuscationdictionary obfuscation/package-dict.txt
-obfuscationdictionary obfuscation/member-dict.txt

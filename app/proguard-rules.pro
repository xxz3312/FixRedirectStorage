# LSPosed loads this class from META-INF/xposed/java_init.list.
-keep class io.github.storageisolation.visibilityfix.VisibilityHook { *; }

# Keep names stable for diagnostics and framework entry points.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

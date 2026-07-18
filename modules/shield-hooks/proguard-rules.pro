-dontwarn io.github.libxposed.annotation.**
-dontwarn java.applet.**
-dontwarn java.awt.**
-dontwarn javax.script.**
-dontwarn javax.servlet.**
-dontwarn javax.swing.**
-dontwarn org.apache.bsf.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep,allowshrinking,allowoptimization class bsh.** { *; }
-keep,allowoptimization class bsh.XThis { *; }
-keep,allowoptimization class bsh.ClassGeneratorImpl { *; }
-keep,allowoptimization class bsh.classpath.ClassManagerImpl { *; }
-keep,allowoptimization class bsh.collection.CollectionManagerImpl { *; }
-keep,allowoptimization class bsh.reflect.ReflectManagerImpl { *; }
-keepclassmembers,allowoptimization class dev.roesler.shieldhooks.script.ScriptApi {
    public <methods>;
}
-keep class dev.roesler.shieldhooks.xposed.ModuleMain { public <init>(); }

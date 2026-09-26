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
-dontwarn okhttp3.internal.Util
-dontwarn org.commonmark.ext.gfm.strikethrough.Strikethrough
-keep class net.zetetic.** { *; }
-keep class net.sqlcipher.** { *; }
# Drawables inflated from XML via <drawable class="..."> are looked up by
# reflection (Drawable.createFromXml -> Class.forName + no-arg ctor). R8 can't
# see that, so keep every app Drawable subclass by name. Without this the
# release build crashes opening any glass popup, sheet or dialog.
-keep public class io.github.stardomains3.oxproxion.** extends android.graphics.drawable.Drawable {
    public <init>();
    public void inflate(...);
}

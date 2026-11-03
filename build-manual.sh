#!/bin/bash
# Manual APK build (no Gradle): aapt2 + kotlinc + d8 + apksigner.
set -e
source ~/workspace/tools/android/env.sh
PROJ=~/workspace/nerv-live-app
SDK=$ANDROID_HOME
BT=$SDK/build-tools/36.0.0
AJAR=$SDK/platforms/android-36/android.jar
B=$PROJ/build-manual
DEPS=$PROJ/manual-deps
rm -rf $B; mkdir -p $B/aars $B/flats $B/gen $B/classes $B/rclasses $B/dex

# 1) Extract AARs
for a in $DEPS/*.aar; do
  n=$(basename $a .aar)
  mkdir -p $B/aars/$n
  unzip -q -o $a -d $B/aars/$n
done
ls $B/aars/*/classes.jar > $B/libjars.txt
for j in $DEPS/*.jar; do
  case "$j" in *compiler-embeddable*|*trove4j*) ;; *) echo $j >> $B/libjars.txt;; esac
done

# 2) aapt2 compile resources (app + libraries that have res files)
$BT/aapt2 compile --dir $PROJ/app/src/main/res -o $B/flats/app.zip
LIBRES=""
EXTRAPKGS=""
declare -A PKGMAP=(
 [ui-android-1.7.6]="androidx.compose.ui"
 [foundation-android-1.7.6]="androidx.compose.foundation"
 [foundation-layout-android-1.7.6]="androidx.compose.foundation.layout"
 [material3-android-1.3.1]="androidx.compose.material3"
 [activity-1.9.3]="androidx.activity"
 [core-1.15.0]="androidx.core"
 [lifecycle-runtime-2.8.7]="androidx.lifecycle.runtime"
 [animation-android-1.7.6]="androidx.compose.animation"
 [ui-text-android-1.7.6]="androidx.compose.ui.text"
 [ui-graphics-android-1.7.6]="androidx.compose.ui.graphics"
 [material-ripple-android-1.7.6]="androidx.compose.material.ripple"
 [runtime-saveable-android-1.7.6]="androidx.compose.runtime.saveable"
 [core-runtime-2.2.0]="androidx.arch.core"
 [versionedparcelable-1.1.1]="androidx.versionedparcelable"
 [profileinstaller-1.3.1]="androidx.profileinstaller"
 [savedstate-1.2.1]="androidx.savedstate"
 [interpolator-1.0.0]="androidx.interpolator"
 [autofill-1.0.0]="androidx.autofill"
 [tracing-1.2.0]="androidx.tracing"
 [annotation-experimental-1.4.1]="androidx.annotation.experimental"
)
for n in "${!PKGMAP[@]}"; do
  d=$B/aars/$n/res
  if [ -d "$d" ] && [ -n "$(find $d -type f | head -1)" ]; then
    $BT/aapt2 compile --dir $d -o $B/flats/$n.zip
    LIBRES="$LIBRES $B/flats/$n.zip"
    EXTRAPKGS="$EXTRAPKGS:${PKGMAP[$n]}"
  fi
done
EXTRAPKGS=${EXTRAPKGS#:}
echo "extra packages: $EXTRAPKGS"

# 3) Processed manifest (aapt2 needs package + version + uses-sdk in the manifest)
sed -e 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.rui.nervlive" android:versionCode="21" android:versionName="1.1.1">|' \
    -e 's|<application|<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36" /><application|' \
    $PROJ/app/src/main/AndroidManifest.xml > $B/AndroidManifest.xml

# 4) aapt2 link
$BT/aapt2 link -I $AJAR --manifest $B/AndroidManifest.xml \
  --java $B/gen --extra-packages "$EXTRAPKGS" \
  --auto-add-overlay -o $B/unsigned.apk $B/flats/app.zip $LIBRES
echo "LINK OK"

# 5) Compile R classes
find $B/gen -name "*.java" > $B/rjava.txt
javac -cp $AJAR -d $B/rclasses @$B/rjava.txt
echo "R OK"

# 6) Kotlin compile
CP=$B/rclasses:$(paste -sd: $B/libjars.txt):$AJAR
find $PROJ/app/src/main/java -name "*.kt" > $B/ktsrc.txt
$JAVA_HOME/bin/java -cp $DEPS/kotlin-compiler-embeddable-2.0.21.jar:$DEPS/kotlin-stdlib-2.0.21.jar:$DEPS/kotlinx-coroutines-core-jvm-1.9.0.jar:$DEPS/trove4j-1.0.20200330.jar:$DEPS/annotations-13.0.jar \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -classpath $CP \
  -Xplugin=$DEPS/kotlin-compose-compiler-plugin-embeddable-2.0.21.jar \
  -d $B/classes @$B/ktsrc.txt
echo "KOTLIN OK"

# 7) Dex everything (this d8 build rejects raw directories, so jar them first)
$JAVA_HOME/bin/jar cf $B/appclasses.jar -C $B/classes . 2>/dev/null || (cd $B/classes && zip -q -r $B/appclasses.jar .)
$JAVA_HOME/bin/jar cf $B/rclasses.jar -C $B/rclasses . 2>/dev/null || (cd $B/rclasses && zip -q -r $B/rclasses.jar .)
INPUTS="$B/appclasses.jar $B/rclasses.jar $(paste -sd' ' $B/libjars.txt)"
$BT/d8 --min-api 29 --lib $AJAR --output $B/dex $INPUTS
echo "D8 OK"

# 8) Package: add dex + native libs, align, sign
cd $B
cp unsigned.apk app.apk
for d in dex/classes*.dex; do zip -q app.apk -j $d; done
if [ -d $PROJ/app/src/main/assets ]; then (cd $PROJ/app/src/main && zip -q -r $B/app.apk assets); fi
for so in $(find aars -path "*jni/arm64-v8a/*.so" | sort -u); do
  base=$(basename $so)
  mkdir -p lib/arm64-v8a
  cp $so lib/arm64-v8a/$base
  zip -q app.apk lib/arm64-v8a/$base
done
$BT/zipalign -f -p 4 app.apk app-aligned.apk
if [ ! -f $PROJ/debug.keystore ]; then
  keytool -genkeypair -keystore $PROJ/debug.keystore -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
$BT/apksigner sign --ks $PROJ/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out $PROJ/app-manual-debug.apk app-aligned.apk
$BT/apksigner verify $PROJ/app-manual-debug.apk
ls -la $PROJ/app-manual-debug.apk
echo "BUILD DONE"

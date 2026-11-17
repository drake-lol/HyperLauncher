#!/bin/bash
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/36.0.0
PLAT=$SDK/platforms/android-36/android.jar
rm -rf build && mkdir -p build/classes build/dex build/stubs
# stand-ins for hidden framework classes: compiled against, never packaged
javac --release 17 -Xlint:-options -cp $PLAT -d build/stubs $(find stubs -name '*.java')
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -o build/base.apk -I $PLAT --manifest AndroidManifest.xml --min-sdk-version 34 --target-sdk-version 35 build/res.zip
javac --release 17 -Xlint:-options -cp build/stubs:$PLAT:libs/xposed-api.jar:libs/annotation.jar -d build/classes $(find src -name '*.java') 2>&1 | grep -v "^warning: unknown enum\|class file for" || true
$BT/d8 --release --min-api 34 --lib $PLAT --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
(cd meta && zip -q -r ../build/unsigned.apk META-INF)
$BT/zipalign -f -p 4 build/unsigned.apk build/aligned.apk
[ -f debug.jks ] || keytool -genkeypair -keystore debug.jks -storepass android -keypass android -alias k -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=dev" >/dev/null 2>&1
$BT/apksigner sign --ks debug.jks --ks-pass pass:android --key-pass pass:android --out build/module.apk build/aligned.apk
echo built build/module.apk

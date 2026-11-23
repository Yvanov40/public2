#!/usr/bin/env bash
# Construit l'APK de la Liseuse sans Android Studio ni Gradle.
# Outils (Debian/Ubuntu) : sudo apt-get install android-sdk-platform-23 aapt dalvik-exchange zipalign apksigner openjdk-17-jdk
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_JAR=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
DX=${DX:-$(command -v dalvik-exchange || command -v dx)}
OUT=build
APK=${APK:-Liseuse.apk}
CLE=${CLE:-liseuse.keystore}

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes"

# version unique (partagée avec Gradle)
VC=$(grep '^versionCode=' version.properties | cut -d= -f2)
VN=$(grep '^versionName=' version.properties | cut -d= -f2)

# manifeste pour aapt (le manifeste du dépôt est au format Gradle : sans paquet ni version)
sed -e "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"fr.liseuse\" android:versionCode=\"$VC\" android:versionName=\"$VN\">\\n    <uses-sdk android:minSdkVersion=\"24\" android:targetSdkVersion=\"34\" />|" \
    -e 's| *android:enableOnBackInvokedCallback="false"||' -e 's|\${applicationId}|fr.liseuse|g' AndroidManifest.xml > "$OUT/AndroidManifest.xml"

echo "▸ Ressources ($VN)"
aapt package -f -m -J "$OUT/gen" -M "$OUT/AndroidManifest.xml" -S res -A assets -I "$ANDROID_JAR" \
  -F "$OUT/base.apk" --min-sdk-version 24 --target-sdk-version 34 --no-version-vectors

echo "▸ Java"
# src-play (achats Google Play) n'est compilé que par Gradle : ici, édition complète
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:none -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v -E '^(warning|Picked up|Note:|[0-9]+ warning)' || true
test -f "$OUT/classes/fr/liseuse/MainActivity.class"

echo "▸ Dex"
"$DX" --dex --min-sdk-version=24 --output="$OUT/classes.dex" "$OUT/classes"

echo "▸ Assemblage"
cp "$OUT/base.apk" "$OUT/non-signe.apk"
(cd "$OUT" && zip -q -j non-signe.apk classes.dex)
zipalign -f -p 4 "$OUT/non-signe.apk" "$OUT/aligne.apk"

if [ ! -f "$CLE" ]; then
  echo "▸ Création de la clé de signature"
  keytool -genkeypair -keystore "$CLE" -storepass liseuse -keypass liseuse -alias liseuse \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Liseuse, O=Liseuse, C=FR" >/dev/null 2>&1
fi

echo "▸ Signature"
apksigner sign --ks "$CLE" --ks-pass pass:liseuse --key-pass pass:liseuse --ks-key-alias liseuse \
  --min-sdk-version 24 --out "$APK" "$OUT/aligne.apk"
apksigner verify "$APK"
echo "✔ $APK ($(du -h "$APK" | cut -f1))"

# HSP Scanner - reconstructed Gradle source

यह project `Hsp.apk` की उपलब्ध सामग्री से reconstructed किया गया है।

## APK से पुष्टि हुई चीजें

- Package: `com.rsn.scanner`
- MainActivity: `com.rsn.scanner.MainActivity`
- App label: `HSP Scanner`
- Version code: `14`
- Version name: `1.0`
- Google ML Kit Code Scanner मौजूद है
- `GmsBarcodeScannerOptions.enableAutoZoom()` मौजूद है
- Main UI `assets/index.html` में है
- JavaScript bridge methods:
  - `Android.startScanner()`
  - `Android.saveFile(base64, fileName)`
- Original web assets को APK से वापस रखा गया है:
  - `index.html`
  - `html5-qrcode.min.js`
  - `xlsx.full.min.js`
  - `profile.jpg`

## Build

यदि आपके सिस्टम में Gradle उपलब्ध है:

```bash
cd HspScanner_Source
gradle assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

यदि `gradle` command नहीं है लेकिन आपके पास SDKMAN Gradle 9.8 है, तो सीधे:

```bash
~/.sdkman/candidates/gradle/9.8.0/bin/gradle assembleDebug
```

ध्यान दें: Android Gradle Plugin 8.5.2 के साथ Gradle 8.7 सबसे सुरक्षित विकल्प है।

## Important

यह original developer का exact Java source नहीं है। APK से compiled DEX का exact source/comments/variable names वापस नहीं आते। यह functional reconstruction है जो APK में मिले WebView UI, assets, native scanner bridge, auto-zoom और XLSX save flow को दोबारा बनाता है।

Google Code Scanner dependency:

```gradle
implementation 'com.google.android.gms:play-services-code-scanner:16.1.0'
```

Auto zoom:

```java
new GmsBarcodeScannerOptions.Builder()
        .enableAutoZoom()
        .build();
```

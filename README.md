# HSP Scanner (native Android)

पूरी तरह native Java app: कोई WebView, `index.html` या JavaScript library नहीं।

## फीचर
- Google ML Kit Code Scanner, auto-zoom चालू, लगातार स्कैन
- सिर्फ़ "R" से शुरू होने वाले RSN जुड़ते हैं, डुप्लिकेट नहीं
- हर RSN के आगे डिलीट, ऊपर सभी डिलीट
- XLSX एक्सपोर्ट (Save As विंडो), एक्सपोर्ट के बाद लिस्ट खाली
- XLSX / CSV इंपोर्ट (पुरानी बाइनरी .xls समर्थित नहीं)
- टॉर्च बटन + कम रोशनी में अपने आप लाइट

## GitHub पर APK बनवाना
`main` branch पर push करें, फिर Actions -> Build APK -> Artifacts -> `HSP-Scanner-release-apk`.

## लोकल build (WSL)
Gradle 8.9 + JDK 17 चाहिए:

```bash
gradle assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk`

## फाइलें
- `app/src/main/java/com/rsn/scanner/MainActivity.java` : मुख्य स्क्रीन और स्कैनर
- `XlsxUtil.java`, `CsvUtil.java`, `RsnUtil.java` : एक्सपोर्ट/इंपोर्ट और RSN नियम
- `app/src/main/res/layout/` : स्क्रीन का XML
- `app/src/main/res/values/strings.xml` : सारे हिंदी संदेश (यहीं से बदलें)

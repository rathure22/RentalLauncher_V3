RentalLauncher V3 (fixed build config)

WHAT WAS FIXED
1. settings.gradle.kts – removed duplicated dependencyResolutionManagement block
2. Root build.gradle.kts – replaced broken Android/app content with correct top-level plugins (apply false)
3. app/build.gradle.kts – cleaned plugins + dependencies; removed unused protobuf
4. .github/workflows/build.yml – fixed invalid **run:** markdown so the YAML is valid
5. Added proper Gradle Wrapper (gradlew + gradle/wrapper/) so GitHub Actions and local builds work
6. themes.xml – made well-formed

HOW TO BUILD (GitHub Actions)
- Keep the repo PRIVATE (google-services.json contains your Firebase key)
- Add GitHub secret: GOOGLE_SERVICES_JSON = entire contents of app/google-services.json
- Push to main or run the workflow manually → APK artifact "app-debug"

HOW TO BUILD (local / Termux)
1. Put google-services.json at: app/google-services.json
2. chmod +x gradlew
3. ./gradlew assembleDebug
4. APK: app/build/outputs/apk/debug/app-debug.apk

FIREBASE
- Enable Authentication → Sign-in method → Anonymous
- Use database.rules.json as a starting template (replace PUT_ADMIN_UID_HERE)
- Realtime DB path: devices/{ANDROID_ID}/command and devices/{ANDROID_ID}/status/...

NEVER commit:
- app/google-services.json
- *.jks / *.keystore

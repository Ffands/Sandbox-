# TECH_NOTES_AND_RISKS.md

## Архитектурные заметки (Шаги 1, 2.1, 2.2)
- **CI (GitHub Actions):** `gradle/actions/setup-gradle@v4` (Gradle 8.10.2) + прямой `sdkmanager` (`platform-tools`, `platforms;android-35`, `build-tools;35.0.0`). Задача `buildShimDex` собирает pure-Java `:shim` в `sandbox_shim.dex` через `d8 --min-api 26`.
- **Тройная защита IPC-моста (`SandboxFileBridgeProvider`):**
  1. `getCallingPackage() == cfg.hostPackage` (`com.sandboxforge.app`).
  2. `Binder.getCallingUid() == pm.getPackageUid(hostPackage)` + проверка SHA-256 сертификата подписи хоста.
  3. Constant-time (`MessageDigest.isEqual`) проверка 256-битного `bridgeToken`, уникального для каждого клона.
  - **Критическая деталь Android 11–17:** `ApkManifestPatcher` автоматически добавляет `<queries><package android:name="com.sandboxforge.app"/></queries>` в манифест клона, иначе приложения с `targetSdk 34/35` не смогли бы проверить UID/сертификат хоста из-за Package Visibility.
- **Потоковая передача файлов без лимита Binder:** метаданные и превью ходят через `ContentResolver.call()`, а экспорт/импорт файлов любого размера — через `openFile()` (`ParcelFileDescriptor`), минуя 1 МБ лимит Binder-транзакций.
- **Снятие `android:permission`:** `ApkManifestPatcher` снимает `android:permission` (`0x01010006`) со стартовой Activity/alias и `<application>` клона, чтобы системные/OEM права (например, `com.oplus.permission.safe.SETTINGS`) не блокировали запуск клона и работу `SandboxFileBridgeProvider`.
- **Щит совместимости (`SandboxShield` + `SandboxAppComponentFactory`):**
  - Оригинальные сертификаты извлекаются из `base.apk` через `com.android.apksig.ApkVerifier` (поддержка v1/v2/v3/v4) и сохраняются в `assets/sandbox_config.json`.
  - Манифестные `com.pairip.licensecheck.*` компоненты отключаются (`android:enabled="false"`), а при динамическом создании перехватываются в `SandboxAppComponentFactory` на `SandboxNoOpActivity` / `SandboxNoOpProvider`.
- **Очистка кэша сборки:** `ApkCloneBuilder.cleanupBuildCache()` автоматически удаляет `cacheDir/clone_build_*` после `InstallEvent.Success`, `Failure`, сбоя сборки и при старте приложения.

## Слабые места и зоны внимания (подтверждено тестом `v0.2.2` на OnePlus / Android 17)
1. **Android 15–17 / ColorOS `FLAG_STOPPED` и фоновый `ContentProvider` (исправлено в `v0.2.3`):**
   - После чистой установки клон находится в состоянии `FLAG_STOPPED`, и система блокирует `ContentResolver.call()` до первого запуска (возвращая `IllegalArgumentException: Unknown URI`).
   - В `v0.2.3` `CloneFileBridgeClient` проверяет `resolveContentProvider()` + `FLAG_STOPPED` и больше не путает остановленный клон со «старой версией Shim», предлагая 1-клик «Запустить клон» и автообновляя файлы при возврате в SandboxForge.
2. **Конфликт подписи при обновлении SandboxForge поверх себя (исправлено в `v0.2.3`):**
   - Раннер GitHub Actions каждый раз генерировал новый `~/.android/debug.keystore`, из-за чего требовалось удалять SandboxForge, терялся `sandboxforge_clone.p12`, и старые клоны (`sf.c1.*`) падали с `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / «Приложение с такой подписью уже установлено».
   - В `android-build.yml` добавлен `actions/cache@v4` для `~/.android/debug.keystore` + в UI добавлена 1-клик кнопка системного удаления скрытого/конфликтного клона (`ACTION_DELETE`).
3. **Google PairIP (`com.pairip.application.Application` + `ILicensingService`) в `Idle Slayer`:**
   - Проверка лицензии выполняется на стороне процесса `com.android.vending` по реальному `Binder.getCallingUid()`, и Play Store возвращает `PendingIntent` на экран «Скачайте игру из Google Play».
   - Два чистых пути решения на Шаг 2.3: (а) движок «Чистый профиль» через Shizuku / Work Profile (оригинальный нетронутый APK — работает и для PairIP, и для банков); (б) перехват `bindService(ILicensingService)` / DEX-нейтрализация `LicenseClient` внутри `SHIM` (только для игр, если нативный `libpairipcore.so` не рвёт процесс по CRC).
4. **Android 17 (API 37) Hidden API ограничения на `IPackageManager`:** проверим статус `pm=OK/BLOCKED` в логе сразу после запуска `OZON` на `v0.2.3`.
5. **Локальный клиент Play Market и C++ NDK (время/скорость/память):** запланированы после закрытия Шага 2.3.

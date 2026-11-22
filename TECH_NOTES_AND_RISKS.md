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

## Слабые места и зоны внимания (на следующие шаги)
1. **Android 17 (API 37) Hidden API ограничения на `IPackageManager`:**
   - Рефлексия `ActivityThread.sPackageManager` и `ApplicationPackageManager.mPM` внутри `SandboxShield` может быть заблокирована политикой Hidden API на Android 15–17 (особенно у приложений с `targetSdk 35+`).
   - Код защищён `try/catch` (клон никогда не упадёт из-за хука) и пишет точный статус `pm=OK(...)` или `pm=BLOCKED(...)` в лог `[ShimBridge]`. Если на API 37 рефлексия окажется `BLOCKED`, в следующем шаге добавим легковесный HiddenApiBypass / C++ NDK слой.
2. **Нативный слой `libpairipcore.so` (C++ проверка целостности APK):**
   - Отключение `com.pairip.licensecheck` и Java-подмена подписи закрывают Java-проверку лицензии. Если конкретная игра (например, `Idle Slayer`) дополнительно проверяет CRC файла `base.apk` внутри нативного `libpairipcore.so` через системный вызов `openat()`, потребуются либо C++ NDK-редирект (`openat` → оригинальный `base.apk`), либо движок «Чистый профиль» (Шаг 2.3).
3. **Движок «Чистый профиль» (Шаг 2.3 — банки и жёсткий PairIP):**
   - Через Shizuku можно создавать/управлять отдельным профилем и ставить оригинальный APK без переподписи (`pm install -i com.android.vending`).
   - Отдельно изучим вариант **без Shizuku** (встроенный Android Managed/Work Profile через `DevicePolicyManager` — ограничение Android: только 1 Work Profile на устройство, зато 0 внешних утилит и работает на любом телефоне).
4. **Старые клоны `v0.2.0-shim`:** установленные до `v0.2.2` клоны не содержат `SandboxFileBridgeProvider` — UI корректно перехватывает это и предлагает 1-клик пересборку поверх (данные/сейвы сохраняются, т.к. подпись тем же локальным ключом `sandboxforge_clone.p12`).
5. **Локальный клиент Play Market (будущий этап):** потребует интеграции с Aurora/Play API (анонимный или пользовательский токен) для прямой загрузки сплит-APK в контейнер.
6. **Сдвиг времени, ускорение и сканер памяти (Шаг 3+):** потребуют минимального 16 КБ-выровненного C++ NDK модуля `libshim_native.so` (`clock_gettime` / `gettimeofday` / `/proc/self/maps`).

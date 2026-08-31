# Автообновление через GitHub Releases — план

## Цель

Публиковать подписанный APK как стабильный GitHub Release после появления новой версии на
`main`, тихо проверять `releases/latest` при запуске release-сборки и давать пользователю
безопасно скачать и установить обновление через системный установщик Android.

## Контракт релиза

- Версия приложения задаётся парой `versionName` + монотонный `versionCode` в `:app`.
- Новый GitHub Release создаётся только если для `v<versionName>` ещё нет релиза.
- Release содержит `ValerochkaGym-v<versionName>.apk` и SHA-256-файл.
- Клиент принимает только стабильный latest release с SemVer-тегом и одним release APK.
- Перед установкой клиент проверяет GitHub SHA-256, package name, `versionName`, более высокий
  `versionCode` и точное совпадение подтверждённой APK-подписи с установленным приложением.

## Стадии

1. Модели и сравнение SemVer, DTO/API GitHub Releases.
2. Потоковое скачивание в private cache с прогрессом, лимитом размера и очисткой `.part`.
3. Проверка APK, разрешение unknown sources и передача байтов в `PackageInstaller.Session`;
   системное подтверждение открывается из `STATUS_PENDING_USER_ACTION`.
4. ViewModel общего update-сценария: автопроверка, разовый пропуск, игнор текущей версии,
   скачивание, повтор и установка.
5. Стартовый диалог и карточка «Приложение» в настройках по дизайн-системе.
6. GitHub Actions: подписанная сборка, проверка, публикация нового релиза с main.
7. Unit-тесты, документация, полный `testDebugUnitTest` + `assembleDebug`.

`ACTION_VIEW` с update-`FileProvider` не используется: на ColorOS переход внутреннего
`InstallStart` → `InstallStaging` терял URI-grant и показывал ложную ошибку разбора APK.

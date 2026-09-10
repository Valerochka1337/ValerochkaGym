# Gate R: Yarumo coach (#48, этап 07)

Root проверил исходники бренда и потребителей имени 10.09.2026. Оригиналы уже сохранены
в docs/branding/yarumo-coach/; повторно запрашивать материалы не нужно.
Сам исходный PNG просмотрен (это предоставленный бренд-материал, не screenshot приложения):
красно-чёрный круглый знак с внешним кольцом, белой гантелью в верхнем круге и сердцем/пульсом
в нижнем. Для adaptive foreground можно упаковать неизменённый оригинал с inset; monochrome
нуждается в отдельной читаемой vector-маске, сохраняющей эти узнаваемые элементы.

- AC-001: отображаемое имя «Yarumo coach» в launcher, account/settings, уведомлении после
  обновления и объяснении APK installation. Старое имя не остаётся в пользовательских сообщениях.
- AC-002: adaptive/round/monochrome и все четыре palette aliases используют предоставленный знак,
  безопасные отступы, сохраняют пропорции. Logo wordmark не встраивается мелким текстом в launcher.
- AC-003: applicationId, подпись, authorities, schema/storage/UUID namespaces, OAuth и updater
  repository/asset naming остаются совместимыми с установленным приложением.
- AC-004: README/дизайн-система отражают бренд; внутренние legacy identifiers и внешние настройки,
  которые не меняются из Android кода, явно перечислены.
- AC-005: resource resolution/alias switching/light-dark themes/large text проверены; данные
  и авторизация переживают обновление. Никаких UI screenshot/video без запроса владельца.

Пути: res/values/strings.xml; ui/account/AccountScreen.kt; ui/settings/SettingsScreen.kt;
data/update/PostUpdateRelaunchCoordinator.kt; ui/update/AppUpdateHost.kt;
data/backend/BackendUploadAdapter.kt; prompt data/ai/ExerciseAiGenerator.kt (позже AI-01 переносит
его на сервер); mipmap-anydpi, drawable launcher foreground/monochrome/background; legacy mipmaps.
Порядок и имена activity-alias в Manifest связаны с ui/theme/AccentColor.kt и
data/appicon/AppIconManager.kt: не переименовывать без нужды.

GitHubReleaseApi ожидает `ValerochkaGym-v<version>.apk`; оставить для совместимости обновлений.
PortableData/ExerciseEntity/CanonicalExerciseRegistry содержат стабильные UUID namespace строки:
это идентичность данных, не пользовательский бренд; их не менять.

Strict: launcher/system resource/update compatibility. Один владелец ресурсов, manifest при
необходимости, shared version bump. Условный assembleRelease/R8 gate кроме unit/debug;
проверка APK metadata и upgrade через ADB при доступном тестовом emulator/device.
Новые материалы не генерировать вместо утверждённых оригиналов. Точный asset pipeline и
semantics тесты зафиксировать в Gate P после чтения исходных ресурсов.

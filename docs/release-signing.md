# Release-подпись и GitHub Actions

Workflow `.github/workflows/android-ci.yml` делает следующее:

- в pull request собирает debug APK и запускает unit-тесты без доступа к секретам;
- при push в `main`, push тега `v*` или ручном запуске собирает подписанный release APK,
  запускает unit-тесты, проверяет подпись через `apksigner` и публикует Actions artifact
  на 30 дней;
- при push в `main` автоматически создаёт stable GitHub Release `v<versionName>`, только если
  такого релиза ещё нет; в него входят подписанный `ValerochkaGym-v<versionName>.apk` и SHA-256;
- ручной тег `v*` тоже публикует релиз, но workflow проверяет точное совпадение тега с
  `versionName` приложения;
- вместе с APK сохраняет SHA-256 checksum и `mapping.txt` для расшифровки R8 stack trace.

GitHub Releases API читается приложением без токена, поэтому репозиторий должен оставаться
публичным. Токен или release-ключ в APK не встраиваются.

## 1. Создать постоянный release-ключ

Создайте keystore один раз через Android Studio:
**Build → Generate Signed Bundle / APK → APK → Create new**.
Выберите сильные пароли, срок действия не менее 25 лет и отдельный alias,
например `valerochka-gym`.

Ключ должен оставаться одним и тем же для всех будущих обновлений. Сделайте как
минимум две защищённые резервные копии вне репозитория. Потеря ключа означает, что
установленное приложение больше нельзя обновить той же подписью.

## 2. Добавить GitHub Secrets

Преобразуйте keystore в одну строку Base64 (команда одинаково работает в macOS и Linux):

```bash
base64 < /absolute/path/to/valerochka-gym-release.jks | tr -d '\n'
```

В GitHub откройте **Settings → Secrets and variables → Actions → New repository secret**
и создайте четыре секрета:

| Secret | Значение |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Base64-строка из команды выше |
| `RELEASE_KEYSTORE_PASSWORD` | Пароль keystore |
| `RELEASE_KEY_ALIAS` | Alias release-ключа |
| `RELEASE_KEY_PASSWORD` | Пароль release-ключа |

Не добавляйте keystore, Base64-копию или пароли в Git. Типичные расширения keystore и
локальный `keystore.properties` уже добавлены в `.gitignore`.

## 3. Локальная release-сборка

Создайте в корне проекта игнорируемый Git файл `keystore.properties`:

```properties
storeFile=/absolute/path/to/valerochka-gym-release.jks
storePassword=change-me
keyAlias=valerochka-gym
keyPassword=change-me
```

После этого `./gradlew :app:assembleRelease` создаст подписанный APK. Если параметры не заданы,
release-сборка намеренно завершится ошибкой, чтобы unsigned APK не был принят за готовый release.

Вместо файла можно задать переменные окружения `RELEASE_KEYSTORE_FILE`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` и `RELEASE_KEY_PASSWORD`.

## 4. Переход с debug-подписи

Android разрешает обновление APK только при совпадении подписи. Установленную debug-сборку
нельзя обновить APK с новым release-ключом. Сначала экспортируйте нужные данные из приложения,
затем удалите старую сборку и установите новый release APK. Все последующие версии подписывайте
этим же release-ключом.

Новая подпись также меняет SHA-1/SHA-256 сертификата. Добавьте SHA-1 release-ключа в Android OAuth
client для `com.valerochka1337.valerochkagym` в Google Cloud, иначе Google Sign-In в release-сборке не заработает.
Отпечатки можно получить так:

```bash
keytool -list -v -keystore /absolute/path/to/valerochka-gym-release.jks -alias valerochka-gym
```

## 5. Выпустить обновление

1. Увеличьте **оба** значения в `app/build.gradle.kts`: человекочитаемый стабильный SemVer
   `versionName` и строго монотонный `versionCode`.
2. Перенесите изменения из `[Unreleased]` в секцию этой версии в `CHANGELOG.md`.
3. Влейте изменения в `main`. После зелёных тестов workflow создаст тег и GitHub Release.
4. Не заменяйте APK уже опубликованного тега. Для любой правки выпускайте следующую версию.

Приложение выбирает asset по точному имени `ValerochkaGym-v<versionName>.apk`, а SHA-256 берёт
из метаданных GitHub asset. Перед открытием системного установщика оно дополнительно проверяет
package name, `versionName`, более высокий `versionCode` и совпадение подтверждённой APK-подписи.

Версия `1.2.0` впервые содержит updater, поэтому переход с `1.1.0` на `1.2.0` нужно сделать
вручную. Начиная с установленной `1.2.0`, следующие релизы обнаруживаются автоматически.

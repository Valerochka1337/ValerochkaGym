# Release-подпись и GitHub Actions

Workflow `.github/workflows/android-ci.yml` делает следующее:

- в pull request собирает debug APK и запускает unit-тесты без доступа к секретам;
- при push в `main`, push тега `v*` или ручном запуске собирает подписанный
  release APK, запускает unit-тесты, проверяет подпись через `apksigner` и публикует
  Actions artifact на 30 дней;
- вместе с APK сохраняет SHA-256 checksum и `mapping.txt` для расшифровки R8 stack trace.

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

Для каждого обновления увеличивайте `versionCode` в `app/build.gradle.kts`.

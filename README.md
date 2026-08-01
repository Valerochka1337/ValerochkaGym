# ValerochkaGym

Android-приложение для ведения силовых тренировок: планы тренировок, библиотека упражнений,
активная тренировка с таймером отдыха, история и выгрузка результатов в Google Sheets и
Google Calendar.

- Пакет: `com.valerochka1337.valerochkagym`
- minSdk 36, Kotlin + Jetpack Compose (Material 3), Hilt, Room, DataStore.

## Настройка Google-интеграции

Чтобы работали вход через Google и выгрузка в Google Sheets/Calendar, нужно создать проект
в Google Cloud и вставить в приложение свой OAuth Web client ID. Всё делается один раз.

### 1. Создать проект

1. Откройте [Google Cloud Console](https://console.cloud.google.com/).
2. Вверху выберите **Select a project → New Project**, задайте имя (например, `ValerochkaGym`)
   и создайте проект.

### 2. Включить нужные API

В меню **APIs & Services → Library** включите два API:

- **Google Sheets API**
- **Google Calendar API**

Для каждого нажмите **Enable**.

### 3. Настроить OAuth consent screen

1. Перейдите в **APIs & Services → OAuth consent screen**.
2. Тип пользователей — **External**, нажмите **Create**.
3. Заполните обязательные поля (название приложения, email поддержки, email разработчика).
4. На шаге **Scopes** можно ничего не добавлять — приложение запрашивает доступ во время работы.
5. На шаге **Test users** добавьте свой Google-аккаунт (иначе вход будет заблокирован, пока
   приложение не прошло верификацию).

### 4. Создать OAuth client ID для Web application

1. Перейдите в **APIs & Services → Credentials → Create Credentials → OAuth client ID**.
2. Тип приложения — **Web application**, задайте имя (например, `ValerochkaGym Web`).
3. Нажмите **Create** и скопируйте **Client ID** (вида `1234567890-xxxx.apps.googleusercontent.com`).
4. Вставьте его в `app/src/main/res/values/strings.xml` в строку `google_web_client_id`
   вместо плейсхолдера `YOUR_WEB_CLIENT_ID.apps.googleusercontent.com`.

> Именно **Web** client ID передаётся в `serverClientId` библиотеки входа — это не опечатка,
> так работает Credential Manager / Google Identity.

### 5. Создать OAuth client ID для Android

1. Снова **Create Credentials → OAuth client ID**, тип приложения — **Android**.
2. **Package name**: `com.valerochka1337.valerochkagym`.
3. **SHA-1**: получите отпечаток командой из корня проекта:

   ```bash
   ./gradlew signingReport
   ```

   Скопируйте значение `SHA1` для нужного варианта (debug для отладочной сборки).
4. Нажмите **Create**. Отдельно в приложение этот ID вставлять не нужно — Google связывает
   Android-приложение с проектом по package name и SHA-1.

### 6. Где взять ID таблицы

ID таблицы Google Sheets — это часть её ссылки:

```
https://docs.google.com/spreadsheets/d/<SPREADSHEET_ID>/edit
```

В приложении на экране **Настройки → Google Sheets** можно вставить как полную ссылку, так и
сам `<SPREADSHEET_ID>` — приложение извлечёт ID автоматически.

## Сборка и тесты

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

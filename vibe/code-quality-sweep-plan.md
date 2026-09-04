# Code quality sweep — план

## Цель

Единообразно форматировать Kotlin и Gradle Kotlin, блокировать style drift в CI и устранить
безопасные предупреждения компилятора/Android Lint без изменения поведения приложения.

## Scope и допущения

- AC-001: `spotlessCheck` проверяет Kotlin в `app/src` и три коммитируемых Gradle Kotlin script.
- AC-002: единственный `spotlessApply` форматирует исходники и удаляет неиспользуемые imports;
  повторный `spotlessCheck` проходит.
- AC-003: Android CI запускает stylecheck при уже определённых code/build changes до сборки.
- AC-004: workflow-only и documentation-only PR сохраняют лёгкий путь без checkout/JDK/Gradle.
- AC-005: исправлены три deprecated bottom-sheet API и четыре простые Android Lint warnings;
  обновления версий библиотек из lint scope не включаются.
- AC-006: `lintDebug`, unit tests и debug assembly проходят; нет изменений Room, runtime-contracts
  или схем.

Не добавляем Detekt, baseline, custom rules, новые Gradle-модули или `.editorconfig`.

## Решения

Spotless + ktfmt применяется с явными target: AGP 9 использует встроенный Kotlin, поэтому
автоопределение Android source set и ktlint-gradle не используются. Версии плагина и formatter
закреплены в version catalog. CI добавляет только `spotlessCheck` под имеющийся `code_changes`
guard; путь триггеров не расширяется. В поставку входит уже согласованное обновление AGP 9.3.2.

## Задачи

| ID | Действие и файлы | AC | Проверка | Владелец |
| --- | --- | --- | --- | --- |
| T-001 | Подключить Spotless/ktfmt в `gradle/libs.versions.toml`, `build.gradle.kts`; выполнить formatter для Kotlin и Gradle scripts. | 001, 002 | `spotlessApply`, `spotlessCheck` | implementer |
| T-002 | Добавить guarded `spotlessCheck` в `.github/workflows/android-ci.yml`. | 003, 004 | YAML diff + existing guards | implementer |
| T-003 | Устранить deprecation/UseKtx/CredentialManager предупреждения в library, update и Google auth; поднять app version в `app/build.gradle.kts`. | 005 | `:app:lintDebug` | implementer |
| T-004 | Проверить итог: lint, unit tests, debug assembly и review diff. | 006 | final gates | tester/reviewer |

## Риски и gates

ktfmt создаёт широкий механический diff, поэтому после apply проверяются затронутые файлы и
идемпотентность. UI поведение сохраняется: bottom sheet получает тот же initial Hidden state,
`NoCredentialException` возвращает тот же `Result.failure`, а `toUri()` эквивалентен `Uri.parse`.
Финальные gates: `:app:lintDebug`, `:app:testDebugUnitTest`, `:app:assembleDebug`.

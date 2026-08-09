# ValGym для Xiaomi Smart Band 10

Vela RPK-компаньон для активной тренировки ValerochkaGym. Экран — вертикальный
«пульт подхода»: двухстрочное название упражнения, крупное значение подхода или
таймера отдыха, live-пульс, цель и одна большая кнопка действия. RPK отправляет
на телефон команды управления подходом и отдыхом.

Исходники RPK лежат независимо от Android-модуля, поэтому Android-сборка приложения
не требует Node.js или Vela Toolkit.

## Сборка debug RPK

~~~bash
cd wearable/valerochka-gym-band
npm install
npm run build
~~~

Готовый файл находится в каталоге dist. Для установки загрузите этот RPK через
настроенный у вас канал Vela/Mi Fitness для debug-приложений.

Каталог sign намеренно исключён из Git: в нём находятся сертификат и закрытый ключ
подписи RPK. Для debug-сборки Toolkit ищет пару
sign/debug/private.pem и sign/debug/certificate.pem, затем sign/private.pem и
sign/certificate.pem. Не добавляйте эти файлы в репозиторий, issue или чат.

Если локальной пары нет, Toolkit подставит свой встроенный development-сертификат.
Такой RPK подходит только для проверки интерфейса: system.interconnect не сможет
авторизовать его рядом с APK, подписанным другим сертификатом. Перед проверкой
канала сравните SHA-256 отпечаток certificate.pem с сертификатом установленного APK.

## Связь с Android-приложением

В manifest.json намеренно указан package
com.valerochka1337.valerochkagym — тот же идентификатор, что у Android-приложения.
Для system.interconnect это обязательная пара с подписью: сертификат RPK должен
соответствовать сертификату APK-компаньона. Не меняйте package только с одной стороны
и не проверяйте интеграцию встроенной development-подписью Toolkit.

RPK использует двусторонний канал system.interconnect. Android-компаньон уже подключён
к официальному Xiaomi Wear SDK: AAR из официального Vela interconnect demo хранится в
app/libs/xms-wearable-lib_1.4_release.aar, а транспорт и протокольный мост находятся в
app/src/main/java/com/valerochka1337/valerochkagym/service/wear/. Они запускаются
foreground-сервисом только во время активной тренировки. Сообщения передаются как UTF-8
JSON с версией v=1.

### Телефон → браслет

Снимок состояния тренировки:

~~~json
{
  "v": 1,
  "type": "state",
  "sequence": 42,
  "phase": "rest",
  "workoutName": "Верх тела",
  "exerciseName": "Жим лёжа",
  "setNumber": 2,
  "setsInExercise": 4,
  "setValue": "60×8",
  "restEndsAtMillis": 1760000090000,
  "restMode": "timer",
  "targetHeartRateMinBpm": 100,
  "targetHeartRateMaxBpm": 120
}
~~~

Протокол также резервирует отдельное частое сообщение пульса:

~~~json
{
  "v": 1,
  "type": "heart_rate",
  "heartRateBpm": 112,
  "heartRateUpdatedAtMillis": 1760000005000
}
~~~

RPK также принимает ping и отвечает pong. Последовательность state защищает экран
от устаревших снимков, а heart_rate с пустым BPM очищает цифру пульса. Android во
время активной тренировки подключается к стандартному BLE Heart Rate Service
(`0x180D` / `0x2A37`), поэтому Share HR на Band 10 и любой другой HRS-датчик работают
одинаково. Просроченное измерение не передаётся как live-пульс. Для обратной
совместимости вместо диапазона можно передать targetHeartRateBpm: он будет использован
как верхняя граница цели.

### Браслет → телефон

После соединения RPK отправляет ready. Кнопки формируют следующие сообщения:

~~~json
{
  "v": 1,
  "type": "command",
  "id": "watch-1760000000000",
  "command": "add_rest_seconds",
  "seconds": 15
}
~~~

Поддерживаемые command:

- add_rest_seconds с seconds = 15 или -15;
- skip_rest;
- complete_set.

Маленькая кнопка «↻» отправляет request_state. После обработки любой команды Android
должен вернуть новый state — экран браслета не предполагает успешное выполнение
команды без подтверждения телефоном.

## Быстрая проверка

1. Соберите и установите свежий Android APK: ./gradlew :app:assembleDebug, затем
   app/build/outputs/apk/debug/app-debug.apk.
2. Соберите и установите этот debug RPK через Mi Fitness: «Я» → «О приложении» →
   Debug → Third-Party Apps. APK и RPK должны быть подписаны одной парой ключей.
3. На Band 10 включите Share HR: «Настройки» → «Share HR» → «Включить».
4. Начните тренировку в Android-приложении. На плитке «Пульс» нажмите
   «Подключить», выберите датчик, если найдено несколько, и дождитесь live BPM.
5. Откройте ValGym вручную на браслете и дождитесь статуса связи с телефоном.
6. Проверьте рабочий подход, отдых, «−15», «+15» и большую кнопку действия. После каждой
   команды Android должен вернуть подтверждённый state.

## Ограничение Share HR на Band 10

На части прошивок включённый Share HR не позволяет открыть приложения браслета. Android не
пытается запускать ValGym принудительно: если браслет не даёт открыть приложение вручную,
поддерживаемого способа одновременно показать live HR и пульт ValGym на этой прошивке нет.
Vela API не даёт прочитать пульс Band 10 напрямую. В таком случае используйте либо Share HR
с плиткой телефона, либо пульт ValGym без live-пульса на браслете.

Справка: [Xiaomi Share HR](https://www.mi.com/uk/support/faq/details/KA-579104/),
[Vela Sensor](https://iot.mi.com/vela/quickapp/en/features/system/sensor.html),
[Vela Bluetooth](https://iot.mi.com/vela/quickapp/en/features/system/bluetooth.html).

Если статус на браслете остаётся «НЕТ СВЯЗИ», сначала убедитесь, что установлены именно
свежие APK и RPK, затем закройте и заново начните тренировку. Подробности о режиме
установки debug-приложений и журналах Mi Fitness приведены в Xiaomi Vela FAQ.

Документация: [Vela Quick App](https://iot.mi.com/vela/quickapp/en/),
[system.interconnect](https://iot.mi.com/vela/quickapp/en/features/network/interconnect.html).

# Yarumo coach — исходники бренда

Оригиналы, предоставленные владельцем приложения 8 сентября 2026 года для
[полного ребрендинга, issue #48](https://github.com/Valerochka1337/ValerochkaGym/issues/48).
Файлы сохранены без изменений.

- [Логотип с надписью](logo-with-wordmark.png) — полная композиция `YARUMO COACH` для представления бренда.
- [Исходник иконки приложения](app-icon-source.png) — круглый знак без надписи в квадратном изображении.

Отображаемое название приложения: **Yarumo coach**. Регистр надписи в логотипе — часть оригинала.

`app-icon-source.png` скопирован byte-for-byte в Android resource
`app/src/main/res/drawable-nodpi/yarumo_app_icon_mark.png` (SHA-256
`0eabd16bbfc8ba3f1edaa14ad25702f5beb0131eb71cb63e3085b2ee230b2431`). Все default, round и
accent adaptive foreground используют этот ресурс через native XML `bitmap` c `gravity="fill"`
и равными inset 15% со всех сторон. Wordmark не используется как мелкий текст в launcher.

`ic_launcher_monochrome` и `ic_notification_gym` — отдельные system-tinted vector-маски с
контуром, гантелью, сердцем и пульсом того же знака. Старые density PNG сохранены без изменений
для совместимости; при minSdk 36 launcher выбирает adaptive resources. Полный объём работ и
критерии приёмки описаны в issue.

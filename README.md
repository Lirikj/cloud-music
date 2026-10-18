# Cloud Music

Cloud Music — минималистичный музыкальный плеер для Android и macOS. Пользователь сам добавляет публичную папку Яндекс Диска или Google Drive; приложение не содержит предзаполненной личной ссылки.

Возможности обеих платформ:

- потоковое воспроизведение из публичной папки с подпапками;
- поиск по треку, исполнителю и альбому;
- фильтр по исполнителю, избранное и очередь;
- обложки с ограниченным кэшем и локальные треки без копирования;
- явное скачивание выбранных треков для офлайн-прослушивания;
- тёмная и светлая темы, плавные переходы и компактный полноэкранный плеер.

Android дополнительно поддерживает Android Auto и Chromecast. macOS использует SwiftUI, AppKit и AVFoundation; сборка Universal рассчитана на Apple Silicon и Intel.

## Структура

- `android/` — Android-приложение `app.cloudmusic`, версия 0.4.0, versionCode 7.
- `macos/` — нативное macOS-приложение `app.cloudmusic.macos`, версия 1.1.0, build 2.
- `docs/` — отчёты проверок обеих платформ.

## Сборка Android

Нужны JDK 17 и Android SDK 36. Создайте локальный `local.properties` и, если нужен подписанный релиз, отдельный `signing.properties` по образцу `android/signing.properties.example`. Секреты не добавляйте в Git.

```sh
cd android
./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease
```

APK и AAB появятся в `android/app/build/outputs/`. Для Google Play используется AAB; публикация в Play Console выполняется отдельно.

## Сборка macOS

Нужны Swift 5.10 и macOS Command Line Tools.

```sh
cd macos
./scripts/build.sh
```

Скрипт создаёт Universal `dist/Cloud Music.app` и подписывает его локальной ad-hoc подписью. Для распространения за пределами тестового Mac используйте собственную Developer ID подпись и нотарификацию Apple; закрытые ключи в репозитории отсутствуют.

Полную проверку и ограничения смотрите в `docs/ANDROID-VALIDATION.md` и `docs/MACOS-VALIDATION.md`.

## Данные пользователя

Ссылки, локальные файлы, security-scoped bookmarks, скачанные копии, кэши, ключи подписи и `local.properties` не входят в репозиторий. Скачивание начинается только по явной команде пользователя; автоматического аудиокэша нет.

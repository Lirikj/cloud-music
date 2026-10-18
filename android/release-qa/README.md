# Проверка готового release APK

Отдельное QA-приложение управляет системным интерфейсом и читает системную медиасессию. Оно работает в своём процессе, не имеет доступа к внутренним классам или базе Cloud Music и не меняет оптимизацию пользовательского APK. Устанавливайте только на отдельный тестовый эмулятор. Тест скачивает и затем удаляет одну копию MP3, подключает публичную папку и переключает сеть эмулятора.

Перед запуском установите подписанный `app-release.apk`, поместите два тестовых WAV-файла `QA Artist - First.wav` и `QA Artist - Second.wav` в `Download/CloudMusicQA` и используйте чистые данные тестируемого приложения.

```sh
CLOUD_MUSIC_SIGNING_PROPERTIES=/private/path/release.properties ./gradlew :release-qa:assembleDebug -PreleaseQa -PproductionQa
adb install -t release-qa/build/outputs/apk/debug/release-qa-debug.apk
adb shell am instrument -w -e class app.cloudmusic.releaseqa.ReleaseSmokeTest app.cloudmusic.releaseqa/androidx.test.runner.AndroidJUnitRunner
```

QA APK не предназначен для пользователей. Скриншоты пишутся в его `files/qa` во внешнем хранилище. Набор из 21 инструментального теста внутренних компонентов остаётся доступен для debug-варианта приложения.

Четыре сценария проверяют облачный поток и ручную загрузку с офлайн-перезапуском, SAF и исполнителей, темы и release-флаги, а также внешний MediaBrowser: каталог, поиск и очередь исполнителя. Для доступа последнего сценария QA APK подписывается тем же ключом, что приложение (`productionQa`); приватная конфигурация не входит в архив. Набор не проверяет реальное подключение к автомобильному хосту или Chromecast.

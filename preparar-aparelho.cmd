@echo off
rem Prepara um aparelho novo para a atualizacao pelo proprio app (rodar uma vez, com o cabo, depois de instalar o app):
rem  - concede WRITE_SECURE_SETTINGS (o app liga sozinho "Fontes desconhecidas" antes de instalar)
rem  - liga "Fontes desconhecidas" ja agora
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
"%ADB%" shell pm grant br.com.lmsoftworks.caixa2 android.permission.WRITE_SECURE_SETTINGS
"%ADB%" shell settings put secure install_non_market_apps 1
echo Pronto.
rem  - permissao do microfone (assistente de voz)
"%ADB%" shell pm grant br.com.lmsoftworks.caixa2 android.permission.RECORD_AUDIO
rem  - modelo de voz (Vosk) e chaves (gemini.txt / tavily.txt) do app antigo, se ainda estiverem no aparelho
"%ADB%" shell "D=/sdcard/Android/data/br.com.lmsoftworks.caixa2/files; mkdir -p $D; O=/sdcard/Android/data/br.com.lmsoftworks.caixa/files; [ -d $O/model ] && [ ! -d $D/model ] && cp -r $O/model $D/model; for f in gemini.txt tavily.txt; do [ -f $O/$f ] && [ ! -f $D/$f ] && cp $O/$f $D/$f; done; ls $D"

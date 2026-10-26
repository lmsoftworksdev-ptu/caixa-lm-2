@echo off
rem Prepara um aparelho novo para a atualizacao pelo proprio app (rodar uma vez, com o cabo, depois de instalar o app):
rem  - concede WRITE_SECURE_SETTINGS (o app liga sozinho "Fontes desconhecidas" antes de instalar)
rem  - liga "Fontes desconhecidas" ja agora
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
"%ADB%" shell pm grant br.com.lmsoftworks.caixa2 android.permission.WRITE_SECURE_SETTINGS
"%ADB%" shell settings put secure install_non_market_apps 1
echo Pronto.

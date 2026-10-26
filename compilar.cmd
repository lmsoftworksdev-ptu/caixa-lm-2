@echo off
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
cd /d "%~dp0"
echo sdk.dir=%ANDROID_HOME:\=\\%> local.properties
call "%~dp0gradlew.bat" assembleDebug --no-daemon
echo.
echo FIM codigo %ERRORLEVEL%
dir /b app\build\outputs\apk\debug

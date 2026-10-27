@echo off
chcp 65001 >nul
REM Baut HomeX + Plugins und startet den Server im Ordner .\server
call mvn -q package || exit /b 1
if not exist server\plugins mkdir server\plugins
copy /Y homex-core\target\HomeX.jar server\ >nul
for /d %%D in (homex-plugins\*) do copy /Y "%%D\target\*.jar" server\plugins\ >nul
cd server
java -Dstdout.encoding=UTF-8 -jar HomeX.jar
cd ..

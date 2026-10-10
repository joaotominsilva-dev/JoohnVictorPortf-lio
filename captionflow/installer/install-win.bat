@echo off
rem Instalador CaptionFlow para Windows
set "SRC=%~dp0.."
set "DEST=%APPDATA%\Adobe\CEP\extensions\com.joohn.captionflow"
if exist "%DEST%" rmdir /s /q "%DEST%"
mkdir "%DEST%"
xcopy "%SRC%" "%DEST%" /E /I /Y /Q >nul
if exist "%DEST%\installer" rmdir /s /q "%DEST%\installer"
if exist "%DEST%\tests" rmdir /s /q "%DEST%\tests"
for %%V in (9 10 11 12) do reg add "HKCU\Software\Adobe\CSXS.%%V" /v PlayerDebugMode /t REG_SZ /d 1 /f >nul
echo CaptionFlow instalado em: %DEST%
echo Reinicie o Premiere Pro e abra: Janela ^> Extensoes ^> CaptionFlow
pause

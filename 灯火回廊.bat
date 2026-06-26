@echo off
rem 灯火回廊 ― ASCENT  Windows 起動スクリプト
rem このフォルダ内の .jar をダブルクリックで起動（Java 17 以降が必要）
cd /d "%~dp0"

rem 文字コードに依存しないよう、フォルダ内の最初の .jar を起動
set "JAR="
for %%f in (*.jar) do if not defined JAR set "JAR=%%f"

if not defined JAR (
  echo jar が見つかりません。このフォルダに 灯火回廊.jar を置いてください。
  pause
  goto :eof
)

where javaw >nul 2>nul
if %errorlevel%==0 (
  start "" javaw -jar "%JAR%"
) else (
  where java >nul 2>nul
  if %errorlevel%==0 (
    start "" java -jar "%JAR%"
  ) else (
    echo Java が見つかりません。Java 17 以降をインストールしてください。
    echo   https://adoptium.net/
    pause
  )
)

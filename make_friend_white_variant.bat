@echo off
setlocal
set TEXDIR=src\main\resources\assets\friend\textures\entity
if not exist "%TEXDIR%\friend.png" (
  echo [ERROR] %TEXDIR%\friend.png not found.
  pause
  exit /b 1
)
copy /Y "%TEXDIR%\friend.png" "%TEXDIR%\friend_white.png"
echo Created: %TEXDIR%\friend_white.png
pause

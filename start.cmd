@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Install Java 21 or newer and make sure java is available on PATH.
  pause
  exit /b 1
)
if not exist "target\attendance-tracker.jar" (
  echo Building the attendance tracker. Internet is needed for the first build.
  call mvnw.cmd -B -ntp verify
  if errorlevel 1 (
    echo Build failed. Check the messages above.
    pause
    exit /b 1
  )
)
java -jar "target\attendance-tracker.jar" %*
if errorlevel 1 pause

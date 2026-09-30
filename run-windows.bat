@echo off
setlocal
title OpenKNX Studio

echo.
echo ========================================
echo        OpenKNX Studio - starten
echo ========================================
echo.

where java >nul 2>&1
if errorlevel 1 (
  echo [FOUT] Java 21 is niet gevonden.
  echo Installeer Java 21 met:
  echo   winget install EclipseAdoptium.Temurin.21.JDK
  echo.
  pause
  exit /b 1
)

echo Java gevonden:
java -version
echo.
echo OpenKNX Studio wordt gestart...
echo De eerste keer downloadt de Gradle Wrapper automatisch Gradle 8.10.2.
echo.

call gradlew.bat run

if errorlevel 1 (
  echo.
  echo [FOUT] OpenKNX Studio kon niet starten.
  echo Kopieer de foutmelding en stuur ze in onze ChatGPT-conversatie.
  echo.
  pause
  exit /b 1
)

endlocal

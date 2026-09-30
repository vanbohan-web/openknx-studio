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
  echo Installeer met:
  echo   winget install EclipseAdoptium.Temurin.21.JDK
  echo.
  pause
  exit /b 1
)

where gradle >nul 2>&1
if errorlevel 1 (
  echo [FOUT] Gradle is niet gevonden.
  echo Installeer met:
  echo   winget install Gradle.Gradle
  echo.
  pause
  exit /b 1
)

echo Java gevonden:
java -version
echo.
echo Gradle gevonden:
gradle -v
echo.
echo OpenKNX Studio wordt gestart...
echo.

gradle run

if errorlevel 1 (
  echo.
  echo [FOUT] OpenKNX Studio kon niet starten.
  echo Kopieer de foutmelding en stuur ze in onze ChatGPT-conversatie.
  echo.
  pause
  exit /b 1
)

endlocal

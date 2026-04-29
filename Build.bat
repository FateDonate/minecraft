@echo off
title Build FateDonate
cd /d "%~dp0"

set "MVN=.maven_cache\apache-maven-3.9.11\bin\mvn.cmd"

if not exist "%MVN%" (
    echo Downloading Maven...
    mkdir .maven_cache 2>nul
    bitsadmin /transfer maven /download /priority normal https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.zip "%temp%\maven.zip" >nul
    tar -xf "%temp%\maven.zip" -C .maven_cache
    del "%temp%\maven.zip" 2>nul
)

echo Building plugin...
call "%MVN%" clean package -DskipTests

for %%f in (target\*-*.jar) do (
    echo %%f | find /v "original" >nul
    if not errorlevel 1 (
        echo.
        echo ================================
        echo BUILD SUCCESSFUL
        echo Jar: %%f
        echo ================================
    )
)

echo.
pause
exit /b %errorlevel%

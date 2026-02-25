@ECHO OFF
SETLOCAL EnableExtensions

SET "BASE_DIR=%~dp0"
IF "%BASE_DIR:~-1%"=="\" SET "BASE_DIR=%BASE_DIR:~0,-1%"

SET "WRAPPER_DIR=%BASE_DIR%\.mvn\wrapper"
SET "WRAPPER_PROPS=%WRAPPER_DIR%\maven-wrapper.properties"
SET "WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar"

IF NOT EXIST "%WRAPPER_PROPS%" (
  ECHO [ERROR] Missing "%WRAPPER_PROPS%".
  EXIT /B 1
)

IF NOT EXIST "%WRAPPER_JAR%" (
  ECHO Downloading Maven Wrapper JAR...
  powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop';" ^
    "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12;" ^
    "$lines = Get-Content -Path '%WRAPPER_PROPS%' | Where-Object { $_ -match '^[^#].+=.+' };" ^
    "$map = @{}; foreach ($line in $lines) { $parts = $line.Split('=', 2); $map[$parts[0].Trim()] = $parts[1].Trim() };" ^
    "$wrapperUrl = $map['wrapperUrl'];" ^
    "if ([string]::IsNullOrWhiteSpace($wrapperUrl)) { throw 'wrapperUrl is missing in maven-wrapper.properties' };" ^
    "New-Item -ItemType Directory -Path '%WRAPPER_DIR%' -Force | Out-Null;" ^
    "Invoke-WebRequest -UseBasicParsing -Uri $wrapperUrl -OutFile '%WRAPPER_JAR%';"

  IF ERRORLEVEL 1 (
    ECHO [ERROR] Failed to download Maven Wrapper JAR.
    EXIT /B 1
  )
)

IF DEFINED JAVA_HOME (
  SET "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) ELSE (
  SET "JAVA_EXE=java"
)

"%JAVA_EXE%" -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%BASE_DIR%" org.apache.maven.wrapper.MavenWrapperMain %*
EXIT /B %ERRORLEVEL%

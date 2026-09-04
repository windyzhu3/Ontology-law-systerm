@echo off
setlocal
set "PROJECT_DIR=%~dp0"

if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" (
  call "%MAVEN_HOME%\bin\mvn.cmd" %*
  exit /b %ERRORLEVEL%
)

set "MAVEN_USER_HOME=%USERPROFILE%\.m2"
set "DIST_PARENT=%MAVEN_USER_HOME%\wrapper\dists\apache-maven-3.9.16-bin\verified"
set "DIST_HOME=%DIST_PARENT%\apache-maven-3.9.16"
set "ARCHIVE=%TEMP%\apache-maven-3.9.16-bin.zip"

if not exist "%DIST_HOME%\bin\mvn.cmd" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$p = Get-Content '%PROJECT_DIR%.mvn\wrapper\maven-wrapper.properties' | ConvertFrom-StringData;" ^
    "Invoke-WebRequest -UseBasicParsing $p.distributionUrl -OutFile '%ARCHIVE%';" ^
    "if ((Get-FileHash '%ARCHIVE%' -Algorithm SHA256).Hash.ToLower() -ne $p.distributionSha256Sum) { throw 'Maven SHA-256 mismatch' };" ^
    "if ((Get-FileHash '%ARCHIVE%' -Algorithm SHA512).Hash.ToLower() -ne $p.distributionSha512Sum) { throw 'Maven SHA-512 mismatch' };" ^
    "New-Item -ItemType Directory -Force '%DIST_PARENT%' | Out-Null;" ^
    "Expand-Archive -Force '%ARCHIVE%' '%DIST_PARENT%'"
  if errorlevel 1 exit /b %ERRORLEVEL%
)

call "%DIST_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%

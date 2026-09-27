@echo off
rem ============================================================
rem Dev-machine-only build helper (ASCII only on purpose:
rem non-ASCII in .cmd files gets mangled by the OEM codepage).
rem Uses the JDK 21 vendored in tools\jdk plus the local Gradle dist.
rem Two paths are forced into this workspace on purpose (this machine only):
rem   GRADLE_USER_HOME -> .gh    Gradle native libs cannot be loaded from
rem                              %USERPROFILE%\.gradle here.
rem   TEMP / TMP       -> .tmp   %LOCALAPPDATA%\Temp is not writable for the
rem                              Kotlin compiler and ADB here.
rem Usage: tools\_ci-build.cmd assembleRelease
rem For normal users use tools\build-apk.ps1 or Android Studio.
rem ============================================================
setlocal
set "JAVA_HOME=D:\DSH\OurSpace\tools\jdk\jdk-21.0.12.1+1"
set "ANDROID_HOME=D:\AndroidSDK"
set "ANDROID_SDK_ROOT=D:\AndroidSDK"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "GRADLE_USER_HOME=D:\DSH\OurSpace\.gh"
set "TEMP=D:\DSH\OurSpace\.tmp"
set "TMP=D:\DSH\OurSpace\.tmp"
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"
if not exist "%TEMP%" mkdir "%TEMP%"

set "GRADLE_BAT=C:\Users\25625\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat"
if not exist "%GRADLE_BAT%" (
  echo [ERROR] gradle not found at %GRADLE_BAT%
  exit /b 1
)

cd /d D:\DSH\OurSpace\android
call "%GRADLE_BAT%" --no-daemon --console=plain %*
exit /b %ERRORLEVEL%

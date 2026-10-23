@echo off
setlocal
set APP_HOME=%~dp0
if "%JAVA_HOME%"=="" (
  set JAVA_EXE=java.exe
) else (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
)
"%JAVA_EXE%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal

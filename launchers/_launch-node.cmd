@echo off
REM ============================================================================
REM  weStream node launcher (CORE - called by the run-nodeN.cmd wrappers).
REM  usage:  _launch-node.cmd <nodeId> [throttleKbps]
REM
REM  Runs in cmd.exe (immune to PowerShell's script-execution policy) and uses
REM  the BUILT UI bundle + Electron directly - NOT `npm run app:dev`, whose
REM  concurrently/sh syntax (`VAR=val electron`) fails in cmd.exe and made the
REM  window close instantly. Electron loads dist\index.html and spawns this
REM  node's Java engine itself.
REM ============================================================================
setlocal

REM --- Force JDK 19 for the spawned engine (electron calls `java` via PATH) ---
if exist "C:\Program Files\Java\jdk-19\bin\java.exe" (
  set "JAVA_HOME=C:\Program Files\Java\jdk-19"
  set "PATH=C:\Program Files\Java\jdk-19\bin;%PATH%"
) else (
  echo [warn] JDK 19 not found at C:\Program Files\Java\jdk-19 - using default java
)

set "WS_NODE_ID=%~1"
if not "%~2"=="" set "WS_THROTTLE_KBPS=%~2"

echo(
echo   weStream  -  node %WS_NODE_ID%
if defined WS_THROTTLE_KBPS (
  echo   upload throttle: %WS_THROTTLE_KBPS% KB/s   ^(sliding-window demo^)
) else (
  echo   upload throttle: off ^(full speed^)
)
echo(

if not exist "%~dp0..\out\production\weStream\app\ServentMain.class" (
  echo [ERROR] engine not compiled. Run this once from the project root:
  echo   javac -d out\production\weStream ^(Get-ChildItem -Recurse src -Filter *.java^).FullName
  echo(
  pause
  exit /b 1
)

cd /d "%~dp0..\react\westream-react"

REM Build the UI bundle on first run (or after you change the React code).
if not exist "dist\index.html" (
  echo Building UI bundle ^(first run^)...
  call npm.cmd run build
)

echo Starting window... ^(close the app window to stop this node^)
call npm.cmd run app:start

echo(
echo Node %WS_NODE_ID% stopped.
pause
endlocal

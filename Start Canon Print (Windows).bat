@echo off
title Canon Print
cd /d "%~dp0"
where py >nul 2>nul && (set PY=py -3) || (set PY=python)
%PY% --version >nul 2>nul
if errorlevel 1 (
  echo Python is not installed.
  echo Install it from https://www.python.org/downloads/ ^(tick "Add python.exe to PATH"^), then run this again.
  pause
  exit /b 1
)
if not exist ".venv\Scripts\python.exe" (
  echo First start: setting things up. This takes a minute...
  %PY% -m venv .venv || goto :fail
  ".venv\Scripts\python.exe" -m pip install --upgrade pip >nul
  ".venv\Scripts\python.exe" -m pip install -r requirements.txt || goto :fail
)
".venv\Scripts\python.exe" run.py
exit /b 0
:fail
echo Setup failed. Check your internet connection and try again.
pause
exit /b 1

@echo off
chcp 65001 >nul
title VCampus Packaging Tool
echo ========================================
echo   Running VCampus Packaging Script...
echo ========================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package.ps1"
echo.
pause

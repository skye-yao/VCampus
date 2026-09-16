@echo off
cd /d "%~dp0"
python scripts\setup-database.py --demo
if errorlevel 1 (
    echo Database setup failed. See the error above.
    pause
    exit /b 1
)
echo Database setup completed.
pause

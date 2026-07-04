@echo off
set ANDROID_SDK_ROOT=D:\AndroidSDK
set ANDROID_AVD_HOME=D:\AndroidStudio\Projects\RCGallery\Test
set JAVA_HOME=C:\Users\16512\.jdks\ms-17.0.19
set PATH=%ANDROID_SDK_ROOT%\emulator;%ANDROID_SDK_ROOT%\platform-tools;%JAVA_HOME%\bin;%PATH%

echo ========================================
echo  Starting Android Emulator (with window)
echo ========================================
echo.

start "Emulator" /WAIT /B /HIGH emulator.exe -avd test_device -gpu auto -memory 2048 -noaudio -show-window -netdelay none -netspeed full -port 5554

echo Emulator exited.
pause

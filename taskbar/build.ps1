#Requires -Version 7.3
# Tests the taskbar app, builds it in Release and (re)starts it. The running app locks its own exe, so it is stopped before the build.
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

dotnet test "$PSScriptRoot/tests"
Get-Process MyAirTaskbar -ErrorAction SilentlyContinue | Stop-Process -PassThru | Wait-Process
dotnet build -c Release $PSScriptRoot
Start-Process "$PSScriptRoot/bin/Release/net10.0-windows10.0.26100.0/win-x64/MyAirTaskbar.exe"

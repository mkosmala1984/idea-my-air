#Requires -Version 7.3
# Tests the taskbar app, builds it in Release and (re)starts it. The running app locks its own exe, which the tests' Debug build
# also writes when it is the Debug one that runs, so it is stopped before the tests.
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

Get-Process MyAirTaskbar -ErrorAction SilentlyContinue | Stop-Process -PassThru | Wait-Process
dotnet test "$PSScriptRoot/tests"
dotnet build -c Release $PSScriptRoot
Start-Process "$PSScriptRoot/bin/Release/net10.0-windows10.0.26100.0/win-x64/MyAirTaskbar.exe"

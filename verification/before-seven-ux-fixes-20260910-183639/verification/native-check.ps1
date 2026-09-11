$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$nativeBuild = [IO.File]::ReadAllText((Join-Path $projectRoot 'android/last-build.txt')).Trim()
$checkClasses = Join-Path $nativeBuild 'verification-classes'
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$jdkRoot = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:/HA/HEALTH APP/.local/toolchains/jdk-17.0.20.1+1' }
$nativeClasspath = (Join-Path $nativeBuild 'classes') + ';' + (Join-Path $sdkRoot 'platforms/android-37.0/android.jar')
New-Item -ItemType Directory -Path $checkClasses -Force | Out-Null
& (Join-Path $jdkRoot 'bin/javac.exe') --release 8 -encoding UTF-8 -classpath $nativeClasspath -d $checkClasses (Join-Path $PSScriptRoot 'NativeFinalCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Native verification compilation failed' }
& (Join-Path $jdkRoot 'bin/java.exe') -classpath ($checkClasses + ';' + $nativeClasspath) com.mani.orbit.NativeFinalCheck
if ($LASTEXITCODE -ne 0) { throw 'Native verification failed' }

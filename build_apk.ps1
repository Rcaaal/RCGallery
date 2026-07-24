$ErrorActionPreference = 'Stop'

# Use the locally cached Gradle runtime so packaging never depends on wrapper downloads.
$env:JAVA_HOME = 'C:\Users\16512\.jdks\ms-17.0.19'
$gradleRoot = Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.13-bin'
$gradleBin = Get-ChildItem -LiteralPath $gradleRoot -Recurse -Filter 'gradle.bat' -File |
    Where-Object { $_.Directory.Name -eq 'bin' } |
    Select-Object -First 1

if ($null -eq $gradleBin) {
    throw "Local Gradle 8.13 was not found under $gradleRoot"
}

& $gradleBin.FullName assembleDebug --no-daemon --max-workers=1 --console=plain
exit $LASTEXITCODE

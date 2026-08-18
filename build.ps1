[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Tasks = @("test", "buildPlugin"),

    [string] $RiderHome
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($RiderHome)) {
    $RiderHome = $env:RIDER_HOME
}

if ([string]::IsNullOrWhiteSpace($RiderHome)) {
    $localAppData = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
    if ([string]::IsNullOrWhiteSpace($localAppData)) {
        throw "Rider could not be located automatically. Pass -RiderHome or set RIDER_HOME."
    }

    $RiderHome = Join-Path $localAppData "Programs\Rider"
}

$riderJbr = Join-Path $RiderHome "jbr"
$javaExecutable = Join-Path $riderJbr "bin\java.exe"

if (-not (Test-Path -LiteralPath $javaExecutable)) {
    throw "Rider's bundled Java runtime was not found at '$javaExecutable'. Pass -RiderHome or set RIDER_HOME."
}

$env:RIDER_HOME = $RiderHome
$env:JAVA_HOME = $riderJbr

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot "gradlew.bat") @Tasks
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

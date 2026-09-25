# Sets the release version in build.sbt and in the installation examples of README.md.
# With -Check it changes nothing and fails when a file names another version; without a
# -Version, -Check takes the one in build.sbt, so CI can verify that the files agree.
param(
    [string]$Version = "",
    [switch]$Check
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildSbt = Join-Path $repoRoot "build.sbt"
$readme = Join-Path $repoRoot "README.md"
$buildPattern = '(?m)^(\s*version\s*:=\s*")([^"]*)(")'
$sbtPattern = '("com\.anjunar\.hibernateddl" %% "schema-[a-z]*" % ")([^"]*)(")'
$mavenPattern = '(<version>)([^<]*)(</version>)'

function Get-BuildVersion {
    $match = [regex]::Match([IO.File]::ReadAllText($buildSbt), $buildPattern)
    if (-not $match.Success) {
        throw "Could not read the version from build.sbt."
    }
    return $match.Groups[2].Value
}

if (-not $Version -and $Check) {
    $Version = Get-BuildVersion
}
if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$') {
    throw "Usage: scripts/set-version.ps1 -Version 1.0.1, or -Check [-Version 1.0.1]"
}

$buildText = [IO.File]::ReadAllText($buildSbt)
$readmeText = [IO.File]::ReadAllText($readme)

if ($Check) {
    $problems = @()
    $found = Get-BuildVersion
    if ($found -ne $Version) {
        $problems += "build.sbt has version '$found', expected '$Version'."
    }
    $named = @([regex]::Matches($readmeText, $sbtPattern)) + @([regex]::Matches($readmeText, $mavenPattern)) |
        ForEach-Object { $_.Groups[2].Value } | Sort-Object -Unique
    foreach ($value in $named) {
        if ($value -ne $Version) {
            $problems += "README.md names version '$value', expected '$Version'."
        }
    }
    if ($problems.Count -gt 0) {
        $problems | ForEach-Object { Write-Error $_ -ErrorAction Continue }
        exit 1
    }
    Write-Host "build.sbt and README.md name version $Version."
    exit 0
}

# Keep each file's line endings; only the version text changes.
$encoding = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($buildSbt, [regex]::Replace($buildText, $buildPattern, "`${1}$Version`${3}"), $encoding)
$readmeText = [regex]::Replace($readmeText, $sbtPattern, "`${1}$Version`${3}")
$readmeText = [regex]::Replace($readmeText, $mavenPattern, "`${1}$Version`${3}")
[IO.File]::WriteAllText($readme, $readmeText, $encoding)
Write-Host "Set version $Version in build.sbt and README.md."

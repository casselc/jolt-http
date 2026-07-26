#requires -version 5
<#
.SYNOPSIS
  Resolve jolt-http's test graph and run one checked-in source-mode Windows gate.

.DESCRIPTION
  Invokes native Chez directly through the runtime's `host\chez\cli.ss`. It does
  not route native paths or Clojure source through a bash wrapper, and it never
  relies on the current `/dev/stdin` implementation of `joltc -`.

  Mirrors jolt-tcp's W6A runner, including its Start-Process rule: materialize
  the process handle, wait with an explicit timeout, refuse a missing exit code,
  and propagate a nonzero one.
#>
param(
  [string]$JoltHttpPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
  [string]$RuntimePath = "D:\src\jolt-proposal-net-runtime-w3",
  [string]$ChezExe = "D:\chez-10.4.1\bin\scheme.exe",
  [string]$TestAlias = "-M:windows-runtime-test",
  [string]$GitLibsPath = "",
  [switch]$InstallHegel,
  [switch]$HegelRequired,
  [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ChezExe)) {
  throw "test-windows-source.ps1: scheme.exe not found at $ChezExe"
}
if (-not (Test-Path (Join-Path $RuntimePath "host\chez\cli.ss"))) {
  throw "test-windows-source.ps1: host\chez\cli.ss not found under $RuntimePath"
}
if ($TimeoutSeconds -le 0) {
  throw "test-windows-source.ps1: TimeoutSeconds must be positive"
}
if (-not $TestAlias.StartsWith("-M:")) {
  throw "test-windows-source.ps1: TestAlias must be a -M: alias"
}

$env:JOLT_PWD = $JoltHttpPath
$env:JOLT_AOT_CACHE = "0"
$env:JOLT_VERSION = "dev"

if ($HegelRequired) {
  $env:JOLT_HEGEL_REQUIRED = "1"
}

# JOLT_SH is required by Jolt's DEPENDENCY TOOLING, not by test execution.
# Scoping it to the libhegel install phase was tried first and fails earlier
# than that: every alias resolves the git graph, and Jolt derives a git cache
# key by shelling out, so without JOLT_SH resolution aborts with
#
#   could not derive git cache key for https://github.com/casselc/jolt-tcp.git
#   ex-data: {... :digest "" :exit 255}
#
# before a single namespace is loaded. It is therefore set for the whole run --
# which is exactly "where dependency tooling genuinely requires it" -- and the
# gate itself still declares no :extra-deps and makes no use of a shell.
$env:JOLT_SH = "C:\Program Files\Git\bin\sh.exe"
if (-not (Test-Path $env:JOLT_SH)) {
  throw "test-windows-source.ps1: JOLT_SH not found at $env:JOLT_SH; Jolt cannot resolve git dependencies without it"
}

# Jolt derives its git cache from $HOME and falls back to a RELATIVE "./.jolt"
# when HOME is unset, which native Windows shells routinely leave empty. That
# relative path is then written under $JOLT_PWD but existence-checked against
# the process working directory, which this script deliberately sets to the
# runtime checkout -- so the ownership claim is published and then reported
# missing, and dependency resolution fails before any test runs. Pinning
# JOLT_GITLIBS to an absolute directory is the supported knob for this and
# keeps the gate hermetic and independent of the ambient profile.
if ([string]::IsNullOrWhiteSpace($GitLibsPath)) {
  $GitLibsPath = Join-Path $JoltHttpPath ".jolt-cache\gitlibs"
}
if (-not (Test-Path $GitLibsPath)) {
  $null = New-Item -ItemType Directory -Force -Path $GitLibsPath
}
$env:JOLT_GITLIBS = (Resolve-Path $GitLibsPath).Path

function Invoke-Jolt {
  param(
    [string]$Phase,
    [string[]]$JoltArgs
  )

  Write-Host $Phase
  $arguments = @("--script", "host\chez\cli.ss") + $JoltArgs
  $process = Start-Process `
    -FilePath $ChezExe `
    -ArgumentList $arguments `
    -NoNewWindow `
    -PassThru
  # PowerShell 5.1 does not reliably populate .ExitCode unless the native
  # process handle has first been materialized. Without this read, a failing
  # gate can be mistaken for success because $null compares equal to zero.
  $null = $process.Handle
  if ($process.WaitForExit($TimeoutSeconds * 1000)) {
    $exitCode = $process.ExitCode
    if ($null -eq $exitCode) {
      throw "$Phase observed no process exit code; refusing to report success"
    }
    if ($exitCode -ne 0) {
      throw "$Phase failed with exit code $exitCode"
    }
  }
  else {
    [Console]::Error.WriteLine(
      "$Phase timed out after $TimeoutSeconds seconds; terminating PID $($process.Id)"
    )
    try {
      $process.Kill()
      $process.WaitForExit()
    }
    catch {
      [Console]::Error.WriteLine(
        "failed to terminate timed-out PID $($process.Id): $($_.Exception.Message)"
      )
    }
    throw "$Phase timed out"
  }
}

Write-Host "jolt-http native Windows source gate"
Write-Host "  JOLT_PWD = $env:JOLT_PWD"
Write-Host "  runtime  = $RuntimePath"
Write-Host "  scheme   = $ChezExe"
Write-Host "  alias    = $TestAlias"
Write-Host "  gitlibs  = $env:JOLT_GITLIBS"
Write-Host "  hegel    = $InstallHegel (required=$HegelRequired)"
Write-Host ""

Push-Location $RuntimePath
try {
  if ($InstallHegel) {
    $installAlias = "-A:" + $TestAlias.Substring(3)
    Invoke-Jolt -Phase "Install libhegel for $installAlias" `
      -JoltArgs @($installAlias, "-m", "hegel.install")
  }
  Invoke-Jolt -Phase "Run $TestAlias" -JoltArgs @($TestAlias)
}
finally {
  Pop-Location
}

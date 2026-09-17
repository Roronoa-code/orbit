param(
    [ValidateSet('doctor','full','changed','replay')][string]$Mode = 'doctor',
    [ValidateSet('phone','wear')][string]$Module,
    [string]$Serial,
    [string]$Baseline,
    [ValidateSet('workout-control','watch-recovery','diagnostic-boundaries','explore-source-reversal')][string]$Case
)
$ErrorActionPreference = 'Stop'
$python = if ($env:ORBIT_PYTHON) { $env:ORBIT_PYTHON } else { (Get-Command python -ErrorAction Stop).Source }
$arguments = @('-B', '-X', 'utf8', (Join-Path $PSScriptRoot 'dev.py'), $Mode)
if ($Module) { $arguments += @('--module', $Module) }
if ($Serial) { $arguments += @('--serial', $Serial) }
if ($Baseline) { $arguments += @('--baseline', $Baseline) }
if ($Case) { $arguments += @('--case', $Case) }
& $python @arguments
exit $LASTEXITCODE

param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $false)]
    [string]$BuildEvidence,

    [Parameter(Mandatory = $false)]
    [ValidateNotNullOrEmpty()]
    [string]$WslDistribution = "Ubuntu-24.04",

    [Parameter(Mandatory = $false)]
    [string]$AdbPath
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$OutputRoot = [IO.Path]::GetFullPath($OutputDirectory)
$RepositoryPrefix = $RepositoryRoot.TrimEnd([char[]]@("\", "/")) + [IO.Path]::DirectorySeparatorChar
if ($OutputRoot.Equals($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $OutputRoot.StartsWith($RepositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Physical-device evidence must be written outside the source repository"
}

if (-not $BuildEvidence) {
    $BuildEvidence = Join-Path $RepositoryRoot (
        "evidence\gsi\20260813-gsi-build5-3c0c685-j12\soong-build-evidence.json"
    )
}
$BuildEvidencePath = [IO.Path]::GetFullPath($BuildEvidence)
if (-not (Test-Path -LiteralPath $BuildEvidencePath -PathType Leaf)) {
    throw "GSI build evidence does not exist: $BuildEvidencePath"
}
foreach ($sibling in @(
    "avb-verification.json",
    "dsu-payload.json",
    "system-interface.json"
)) {
    $path = Join-Path (Split-Path -Parent $BuildEvidencePath) $sibling
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "GSI evidence is missing required sibling: $path"
    }
}

if (-not (Test-Path -LiteralPath $OutputRoot)) {
    New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
}

function Convert-ToWslPath {
    param([Parameter(Mandatory = $true)][string]$WindowsPath)
    $absolute = [IO.Path]::GetFullPath($WindowsPath)
    if ($absolute -notmatch "^([A-Za-z]):\\(.*)$") {
        throw "Only absolute Windows drive paths can be converted for WSL: $absolute"
    }
    $drive = $Matches[1].ToLowerInvariant()
    $relative = $Matches[2].Replace("\", "/")
    return "/mnt/$drive/$relative"
}

$RunId = [DateTime]::UtcNow.ToString("yyyyMMddTHHmmssZ")
$InventoryPath = Join-Path $OutputRoot "pixel9a-factory-$RunId.json"
$PreflightPath = Join-Path $OutputRoot "pixel9a-gsi-preflight-$RunId.json"
if ((Test-Path -LiteralPath $InventoryPath) -or
    (Test-Path -LiteralPath $PreflightPath)) {
    throw "Refusing to overwrite an existing Pixel 9a preflight run"
}

$inventoryArguments = @{
    Serial = $Serial
    Output = $InventoryPath
}
if ($AdbPath) {
    $inventoryArguments.AdbPath = $AdbPath
}
& (Join-Path $PSScriptRoot "device-inventory.ps1") @inventoryArguments

$ToolPath = Convert-ToWslPath (
    Join-Path $RepositoryRoot "tools\check_gsi_preflight.py"
)
$InventoryWslPath = Convert-ToWslPath $InventoryPath
$BuildEvidenceWslPath = Convert-ToWslPath $BuildEvidencePath
$PreflightWslPath = Convert-ToWslPath $PreflightPath

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $preflightOutput = & wsl.exe -d $WslDistribution -- python3 $ToolPath `
        --inventory $InventoryWslPath `
        --build-evidence $BuildEvidenceWslPath `
        --expected-device tegu `
        --output $PreflightWslPath 2>&1
    $preflightExit = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$preflightOutput | ForEach-Object { Write-Output $_.ToString() }
if ($preflightExit -ne 0) {
    Write-Error -ErrorAction Continue "Pixel 9a GSI preflight rejected this device (exit $preflightExit); review $PreflightPath"
    exit $preflightExit
}

$Preflight = Get-Content -LiteralPath $PreflightPath -Raw | ConvertFrom-Json
if ($Preflight.status -ne "candidate" -or
    $Preflight.observed_device -ne "tegu" -or
    $Preflight.safe_to_flash -ne $false -or
    $Preflight.proves_physical_runtime_gate -ne $false) {
    throw "Preflight output violated the non-authorizing Pixel 9a contract"
}

Write-Output "Read-only Pixel 9a inventory: $InventoryPath"
Write-Output "Exact-image GSI preflight: $PreflightPath"
Write-Output "DSU candidate: $($Preflight.dsu_candidate)"
Write-Output "Fastboot structural candidate: $($Preflight.fastboot_candidate)"
Write-Output "No image was pushed, installed, flashed, or booted."

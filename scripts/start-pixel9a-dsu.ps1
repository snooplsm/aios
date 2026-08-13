param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Inventory,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Preflight,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Payload,

    [Parameter(Mandatory = $false)]
    [string]$BuildEvidence,

    [Parameter(Mandatory = $false)]
    [string]$AdbPath,

    [Parameter(Mandatory = $false)]
    [string]$StagingDirectory,

    [Parameter(Mandatory = $true)]
    [switch]$IUnderstandThisStartsDsu
)

$ErrorActionPreference = "Stop"
if (-not $IUnderstandThisStartsDsu) {
    throw "DSU start requires the explicit -IUnderstandThisStartsDsu switch"
}

$RepositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if (-not $BuildEvidence) {
    $BuildEvidence = Join-Path $RepositoryRoot (
        "evidence\gsi\20260813-gsi-build5-3c0c685-j12\soong-build-evidence.json"
    )
}
$InventoryPath = [IO.Path]::GetFullPath($Inventory)
$PreflightPath = [IO.Path]::GetFullPath($Preflight)
$PayloadPath = [IO.Path]::GetFullPath($Payload)
$BuildEvidencePath = [IO.Path]::GetFullPath($BuildEvidence)
$EvidenceRoot = Split-Path -Parent $BuildEvidencePath
$AvbEvidencePath = Join-Path $EvidenceRoot "avb-verification.json"
$DsuEvidencePath = Join-Path $EvidenceRoot "dsu-payload.json"
foreach ($path in @(
    $InventoryPath,
    $PreflightPath,
    $PayloadPath,
    $BuildEvidencePath,
    $AvbEvidencePath,
    $DsuEvidencePath
)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required DSU input does not exist: $path"
    }
}

function Read-JsonObject {
    param([Parameter(Mandatory = $true)][string]$Path)
    $value = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ($null -eq $value) {
        throw "JSON input is empty: $Path"
    }
    return $value
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-TextSha256 {
    param([Parameter(Mandatory = $true)][string]$Text)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString(
            $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))
        )).Replace("-", "").ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }
}

function Resolve-AdbExecutable {
    if ($AdbPath) {
        $explicit = [IO.Path]::GetFullPath($AdbPath)
        if (-not (Test-Path -LiteralPath $explicit -PathType Leaf)) {
            throw "Explicit adb executable does not exist: $explicit"
        }
        return $explicit
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    $sdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
    if ($env:LOCALAPPDATA) {
        $sdkRoots += (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    }
    foreach ($sdkRoot in $sdkRoots | Where-Object { $_ } | Select-Object -Unique) {
        $candidate = Join-Path $sdkRoot "platform-tools\adb.exe"
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    throw "adb was not found on PATH or in a configured Android SDK; pass -AdbPath"
}

$AdbExecutable = Resolve-AdbExecutable
function Invoke-AdbText {
    param([Parameter(Mandatory = $true)][string[]]$AdbArguments)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $raw = & $AdbExecutable -s $Serial @AdbArguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = (($raw | ForEach-Object { $_.ToString() }) -join "`n").Trim()
    if ($exitCode -ne 0) {
        throw "adb -s $Serial $($AdbArguments -join ' ') failed: $text"
    }
    return $text
}

$InventoryRecord = Read-JsonObject $InventoryPath
$PreflightRecord = Read-JsonObject $PreflightPath
$BuildRecord = Read-JsonObject $BuildEvidencePath
$AvbRecord = Read-JsonObject $AvbEvidencePath
$DsuRecord = Read-JsonObject $DsuEvidencePath

if ($PreflightRecord.schema_version -ne 1 -or
    $PreflightRecord.status -ne "candidate" -or
    $PreflightRecord.expected_device -ne "tegu" -or
    $PreflightRecord.observed_device -ne "tegu" -or
    $PreflightRecord.dsu_candidate -ne $true -or
    $PreflightRecord.safe_to_flash -ne $false -or
    $PreflightRecord.proves_gsi_compatibility -ne $false -or
    $PreflightRecord.proves_physical_runtime_gate -ne $false) {
    throw "Preflight does not authorize this exact Pixel 9a as a DSU candidate"
}
if ($InventoryRecord.schema_version -ne 2 -or
    $InventoryRecord.status -ne "captured" -or
    $InventoryRecord.adb_state -ne "device" -or
    $InventoryRecord.collection.read_only -ne $true -or
    $InventoryRecord.collection.unlock_attempted -ne $false -or
    $InventoryRecord.collection.flash_attempted -ne $false) {
    throw "Inventory is not a read-only factory capture"
}

if ((Get-Sha256 $InventoryPath) -ne $PreflightRecord.inventory_sha256 -or
    (Get-Sha256 $BuildEvidencePath) -ne $PreflightRecord.build_evidence_sha256 -or
    (Get-Sha256 $AvbEvidencePath) -ne $PreflightRecord.avb_evidence_sha256 -or
    (Get-Sha256 $DsuEvidencePath) -ne $PreflightRecord.dsu_payload_evidence_sha256) {
    throw "Inventory, build, AVB, or DSU evidence changed after preflight"
}
if ((Get-TextSha256 $Serial) -ne $InventoryRecord.serial_sha256) {
    throw "Connected serial does not match the preflight inventory"
}

$expectedPayload = $DsuRecord.payload
$sourcePayloadInfo = Get-Item -LiteralPath $PayloadPath
if ($sourcePayloadInfo.Name -ne $expectedPayload.name -or
    $sourcePayloadInfo.Length -ne $expectedPayload.size_bytes -or
    $PreflightRecord.dsu_payload.sha256 -ne $expectedPayload.sha256 -or
    $PreflightRecord.dsu_payload.uncompressed_size_bytes -ne
        $expectedPayload.uncompressed_size_bytes) {
    throw "DSU payload does not match the verified preflight identity"
}
if ($AvbRecord.status -ne "passed" -or
    $DsuRecord.status -ne "passed" -or
    $DsuRecord.safe_to_install -ne $false -or
    $BuildRecord.proves_physical_runtime_gate -ne $false) {
    throw "Build evidence does not preserve the pre-physical-test safety contract"
}

$PayloadForTransfer = $PayloadPath
$StagingRunRoot = $null
if (-not $StagingDirectory) {
    $StagingDirectory = Join-Path ([IO.Path]::GetTempPath()) "aios-dsu-staging"
}
$StagingRoot = [IO.Path]::GetFullPath($StagingDirectory)
$RepositoryPrefix = $RepositoryRoot.TrimEnd([char[]]@("\", "/")) +
    [IO.Path]::DirectorySeparatorChar
if ($StagingRoot.Equals($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $StagingRoot.StartsWith($RepositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "DSU staging must remain outside the source repository"
}

try {
    if ($PayloadPath.StartsWith("\\", [StringComparison]::Ordinal)) {
        $drive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($StagingRoot))
        $minimumFree = [Int64]$expectedPayload.size_bytes + 1073741824
        if ($drive.AvailableFreeSpace -lt $minimumFree) {
            throw "Local DSU staging drive lacks payload size plus 1 GiB headroom"
        }
        if (-not (Test-Path -LiteralPath $StagingRoot)) {
            New-Item -ItemType Directory -Path $StagingRoot -Force | Out-Null
        }
        $StagingRunRoot = Join-Path $StagingRoot (
            "run-" + [Guid]::NewGuid().ToString("N")
        )
        New-Item -ItemType Directory -Path $StagingRunRoot | Out-Null
        $PayloadForTransfer = Join-Path $StagingRunRoot $expectedPayload.name
        Write-Output "Staging one temporary local copy for reliable adb transfer..."
        Copy-Item -LiteralPath $PayloadPath -Destination $PayloadForTransfer
    }

    $payloadInfo = Get-Item -LiteralPath $PayloadForTransfer
    if ($payloadInfo.Name -ne $expectedPayload.name -or
        $payloadInfo.Length -ne $expectedPayload.size_bytes -or
        (Get-Sha256 $PayloadForTransfer) -ne $expectedPayload.sha256) {
        throw "Transfer payload does not match the verified DSU identity"
    }

if ((Invoke-AdbText -AdbArguments @("get-state")) -ne "device") {
    throw "Pixel 9a is not authorized and online through ADB"
}
$currentProperties = @(
    "ro.product.device",
    "ro.build.fingerprint",
    "ro.build.version.security_patch",
    "ro.vendor.api_level",
    "ro.vndk.version",
    "ro.boot.flash.locked"
)
foreach ($name in $currentProperties) {
    $current = Invoke-AdbText -AdbArguments @("shell", "getprop", $name)
    if ($current -ne $InventoryRecord.properties.$name) {
        throw "Connected phone changed since inventory: $name"
    }
}
if ((Invoke-AdbText -AdbArguments @(
    "shell", "getprop", "ro.gsid.image_running"
)) -eq "1") {
    throw "A DSU image is already running; return to the factory system first"
}
if ((Invoke-AdbText -AdbArguments @(
    "shell", "pm", "has-feature", "android.software.dynamic_system"
)) -ne "true") {
    throw "The connected factory build no longer advertises DSU"
}

$dataFilesystem = Invoke-AdbText -AdbArguments @("shell", "df", "-k", "/data")
$dataLines = @($dataFilesystem -split "`n" | Where-Object { $_.Trim() })
$dataFields = @($dataLines[-1] -split "\s+" | Where-Object { $_ })
if ($dataFields.Count -lt 6 -or $dataFields[-3] -notmatch "^[0-9]+$") {
    throw "Could not parse current free space from df -k /data"
}
$availableBytes = [Int64]$dataFields[-3] * 1024
$requiredBytes = [Int64]$PreflightRecord.dsu_storage.required_bytes
if ($availableBytes -lt $requiredBytes) {
    throw "Current free space is below the exact DSU preflight requirement"
}

$remoteName = $expectedPayload.name
if ($remoteName -notmatch "^[A-Za-z0-9._-]+\.raw\.gz$") {
    throw "DSU payload filename is unsafe for device transfer"
}
$remotePath = "/storage/emulated/0/Download/$remoteName"
$remoteUri = "file://$remotePath"
try {
    Invoke-AdbText -AdbArguments @("shell", "test", "!", "-e", $remotePath) |
        Out-Null
}
catch {
    throw "Refusing to overwrite an existing device payload: $remotePath"
}
Write-Output "Pushing the verified DSU payload; this may take several minutes..."
Invoke-AdbText -AdbArguments @(
    "push", $PayloadForTransfer, $remotePath
) | Write-Output
$remoteSize = Invoke-AdbText -AdbArguments @(
    "shell", "stat", "-c", "%s", $remotePath
)
if ($remoteSize -ne $expectedPayload.size_bytes.ToString()) {
    throw "Pushed DSU payload size does not match the verified local file"
}

$systemSize = $expectedPayload.uncompressed_size_bytes.ToString()
$userdataSize = ([Int64]8589934592).ToString()
Invoke-AdbText -AdbArguments @(
    "shell", "am", "start-activity",
    "-n", "com.android.dynsystem/com.android.dynsystem.VerificationActivity",
    "-a", "android.os.image.action.START_INSTALL",
    "-d", $remoteUri,
    "--el", "KEY_SYSTEM_SIZE", $systemSize,
    "--el", "KEY_USERDATA_SIZE", $userdataSize
) | Write-Output

Write-Output "DSU verification/install UI started for the exact AIOS payload."
Write-Output "Watch the DSU notification; restart only from that UI after installation succeeds."
Write-Output "This script did not unlock, fastboot-flash, disable AVB, or reboot the phone."
}
finally {
    if ($StagingRunRoot -and (Test-Path -LiteralPath $StagingRunRoot)) {
        $stagingPrefix = $StagingRoot.TrimEnd([char[]]@("\", "/")) +
            [IO.Path]::DirectorySeparatorChar
        $resolvedRunRoot = [IO.Path]::GetFullPath($StagingRunRoot)
        if (-not $resolvedRunRoot.StartsWith(
            $stagingPrefix, [StringComparison]::OrdinalIgnoreCase
        )) {
            throw "Refusing to clean an unbounded DSU staging path"
        }
        Remove-Item -LiteralPath $resolvedRunRoot -Recurse -Force
        Write-Output "Removed the generated local DSU staging copy."
    }
}

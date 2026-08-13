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
    [string]$Output,

    [Parameter(Mandatory = $false)]
    [string]$BuildEvidence,

    [Parameter(Mandatory = $false)]
    [string]$AdbPath
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
if (-not $BuildEvidence) {
    $BuildEvidence = Join-Path $RepositoryRoot (
        "evidence\gsi\20260813-gsi-build5-3c0c685-j12\soong-build-evidence.json"
    )
}
$InventoryPath = [IO.Path]::GetFullPath($Inventory)
$PreflightPath = [IO.Path]::GetFullPath($Preflight)
$OutputPath = [IO.Path]::GetFullPath($Output)
$RepositoryPrefix = $RepositoryRoot.TrimEnd([char[]]@("\", "/")) + [IO.Path]::DirectorySeparatorChar
if ($OutputPath.Equals($RepositoryRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $OutputPath.StartsWith($RepositoryPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Unreviewed physical first-boot evidence must remain outside the source repository"
}
$BuildEvidencePath = [IO.Path]::GetFullPath($BuildEvidence)
$EvidenceRoot = Split-Path -Parent $BuildEvidencePath
$AvbEvidencePath = Join-Path $EvidenceRoot "avb-verification.json"
$DsuEvidencePath = Join-Path $EvidenceRoot "dsu-payload.json"
foreach ($path in @(
    $InventoryPath,
    $PreflightPath,
    $BuildEvidencePath,
    $AvbEvidencePath,
    $DsuEvidencePath
)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required first-boot input does not exist: $path"
    }
}
if (Test-Path -LiteralPath $OutputPath) {
    throw "Refusing to overwrite Pixel 9a first-boot evidence: $OutputPath"
}
$OutputParent = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $OutputParent)) {
    New-Item -ItemType Directory -Path $OutputParent -Force | Out-Null
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

function Get-DeviceProperty {
    param([Parameter(Mandatory = $true)][string]$Name)
    return Invoke-AdbText -AdbArguments @("shell", "getprop", $Name)
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
    $PreflightRecord.proves_gsi_compatibility -ne $false -or
    $PreflightRecord.proves_physical_runtime_gate -ne $false) {
    throw "Input preflight is not the exact unproven Pixel 9a DSU candidate"
}
if ((Get-Sha256 $InventoryPath) -ne $PreflightRecord.inventory_sha256 -or
    (Get-Sha256 $BuildEvidencePath) -ne $PreflightRecord.build_evidence_sha256 -or
    (Get-Sha256 $AvbEvidencePath) -ne $PreflightRecord.avb_evidence_sha256 -or
    (Get-Sha256 $DsuEvidencePath) -ne $PreflightRecord.dsu_payload_evidence_sha256) {
    throw "First-boot inputs no longer match the preflight evidence chain"
}
if ((Get-TextSha256 $Serial) -ne $InventoryRecord.serial_sha256) {
    throw "Connected serial does not match the factory inventory"
}
if ($BuildRecord.status -ne "passed" -or
    $BuildRecord.lane -ne "android_gsi_arm64" -or
    $BuildRecord.product -ne "aios_gsi_arm64" -or
    $BuildRecord.proves_physical_runtime_gate -ne $false -or
    $AvbRecord.status -ne "passed" -or
    $DsuRecord.status -ne "passed") {
    throw "Build records are not an eligible unproven ARM64 GSI evidence set"
}

if ((Invoke-AdbText -AdbArguments @("get-state")) -ne "device") {
    throw "Pixel 9a is not authorized and online through ADB"
}
$propertyNames = @(
    "sys.boot_completed",
    "ro.gsid.image_running",
    "ro.build.fingerprint",
    "ro.build.type",
    "ro.build.version.release",
    "ro.build.version.security_patch",
    "ro.product.device",
    "ro.product.vendor.device",
    "ro.product.cpu.abilist64",
    "ro.boot.hardware",
    "ro.boot.verifiedbootstate",
    "ro.boot.flash.locked",
    "ro.crypto.state",
    "ro.crypto.type"
)
$properties = [ordered]@{}
foreach ($name in $propertyNames) {
    $properties[$name] = Get-DeviceProperty $name
}
if ($properties["sys.boot_completed"] -ne "1" -or
    $properties["ro.gsid.image_running"] -ne "1" -or
    $properties["ro.build.fingerprint"] -ne $BuildRecord.build_fingerprint -or
    $properties["ro.build.type"] -ne "userdebug" -or
    $properties["ro.build.version.release"] -ne $BuildRecord.android_release -or
    $properties["ro.build.version.security_patch"] -ne $BuildRecord.security_patch -or
    $properties["ro.product.cpu.abilist64"] -notmatch "(^|,)arm64-v8a(,|$)") {
    throw "Connected system is not the completed exact AIOS ARM64 DSU boot"
}

$requiredPackages = @(
    "com.aios.phone",
    "com.aios.messaging",
    "com.aios.callintelligence",
    "com.aios.contextintelligence",
    "com.aios.mediaintelligence",
    "com.aios.modelbroker"
)
$packages = [ordered]@{}
foreach ($packageName in $requiredPackages) {
    $packagePath = Invoke-AdbText -AdbArguments @("shell", "pm", "path", $packageName)
    if ($packagePath -notmatch "^package:/system/product/(app|priv-app)/") {
        throw "Required AIOS package is not installed from system/product: $packageName"
    }
    $packages[$packageName] = $packagePath
}

$dialerRole = Invoke-AdbText -AdbArguments @(
    "shell", "cmd", "role", "get-role-holders", "--user", "0",
    "android.app.role.DIALER"
)
if (@($dialerRole -split "`n" | Where-Object { $_.Trim() }) -notcontains
    "com.aios.phone") {
    throw "AIOS Phone is not the default dialer on the fresh DSU user"
}
$dialerOverlay = Invoke-AdbText -AdbArguments @(
    "shell", "cmd", "overlay", "lookup", "android",
    "android:string/config_defaultDialer"
)
if ($dialerOverlay -notmatch "com\.aios\.phone") {
    throw "Framework default-dialer overlay does not resolve to AIOS Phone"
}

$artifactRows = @()
$systemArtifacts = @($BuildRecord.artifacts | Where-Object {
    $_.path -like "system/*" -and $_.path -ne "system.img"
})
if ($systemArtifacts.Count -lt 30) {
    throw "Build evidence does not enumerate the complete AIOS system payload"
}
foreach ($artifact in $systemArtifacts) {
    $devicePath = "/" + $artifact.path
    $observedSize = Invoke-AdbText -AdbArguments @(
        "shell", "stat", "-c", "%s", $devicePath
    )
    $digestOutput = Invoke-AdbText -AdbArguments @(
        "shell", "sha256sum", $devicePath
    )
    $observedDigest = ($digestOutput -split "\s+")[0].ToLowerInvariant()
    if ($observedSize -ne $artifact.size_bytes.ToString() -or
        $observedDigest -ne $artifact.sha256) {
        throw "Installed artifact differs from build evidence: $devicePath"
    }
    $artifactRows += [ordered]@{
        path = $devicePath
        size_bytes = [Int64]$observedSize
        sha256 = $observedDigest
    }
}

$document = [ordered]@{
    schema_version = 1
    status = "passed"
    kind = "pixel9a_gsi_dsu_first_boot"
    collected_at_utc = [DateTime]::UtcNow.ToString("o")
    serial_sha256 = $InventoryRecord.serial_sha256
    inventory_sha256 = Get-Sha256 $InventoryPath
    preflight_sha256 = Get-Sha256 $PreflightPath
    build_evidence_sha256 = Get-Sha256 $BuildEvidencePath
    avb_evidence_sha256 = Get-Sha256 $AvbEvidencePath
    dsu_payload_evidence_sha256 = Get-Sha256 $DsuEvidencePath
    images = $PreflightRecord.gsi_images
    build_fingerprint = $BuildRecord.build_fingerprint
    properties = $properties
    packages = $packages
    dialer_role_holders = @($dialerRole -split "`n" | Where-Object { $_.Trim() })
    default_dialer_overlay = $dialerOverlay
    installed_artifacts = $artifactRows
    checks = [ordered]@{
        exact_preflight_chain_verified = $true
        dsu_running = $true
        boot_completed = $true
        exact_build_fingerprint = $true
        arm64_userdebug = $true
        required_packages_present = $true
        default_dialer_resolved = $true
        every_evidenced_system_artifact_verified = $true
    }
    proves_gsi_compatibility = $true
    proves_boot_first_boot = $true
    proves_physical_runtime_gate = $false
    proves_telephony_gate = $false
    proves_model_latency_gate = $false
    proves_media_gate = $false
    proves_factory_restore = $false
}

$temporary = Join-Path $OutputParent (
    ".{0}.{1}.tmp" -f ([IO.Path]::GetFileName($OutputPath)), [Guid]::NewGuid()
)
try {
    $json = $document | ConvertTo-Json -Depth 12
    [IO.File]::WriteAllText(
        $temporary,
        $json + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $temporary -Destination $OutputPath
}
finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Force
    }
}

Write-Output "Exact Pixel 9a DSU first-boot evidence captured: $OutputPath"
Write-Output "This proves first boot and packaged artifact identity, not telephony, AI latency, media, or factory restoration."

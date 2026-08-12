param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Output
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb is not available on PATH"
}

function Invoke-AdbText {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$AdbArguments,
        [switch]$AllowFailure
    )

    $raw = & adb -s $Serial @AdbArguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = (($raw | ForEach-Object { $_.ToString() }) -join "`n").Trim()
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb -s $Serial $($AdbArguments -join ' ') failed: $text"
    }
    if ($exitCode -ne 0) {
        return ""
    }
    return $text
}

function Get-Property {
    param([Parameter(Mandatory = $true)][string]$Name)
    return Invoke-AdbText -AdbArguments @("shell", "getprop", $Name)
}

$state = Invoke-AdbText -AdbArguments @("get-state")
if ($state -ne "device") {
    throw "ADB serial $Serial is not an authorized online device: $state"
}

$propertyNames = @(
    "ro.product.manufacturer",
    "ro.product.model",
    "ro.product.device",
    "ro.product.cpu.abilist64",
    "ro.build.fingerprint",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.build.version.security_patch",
    "ro.system.build.version.security_patch",
    "ro.vendor.build.security_patch",
    "ro.vendor.api_level",
    "ro.vndk.version",
    "ro.treble.enabled",
    "ro.boot.dynamic_partitions",
    "ro.boot.slot_suffix",
    "ro.boot.bootloader",
    "gsm.version.baseband",
    "ro.boot.avb_version",
    "ro.boot.verifiedbootstate",
    "ro.boot.flash.locked",
    "ro.gsid.image_running"
)

$properties = [ordered]@{}
foreach ($name in $propertyNames) {
    $properties[$name] = Get-Property -Name $name
}

$memoryLine = Invoke-AdbText -AdbArguments @("shell", "sh", "-c", "grep '^MemTotal:' /proc/meminfo")
$dataFilesystem = Invoke-AdbText -AdbArguments @("shell", "df", "-k", "/data") -AllowFailure
$dynamicSystemFeature = Invoke-AdbText -AdbArguments @(
    "shell", "pm", "has-feature", "android.software.dynamic_system"
) -AllowFailure
$currentSlot = Invoke-AdbText -AdbArguments @(
    "shell", "bootctl", "get-current-slot"
) -AllowFailure

$slotState = [ordered]@{
    current = $currentSlot
    slot_0_bootable = Invoke-AdbText -AdbArguments @(
        "shell", "bootctl", "is-slot-bootable", "0"
    ) -AllowFailure
    slot_1_bootable = Invoke-AdbText -AdbArguments @(
        "shell", "bootctl", "is-slot-bootable", "1"
    ) -AllowFailure
    slot_0_successful = Invoke-AdbText -AdbArguments @(
        "shell", "bootctl", "is-slot-marked-successful", "0"
    ) -AllowFailure
    slot_1_successful = Invoke-AdbText -AdbArguments @(
        "shell", "bootctl", "is-slot-marked-successful", "1"
    ) -AllowFailure
}

$sha256 = [Security.Cryptography.SHA256]::Create()
try {
    $serialDigest = ([BitConverter]::ToString(
        $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($Serial))
    )).Replace("-", "").ToLowerInvariant()
}
finally {
    $sha256.Dispose()
}

$document = [ordered]@{
    schema_version = 2
    status = "captured"
    serial_sha256 = $serialDigest
    collected_at_utc = [DateTime]::UtcNow.ToString("o")
    adb_state = $state
    properties = $properties
    capabilities = [ordered]@{
        dynamic_system_feature = $dynamicSystemFeature
        memory = $memoryLine
        data_filesystem = $dataFilesystem
    }
    slots = $slotState
    collection = [ordered]@{
        read_only = $true
        unlock_attempted = $false
        flash_attempted = $false
    }
    proves_gsi_compatibility = $false
    proves_physical_runtime_gate = $false
}

$outputPath = [IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $outputPath) {
    throw "Refusing to overwrite existing device inventory: $outputPath"
}
$parent = Split-Path -Parent $outputPath
if (-not (Test-Path -LiteralPath $parent)) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}
$temporary = Join-Path $parent (".{0}.{1}.tmp" -f ([IO.Path]::GetFileName($outputPath)), [Guid]::NewGuid())
try {
    $json = $document | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText($temporary, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $outputPath
}
finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Force
    }
}

Write-Output "Read-only device inventory captured: $outputPath"

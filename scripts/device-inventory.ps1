param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Output,

    [Parameter(Mandatory = $false)]
    [ValidateNotNullOrEmpty()]
    [string]$AdbPath
)

$ErrorActionPreference = "Stop"

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
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$AdbArguments,
        [switch]$AllowFailure,
        [switch]$PreserveFailureOutput
    )

    # Windows PowerShell promotes redirected native stderr to ErrorRecord
    # objects. Keep those records as data so AllowFailure probes can be handled
    # by the explicit exit-code policy below instead of ErrorActionPreference.
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
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb -s $Serial $($AdbArguments -join ' ') failed: $text"
    }
    if ($exitCode -ne 0 -and -not $PreserveFailureOutput) {
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
    "ro.product.first_api_level",
    "ro.board.api_level",
    "ro.vendor.build.version.sdk",
    "ro.llndk.api_level",
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

$memoryDocument = Invoke-AdbText -AdbArguments @("shell", "cat", "/proc/meminfo")
$memoryLine = @(
    $memoryDocument -split "`n" |
        Where-Object { $_ -match "^MemTotal:\s+[0-9]+\s+kB\s*$" }
)
if ($memoryLine.Count -ne 1) {
    throw "Could not identify exactly one MemTotal row in /proc/meminfo"
}
$memoryLine = $memoryLine[0].Trim()
$dataFilesystem = Invoke-AdbText -AdbArguments @("shell", "df", "-k", "/data") -AllowFailure
$dynamicSystemFeature = Invoke-AdbText -AdbArguments @(
    "shell", "pm", "has-feature", "android.software.dynamic_system"
) -AllowFailure -PreserveFailureOutput
$virtualizationFrameworkFeature = Invoke-AdbText -AdbArguments @(
    "shell", "pm", "has-feature", "android.software.virtualization_framework"
) -AllowFailure -PreserveFailureOutput
$dynamicPartitionMetadata = Invoke-AdbText -AdbArguments @(
    "shell", "lpdump"
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
        virtualization_framework_feature = $virtualizationFrameworkFeature
        memory = $memoryLine
        data_filesystem = $dataFilesystem
        dynamic_partition_metadata = $dynamicPartitionMetadata
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

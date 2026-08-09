$ErrorActionPreference = "Stop"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb is not available on PATH"
}

adb get-state | Out-Null

$propertyNames = @(
    "ro.product.manufacturer",
    "ro.product.model",
    "ro.product.device",
    "ro.build.fingerprint",
    "ro.build.version.release",
    "ro.build.version.security_patch",
    "ro.boot.slot_suffix",
    "ro.boot.bootloader",
    "gsm.version.baseband",
    "ro.boot.verifiedbootstate",
    "ro.boot.flash.locked"
)

$properties = [ordered]@{}
foreach ($name in $propertyNames) {
    $properties[$name] = (adb shell getprop $name).Trim()
}

$memoryLine = (adb shell cat /proc/meminfo | Select-String "^MemTotal:").Line
$properties["mem_total"] = $memoryLine.Trim()
$properties["collected_at_utc"] = [DateTime]::UtcNow.ToString("o")

$properties | ConvertTo-Json

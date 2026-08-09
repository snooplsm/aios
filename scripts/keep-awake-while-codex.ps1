param(
    [int]$PollSeconds = 30
)

$ErrorActionPreference = 'Stop'

Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class AiosExecutionState {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint SetThreadExecutionState(uint flags);
}
'@

$continuous = [Convert]::ToUInt32('80000000', 16)
$systemRequired = [uint32]0x00000001
$displayRequired = [uint32]0x00000002
$mutex = [Threading.Mutex]::new($false, 'Local\AIOSCodexKeepAwake')
$ownsMutex = $false

try {
    $ownsMutex = $mutex.WaitOne(0)
    if (-not $ownsMutex) {
        exit 0
    }

    while (Get-Process -Name 'codex' -ErrorAction SilentlyContinue) {
        $result = [AiosExecutionState]::SetThreadExecutionState(
            $continuous -bor $systemRequired -bor $displayRequired
        )
        if ($result -eq 0) {
            throw 'Windows rejected the keep-awake request.'
        }
        Start-Sleep -Seconds ([Math]::Max(5, $PollSeconds))
    }
}
finally {
    [void][AiosExecutionState]::SetThreadExecutionState($continuous)
    if ($ownsMutex) {
        $mutex.ReleaseMutex()
    }
    $mutex.Dispose()
}

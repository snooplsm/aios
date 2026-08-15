Set-StrictMode -Version Latest

function New-AiosRuntimeSession {
    param(
        [Parameter(Mandatory = $true)]
        [long]$SessionId,

        [Parameter(Mandatory = $true)]
        [string]$Runtime
    )

    return [ordered]@{
        runtime = $Runtime
        session_id = $SessionId
        terminal_status = "incomplete"
    }
}

function Get-AiosRuntimeSession {
    param(
        [Parameter(Mandatory = $true)]
        [Collections.IDictionary]$Sessions,

        [Parameter(Mandatory = $true)]
        [long]$SessionId,

        [Parameter(Mandatory = $true)]
        [string]$Runtime
    )

    if (-not $Sessions.Contains($SessionId)) {
        $Sessions[$SessionId] = New-AiosRuntimeSession `
            -SessionId $SessionId -Runtime $Runtime
    }
    return $Sessions[$SessionId]
}

function ConvertTo-AiosRuntimeSessionArray {
    param(
        [Parameter(Mandatory = $true)]
        [Collections.IDictionary]$Sessions
    )

    return @($Sessions.Keys | Sort-Object | ForEach-Object {
        [pscustomobject]$Sessions[$_]
    })
}

function ConvertFrom-AiosRuntimeDiagnosticLog {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$Lines
    )

    $tts = [ordered]@{}
    $liteRt = [ordered]@{}
    $whisper = [ordered]@{}
    $ttsEngineEvents = [Collections.Generic.List[object]]::new()
    $liteRtEngineEvents = [Collections.Generic.List[object]]::new()
    $whisperModelEvents = [Collections.Generic.List[object]]::new()

    foreach ($line in $Lines) {
        if ($line -match "AiosTtsRuntime.*SESSION_CREATED id=(\d+) model=(\S+) language=(\S+) backend=(\S+)") {
            $session = Get-AiosRuntimeSession -Sessions $tts `
                -SessionId ([long]$Matches[1]) -Runtime "sherpa_onnx_tts"
            $session.model_id = $Matches[2]
            $session.language = $Matches[3]
            $session.backend = $Matches[4]
            continue
        }
        if ($line -match "AiosTtsRuntime.*ENGINE_READY id=(\d+) elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $tts `
                -SessionId ([long]$Matches[1]) -Runtime "sherpa_onnx_tts"
            $session.engine_ready_ms = [long]$Matches[2]
            continue
        }
        if ($line -match "AiosTtsRuntime.*AUDIO_CHUNK id=(\d+) chunk=(\d+) elapsed_ms=(\d+)(?: after_text_ms=(\d+))? samples=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $tts `
                -SessionId ([long]$Matches[1]) -Runtime "sherpa_onnx_tts"
            $session.chunk_count = [int]$Matches[2]
            if (-not $session.Contains("first_chunk_ms")) {
                $session.first_chunk_ms = [long]$Matches[3]
                if ($Matches[4]) {
                    $session.first_chunk_after_text_ms = [long]$Matches[4]
                }
                $session.first_chunk_samples = [long]$Matches[5]
            }
            continue
        }
        if ($line -match "AiosTtsRuntime.*FIRST_AUDIO id=(\d+) elapsed_ms=(\d+)(?: after_text_ms=(\d+))? samples=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $tts `
                -SessionId ([long]$Matches[1]) -Runtime "sherpa_onnx_tts"
            $session.first_audio_ms = [long]$Matches[2]
            if ($Matches[3]) {
                $session.first_audio_after_text_ms = [long]$Matches[3]
            }
            $session.first_audio_samples = [long]$Matches[4]
            continue
        }
        if ($line -match "AiosTtsRuntime.*SYNTHESIS_DONE id=(\d+) samples=(\d+) elapsed_ms=(\d+)(?: after_text_ms=(\d+))?") {
            $session = Get-AiosRuntimeSession -Sessions $tts `
                -SessionId ([long]$Matches[1]) -Runtime "sherpa_onnx_tts"
            $session.sample_count = [long]$Matches[2]
            $session.elapsed_ms = [long]$Matches[3]
            if ($Matches[4]) {
                $session.generation_elapsed_ms = [long]$Matches[4]
            }
            $session.terminal_status = "completed"
            continue
        }
        if ($line -match "AiosTtsRuntime.*SESSION_FAILED id=(\d+) code=(\d+) elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $tts `
                -SessionId ([long]$Matches[1]) -Runtime "sherpa_onnx_tts"
            $session.error_code = [int]$Matches[2]
            $session.elapsed_ms = [long]$Matches[3]
            $session.terminal_status = "failed"
            continue
        }
        if ($line -match "AiosTtsRuntime.*ENGINE_INITIALIZE_DONE model=(\S+) elapsed_ms=(\d+)") {
            $ttsEngineEvents.Add([pscustomobject][ordered]@{
                model_id = $Matches[1]
                prepare_elapsed_ms = [long]$Matches[2]
            })
            continue
        }

        if ($line -match "AiosLiteRtLmRuntime.*SESSION_CREATED id=(\d+) capability=(\S+) model=(\S+) backend=(\S+)") {
            $session = Get-AiosRuntimeSession -Sessions $liteRt `
                -SessionId ([long]$Matches[1]) -Runtime "litert_lm"
            $session.capability = $Matches[2]
            $session.model_id = $Matches[3]
            $session.backend = $Matches[4]
            continue
        }
        if ($line -match "AiosLiteRtLmRuntime.*MODEL_VERIFIED id=(\d+) bytes=(\d+) elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $liteRt `
                -SessionId ([long]$Matches[1]) -Runtime "litert_lm"
            $session.model_bytes = [long]$Matches[2]
            $session.model_verified_ms = [long]$Matches[3]
            continue
        }
        if ($line -match "AiosLiteRtLmRuntime.*ENGINE_READY id=(\d+) elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $liteRt `
                -SessionId ([long]$Matches[1]) -Runtime "litert_lm"
            $session.engine_ready_ms = [long]$Matches[2]
            if ($session.Contains("model_verified_ms")) {
                $session.engine_after_verification_ms = [long]$Matches[2] -
                    [long]$session.model_verified_ms
            }
            continue
        }
        if ($line -match "AiosLiteRtLmRuntime.*CONVERSATION_READY id=(\d+) elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $liteRt `
                -SessionId ([long]$Matches[1]) -Runtime "litert_lm"
            $session.conversation_ready_ms = [long]$Matches[2]
            continue
        }
        if ($line -match "AiosLiteRtLmRuntime.*FIRST_TOKEN id=(\d+) elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $liteRt `
                -SessionId ([long]$Matches[1]) -Runtime "litert_lm"
            $session.first_token_ms = [long]$Matches[2]
            if ($session.Contains("conversation_ready_ms")) {
                $session.first_token_after_ready_ms = [long]$Matches[2] -
                    [long]$session.conversation_ready_ms
            }
            continue
        }
        if ($line -match "AiosLiteRtLmRuntime.*INFERENCE_DONE id=(\d+) chars=(\d+) elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $liteRt `
                -SessionId ([long]$Matches[1]) -Runtime "litert_lm"
            $session.output_chars = [long]$Matches[2]
            $session.elapsed_ms = [long]$Matches[3]
            $session.terminal_status = "completed"
            continue
        }
        if ($line -match "AiosLiteRtLmRuntime.*SESSION_FAILED id=(\d+) code=(\d+) elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $liteRt `
                -SessionId ([long]$Matches[1]) -Runtime "litert_lm"
            $session.error_code = [int]$Matches[2]
            $session.elapsed_ms = [long]$Matches[3]
            $session.terminal_status = "failed"
            continue
        }
        if ($line -match "AiosLiteRtLmRuntime.*ENGINE_INITIALIZE_DONE backend=(\S+) elapsed_ms=(\d+)") {
            $liteRtEngineEvents.Add([pscustomobject][ordered]@{
                backend = $Matches[1]
                initialize_elapsed_ms = [long]$Matches[2]
            })
            continue
        }

        if ($line -match "AiosWhisperRuntime.*SESSION_CREATED id=(\d+) model=(\S+) workload=(\S+) backend=(\S+)") {
            $session = Get-AiosRuntimeSession -Sessions $whisper `
                -SessionId ([long]$Matches[1]) -Runtime "whisper_cpp"
            $session.model_id = $Matches[2]
            $session.workload = $Matches[3]
            $session.backend = $Matches[4]
            continue
        }
        if ($line -match "AiosWhisperRuntime.*DECODE_DONE id=(\d+).*elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $whisper `
                -SessionId ([long]$Matches[1]) -Runtime "whisper_cpp"
            $elapsed = [long]$Matches[2]
            if (-not $session.Contains("decode_window_count")) {
                $session.decode_window_count = 0
                $session.decode_elapsed_total_ms = 0L
                $session.decode_elapsed_max_ms = 0L
            }
            $session.decode_window_count = [int]$session.decode_window_count + 1
            $session.decode_elapsed_total_ms =
                [long]$session.decode_elapsed_total_ms + $elapsed
            $session.decode_elapsed_max_ms = [math]::Max(
                [long]$session.decode_elapsed_max_ms, $elapsed)
            continue
        }
        if ($line -match "AiosWhisperRuntime.*SESSION_DONE id=(\d+) windows=(\d+) elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $whisper `
                -SessionId ([long]$Matches[1]) -Runtime "whisper_cpp"
            $session.decoded_windows = [int]$Matches[2]
            $session.elapsed_ms = [long]$Matches[3]
            $session.terminal_status = "completed"
            continue
        }
        if ($line -match "AiosWhisperRuntime.*SESSION_FAILED id=(\d+) code=(\d+) elapsed_ms=(\d+)") {
            $session = Get-AiosRuntimeSession -Sessions $whisper `
                -SessionId ([long]$Matches[1]) -Runtime "whisper_cpp"
            $session.error_code = [int]$Matches[2]
            $session.elapsed_ms = [long]$Matches[3]
            $session.terminal_status = "failed"
            continue
        }
        if ($line -match "AiosWhisperRuntime.*MODEL_INITIALIZE_DONE model=(\S+) elapsed_ms=(\d+)") {
            $whisperModelEvents.Add([pscustomobject][ordered]@{
                model_id = $Matches[1]
                initialize_elapsed_ms = [long]$Matches[2]
            })
        }
    }

    foreach ($session in $tts.Values) {
        if ($session.Contains("first_audio_ms") -and $session.Contains("engine_ready_ms")) {
            $session.first_audio_after_engine_ready_ms =
                [long]$session.first_audio_ms - [long]$session.engine_ready_ms
        }
    }

    return [pscustomobject][ordered]@{
        schema_version = 1
        tts_sessions = ConvertTo-AiosRuntimeSessionArray -Sessions $tts
        tts_engine_events = @($ttsEngineEvents)
        litert_lm_sessions = ConvertTo-AiosRuntimeSessionArray -Sessions $liteRt
        litert_lm_engine_events = @($liteRtEngineEvents)
        whisper_sessions = ConvertTo-AiosRuntimeSessionArray -Sessions $whisper
        whisper_model_events = @($whisperModelEvents)
    }
}

Export-ModuleMember -Function ConvertFrom-AiosRuntimeDiagnosticLog

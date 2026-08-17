$ErrorActionPreference = "Stop"
$module = Join-Path $PSScriptRoot "AiosRuntimeDiagnostics.psm1"
Import-Module -Name $module -Force

$lines = @(
    "08-15 I AiosReceptionist: PREWARM_START serial=9 language=en elapsed_ms=12",
    "08-15 I AiosReceptionist: PREWARM_HANDOFF serial=9 elapsed_ms=4300",
    "08-15 I AiosReceptionist: PREWARM_START serial=10 language=es elapsed_ms=4",
    "08-15 I AiosReceptionist: PREWARM_END serial=10 detail=receptionist_prewarm_error_4 elapsed_ms=900",
    "08-15 I AiosTtsRuntime: SESSION_CREATED id=7 model=supertonic3-en-es-int8 language=en backend=cpu",
    "08-15 I AiosTtsRuntime: ENGINE_INITIALIZE_DONE model=supertonic3-en-es-int8 elapsed_ms=1200",
    "08-15 I AiosTtsRuntime: ENGINE_CACHE_HIT model=supertonic3-en-es-int8",
    "08-15 I AiosTtsRuntime: ENGINE_READY id=7 elapsed_ms=1300",
    "08-15 I AiosTtsRuntime: AUDIO_CHUNK id=7 chunk=1 elapsed_ms=2100 after_text_ms=800 samples=4096",
    "08-15 I AiosTtsRuntime: FIRST_AUDIO id=7 elapsed_ms=2100 after_text_ms=800 samples=4096",
    "08-15 I AiosTtsRuntime: AUDIO_CHUNK id=7 chunk=2 elapsed_ms=2400 after_text_ms=1100 samples=2048",
    "08-15 I AiosTtsRuntime: SYNTHESIS_DONE id=7 samples=6144 elapsed_ms=2450 after_text_ms=1150",
    "08-15 I AiosLiteRtLmRuntime: SESSION_CREATED id=2 capability=text_generation model=gemma4-e2b-mobile-multimodal backend=gpu",
    "08-15 I AiosLiteRtLmRuntime: MODEL_VERIFIED id=2 bytes=2588147712 elapsed_ms=3000",
    "08-15 I AiosLiteRtLmRuntime: ENGINE_INITIALIZE_DONE backend=gpu elapsed_ms=5000",
    "08-15 I AiosLiteRtLmRuntime: MODEL_DIGEST_CACHE_HIT model=gemma4-e2b-mobile-multimodal bytes=2588147712",
    "08-15 I AiosLiteRtLmRuntime: ENGINE_CACHE_HIT backend=gpu vision=true audio=false",
    "08-15 I AiosLiteRtLmRuntime: ENGINE_READY id=2 elapsed_ms=8100",
    "08-15 I AiosLiteRtLmRuntime: CONVERSATION_READY id=2 elapsed_ms=8200",
    "08-15 I AiosLiteRtLmRuntime: FIRST_TOKEN id=2 elapsed_ms=9900",
    "08-15 I AiosLiteRtLmRuntime: INFERENCE_DONE id=2 chars=42 elapsed_ms=11000",
    "08-15 I AiosWhisperRuntime: SESSION_CREATED id=4 model=whisper-base-multilingual-quantized workload=call_downlink backend=cpu",
    "08-15 I AiosWhisperRuntime: MODEL_INITIALIZE_DONE model=whisper-base-multilingual-quantized elapsed_ms=700",
    "08-15 I AiosWhisperRuntime: MODEL_DIGEST_VERIFIED model=whisper-base-multilingual-quantized bytes=150000000",
    "08-15 I AiosWhisperRuntime: MODEL_CACHE_HIT model=whisper-base-multilingual-quantized",
    "08-15 I AiosWhisperRuntime: DECODE_DONE id=4 language=en reported_language=en chars=5 elapsed_ms=450",
    "08-15 I AiosWhisperRuntime: DECODE_DONE id=4 language=en reported_language=en chars=9 elapsed_ms=500",
    "08-15 I AiosWhisperRuntime: SESSION_DONE id=4 windows=2 elapsed_ms=2600",
    "08-15 I AiosTtsRuntime: ENGINE_RELEASE_REQUEST reason=thermal_status_3",
    "08-15 I AiosTtsRuntime: ENGINE_RELEASE reason=thermal_status_3 count=1",
    "08-15 I lmkd: Kill 'com.example.cached' (123), uid 10001, oom_score_adj 900",
    "08-15 E AndroidRuntime: FATAL EXCEPTION: background-worker"
)

$parsed = ConvertFrom-AiosRuntimeDiagnosticLog -Lines $lines
if ($parsed.schema_version -ne 2) { throw "schema version changed" }
if (@($parsed.receptionist_prewarm_events).Count -ne 2) {
    throw "receptionist prewarm event count changed"
}
$prewarm = $parsed.receptionist_prewarm_events[0]
if ($prewarm.language -ne "en" -or $prewarm.start_elapsed_ms -ne 12 -or
    $prewarm.handoff_elapsed_ms -ne 4300 -or
    $prewarm.terminal_status -ne "handed_off") {
    throw "receptionist prewarm parsing failed"
}
$failedPrewarm = $parsed.receptionist_prewarm_events[1]
if ($failedPrewarm.language -ne "es" -or $failedPrewarm.elapsed_ms -ne 900 -or
    $failedPrewarm.detail -ne "receptionist_prewarm_error_4" -or
    $failedPrewarm.terminal_status -ne "ended") {
    throw "receptionist prewarm terminal parsing failed"
}
if (@($parsed.tts_sessions).Count -ne 1) { throw "TTS session count changed" }
$tts = $parsed.tts_sessions[0]
if ($tts.first_audio_after_engine_ready_ms -ne 800 -or
    $tts.first_audio_after_text_ms -ne 800 -or
    $tts.generation_elapsed_ms -ne 1150 -or
    $tts.chunk_count -ne 2 -or $tts.sample_count -ne 6144 -or
    $tts.terminal_status -ne "completed") {
    throw "TTS phase parsing failed"
}
if (@($parsed.tts_engine_events).Count -ne 1 -or
    $parsed.tts_engine_events[0].prepare_elapsed_ms -ne 1200) {
    throw "TTS engine event parsing failed"
}

if (@($parsed.litert_lm_sessions).Count -ne 1) { throw "LiteRT session count changed" }
$liteRt = $parsed.litert_lm_sessions[0]
if ($liteRt.engine_after_verification_ms -ne 5100 -or
    $liteRt.first_token_after_ready_ms -ne 1700 -or
    $liteRt.terminal_status -ne "completed") {
    throw "LiteRT phase parsing failed"
}
if (@($parsed.litert_lm_engine_events).Count -ne 1 -or
    $parsed.litert_lm_engine_events[0].initialize_elapsed_ms -ne 5000) {
    throw "LiteRT engine event parsing failed"
}

if (@($parsed.whisper_sessions).Count -ne 1) { throw "Whisper session count changed" }
$whisper = $parsed.whisper_sessions[0]
if ($whisper.decode_window_count -ne 2 -or
    $whisper.decode_elapsed_total_ms -ne 950 -or
    $whisper.decode_elapsed_max_ms -ne 500 -or
    $whisper.terminal_status -ne "completed") {
    throw "Whisper phase parsing failed"
}
if (@($parsed.whisper_model_events).Count -ne 1 -or
    $parsed.whisper_model_events[0].initialize_elapsed_ms -ne 700) {
    throw "Whisper model event parsing failed"
}
if (@($parsed.residency_events | Where-Object { $_.action -eq "cache_hit" }).Count -ne 3 -or
    @($parsed.residency_events | Where-Object { $_.action -eq "release_requested" }).Count -ne 1 -or
    @($parsed.residency_events | Where-Object { $_.action -eq "released" }).Count -ne 1) {
    throw "runtime residency event parsing failed"
}
if (@($parsed.artifact_verification_events).Count -ne 2 -or
    @($parsed.artifact_verification_events | Where-Object {
        $_.action -eq "digest_cache_hit" -and $_.runtime -eq "litert_lm"
    }).Count -ne 1 -or
    @($parsed.artifact_verification_events | Where-Object {
        $_.action -eq "digest_verified" -and $_.runtime -eq "whisper_cpp"
    }).Count -ne 1) {
    throw "artifact verification event parsing failed"
}
if ($parsed.system_health.low_memory_event_count -ne 1 -or
    $parsed.system_health.low_memory_kill_count -ne 1 -or
    $parsed.system_health.aios_low_memory_kill_count -ne 0 -or
    $parsed.system_health.background_low_memory_kill_count -ne 1 -or
    $parsed.system_health.oom_event_count -ne 0 -or
    $parsed.system_health.fatal_event_count -ne 1 -or
    $parsed.system_health.max_runtime_thermal_status -ne 3) {
    throw "system health parsing failed"
}

Write-Output "Runtime diagnostic parser test passed"

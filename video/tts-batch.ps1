# Batch TTS: one wav per narration line, then write durations.json
$ErrorActionPreference = 'Stop'
$root = 'E:\AIWEB\video'
$ffprobe = 'E:\AIWEB\tools\ffmpeg\bin\ffprobe.exe'
$manifest = Get-Content (Join-Path $root 'narration.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$outDir = Join-Path $root 'audio'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$result = @()
foreach ($seg in $manifest.segments) {
  $entries = @()
  for ($i = 0; $i -lt $seg.lines.Count; $i++) {
    $text = $seg.lines[$i]
    $name = "{0}-{1}.wav" -f $seg.id, ($i + 1)
    $path = Join-Path $outDir $name
    $json = @{ model = 'breeze-tts-2'; input = $text; voice = $manifest.voice; response_format = 'wav' } | ConvertTo-Json -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $ok = $false
    for ($try = 1; $try -le 3 -and -not $ok; $try++) {
      try {
        Invoke-WebRequest -Uri 'http://127.0.0.1:8080/v1/audio/speech' -Method Post `
          -ContentType 'application/json; charset=utf-8' -Body $bytes -OutFile $path -TimeoutSec 300
        $ok = $true
      } catch {
        Start-Sleep -Seconds 2
      }
    }
    if (-not $ok) { throw "TTS failed for $name" }
    $dur = [double](& $ffprobe -v error -show_entries format=duration -of csv=p=0 $path)
    $entries += [pscustomobject]@{ file = $name; dur = [math]::Round($dur, 3); text = $text }
    Write-Output ("{0}  {1:N2}s" -f $name, $dur)
  }
  $result += [pscustomobject]@{ id = $seg.id; kind = $seg.kind; lines = $entries }
}
$json = $result | ConvertTo-Json -Depth 6
Set-Content -LiteralPath (Join-Path $root 'durations.json') -Value $json -Encoding UTF8
$total = ($result | ForEach-Object { $_.lines.dur } | Measure-Object -Sum).Sum
Write-Output ("TOTAL AUDIO {0:N1}s" -f $total)

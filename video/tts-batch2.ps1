# 可续跑的批量 TTS：已有且有效的 wav 直接跳过，失败重试，最后重算 durations.json
$ErrorActionPreference = 'Stop'
$root = 'E:\AIWEB\video'
$ffprobe = 'E:\AIWEB\tools\ffmpeg\bin\ffprobe.exe'
$manifest = Get-Content (Join-Path $root 'narration.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$outDir = Join-Path $root 'audio'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$result = @()
$index = 0
$total = ($manifest.segments | ForEach-Object { $_.lines.Count } | Measure-Object -Sum).Sum
foreach ($seg in $manifest.segments) {
  $entries = @()
  for ($i = 0; $i -lt $seg.lines.Count; $i++) {
    $index++
    $text = $seg.lines[$i]
    $name = "{0}-{1}.wav" -f $seg.id, ($i + 1)
    $path = Join-Path $outDir $name

    $need = $true
    if (Test-Path $path) {
      $len = (Get-Item $path).Length
      if ($len -gt 20000) {
        try {
          $d = [double](& $ffprobe -v error -show_entries format=duration -of csv=p=0 $path)
          if ($d -gt 0.5) { $need = $false }
        } catch { $need = $true }
      }
    }

    if ($need) {
      $json = @{ model = 'breeze-tts-2'; input = $text; voice = $manifest.voice; response_format = 'wav' } | ConvertTo-Json -Compress
      $bytes = [Text.Encoding]::UTF8.GetBytes($json)
      $ok = $false
      for ($try = 1; $try -le 3 -and -not $ok; $try++) {
        try {
          if (Test-Path $path) { Remove-Item $path -Force }
          Invoke-WebRequest -Uri 'http://127.0.0.1:8080/v1/audio/speech' -Method Post `
            -ContentType 'application/json; charset=utf-8' -Body $bytes -OutFile $path -TimeoutSec 180
          if ((Test-Path $path) -and (Get-Item $path).Length -gt 20000) {
            $ok = $true
          } else {
            Start-Sleep -Seconds 3
          }
        } catch {
          Write-Output ("  retry {0} {1}: {2}" -f $try, $name, $_.Exception.Message)
          Start-Sleep -Seconds 3
        }
      }
      if (-not $ok) { throw "TTS failed for $name" }
    } else {
      Write-Output ("  keep {0}" -f $name)
    }

    $dur = [double](& $ffprobe -v error -show_entries format=duration -of csv=p=0 $path)
    $entries += [pscustomobject]@{ file = $name; dur = [math]::Round($dur, 3); text = $text }
    Write-Output ("[{0}/{1}] {2}  {3:N2}s" -f $index, $total, $name, $dur)
  }
  $result += [pscustomobject]@{ id = $seg.id; kind = $seg.kind; lines = $entries }
}

$json = $result | ConvertTo-Json -Depth 6
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $root 'durations.json'), $json, $utf8)
$sum = ($result | ForEach-Object { $_.lines.dur } | Measure-Object -Sum).Sum
Write-Output ("TOTAL AUDIO {0:N1}s" -f $sum)

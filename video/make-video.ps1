# 一键：生成分镜/时间轴 → 录制 → 渲染 → 配音对齐 → 烧字幕 → 出片
$ErrorActionPreference = 'Continue'
$root = 'E:\AIWEB\video'
$ff = 'E:\AIWEB\tools\ffmpeg\bin\ffmpeg.exe'
$ffprobe = 'E:\AIWEB\tools\ffmpeg\bin\ffprobe.exe'
$cd = 'E:\AIWEB\tools\capture-director\bin\capture-director.js'

$env:FFMPEG = $ff
$env:FFPROBE = $ffprobe
$env:CD_CHROME_CHANNEL = 'msedge'
$env:CD_NO_SANDBOX = '1'

Set-Location $root

# 管理令牌（录后台镜头用）
$pw = (Get-Content 'E:\AIWEB\server\data\admin-password.txt' -Raw).Trim()
$login = Invoke-RestMethod -Uri 'http://127.0.0.1:8082/api/admin/login' -Method Post `
  -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes((@{ password = $pw } | ConvertTo-Json -Compress))) -TimeoutSec 15
$env:ADMIN_TOKEN = $login.token
Write-Output "admin token ok ($($login.token.Length) chars)"

# 1) 生成 YAML / 字幕 / 音频计划
node build.mjs

# 2) 录制
Write-Output '--- recording ---'
node $cd record shots/full.yaml 2> 'record.log'
if ($LASTEXITCODE -ne 0) { Get-Content 'record.log' -Tail 25; throw 'record failed' }

# 3) 渲染（硬切）
Write-Output '--- render ---'
node $cd render shots/full-timeline.yaml 2> 'render.log'
if ($LASTEXITCODE -ne 0) { Get-Content 'render.log' -Tail 25; throw 'render failed' }

# 4) 配音轨：按 plan.json 拼「句子 + 句间静音 + 段尾静音」
Write-Output '--- audio ---'
$planData = Get-Content 'audio\plan.json' -Raw -Encoding UTF8 | ConvertFrom-Json
$gap = [double]$planData.gap
& $ff -hide_banner -loglevel error -y -f lavfi -i "anullsrc=r=24000:cl=mono" -t $gap -c:a pcm_s16le 'audio\gap.wav'

$list = New-Object System.Collections.Generic.List[string]
foreach ($p in $planData.plan) {
  $i = 0
  foreach ($f in $p.lines) {
    if ($i -gt 0) { $list.Add("file 'gap.wav'") }
    $list.Add("file '$f'")
    $i++
  }
  if ($p.pad -gt 0.01) {
    $sil = "sil-$($p.id).wav"
    & $ff -hide_banner -loglevel error -y -f lavfi -i "anullsrc=r=24000:cl=mono" -t $p.pad -c:a pcm_s16le "audio\$sil"
    $list.Add("file '$sil'")
  }
}
Set-Content -LiteralPath 'audio\full-list.txt' -Value ($list -join "`n") -Encoding ascii
& $ff -hide_banner -loglevel error -y -f concat -safe 0 -i 'audio\full-list.txt' -c copy 'audio\full-narration.wav'
$adur = [double](& $ffprobe -v error -show_entries format=duration -of csv=p=0 'audio\full-narration.wav')
Write-Output ("narration {0:N2}s" -f $adur)

# 5) 合成：视频 + 音轨 + 硬字幕
Write-Output '--- encode ---'
$vdur = [double](& $ffprobe -v error -show_entries format=duration -of csv=p=0 'shots\out\full-silent.mp4')
Write-Output ("video {0:N2}s" -f $vdur)
$filter = (Get-Content 'sub\filter.txt' -Raw -Encoding UTF8).Trim()
& $ff -hide_banner -loglevel error -y -i 'shots\out\full-silent.mp4' -i 'audio\full-narration.wav' `
  -filter_complex $filter -map "[v]" -map 1:a:0 -t $vdur `
  -c:v libx264 -crf 18 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 192k -ar 44100 `
  -movflags +faststart 'out\full-小鲸鱼.mp4'
& $ffprobe -v error -show_entries format=duration,size -show_entries stream=codec_type,codec_name,width,height,sample_rate -of default=noprint_wrappers=1 'out\full-小鲸鱼.mp4'
Write-Output 'DONE'

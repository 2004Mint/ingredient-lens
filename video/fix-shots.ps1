# 重录指定镜头 → 重渲染 → 重编码
$ErrorActionPreference = 'Continue'
$root = 'E:\AIWEB\video'
$ff = 'E:\AIWEB\tools\ffmpeg\bin\ffmpeg.exe'
$ffprobe = 'E:\AIWEB\tools\ffmpeg\bin\ffprobe.exe'
$cd = 'E:\AIWEB\tools\capture-director\bin\capture-director.js'
$env:FFMPEG = $ff; $env:FFPROBE = $ffprobe
$env:CD_CHROME_CHANNEL = 'msedge'; $env:CD_NO_SANDBOX = '1'
Set-Location $root

$pwPath = Join-Path $env:USERPROFILE '.cosme\data\admin-password.txt'
$pw = (Get-Content $pwPath -Raw).Trim()
$login = Invoke-RestMethod -Uri 'http://127.0.0.1:8083/api/admin/login' -Method Post `
  -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes((@{ password = $pw } | ConvertTo-Json -Compress))) -TimeoutSec 15
$env:ADMIN_TOKEN = $login.token

node build.mjs
Write-Output '--- re-record s3,s8,s9,s10 ---'
node $cd record shots/full.run.yaml --only s3,s8,s9,s10 2> 'record2.log'
if ($LASTEXITCODE -ne 0) { Get-Content 'record2.log' -Tail 25; throw 'record failed' }

Write-Output '--- render ---'
node $cd render shots/full-timeline.yaml 2> 'render2.log'
if ($LASTEXITCODE -ne 0) { Get-Content 'render2.log' -Tail 25; throw 'render failed' }

Write-Output '--- encode ---'
$vdur = [double](& $ffprobe -v error -show_entries format=duration -of csv=p=0 'shots\out\full-silent.mp4')
$filter = (Get-Content 'sub\filter.txt' -Raw -Encoding UTF8).Trim()
& $ff -hide_banner -loglevel error -y -i 'shots\out\full-silent.mp4' -i 'audio\full-narration.wav' `
  -filter_complex $filter -map "[v]" -map 1:a:0 -t $vdur `
  -c:v libx264 -crf 18 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 192k -ar 44100 `
  -movflags +faststart 'out\full-xiaojingyu.mp4'
& $ffprobe -v error -show_entries format=duration,size -of default=noprint_wrappers=1 'out\full-xiaojingyu.mp4'
Write-Output 'DONE'

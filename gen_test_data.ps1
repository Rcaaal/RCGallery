$albums = @(
    @{name='test_album_1'; color='#FF4444'},
    @{name='test_album_2'; color='#4488FF'},
    @{name='test_album_3'; color='#44BB44'},
    @{name='test_album_4'; color='#FF8800'},
    @{name='test_album_5'; color='#AA44FF'}
)

Add-Type -AssemblyName System.Drawing

$basePath = 'D:\AndroidStudio\Projects\RCGallery\test_data'
if (Test-Path $basePath) { Remove-Item -Recurse -Force $basePath }
New-Item -ItemType Directory -Path $basePath -Force | Out-Null

$idx = 0
$totalImages = 0
foreach ($album in $albums) {
    $dir = Join-Path $basePath $album.name
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $color = [System.Drawing.ColorTranslator]::FromHtml($album.color)

    for ($i = 1; $i -le 30; $i++) {
        $bm = New-Object System.Drawing.Bitmap(640, 480)
        $g = [System.Drawing.Graphics]::FromImage($bm)
        $g.Clear($color)
        $brush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
        $font = New-Object System.Drawing.Font('Arial', 120, [System.Drawing.FontStyle]::Bold)
        $sz = $g.MeasureString($i.ToString(), $font)
        $g.DrawString($i.ToString(), $font, $brush, (640-$sz.Width)/2, (480-$sz.Height)/2)
        $g.Dispose()
        $bm.Save((Join-Path $dir "$i.jpg"), [System.Drawing.Imaging.ImageFormat]::Jpeg)
        $bm.Dispose()
        $totalImages++
    }

    for ($v = 1; $v -le 2; $v++) {
        $bm = New-Object System.Drawing.Bitmap(320, 240)
        $g = [System.Drawing.Graphics]::FromImage($bm)
        $g.Clear($color)
        $brush2 = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
        $font2 = New-Object System.Drawing.Font('Arial', 48, [System.Drawing.FontStyle]::Bold)
        $g.DrawString("video $v", $font2, $brush2, 80, 80)
        $g.Dispose()
        $bm.Save((Join-Path $dir "video_$v.png"), [System.Drawing.Imaging.ImageFormat]::Png)
        $bm.Dispose()
    }

    $idx++
    Write-Host "[$idx/5] $($album.name) - 30 images + 2 video thumbs done"
}

Write-Host "Total: $totalImages images generated"

function New-MinimalMp4($path) {
    $ftyp = [byte[]]@(
        0x00,0x00,0x00,0x20,0x66,0x74,0x79,0x70,0x69,0x73,0x6F,0x6D,0x00,0x00,0x02,0x00,
        0x69,0x73,0x6F,0x6D,0x69,0x73,0x6F,0x32,0x6D,0x70,0x34,0x31,0x61,0x76,0x63,0x31
    )
    $moovData = [byte[]]@(
        0x00,0x00,0x00,0x08,0x6D,0x6F,0x6F,0x76
    )
    $data = $ftyp + $moovData
    [System.IO.File]::WriteAllBytes($path, $data)
}

foreach ($album in $albums) {
    $dir = Join-Path $basePath $album.name
    for ($v = 1; $v -le 2; $v++) {
        New-MinimalMp4 (Join-Path $dir "video_$v.mp4")
    }
}

Write-Host "MP4 videos generated"

$adb = "D:/AndroidSDK/platform-tools/adb.exe"

# Clean and create dirs on device
& $adb shell rm -rf /sdcard/DCIM/test_album_1 /sdcard/DCIM/test_album_2 /sdcard/DCIM/test_album_3 /sdcard/DCIM/test_album_4 /sdcard/DCIM/test_album_5
& $adb shell mkdir -p /sdcard/DCIM/test_album_1 /sdcard/DCIM/test_album_2 /sdcard/DCIM/test_album_3 /sdcard/DCIM/test_album_4 /sdcard/DCIM/test_album_5

foreach ($album in $albums) {
    $dir = Join-Path $basePath $album.name
    Write-Host "Pushing $($album.name)..."
    # Windows adb 不支持通配符，逐个文件推送
    Get-ChildItem $dir | ForEach-Object {
        & $adb push $_.FullName "/sdcard/DCIM/$($album.name)/"
    }
}

# Use MediaScanner service instead of broadcast
& $adb shell cmd media exit 2>$null
& $adb shell am broadcast -a android.intent.action.BOOT_COMPLETED 2>$null
# Re-scan via content insert
& $adb shell "content call --uri content://media/external/file --method 'scan_volume' --arg 'external_primary'" 2>$null

Write-Host "All done!"

# Windows PowerShell version of download_model.sh.
# Run from the project folder:   powershell -ExecutionPolicy Bypass -File scripts\download_model.ps1
$ErrorActionPreference = "Stop"

$url = "https://raw.githubusercontent.com/syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing/master/app/src/main/assets/MobileFaceNet.tflite"
$sha256 = "d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b"
$dest = "android\app\src\main\assets\MobileFaceNet.tflite"

New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
Write-Host "Downloading model (about 5 MB)..."
Invoke-WebRequest -Uri $url -OutFile $dest

$actual = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
if ($actual -ne $sha256) {
    Write-Host "WARNING: the downloaded file is different from the one this app was tested with."
    Write-Host "  expected: $sha256"
    Write-Host "  actual  : $actual"
    exit 1
}
Write-Host "OK: $dest is the tested model."

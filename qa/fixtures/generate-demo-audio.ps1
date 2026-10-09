$ErrorActionPreference = 'Stop'
$fixturePath = Join-Path $PSScriptRoot 'demo-meeting-ko.wav'
$scriptPath = Join-Path $PSScriptRoot 'demo-meeting-ko.txt'
$speech = New-Object -ComObject SAPI.SpVoice
$voice = @($speech.GetVoices()) | Where-Object { $_.GetDescription() -eq 'Microsoft Heami Desktop - Korean' } | Select-Object -First 1
if (-not $voice) { throw 'Korean SAPI voice is required; no online fallback is used.' }
$speech.Voice = $voice
$speech.Rate = -1
$file = New-Object -ComObject SAPI.SpFileStream
$format = New-Object -ComObject SAPI.SpAudioFormat
$format.Type = 22 # PCM, 22050 Hz, 16 bit, mono
$file.Format = $format
try {
  $file.Open($fixturePath, 3, $false)
  $speech.AudioOutputStream = $file
  $speech.Speak([IO.File]::ReadAllText($scriptPath, [Text.Encoding]::UTF8)) | Out-Null
} finally {
  $file.Close()
  [Runtime.InteropServices.Marshal]::ReleaseComObject($file) | Out-Null
  [Runtime.InteropServices.Marshal]::ReleaseComObject($speech) | Out-Null
}
Get-Item -LiteralPath $fixturePath | Select-Object Name, Length

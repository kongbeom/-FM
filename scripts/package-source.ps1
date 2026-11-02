$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $sourceRoot 'dist'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$output = [IO.Path]::GetFullPath((Join-Path $outputDir '여운FM_source.zip'))
if (!$output.StartsWith($sourceRoot + [IO.Path]::DirectorySeparatorChar)) { throw 'Output must be inside this project.' }
if (Test-Path -LiteralPath $output) { Remove-Item -LiteralPath $output }
Push-Location $sourceRoot
try {
    $files = & rg --files --hidden -g '!.tooling/**' -g '!**/build/**' -g '!**/.gradle/**' -g '!.git/**' -g '!.idea/**' -g '!dist/**' -g '!local.properties' -g '!apk/**/*.apk' -g '!*.jks' -g '!*.keystore' -g '!keystore.properties' -g '!.env*'
    if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate project files with rg.' }
    $archive = [IO.Compression.ZipFile]::Open($output, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($relative in $files) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, (Join-Path $sourceRoot $relative), $relative.Replace('\','/'), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $archive.Dispose() }
} finally { Pop-Location }
Get-Item -LiteralPath $output | Select-Object FullName,Length

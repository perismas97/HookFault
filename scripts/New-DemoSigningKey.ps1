$ErrorActionPreference = 'Stop'

$directory = Join-Path (Split-Path -Parent $PSScriptRoot) '.secrets'
$path = Join-Path $directory 'demo-signing-key'

if (Test-Path -LiteralPath $path -PathType Leaf) {
    if ((Get-Item -LiteralPath $path).Length -ne 32) {
        throw 'The existing demo signing key must be exactly 32 bytes; it was not changed.'
    }
    Write-Output 'Demo signing key already exists; left unchanged.'
    return
}

[IO.Directory]::CreateDirectory($directory) | Out-Null
$key = [byte[]]::new(32)
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($key)
    $file = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $file.Write($key, 0, $key.Length)
    } finally {
        $file.Dispose()
    }
} finally {
    $random.Dispose()
    [Array]::Clear($key, 0, $key.Length)
}

Write-Output 'Created the local demo signing key.'

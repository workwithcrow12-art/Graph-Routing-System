param([string] $OutputPath = (Join-Path $PSScriptRoot 'target/GraphRoutingSystem-source.zip'))

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sourceRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$sourcePrefix = $sourceRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$archiveOutput = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputPath)
$archiveParent = [IO.Path]::GetDirectoryName($archiveOutput)
[IO.Directory]::CreateDirectory($archiveParent) | Out-Null
$stagingArchive = Join-Path $archiveParent ('.source-' + [Guid]::NewGuid().ToString('N') + '.zip')
$archiveFiles = @()
foreach ($file in @('pom.xml', 'README.md', 'LICENSE', 'run-backend.ps1', 'package-source.ps1', '.gitignore', '.gitattributes')) {
    $archiveFiles += Get-Item -Force -LiteralPath (Join-Path $sourceRoot $file)
}
foreach ($folder in @('src', 'docs', '.github/workflows')) {
    $directory = Get-Item -Force -LiteralPath (Join-Path $sourceRoot $folder)
    if (!$directory.PSIsContainer) { throw "Expected a source folder: $($directory.FullName)" }
    for ($ancestor = $directory; $ancestor -and $ancestor.FullName -ne $sourceRoot; $ancestor = $ancestor.Parent) {
        if ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Linked source folder: $($ancestor.FullName)"
        }
    }
    $archiveFiles += Get-ChildItem -File -Force -Recurse -LiteralPath $directory.FullName
}
try {
    $stream = [IO.File]::Open($stagingArchive, [IO.FileMode]::CreateNew)
    try {
        $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $true, [Text.Encoding]::UTF8)
        try {
            $included = 0
            foreach ($file in $archiveFiles) {
                if (!$file.FullName.StartsWith($sourcePrefix, [StringComparison]::Ordinal)) {
                    throw "Source path escaped workspace: $($file.FullName)"
                }
                $relative = $file.FullName.Substring($sourcePrefix.Length).Replace('\', '/')
                if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { continue }
                if ($relative -match '(^|/)(target|node_modules|dist|out|test-results|playwright-report|\.build-checks|\.vite|\.git|\.idea|\.vscode)(/|$)') { continue }
                if ($file.Name -match '^\.env($|\.)|^\.DS_Store$|^Thumbs\.db$|\.(class|jar|zip|log|tmp|key|p12|pfx|jks)$') { continue }
                [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName,
                    'GraphRoutingSystem/' + $relative, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
                $included++
            }
        } finally { $archive.Dispose() }
    } finally { $stream.Dispose() }
    Move-Item -LiteralPath $stagingArchive -Destination $archiveOutput -Force
    Write-Output "Source archive: $archiveOutput ($included files; generated outputs excluded)"
} finally {
    if (Test-Path -LiteralPath $stagingArchive) { Remove-Item -LiteralPath $stagingArchive -Force }
}

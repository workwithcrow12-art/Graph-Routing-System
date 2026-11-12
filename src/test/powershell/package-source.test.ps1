$ErrorActionPreference = 'Stop'

$project = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$testRoot = Join-Path $project 'target'
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$fixture = Join-Path $testRoot ('source-package-test-' + [Guid]::NewGuid().ToString('N'))
$source = Join-Path $fixture 'project with spaces'
$caller = Join-Path $fixture 'working directory'
[IO.Directory]::CreateDirectory($source) | Out-Null
[IO.Directory]::CreateDirectory($caller) | Out-Null

try {
    Copy-Item -LiteralPath (Join-Path $project 'package-source.ps1') -Destination $source
    $unicodeFile = 'docs/' + [char]0x0111 + 'oc.md'
    $included = @('pom.xml', 'README.md', 'LICENSE', 'run-backend.ps1', '.gitignore', '.gitattributes',
        'src/main/java/Example.java', 'src/main/frontend/package-lock.json',
        '.github/workflows/backend.yml', $unicodeFile)
    $excluded = @('src/main/frontend/node_modules/example/index.js', 'src/main/frontend/dist/index.html',
        'src/main/frontend/test-results/result.txt', 'src/main/frontend/.env.local',
        'src/main/resources/certificate.p12', 'docs/build.log', '.github/modernize/report.md',
        'target/existing.jar', '.build-checks/check.txt')
    foreach ($relative in ($included + $excluded)) {
        $path = Join-Path $source $relative
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
        [IO.File]::WriteAllText($path, $relative)
    }

    Push-Location -LiteralPath $caller
    try {
        & (Join-Path $source 'package-source.ps1') -OutputPath 'results/source archive.zip'
    } finally {
        Pop-Location
    }

    $zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $caller 'results/source archive.zip'))
    try {
        $expected = @($included + 'package-source.ps1' | ForEach-Object { 'GraphRoutingSystem/' + $_ })
        $actual = @($zip.Entries | ForEach-Object { $_.FullName })
        $difference = Compare-Object -ReferenceObject $expected -DifferenceObject $actual -CaseSensitive
        if ($actual.Count -ne $expected.Count -or $difference) {
            throw ('Unexpected source archive entries: ' + ($actual -join ', '))
        }
        $entry = $zip.GetEntry('GraphRoutingSystem/' + $unicodeFile)
        $reader = [IO.StreamReader]::new($entry.Open())
        try {
            if ($reader.ReadToEnd() -cne $unicodeFile) { throw 'Unicode source contents changed' }
        } finally {
            $reader.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
    Write-Output 'PASS: source archive entries, exclusions, Unicode and relative output path'

    if ($env:OS -eq 'Windows_NT') {
        $linkedSource = Join-Path $fixture 'linked project'
        $outside = Join-Path $fixture 'outside linked project'
        foreach ($directory in @((Join-Path $linkedSource 'src'), (Join-Path $linkedSource '.github/workflows'), $outside)) {
            [IO.Directory]::CreateDirectory($directory) | Out-Null
        }
        foreach ($name in @('pom.xml', 'README.md', 'LICENSE', 'run-backend.ps1', 'package-source.ps1', '.gitignore', '.gitattributes')) {
            Copy-Item -LiteralPath (Join-Path $source $name) -Destination $linkedSource
        }
        [IO.File]::WriteAllText((Join-Path $outside 'fixture.txt'), 'outside source tree')
        $junction = Join-Path $linkedSource 'docs'
        $archive = Join-Path $caller 'results/source archive.zip'
        $originalHash = (Get-FileHash -LiteralPath $archive).Hash
        New-Item -Path $junction -ItemType Junction -Target $outside | Out-Null
        try {
            try {
                & (Join-Path $linkedSource 'package-source.ps1') -OutputPath $archive
                throw 'The packager accepted a junction as a source folder'
            } catch {
                if ($_.Exception.Message -notlike 'Linked source folder:*') { throw }
            }
            if ((Get-FileHash -LiteralPath $archive).Hash -ne $originalHash) {
                throw 'The failed package operation replaced the existing ZIP'
            }
        } finally {
            [IO.Directory]::Delete($junction)
        }
        Write-Output 'PASS: linked source folder rejected and existing ZIP preserved'
    }
} finally {
    $resolvedFixture = (Resolve-Path -LiteralPath $fixture).Path
    $resolvedRoot = (Resolve-Path -LiteralPath $testRoot).Path + [IO.Path]::DirectorySeparatorChar
    if (!$resolvedFixture.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unexpected fixture cleanup path'
    }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}

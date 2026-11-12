$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$build = (Resolve-Path (Join-Path $project 'target')).Path
$jar = 'graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar'
$fixture = Join-Path $build ('launcher-test-' + [Guid]::NewGuid().ToString('N'))
$unicodeName = 'project ' + [char]0x0111 + [char]0x01B0 + [char]0x1EDD + 'ng'
$launcherDir = Join-Path $fixture $unicodeName
$caller = Join-Path $fixture 'caller with spaces'
[IO.Directory]::CreateDirectory((Join-Path $launcherDir 'target')) | Out-Null
[IO.Directory]::CreateDirectory($caller) | Out-Null

try {
    Copy-Item -LiteralPath (Join-Path $project 'run-backend.ps1') -Destination $launcherDir
    Copy-Item -LiteralPath (Join-Path $build $jar) -Destination (Join-Path $launcherDir 'target')
    $dataset = Join-Path $project 'src/main/resources/data'
    Copy-Item -LiteralPath (Join-Path $dataset 'thanh_xuan_nodes.json') -Destination (Join-Path $caller 'test nodes.json')
    Copy-Item -LiteralPath (Join-Path $dataset 'thanh_xuan_edges.json') -Destination (Join-Path $caller 'test edges.json')

    Push-Location -LiteralPath $caller
    try {
        $query = 'Nguy' + [char]0x1EC5 + 'n Tr' + [char]0x00E3 + 'i'
        $output = & (Join-Path $launcherDir 'run-backend.ps1') search $query --nodes 'test nodes.json' --edges 'test edges.json'
        if ($LASTEXITCODE -ne 0) { throw 'The launcher failed from a Unicode project path' }
        $results = ConvertFrom-Json -InputObject ($output -join "`n")
        if ($results.id -notcontains 'tx-khuat-duy-tien-nguyen-trai') {
            throw 'The launcher did not return the expected node from the caller-relative files'
        }
        if ($PWD.Path -ne $caller) { throw 'The launcher changed the caller working directory' }
    } finally {
        Pop-Location
    }
    Write-Output 'PASS: Unicode JAR path, Unicode query and caller-relative inputs'
} finally {
    $resolvedFixture = (Resolve-Path -LiteralPath $fixture).Path
    $prefix = $build + [IO.Path]::DirectorySeparatorChar
    if (!$resolvedFixture.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unexpected launcher fixture cleanup path'
    }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}

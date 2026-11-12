param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $BackendArguments
)

$ErrorActionPreference = 'Stop'
$taskJarPath = 'target/graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar'
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $taskJarPath))) {
    throw 'Build the backend first: mvn clean verify'
}

if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $taskJavaPath = Join-Path $env:JAVA_HOME 'bin/java.exe'
} else {
    $taskJavaPath = (Get-Command java -CommandType Application -ErrorAction Stop).Source
}

if (-not $BackendArguments -or $BackendArguments.Count -eq 0) {
    $BackendArguments = @('serve')
}

# Resolve relative file arguments in the caller's directory before changing the Java working directory.
$taskPathOptions = @('--nodes', '--edges', '--osm', '--traffic', '--output')
for ($taskIndex = 0; $taskIndex -lt $BackendArguments.Count - 1; $taskIndex++) {
    $taskValue = $BackendArguments[$taskIndex + 1]
    if ($taskPathOptions -ccontains $BackendArguments[$taskIndex] -and $taskValue -and !$taskValue.StartsWith('--')) {
        $BackendArguments[$taskIndex + 1] = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($taskValue)
        $taskIndex++
    }
}

# Keep the native JAR argument ASCII. Java receives the remaining arguments through UTF-8 JSON.
$taskArgumentsJson = ConvertTo-Json -InputObject @($BackendArguments) -Compress
$taskEncodedArguments = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($taskArgumentsJson))
Push-Location -LiteralPath $PSScriptRoot
try {
    & $taskJavaPath -jar $taskJarPath --utf8-arguments $taskEncodedArguments
    $taskExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $taskExitCode

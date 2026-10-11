param(
    [Parameter(Mandatory=$true)][string]$From,
    [Parameter(Mandatory=$true)][string]$To,
    [string]$Tickers,
    [ValidateSet('MOMENTUM','ENTRY','BREAKOUT','PULLBACK')][string]$RankingMode = 'MOMENTUM',
    [ValidateSet('mt5','brapi')][string]$HistoricalProvider = 'mt5',
    [string]$OutputDirectory,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$replayJavaHome = $env:JAVA_HOME
if (-not $replayJavaHome -or -not (Test-Path -LiteralPath (Join-Path $replayJavaHome 'bin/java.exe'))) {
    $replayJavaHome = Join-Path $env:USERPROFILE '.jdks/corretto-21.0.4'
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repoRoot ('research-results/panic-replay/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
}
$classpathFile = Join-Path $repoRoot 'panic-scanner/target/panic-replay-classpath.txt'
$previousJavaHome = $env:JAVA_HOME
Push-Location $repoRoot
try {
    $env:JAVA_HOME = $replayJavaHome
    if (-not $SkipBuild) {
        & mvn -B install -DskipTests -pl panic-scanner -am
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    if (-not (Test-Path -LiteralPath $classpathFile)) {
        & mvn -B -f panic-scanner/pom.xml dependency:build-classpath "-Dmdep.outputFile=$classpathFile" '-DincludeScope=runtime'
        if ($LASTEXITCODE -ne 0) { throw 'Could not generate classpath' }
    }
    $classes = @('panic-scanner','ta4j-engine','bolsai-client','common') | ForEach-Object { Join-Path $repoRoot "$_/target/classes" }
    $classpath = ($classes -join ';') + ';' + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    $replayArguments = @('--from', $From, '--to', $To, '--ranking-mode', $RankingMode,
        '--historical-provider', $HistoricalProvider, '--output', $OutputDirectory)
    if ($Tickers) { $replayArguments += @('--tickers', $Tickers) }
    & (Join-Path $replayJavaHome 'bin/java.exe') -cp $classpath br.com.bauzin.market.panic.panicscanner.application.replay.PanicReplayCommand @replayArguments
    if ($LASTEXITCODE -ne 0) { throw 'Replay failed' }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}

param(
    [string]$StudyDirectory = 'research-results/panic-opportunity-study/ago-set-2026',
    [string]$CacheDirectory = 'historical-data/panic-d1',
    [string]$ValidationFrom = '2026-09-01',
    [string]$OutputDirectory,
    [string]$ReportFrom,
    [string]$Tickers,
    [switch]$FetchMissing,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$reportOverrideNames = @('ValidationFrom','StudyDirectory','CacheDirectory')
$hasReportOverride = @($reportOverrideNames | Where-Object { $PSBoundParameters.ContainsKey($_) }).Count -gt 0
if ($ReportFrom -and ($FetchMissing -or $Tickers -or $hasReportOverride)) {
    throw 'ReportFrom uses frozen cases and saved settings; do not combine it with fetch, tickers, study, cache or a new split.'
}
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$comparisonJavaHome = $env:JAVA_HOME
if (-not $comparisonJavaHome -or -not (Test-Path -LiteralPath (Join-Path $comparisonJavaHome 'bin/java.exe'))) {
    $comparisonJavaHome = Join-Path $env:USERPROFILE '.jdks/corretto-21.0.4'
}
if (-not $OutputDirectory) {
    $OutputDirectory = 'research-results/panic-experimental/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
}
$previousJavaHome = $env:JAVA_HOME
Push-Location $repoRoot
try {
    $env:JAVA_HOME = $comparisonJavaHome
    if (-not $SkipBuild) {
        & mvn -B install -DskipTests -pl panic-scanner -am
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    $classpathFile = Join-Path $repoRoot 'panic-scanner/target/panic-replay-classpath.txt'
    if (-not (Test-Path -LiteralPath $classpathFile)) {
        & mvn -B -f panic-scanner/pom.xml dependency:build-classpath "-Dmdep.outputFile=$classpathFile" '-DincludeScope=runtime'
        if ($LASTEXITCODE -ne 0) { throw 'Classpath generation failed' }
    }
    $classes = @('panic-scanner','ta4j-engine','bolsai-client','common') | ForEach-Object { Join-Path $repoRoot "$_/target/classes" }
    $classpath = ($classes -join ';') + ';' + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    if ($ReportFrom) {
        $comparisonArguments = @('--report-from',$ReportFrom,'--output',$OutputDirectory)
    } else {
        $comparisonArguments = @('--study',$StudyDirectory,'--cache',$CacheDirectory,'--output',$OutputDirectory,
            '--validation-from',$ValidationFrom,'--fetch-missing',$FetchMissing.IsPresent.ToString().ToLowerInvariant())
        if ($Tickers) { $comparisonArguments += @('--tickers',$Tickers.ToUpperInvariant()) }
    }
    & (Join-Path $comparisonJavaHome 'bin/java.exe') -Xmx1g -cp $classpath br.com.bauzin.market.panic.panicscanner.application.experimental.ExperimentalComparisonCommand @comparisonArguments
    if ($LASTEXITCODE -ne 0) { throw 'Experimental comparison failed' }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}

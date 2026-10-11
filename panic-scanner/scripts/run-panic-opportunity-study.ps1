param(
    [Parameter(Mandatory=$true)][string]$From,
    [Parameter(Mandatory=$true)][string]$To,
    [string]$Tickers,
    [ValidateSet('MOMENTUM','ENTRY','BREAKOUT','PULLBACK')][string]$RankingMode = 'MOMENTUM',
    [ValidateSet('mt5','brapi')][string]$HistoricalProvider = 'mt5',
    [decimal]$Return5Threshold = 10,
    [decimal]$Return10Threshold = 15,
    [ValidateRange(14,365)][int]$FutureCalendarDays = 40,
    [string]$OutputDirectory,
    [switch]$Resume,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$studyJavaHome = $env:JAVA_HOME
if (-not $studyJavaHome -or -not (Test-Path -LiteralPath (Join-Path $studyJavaHome 'bin/java.exe'))) {
    $studyJavaHome = Join-Path $env:USERPROFILE '.jdks/corretto-21.0.4'
}
if ($Resume -and -not $OutputDirectory) { throw 'Resume requires the original OutputDirectory.' }
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repoRoot ('research-results/panic-opportunity-study/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
}
$classpathFile = Join-Path $repoRoot 'panic-scanner/target/panic-replay-classpath.txt'
$previousJavaHome = $env:JAVA_HOME
Push-Location $repoRoot
try {
    $env:JAVA_HOME = $studyJavaHome
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
    $culture = [Globalization.CultureInfo]::InvariantCulture
    $studyArguments = @('--from',$From,'--to',$To,'--ranking-mode',$RankingMode,'--historical-provider',$HistoricalProvider,
        '--return5-threshold',$Return5Threshold.ToString($culture),'--return10-threshold',$Return10Threshold.ToString($culture),
        '--future-calendar-days',"$FutureCalendarDays",'--output',$OutputDirectory,'--resume',$Resume.IsPresent.ToString().ToLowerInvariant())
    if ($Tickers) { $studyArguments += @('--tickers',$Tickers) }
    & (Join-Path $studyJavaHome 'bin/java.exe') -cp $classpath br.com.bauzin.market.panic.panicscanner.application.replay.PanicOpportunityStudyCommand @studyArguments
    if ($LASTEXITCODE -ne 0) { throw 'Opportunity study failed. Completed ticker checkpoints can be resumed.' }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}

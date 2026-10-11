param(
    [ValidateRange(1, 3650)][int]$Days = 180,
    [ValidatePattern('^(WIN\$N|[A-Z0-9_]{1,24})$')][string]$Symbol = 'WIN$N',
    [string]$StartDate,
    [string]$EndDate,
    [string]$OutputDirectory,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$culture = [Globalization.CultureInfo]::InvariantCulture
$lastDay = if ($EndDate) { [DateTime]::ParseExact($EndDate, 'yyyy-MM-dd', $culture) } else { [DateTime]::UtcNow.Date.AddDays(-1) }
$firstDay = if ($StartDate) { [DateTime]::ParseExact($StartDate, 'yyyy-MM-dd', $culture) } else { $lastDay.AddDays(1 - $Days) }
if ($firstDay -gt $lastDay) { throw 'StartDate deve ser anterior ou igual a EndDate.' }
if (-not $OutputDirectory) {
    $runName = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $OutputDirectory = Join-Path $repoRoot "research-results/setup91-risk-calibration/$runName"
} else { $OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory) }
if (Test-Path -LiteralPath $OutputDirectory) { throw "Escolha uma pasta nova para preservar os resultados anteriores: $OutputDirectory" }
$originalJavaHome = $env:JAVA_HOME
Push-Location $repoRoot
try {
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/corretto-21.0.4'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw 'Configure JAVA_HOME para JDK 21.' }
    $classpathFile = Join-Path $repoRoot 'panic-scanner/target/ema9-classpath.txt'
    if (-not $SkipBuild) {
        & mvn -B install -DskipTests -pl panic-scanner -am
        if ($LASTEXITCODE -ne 0) { throw 'Build falhou.' }
        & mvn -B -f panic-scanner/pom.xml dependency:build-classpath "-Dmdep.outputFile=$classpathFile" '-DincludeScope=runtime'
        if ($LASTEXITCODE -ne 0) { throw 'Classpath falhou.' }
    }
    if (-not (Test-Path -LiteralPath $classpathFile)) { throw 'Classpath ausente. Rode uma vez sem -SkipBuild.' }
    # Prefer reactor classes over installed dependency jars, including when SkipBuild is used.
    $projectClasses = @('panic-scanner', 'ta4j-engine', 'bolsai-client', 'common') | ForEach-Object { Join-Path $repoRoot "$_/target/classes" }
    $classpath = ($projectClasses -join [IO.Path]::PathSeparator) + [IO.Path]::PathSeparator + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    Write-Host "Medindo entradas Setup91 com risco ATR calibrado: $Symbol, $($firstDay.ToString('yyyy-MM-dd')) a $($lastDay.ToString('yyyy-MM-dd')) UTC."
    Write-Host 'Saidas: parcial 50% em +1R; stop do saldo na entrada; alvo fixo +3.5R. Entradas ate 17:30 UTC.'
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') -Xmx1g -cp $classpath `
        br.com.bauzin.market.panic.panicscanner.application.win.replay.Setup91RiskCalibrationCommand `
        $Symbol $firstDay.ToString('yyyy-MM-dd') $lastDay.ToString('yyyy-MM-dd') $OutputDirectory
    $reportExitCode = $LASTEXITCODE
    if ($reportExitCode -eq 1) { throw 'O relatorio falhou. Consulte a mensagem acima.' }
    if ($reportExitCode -eq 2) { Write-Warning 'Relatorio PARCIAL: ha dias ausentes ou invalidos. Os detalhes estao no output e em daily.csv.' }
    Write-Host "Relatorio de operacoes aceitas: $OutputDirectory/summary.txt"
    exit $reportExitCode
} finally {
    $env:JAVA_HOME = $originalJavaHome
    Pop-Location
}

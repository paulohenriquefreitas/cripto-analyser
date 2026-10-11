param(
    [ValidateRange(1, 3650)][int]$Days = 180,
    [ValidateSet('WIN$N', 'WINV26')][string]$Symbol = 'WIN$N',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $repoRoot
try {
    if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $env:JAVA_HOME = Join-Path $env:USERPROFILE '.jdks/corretto-21.0.4'
    }
    if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw 'Configure JAVA_HOME para JDK 21.' }
    if (-not $SkipBuild) {
        & mvn -B install -DskipTests -pl panic-scanner -am
        if ($LASTEXITCODE -ne 0) { throw 'Build falhou.' }
        & mvn -B -f panic-scanner/pom.xml dependency:build-classpath '-Dmdep.outputFile=target/shadow-classpath.txt'
        if ($LASTEXITCODE -ne 0) { throw 'Classpath falhou.' }
    }
    $classpath = (Join-Path $repoRoot 'panic-scanner/target/classes') + ';' + (Get-Content 'panic-scanner/target/shadow-classpath.txt' -Raw).Trim()
    $lastDay = [DateTime]::UtcNow.Date.AddDays(-1)
    $firstDay = $lastDay.AddDays(1 - $Days)
    Write-Host "Importando $Symbol de $($firstDay.ToString('yyyy-MM-dd')) a $($lastDay.ToString('yyyy-MM-dd')) UTC ($Days dias)."
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') -Xmx1g -cp $classpath br.com.bauzin.market.panic.panicscanner.application.win.replay.HistoricalDatasetImportCommand $Symbol $firstDay.ToString('yyyy-MM-dd') $lastDay.ToString('yyyy-MM-dd')
    if ($LASTEXITCODE -ne 0) { throw 'Importacao terminou com falhas; consulte as datas no resumo. Dias COMPLETE serao reutilizados ao repetir.' }
} finally { Pop-Location }

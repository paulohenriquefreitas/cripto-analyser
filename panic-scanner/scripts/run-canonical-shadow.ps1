param(
    [ValidateSet('30m','60m')][string]$Duration = '60m',
    [ValidateSet('WINV26')][string]$Symbol = 'WINV26',
    [string]$Report = 'panic-scanner/target/canonical-shadow-report.json',
    [switch]$StartLive,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $repoRoot
Push-Location $repoRoot
try {
    if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $localJdk = Join-Path $env:USERPROFILE '.jdks/corretto-21.0.4'
        if (Test-Path (Join-Path $localJdk 'bin/java.exe')) { $env:JAVA_HOME = $localJdk }
        else { throw 'Configure JAVA_HOME para um JDK 21.' }
    }
    if (-not $SkipBuild) {
        & mvn -B install -DskipTests -pl panic-scanner -am
        if ($LASTEXITCODE -ne 0) { throw 'Build falhou.' }
        & mvn -B -f panic-scanner/pom.xml dependency:build-classpath '-Dmdep.outputFile=target/shadow-classpath.txt'
        if ($LASTEXITCODE -ne 0) { throw 'Classpath falhou.' }
    }
    $classpath = (Join-Path $repoRoot 'panic-scanner/target/classes') + ';' + (Get-Content 'panic-scanner/target/shadow-classpath.txt' -Raw).Trim()
    $entry = 'br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.CanonicalLiveShadowDiagnostics'
    if ($StartLive) { $entry = 'br.com.bauzin.market.panic.panicscanner.infrastructure.marketdata.CanonicalShadowSession' }
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') -cp $classpath $entry --symbol $Symbol --duration $Duration --report $Report
    if ($LASTEXITCODE -ne 0) { throw 'Diagnostico falhou; consulte o relatorio e o log.' }
} finally { Pop-Location }

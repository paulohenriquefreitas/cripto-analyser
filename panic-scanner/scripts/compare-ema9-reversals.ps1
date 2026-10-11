param(
    [Parameter(Mandatory = $true)][string]$Baseline,
    [Parameter(Mandatory = $true)][string]$Current
)
$ErrorActionPreference = 'Stop'
$Baseline = (Resolve-Path -LiteralPath $Baseline).Path
$Current = (Resolve-Path -LiteralPath $Current).Path
$beforeRun = Get-Content -LiteralPath (Join-Path $Baseline 'run.json') -Raw | ConvertFrom-Json
$afterRun = Get-Content -LiteralPath (Join-Path $Current 'run.json') -Raw | ConvertFrom-Json
foreach ($field in @('symbol', 'start', 'end', 'signal', 'clock')) {
    if ($beforeRun.$field -ne $afterRun.$field) { throw "Comparacao recusada: $field diferente entre as execucoes." }
}
$beforeDays = @(Import-Csv -LiteralPath (Join-Path $Baseline 'daily.csv'))
$afterDays = @(Import-Csv -LiteralPath (Join-Path $Current 'daily.csv'))
$beforeByDate = @{}
foreach ($day in $beforeDays) { $beforeByDate[$day.date] = $day }
$comparable = [Collections.Generic.HashSet[string]]::new()
$comparison = @()
$excluded = @()
foreach ($day in $afterDays) {
    $old = $beforeByDate[$day.date]
    if ($null -eq $old -or $day.status -ne 'OK' -or $old.status -ne 'OK' -or
        -not $day.datasetSha256 -or $day.datasetSha256 -ne $old.datasetSha256) {
        $excluded += $day.date
        continue
    }
    [void]$comparable.Add($day.date)
    $comparison += [pscustomobject]@{
        Date = $day.date
        BaselineUp = [long]$old.up; CurrentUp = [long]$day.up; DeltaUp = [long]$day.up - [long]$old.up
        BaselineDown = [long]$old.down; CurrentDown = [long]$day.down; DeltaDown = [long]$day.down - [long]$old.down
        BaselineTotal = [long]$old.total; CurrentTotal = [long]$day.total; DeltaTotal = [long]$day.total - [long]$old.total
    }
}
if ($comparable.Count -eq 0) { throw 'Nenhum dia valido com o mesmo SHA256 de dataset para comparar.' }
$beforeKeys = [Collections.Generic.HashSet[string]]::new()
$afterKeys = [Collections.Generic.HashSet[string]]::new()
foreach ($entry in @(Import-Csv -LiteralPath (Join-Path $Baseline 'events.csv'))) {
    if ($comparable.Contains($entry.date)) { [void]$beforeKeys.Add("$($entry.date)|$($entry.candleTimeMsc)|$($entry.direction)") }
}
foreach ($entry in @(Import-Csv -LiteralPath (Join-Path $Current 'events.csv'))) {
    if ($comparable.Contains($entry.date)) { [void]$afterKeys.Add("$($entry.date)|$($entry.candleTimeMsc)|$($entry.direction)") }
}
$changes = @()
foreach ($key in $afterKeys) {
    if (-not $beforeKeys.Contains($key)) { $parts = $key.Split('|'); $changes += [pscustomobject]@{ Change = 'ADDED'; Date = $parts[0]; CandleTimeMsc = $parts[1]; Direction = $parts[2] } }
}
foreach ($key in $beforeKeys) {
    if (-not $afterKeys.Contains($key)) { $parts = $key.Split('|'); $changes += [pscustomobject]@{ Change = 'REMOVED'; Date = $parts[0]; CandleTimeMsc = $parts[1]; Direction = $parts[2] } }
}
$comparison | Export-Csv -LiteralPath (Join-Path $Current 'comparison.csv') -NoTypeInformation -Encoding UTF8
if ($changes.Count -gt 0) { $changes | Sort-Object Date, CandleTimeMsc, Direction, Change | Export-Csv -LiteralPath (Join-Path $Current 'event-changes.csv') -NoTypeInformation -Encoding UTF8 }
else { Set-Content -LiteralPath (Join-Path $Current 'event-changes.csv') -Value 'Change,Date,CandleTimeMsc,Direction' -Encoding UTF8 }
$baselineTotal = ($comparison | Measure-Object BaselineTotal -Sum).Sum
$currentTotal = ($comparison | Measure-Object CurrentTotal -Sum).Sum
$lines = @(
    'COMPARACAO MME9 - MESMOS DADOS',
    "Anterior: $Baseline",
    "Atual:    $Current",
    "Codigo anterior: $($beforeRun.codeSha256)",
    "Codigo atual:    $($afterRun.codeSha256)",
    "ATR nos fechamentos: anterior=$([bool]$beforeRun.atrDisplacementFilter) atual=$([bool]$afterRun.atrDisplacementFilter)",
    "Dias comparaveis: $($comparable.Count) | Excluidos: $($excluded.Count)",
    "Reversoes: anterior=$baselineTotal atual=$currentTotal delta=$($currentTotal - $baselineTotal)",
    "Variacao percentual: $(if ($baselineTotal -gt 0) { ((100.0 * ($currentTotal - $baselineTotal) / $baselineTotal).ToString('F2', [Globalization.CultureInfo]::InvariantCulture)) + '%' } else { 'N/A (referencia zero)' })",
    "Setas adicionadas: $(@($changes | Where-Object Change -eq 'ADDED').Count) | removidas: $(@($changes | Where-Object Change -eq 'REMOVED').Count)"
)
if ($excluded.Count -gt 0) { $lines += 'Comparacao PARCIAL: dias ausentes, invalidos ou com dataset diferente: ' + ($excluded -join ', ') }
$changedDays = @($comparison | Where-Object { $_.DeltaUp -ne 0 -or $_.DeltaDown -ne 0 })
if ($changedDays.Count -gt 0) { $lines += ($changedDays | Format-Table Date, BaselineTotal, CurrentTotal, DeltaTotal, DeltaUp, DeltaDown -AutoSize | Out-String).TrimEnd() }
$lines += "Detalhes: $Current/comparison.csv e event-changes.csv (detecta mudancas mesmo quando os totais sao iguais)."
$lines | Tee-Object -FilePath (Join-Path $Current 'comparison.txt')

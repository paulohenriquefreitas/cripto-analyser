param(
    [ValidateRange(1, 60)][int]$Seconds = 60,
    [string]$Uri = 'ws://localhost:8080/ws/mt5/ticks'
)
# Read-only subscriber to an already running backend. Does not start MT5 or submit orders.
$ErrorActionPreference = 'Stop'
$socket = $null
$request = $null
$response = $null
$timeout = [System.Threading.CancellationTokenSource]::new()
$timeout.CancelAfter($Seconds * 1000)
$buffer = [byte[]]::new(65536)
$segment = [ArraySegment[byte]]::new($buffer)
$previous = -1L
$frames = 0
$active = 0
$printedSecond = -1L
$watch = [Diagnostics.Stopwatch]::StartNew()
try {
    if ($PSVersionTable.PSEdition -eq 'Desktop') {
        # Framework ClientWebSocket rejects Connection: upgrade, keep-alive and
        # its Options cannot change the restricted Connection header. Use the
        # native HTTP upgrade with HTTP persistence disabled, then native framing.
        [System.Net.WebSockets.WebSocket]::RegisterPrefixes()
        $request = [System.Net.WebRequest]::Create([uri]$Uri)
        $request.KeepAlive = $false
        $request.Timeout = $Seconds * 1000
        $response = $request.GetResponse()
        $sha1 = [Security.Cryptography.SHA1]::Create()
        try {
            $accept = [Convert]::ToBase64String($sha1.ComputeHash([Text.Encoding]::ASCII.GetBytes(
                $request.Headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')))
        } finally { $sha1.Dispose() }
        if ($response.StatusCode -ne [Net.HttpStatusCode]::SwitchingProtocols -or
            $response.Headers['Upgrade'] -ine 'websocket' -or
            @($response.Headers['Connection'] -split ',' | Where-Object { $_.Trim() -ieq 'Upgrade' }).Count -eq 0 -or
            $response.Headers['Sec-WebSocket-Accept'] -cne $accept -or
            $response.Headers['Sec-WebSocket-Protocol']) {
            throw 'Invalid WebSocket HTTP 101 handshake.'
        }
        $socket = [System.Net.WebSockets.WebSocket]::CreateClientWebSocket(
            $response.GetResponseStream(), [System.Management.Automation.Language.NullString]::Value,
            65536, 65536, [TimeSpan]::FromSeconds(30), $false,
            [System.Net.WebSockets.WebSocket]::CreateClientBuffer(65536, 65536))
    } else {
        $socket = [System.Net.WebSockets.ClientWebSocket]::new()
        $socket.ConnectAsync([uri]$Uri, $timeout.Token).GetAwaiter().GetResult()
    }
    while (-not $timeout.IsCancellationRequested) {
        $message = [IO.MemoryStream]::new()
        try {
            do {
                $result = $socket.ReceiveAsync($segment, $timeout.Token).GetAwaiter().GetResult()
                if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                    throw 'Backend closed the WebSocket before validation completed.'
                }
                $message.Write($buffer, 0, $result.Count)
            } while (-not $result.EndOfMessage)
            $flow = [Text.Encoding]::UTF8.GetString($message.ToArray()) | ConvertFrom-Json
        } finally { $message.Dispose() }
        if ($flow.type -ne 'FLOW') { continue }
        if ($null -eq $flow.timeBasis) { throw 'Missing timeBasis: restart the backend with the updated build.' }
        if ([long]$flow.timeMsc -lt $previous) { throw 'FLOW clock regressed; check for backend restart/configuration change.' }
        $previous = [long]$flow.timeMsc
        if (@($flow.windows).Count -ne 3) { throw 'Expected three FLOW windows.' }
        foreach ($window in $flow.windows) {
            if (([long]$window.toTimeMsc - [long]$window.fromTimeMsc) -ne ([long]$window.seconds * 1000)) {
                throw 'Incorrect window duration.'
            }
            if ([long]$window.toTimeMsc -ne [long]$flow.timeMsc) { throw 'Window clock differs from FLOW clock.' }
            if ($window.events -gt 0 -and ([long]$flow.timeMsc - [long]$flow.lastTimeMsc) -ge ([long]$window.seconds * 1000)) {
                throw 'Expired trades still present in FLOW.'
            }
        }
        $frames++
        if (@($flow.windows | Where-Object { $_.events -gt 0 }).Count -gt 0) { $active++ }
        $second = [long][Math]::Floor($watch.Elapsed.TotalSeconds)
        if ($second -ne $printedSecond) {
            $printedSecond = $second
            $flow | ConvertTo-Json -Depth 8 -Compress
        }
    }
} catch {
    if (-not $timeout.IsCancellationRequested) { throw }
} finally {
    if ($socket) { $socket.Abort(); $socket.Dispose() }
    if ($response) { $response.Dispose() }
    if ($request) { $request.Abort() }
    $timeout.Dispose()
}
if ($frames -eq 0) { throw 'No FLOW snapshots received.' }
Write-Output "Validated $frames FLOW snapshots; $active contained trades. NO_TRADES during idle periods is expected."
Write-Output 'timeBasis.confirmed=false means the feed convention remains unvalidated; activity does not confirm it.'

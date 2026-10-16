<#
.SYNOPSIS
    One place for every command used to run and demo the service.
    Run it with no arguments (or "help") to see every command.

.EXAMPLE
    ./scripts/demo.ps1 run          # everything: Postgres + service + UI, opens the browser
    ./scripts/demo.ps1 stop         # stop the service and the UI
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('help', 'run', 'up', 'down', 'build', 'start', 'ui', 'cluster', 'stop', 'kill', 'health',
                 'book', 'get', 'cancel', 'due', 'seed', 'status', 'appointments', 'reminders',
                 'log', 'logs', 'dupes', 'psql', 'reset', 'test',
                 'chaos', 'track', 'receipt', 'metrics')]
    [string]$Command = 'help',

    [int]$Port = 8080,          # start / kill / health / book / get / cancel
    [int]$Count = 2,            # cluster: instances · seed: reminders
    [int]$BatchSize = 0,        # start / cluster: reminders claimed per poll (0 = app default, 50)
    [int]$LeaseMinutes = 0,     # start / cluster: lower it to demo crash recovery quickly
    [switch]$WorkerOnly,        # start: no HTTP server, just the reminder worker
    [double]$Hours = 48,        # book: how far out the appointment is
    [switch]$Utc,               # book: send the time as Z instead of this machine's offset
    [string]$Contact = '+15550001111',
    [long]$Dealership = 1,
    [long]$Id = 0,              # get / cancel: appointment id · due: reminder id (0 = all pending)
    [string]$Sql = '',          # psql: run this query
    [switch]$All,               # kill: also stop Postgres
    [int]$RetryMinutes = -1,    # start / cluster: wait between attempts (0 = next poll; default 5)
    [int]$DeadlineMinutes = -1, # start / cluster: how long a send may stay unknown before IN_DOUBT (default 30)
    [ValidateSet('DELIVERED', 'UNDELIVERED')]
    [string]$Status = 'DELIVERED', # receipt: what the provider reports
    [switch]$Forge              # receipt: send a wrong signature (expect 401)
)

$ErrorActionPreference = 'Stop'
# docker, mvnw and npm print progress on stderr; Windows PowerShell would treat that as a fatal
# error under 'Stop'. Native commands are judged by their exit code instead (see Invoke-Native).
$PSNativeCommandUseErrorActionPreference = $false
$Root = Split-Path -Parent $PSScriptRoot

function Invoke-Native([scriptblock]$Block, [string]$What) {
    $ErrorActionPreference = 'Continue'
    & $Block 2>&1 | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.ToString() } else { $_ } }
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
}
$Jar = Join-Path $Root 'target\appointment-0.0.1-SNAPSHOT.jar'
$Frontend = Join-Path $Root 'frontend'
$LogDir = Join-Path $Root 'logs'

function Show-Help {
    $rows = @(
        @('START HERE', $null),
        @('run', 'Everything: Postgres, build, UI in the background, service in THIS terminal; Ctrl+C stops both'),
        @('stop', 'Stop the service instance(s) and the UI from another terminal (Postgres keeps running)'),
        @('kill [-All]', 'Force-kill everything: services, UI, anything Java/Node on 8080/3000; -All stops Postgres too'),
        @('', $null),
        @('PIECES', $null),
        @('up / down', 'Start / stop Postgres (docker compose, port 5431)'),
        @('build', 'Build the jar (tests skipped)'),
        @('start', 'One service instance on -Port (default 8080) in this terminal; Ctrl+C stops it'),
        @('ui', 'The UI on http://localhost:3000, in the background (output: logs/ui.log)'),
        @('health', 'Is the service up, and can it reach the database?'),
        @('test', 'Run the test suite (needs Docker; uses a throwaway Postgres)'),
        @('', $null),
        @('API', $null),
        @('book', 'Book an appointment -Hours ahead (default 48); prints both reminders'),
        @('get -Id N', 'Show appointment N with its reminders'),
        @('cancel -Id N', 'Cancel appointment N; its pending reminders become SKIPPED'),
        @('', $null),
        @('DATA', $null),
        @('due [-Id N]', 'Make pending reminder N (or all) due now; the next poll sends it'),
        @('seed -Count N', 'Insert N appointments whose reminders are already due'),
        @('status', 'Reminders by status, plus delivered vs distinct reminders'),
        @('appointments', 'Latest 20 appointments'),
        @('reminders', 'Latest 20 reminders with their lease and retry columns'),
        @('log', 'Latest 20 notification_log rows (SENDING / SENT, provider message id)'),
        @('logs [-Port P]', 'Follow a service log file live (Ctrl+C to stop)'),
        @('dupes', 'Duplicate check: both queries must return no rows'),
        @('psql [-Sql "..."]', 'Open psql, or run one query'),
        @('reset', 'Delete all data (asks first)'),
        @('', $null),
        @('UNRELIABLE PROVIDER DEMO', $null),
        @('start -RetryMinutes 0 -DeadlineMinutes 0 -LeaseMinutes 1', 'Start with no waits: every case plays out in about a minute'),
        @('chaos', 'Book 4 appointments whose reminders go out in ~30 s: normal, reply lost, provider down, invalid number'),
        @('track', 'One row per reminder: contact, reminder status, attempts, delivery status, last error'),
        @('receipt -Id N', 'Signed webhook receipt for reminder N (-Status DELIVERED|UNDELIVERED, -Forge for a bad signature)'),
        @('metrics', 'reminders.outcome counters: sent, unknown, rejected, probe_*, in_doubt, receipt_*'),
        @('', $null),
        @('CRASH DEMO', $null),
        @('cluster -Count N', 'N background instances on 8080, 8081, ... sharing the work through SKIP LOCKED'),
        @('kill -Port P', 'Hard-kill one instance mid-run; its claimed reminders recover after the lease')
    )
    Write-Host ''
    Write-Host '  Appointment Reminder Service - demo script' -ForegroundColor White
    Write-Host '  Usage: ./scripts/demo.ps1 <command> [options]' -ForegroundColor DarkGray
    foreach ($r in $rows) {
        if ($null -eq $r[1]) {
            if ($r[0]) { Write-Host ''; Write-Host "  $($r[0])" -ForegroundColor Cyan }
            continue
        }
        Write-Host ('    {0,-20}' -f $r[0]) -ForegroundColor Green -NoNewline
        Write-Host $r[1]
    }
    Write-Host ''
    Write-Host '  Options: -Port -Count -Id -Hours -Contact -Dealership -BatchSize -LeaseMinutes -RetryMinutes -DeadlineMinutes -Status -Forge -WorkerOnly -Utc -Sql -All' -ForegroundColor DarkGray
    Write-Host ''
}

function Use-Java {
    # java may not be on PATH (an IDE-downloaded JDK); mvnw needs JAVA_HOME either way.
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    $onPath = Get-Command java -ErrorAction SilentlyContinue
    if ($onPath) {
        return $onPath.Source
    }
    $jdk = Get-ChildItem (Join-Path $HOME '.jdks') -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) {
        throw 'No JDK found. Install JDK 21+ or set JAVA_HOME.'
    }
    $env:JAVA_HOME = $jdk.FullName
    return Join-Path $jdk.FullName 'bin\java.exe'
}

function Invoke-Sql([string]$Query) {
    Push-Location $Root
    try {
        Invoke-Native { docker compose exec -T postgres psql -U postgres -d appointments -c $Query } 'psql'
    } finally {
        Pop-Location
    }
}

function Start-Postgres {
    Push-Location $Root
    try {
        Invoke-Native { docker compose up -d } 'docker compose up'
        Write-Host 'Waiting for Postgres...' -NoNewline
        for ($i = 0; $i -lt 60; $i++) {
            $ErrorActionPreference = 'Continue'
            docker compose exec -T postgres pg_isready -U postgres -d appointments 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) { Write-Host ' ready'; return }
            Start-Sleep 2; Write-Host '.' -NoNewline
        }
        throw 'Postgres did not become ready'
    } finally {
        Pop-Location
    }
}

function Build-Jar {
    Use-Java | Out-Null
    Write-Host 'Building the jar (tests skipped)...'
    Push-Location $Root
    try { Invoke-Native { & .\mvnw.cmd -q package -DskipTests } 'Build' } finally { Pop-Location }
    Write-Host "Built $Jar"
}

function Get-Instances {
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like '*appointment-0.0.1-SNAPSHOT.jar*' }
}

function Get-UiProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" |
        Where-Object { $_.CommandLine -like "*$Frontend*" -or $_.CommandLine -like '*next*dev*-p 3000*' }
}

function Get-InstanceArgs([int]$OnPort, [switch]$NoWeb) {
    # The UI tails logs/app.log, so the default instance writes there; extra instances get their own file.
    $logFile = if ($OnPort -eq 8080 -and -not $NoWeb) { 'logs/app.log' } else { "logs/app-$OnPort.log" }
    $appArgs = @("--logging.file.name=$logFile")
    if ($NoWeb) { $appArgs += '--spring.main.web-application-type=none' } else { $appArgs += "--server.port=$OnPort" }
    if ($BatchSize -gt 0) { $appArgs += "--reminders.batch-size=$BatchSize" }
    if ($LeaseMinutes -gt 0) { $appArgs += "--reminders.lease-minutes=$LeaseMinutes" }
    if ($RetryMinutes -ge 0) { $appArgs += "--reminders.retry-delay-minutes=$RetryMinutes" }
    if ($DeadlineMinutes -ge 0) { $appArgs += "--reminders.unknown-deadline-minutes=$DeadlineMinutes" }
    return $appArgs
}

function Assert-Jar {
    if (-not (Test-Path $Jar)) { throw "No jar at $Jar - run: ./scripts/demo.ps1 build" }
    New-Item -ItemType Directory -Force $LogDir | Out-Null
}

# Runs one instance in THIS terminal: its log scrolls here, and Ctrl+C stops it.
function Start-InstanceHere([int]$OnPort, [switch]$NoWeb) {
    Assert-Jar
    $java = Use-Java
    $appArgs = Get-InstanceArgs -OnPort $OnPort -NoWeb:$NoWeb
    Write-Host "Starting the service on :$OnPort  ($($appArgs -join ' ')) - Ctrl+C to stop" -ForegroundColor Cyan
    Push-Location $Root
    try {
        $ErrorActionPreference = 'Continue'
        & $java -jar $Jar @appArgs
    } finally {
        Pop-Location
    }
}

# Runs one instance with no window and no console output (cluster demo); its log goes to logs/.
function Start-InstanceHidden([int]$OnPort) {
    Assert-Jar
    $java = Use-Java
    $appArgs = Get-InstanceArgs -OnPort $OnPort
    Start-Process -FilePath $java -ArgumentList (@('-jar', "`"$Jar`"") + $appArgs) -WorkingDirectory $Root `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $LogDir "console-$OnPort.log") `
        -RedirectStandardError (Join-Path $LogDir "console-$OnPort.err.log") | Out-Null
    Write-Host "Started instance :$OnPort in the background (log: $($appArgs[0] -replace '--logging.file.name=', ''))"
}

function Start-Ui {
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
        throw 'npm not found. Install Node.js 20+ to run the UI.'
    }
    if (-not (Test-Path (Join-Path $Frontend 'package.json'))) {
        throw "No UI at $Frontend"
    }
    if (-not (Test-Path (Join-Path $Frontend 'node_modules'))) {
        Write-Host 'Installing UI packages (first run only)...'
        Push-Location $Frontend
        try { Invoke-Native { npm install --no-fund --no-audit } 'npm install' } finally { Pop-Location }
    }
    # No window, and its output goes to logs/ui.log rather than this terminal.
    New-Item -ItemType Directory -Force $LogDir | Out-Null
    $npm = (Get-Command npm.cmd).Source
    Start-Process -FilePath $npm -ArgumentList 'run', 'dev' -WorkingDirectory $Frontend -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $LogDir 'ui.log') -RedirectStandardError (Join-Path $LogDir 'ui.err.log') | Out-Null
    Write-Host 'Started the UI in the background on http://localhost:3000 (its output: logs/ui.log)'
}

function Stop-Ui {
    @(Get-UiProcesses) | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

# Opens the browser once the service answers, without blocking this terminal.
function Open-BrowserWhenReady {
    Start-Job -ScriptBlock {
        for ($i = 0; $i -lt 120; $i++) {
            try {
                if ((Invoke-RestMethod 'http://localhost:8080/actuator/health' -TimeoutSec 2).status -eq 'UP') {
                    Start-Sleep 3
                    Start-Process 'http://localhost:3000'
                    return
                }
            } catch { }
            Start-Sleep 1
        }
    } | Out-Null
}

function Get-BaseUrl { "http://localhost:$Port" }

switch ($Command) {
    'help' {
        Show-Help
    }

    # ---------------------------------------------------------------- one command
    'run' {
        Start-Postgres
        Build-Jar
        if (Get-Instances) {
            Write-Host 'A service instance is already running - restarting it with the new build'
            Get-Instances | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
            Start-Sleep 2
        }
        if (-not (Get-UiProcesses)) { Start-Ui } else { Write-Host 'UI already running' }
        Open-BrowserWhenReady
        Write-Host ''
        Write-Host '  UI    http://localhost:3000  (opens in your browser once the service is up)' -ForegroundColor Green
        Write-Host '  API   http://localhost:8080/appointments' -ForegroundColor Green
        Write-Host '  Ctrl+C stops the service and the UI' -ForegroundColor Green
        Write-Host ''
        try {
            Start-InstanceHere -OnPort 8080
        } finally {
            # Ctrl+C lands here too: take the background UI down with the service.
            Stop-Ui
            Write-Host 'Stopped the service and the UI. Postgres is still up (./scripts/demo.ps1 down).' -ForegroundColor Yellow
        }
    }

    # ---------------------------------------------------------------- infrastructure
    'up' {
        Start-Postgres
    }
    'down' {
        Push-Location $Root
        try { Invoke-Native { docker compose down } 'docker compose down' } finally { Pop-Location }
    }
    'build' {
        Build-Jar
    }
    'test' {
        Use-Java | Out-Null
        Push-Location $Root
        try { Invoke-Native { & .\mvnw.cmd test } 'Tests' } finally { Pop-Location }
    }

    # ---------------------------------------------------------------- instances
    'start' {
        Start-InstanceHere -OnPort $Port -NoWeb:$WorkerOnly
    }
    'ui' {
        Start-Ui
    }
    'cluster' {
        # Every instance runs the same worker; SKIP LOCKED hands each a disjoint batch.
        for ($i = 0; $i -lt $Count; $i++) {
            Start-InstanceHidden -OnPort (8080 + $i)
        }
        Write-Host 'Follow one with: ./scripts/demo.ps1 logs -Port 8081   Stop all with: ./scripts/demo.ps1 stop'
    }
    'stop' {
        $procs = @(Get-Instances)
        $procs | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
        $hadUi = @(Get-UiProcesses).Count -gt 0
        Stop-Ui
        Write-Host "Stopped $($procs.Count) instance(s) and $(if ($hadUi) { 'the UI' } else { 'no UI' }). Postgres is still up (./scripts/demo.ps1 down)."
    }
    'kill' {
        if ($PSBoundParameters.ContainsKey('Port')) {
            # Crash demo: a hard kill of one instance, like kill -9. Its claimed rows stay PROCESSING.
            $target = Get-Instances | Where-Object { $_.CommandLine -like "*--server.port=$Port*" }
            if (-not $target) { throw "No instance running on :$Port" }
            $target | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
            Write-Host "Killed :$Port - its PROCESSING rows go back to PENDING once the lease expires"
            return
        }

        # Everything this project runs, whichever terminal started it.
        $killed = @()
        Get-Instances | ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            $killed += "service (pid $($_.ProcessId))"
        }
        Get-UiProcesses | ForEach-Object {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            $killed += "UI (pid $($_.ProcessId))"
        }
        # Anything still holding our ports - but only Java or Node, never an unrelated program.
        foreach ($p in 8080, 3000) {
            Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
                Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {
                    $proc = Get-Process -Id $_ -ErrorAction SilentlyContinue
                    if ($proc -and $proc.ProcessName -in 'java', 'node') {
                        Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
                        $killed += "$($proc.ProcessName) on :$p (pid $_)"
                    } elseif ($proc) {
                        Write-Host "Port $p is held by $($proc.ProcessName) (pid $_) - not ours, left alone" -ForegroundColor Yellow
                    }
                }
        }
        if ($All) {
            Push-Location $Root
            try { Invoke-Native { docker compose stop } 'docker compose stop' } finally { Pop-Location }
            $killed += 'Postgres container (stopped, data kept)'
        }
        if ($killed.Count) { $killed | ForEach-Object { Write-Host "Killed $_" } } else { Write-Host 'Nothing was running' }
        if (-not $All) { Write-Host 'Postgres is still up (add -All to stop it too)' -ForegroundColor DarkGray }
    }
    'health' {
        Invoke-RestMethod "$(Get-BaseUrl)/actuator/health" | ConvertTo-Json
    }

    # ---------------------------------------------------------------- API
    'book' {
        $at = [DateTimeOffset]::Now.AddHours($Hours)
        if ($Utc) {
            $scheduledAt = $at.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
        } else {
            $scheduledAt = $at.ToString('yyyy-MM-ddTHH:mm:sszzz')
        }
        $body = @{ dealershipId = $Dealership; customerContact = $Contact; scheduledAt = $scheduledAt } |
            ConvertTo-Json
        Write-Host "POST /appointments  $body"
        Invoke-RestMethod -Method Post -Uri "$(Get-BaseUrl)/appointments" -ContentType 'application/json' -Body $body |
            ConvertTo-Json -Depth 5
    }
    'get' {
        if ($Id -le 0) { throw 'Pass -Id <appointment id>' }
        Invoke-RestMethod "$(Get-BaseUrl)/appointments/$Id" | ConvertTo-Json -Depth 5
    }
    'cancel' {
        if ($Id -le 0) { throw 'Pass -Id <appointment id>' }
        Invoke-RestMethod -Method Post "$(Get-BaseUrl)/appointments/$Id/cancel" | ConvertTo-Json -Depth 5
    }

    # ---------------------------------------------------------------- data
    'due' {
        # Pull send times into the past so the next poll (within 15 s) sends them.
        if ($Id -gt 0) {
            Invoke-Sql "UPDATE reminders SET scheduled_at = now() - interval '1 minute' WHERE id = $Id AND status = 'PENDING';"
        } else {
            Invoke-Sql "UPDATE reminders SET scheduled_at = now() - interval '1 minute' WHERE status = 'PENDING';"
        }
    }
    'seed' {
        # $Count appointments two days out, each with a 2h reminder that is already due.
        Invoke-Sql @"
WITH booked AS (
    INSERT INTO appointments (dealership_id, customer_contact, scheduled_at, status)
    SELECT (i % 500) + 1, '+1555' || lpad(i::text, 7, '0'), now() + interval '2 days', 'SCHEDULED'
    FROM generate_series(1, $Count) i
    RETURNING id
)
INSERT INTO reminders (appointment_id, reminder_type, scheduled_at, status)
SELECT id, 'TWO_HOURS', now() - interval '1 minute', 'PENDING' FROM booked;
"@
    }
    'status' {
        Invoke-Sql "SELECT status, count(*) FROM reminders GROUP BY status ORDER BY status;"
        Invoke-Sql "SELECT count(*) AS delivered, count(DISTINCT reminder_id) AS distinct_reminders FROM notification_log WHERE status = 'SENT';"
    }
    'appointments' {
        Invoke-Sql "SELECT id, dealership_id, customer_contact, scheduled_at, status FROM appointments ORDER BY id DESC LIMIT 20;"
    }
    'reminders' {
        Invoke-Sql @"
SELECT id, appointment_id, reminder_type, scheduled_at, status, attempt_count,
       processing_started_at, next_attempt_at, sent_at
FROM reminders ORDER BY id DESC LIMIT 20;
"@
    }
    'log' {
        Invoke-Sql "SELECT reminder_id, status, provider_message_id, created_at, sent_at FROM notification_log ORDER BY id DESC LIMIT 20;"
    }
    'logs' {
        $file = Join-Path $LogDir $(if ($Port -eq 8080) { 'app.log' } else { "app-$Port.log" })
        if (-not (Test-Path $file)) { throw "No log at $file - start the service with ./scripts/demo.ps1 start (or run)" }
        Get-Content $file -Tail 30 -Wait
    }
    'dupes' {
        # Both queries must return no rows - backed by unique constraints.
        Write-Host 'Reminders delivered more than once (must be empty):'
        Invoke-Sql "SELECT reminder_id, count(*) FROM notification_log GROUP BY reminder_id HAVING count(*) > 1;"
        Write-Host 'Appointments with two reminders of one type (must be empty):'
        Invoke-Sql "SELECT appointment_id, reminder_type, count(*) FROM reminders GROUP BY appointment_id, reminder_type HAVING count(*) > 1;"
    }
    'psql' {
        if ($Sql) {
            Invoke-Sql $Sql
        } else {
            Push-Location $Root
            $ErrorActionPreference = 'Continue'
            try { docker compose exec postgres psql -U postgres -d appointments } finally { Pop-Location }
        }
    }
    'reset' {
        $answer = Read-Host 'Delete ALL appointments, reminders and delivery records in the local database? (yes/no)'
        if ($answer -ne 'yes') {
            Write-Host 'Nothing deleted.'
            return
        }
        Invoke-Sql "TRUNCATE notification_log, reminders, appointments RESTART IDENTITY CASCADE;"
    }
    # ---------------------------------------------------------------- unreliable provider demo
    'chaos' {
        # The stub provider picks its behaviour from the contact's last four digits.
        $cases = @(
            @('+15550001111', 'normal: sent first time'),
            @('+15550000000', 'reply lost: sent, but we hear nothing -> UNKNOWN, probe finds it'),
            @('+15557777777', 'provider down: no answer, probes fail -> IN_DOUBT + ALERT'),
            @('+15559999999', 'invalid number: rejected for good -> FAILED, no retry')
        )
        $at = [DateTimeOffset]::Now.AddHours(2).AddSeconds(30).ToString('yyyy-MM-ddTHH:mm:sszzz')
        foreach ($c in $cases) {
            $body = @{ dealershipId = $Dealership; customerContact = $c[0]; scheduledAt = $at } | ConvertTo-Json
            $appt = Invoke-RestMethod -Method Post -Uri "$(Get-BaseUrl)/appointments" -ContentType 'application/json' -Body $body
            Write-Host ('  appointment {0,-4} {1}  {2}' -f $appt.id, $c[0], $c[1])
        }
        Write-Host 'Their 2-hour reminders go out in about 30 seconds. Watch the service log, then: ./scripts/demo.ps1 track' -ForegroundColor Cyan
    }
    'track' {
        Invoke-Sql @"
SELECT r.id AS reminder, a.customer_contact AS contact, r.status AS reminder_status,
       r.attempt_count AS attempts, n.status AS delivery, n.provider_message_id, n.last_error
FROM reminders r
JOIN appointments a ON a.id = r.appointment_id
LEFT JOIN notification_log n ON n.reminder_id = r.id
WHERE r.reminder_type = 'TWO_HOURS'
ORDER BY r.id DESC LIMIT 20;
"@
    }
    'receipt' {
        if ($Id -le 0) { throw 'Pass -Id <reminder id> (see: ./scripts/demo.ps1 track)' }
        $key = "reminder-$Id"
        $secret = if ($env:WEBHOOK_SECRET) { $env:WEBHOOK_SECRET } else { 'dev-webhook-secret' }
        if ($Forge) {
            $sig = 'deadbeef'
        } else {
            # What the provider would sign: key | provider message id (none here) | status.
            $hmac = New-Object System.Security.Cryptography.HMACSHA256 (, [Text.Encoding]::UTF8.GetBytes($secret))
            $sig = -join ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes("$key||$Status")) | ForEach-Object { $_.ToString('x2') })
        }
        $body = @{ idempotencyKey = $key; status = $Status } | ConvertTo-Json -Compress
        Write-Host "POST /webhooks/delivery-status  $body  X-Signature: $sig"
        try {
            Invoke-RestMethod -Method Post -Uri "$(Get-BaseUrl)/webhooks/delivery-status" -ContentType 'application/json' `
                -Headers @{ 'X-Signature' = $sig } -Body $body | ConvertTo-Json
        } catch {
            Write-Host "-> $([int]$_.Exception.Response.StatusCode) $($_.Exception.Response.StatusCode)" -ForegroundColor Yellow
        }
    }
    'metrics' {
        $metric = Invoke-RestMethod "$(Get-BaseUrl)/actuator/metrics/reminders.outcome"
        $outcomes = ($metric.availableTags | Where-Object { $_.tag -eq 'outcome' }).values
        if (-not $outcomes) { Write-Host 'No outcomes counted yet.'; return }
        foreach ($o in ($outcomes | Sort-Object)) {
            $n = (Invoke-RestMethod "$(Get-BaseUrl)/actuator/metrics/reminders.outcome?tag=outcome:$o").measurements[0].value
            Write-Host ('  {0,-22} {1}' -f $o, $n)
        }
    }
}

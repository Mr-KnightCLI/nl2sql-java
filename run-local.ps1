<#
Build and run nl2sql (Java) on Windows.

Settings come from nl2sql.properties in this folder, plus nl2sql.env in this folder if present (NL2SQL_* variables,
which override the properties file). Use -EnvFile to load a different env file.

  powershell -ExecutionPolicy Bypass -File .\run-local.ps1                       # build if needed, then start the server
  powershell -ExecutionPolicy Bypass -File .\run-local.ps1 -EnvFile .\my.env
  powershell -ExecutionPolicy Bypass -File .\run-local.ps1 -Rebuild -Test        # rebuild and run the test suite first
  powershell -ExecutionPolicy Bypass -File .\run-local.ps1 -Ask "How many shipments are in transit?"   # second window

If Java 21+ or Maven are missing, portable copies are downloaded into .tools\ (no admin rights, nothing installed system-wide).
#>
param(
    [string]$EnvFile = "",
    [switch]$Rebuild,
    [switch]$Test,
    [string]$Ask = "",
    [int]$Port = 0
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # makes Invoke-WebRequest much faster
$Root = $PSScriptRoot
Set-Location $Root
function Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }

# ---------------------------------------------------------------- env file
if ($EnvFile -eq "none") {
    $EnvFile = ""
} elseif (-not $EnvFile) {
    foreach ($c in @("$Root\nl2sql.env")) {   # only this project's folder; use -EnvFile for anything else
        if (Test-Path $c) { $EnvFile = $c; break }
    }
}
if ($EnvFile) {
    $EnvFile = (Resolve-Path $EnvFile).Path
    $n = 0
    foreach ($line in Get-Content $EnvFile) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith("#") -or -not $t.Contains("=")) { continue }
        $k, $v = $t -split "=", 2
        $k = $k.Trim() -replace "^export\s+", ""
        $v = $v.Trim()
        if ($v -match '^"(.*)"$' -or $v -match "^'(.*)'$") { $v = $Matches[1] } else { $v = $v -replace '\s+#.*$', '' }
        if ($k -like "NL2SQL_*" -and $v) { Set-Item -Path "Env:$k" -Value $v; $n++ }
    }
    Write-Host "Loaded $n settings from $EnvFile"
} elseif (-not (Test-Path "$Root\nl2sql.properties")) {
    Write-Warning "No env file or nl2sql.properties found; relying on environment variables already set."
}
if ($Port -gt 0) { $env:NL2SQL_SERVER_PORT = "$Port" }
$ServerPort = if ($env:NL2SQL_SERVER_PORT) { $env:NL2SQL_SERVER_PORT } else { "8080" }

# ---------------------------------------------------------------- -Ask: call a running server
if ($Ask) {
    $headers = @{}
    if ($env:NL2SQL_SERVER_API_KEY) { $headers["X-API-Key"] = $env:NL2SQL_SERVER_API_KEY }
    $body = @{ question = $Ask } | ConvertTo-Json
    $r = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$ServerPort/v1/ask" -Headers $headers `
        -ContentType "application/json" -Body $body
    if ($r.clarification) { Write-Host "Clarification needed: $($r.clarification)" -ForegroundColor Yellow }
    if ($r.error) { Write-Host "Error: $($r.error)" -ForegroundColor Red }
    if ($r.sql) { Write-Host "`nSQL:`n$($r.sql)`n" -ForegroundColor Green; Write-Host $r.explanation }
    if ($r.columns) {
        $rows = foreach ($row in $r.rows) {
            $o = [ordered]@{}
            for ($i = 0; $i -lt $r.columns.Count; $i++) { $o[$r.columns[$i]] = $row[$i] }
            [pscustomobject]$o
        }
        $rows | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
        Write-Host "$($r.row_count) row(s)$(if ($r.truncated) { ' (truncated)' }) in $($r.elapsed_ms) ms"
    }
    exit 0
}

# ---------------------------------------------------------------- Java 21 and Maven
$Tools = "$Root\.tools"
function JavaMajor($javaExe) {
    try {
        $out = & cmd /c "`"$javaExe`" -version 2>&1"
        if ("$out" -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}
$java = (Get-Command java -ErrorAction SilentlyContinue).Source
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { $java = "$env:JAVA_HOME\bin\java.exe" }
if (-not $java -or (JavaMajor $java) -lt 21) {
    $jdk = Get-ChildItem "$Tools" -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jdk) {
        Step "Java 21 not found; downloading a portable Temurin JDK 21 into .tools (about 200 MB)"
        New-Item -ItemType Directory -Force $Tools | Out-Null
        Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile "$Tools\jdk21.zip" -UseBasicParsing
        Expand-Archive "$Tools\jdk21.zip" -DestinationPath $Tools -Force
        Remove-Item "$Tools\jdk21.zip"
        $jdk = Get-ChildItem "$Tools" -Directory -Filter "jdk-21*" | Select-Object -First 1
    }
    $env:JAVA_HOME = $jdk.FullName
    $java = "$env:JAVA_HOME\bin\java.exe"
} elseif (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = Split-Path (Split-Path $java)
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Write-Host "Java: $java (version $(JavaMajor $java))"

$Jar = Get-ChildItem "$Root\nl2sql-server\target" -Filter "nl2sql-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
if ($Rebuild -or $Test -or -not $Jar) {
    $mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
    if (-not $mvn) {
        $mvnHome = "$Tools\apache-maven-3.9.11"
        if (-not (Test-Path "$mvnHome\bin\mvn.cmd")) {
            Step "Maven not found; downloading a portable Maven 3.9.11 into .tools"
            New-Item -ItemType Directory -Force $Tools | Out-Null
            Invoke-WebRequest "https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.zip" -OutFile "$Tools\maven.zip" -UseBasicParsing
            Expand-Archive "$Tools\maven.zip" -DestinationPath $Tools -Force
            Remove-Item "$Tools\maven.zip"
        }
        $mvn = "$mvnHome\bin\mvn.cmd"
    }
    Step "Building (first build downloads dependencies, a few minutes)"
    $mvnArgs = @("-B", "-ntp", "package")
    if (-not $Test) { $mvnArgs += "-DskipTests" }
    & $mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit code $LASTEXITCODE). Scroll up for the first [ERROR] line." }
    $Jar = Get-ChildItem "$Root\nl2sql-server\target" -Filter "nl2sql-*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
}

# ---------------------------------------------------------------- run
Step "Starting nl2sql on http://127.0.0.1:$ServerPort  (Ctrl+C to stop)"
Write-Host "Try it from a second PowerShell window in this folder:"
Write-Host "  powershell -ExecutionPolicy Bypass -File .\run-local.ps1 -Ask `"How many shipments are there?`"" -ForegroundColor Yellow
if ($EnvFile -and $env:NL2SQL_SEMANTICS_PATH -and -not [IO.Path]::IsPathRooted($env:NL2SQL_SEMANTICS_PATH)) {
    Set-Location (Split-Path $EnvFile)   # relative paths in the env file are relative to its folder
}
& $java -jar $Jar.FullName

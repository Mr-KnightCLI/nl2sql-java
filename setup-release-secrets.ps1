<#
One-time Maven Central release setup for alok26sinha/nl2sql-java. Run it yourself; it asks for every secret and
never prints or stores them.

  powershell -ExecutionPolicy Bypass -File .\setup-release-secrets.ps1

What it does:
  1. creates a GPG signing key (or reuses one you already have for the same e-mail)
  2. publishes the public key to keyserver.ubuntu.com (Maven Central checks signatures there)
  3. stores GPG_PRIVATE_KEY, GPG_PASSPHRASE, CENTRAL_USERNAME and CENTRAL_PASSWORD as GitHub Actions secrets
  4. re-runs the last failed "Release" workflow

Needs: Git for Windows (includes gpg) and the GitHub CLI:  winget install GitHub.cli   then   gh auth login
Before running: sign in at https://central.sonatype.com with GitHub and generate a user token (Account > Generate User Token).
#>
param([string]$Repo = "alok26sinha/nl2sql-java")

$ErrorActionPreference = "Stop"
function Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Plain([Security.SecureString]$s) { [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)) }

# ---------------------------------------------------------------- tools
$gpg = (Get-Command gpg -ErrorAction SilentlyContinue).Source
if (-not $gpg) {
    foreach ($c in @("$env:ProgramFiles\Git\usr\bin\gpg.exe", "${env:ProgramFiles(x86)}\GnuPG\bin\gpg.exe", "$env:ProgramFiles\GnuPG\bin\gpg.exe")) {
        if (Test-Path $c) { $gpg = $c; break }
    }
}
if (-not $gpg) { throw "gpg not found. Install Git for Windows (includes gpg) or Gpg4win, then run this again." }
$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { throw "GitHub CLI not found. Run:  winget install GitHub.cli   then open a new PowerShell window and run:  gh auth login" }
& $gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI is not signed in. Run:  gh auth login" }
Write-Host "gpg: $gpg"
Write-Host "gh:  $gh"

$tmp = Join-Path $env:TEMP ("nl2sql-release-" + [guid]::NewGuid())
New-Item -ItemType Directory $tmp | Out-Null
try {
    # ------------------------------------------------------------ 1. key
    Step "GPG signing key"
    $name = Read-Host "Your name for the key (e.g. Alok Sinha)"
    $email = Read-Host "Your e-mail for the key"
    $existing = & $gpg --batch --with-colons --list-secret-keys $email 2>$null | Where-Object { $_ -like "fpr:*" } | Select-Object -First 1
    $reuse = $false
    if ($existing) { $reuse = (Read-Host "A key for $email already exists. Reuse it? (y/n)") -eq "y" }

    $p1 = Plain (Read-Host -AsSecureString ($(if ($reuse) { "Passphrase of that key" } else { "Choose a passphrase for the new key" })))
    if (-not $reuse) {
        $p2 = Plain (Read-Host -AsSecureString "Repeat the passphrase")
        if ($p1 -ne $p2) { throw "The passphrases do not match." }
        if ($p1.Length -lt 8) { throw "Use a passphrase of at least 8 characters." }
    }
    $passFile = Join-Path $tmp "p"
    [IO.File]::WriteAllText($passFile, $p1)   # no trailing newline, so Windows line endings cannot creep in

    if (-not $reuse) {
        & $gpg --batch --pinentry-mode loopback --passphrase-file $passFile --quick-generate-key "$name <$email>" rsa4096 sign 3y
        if ($LASTEXITCODE -ne 0) { throw "Key generation failed." }
    }
    $fpr = (& $gpg --batch --with-colons --list-secret-keys $email | Where-Object { $_ -like "fpr:*" } | Select-Object -First 1).Split(":")[9]
    Write-Host "Key fingerprint: $fpr"

    # check the passphrase really unlocks the key (signs a test message)
    "test" | Out-File -Encoding ascii (Join-Path $tmp "t.txt")
    & $gpg --batch --yes --pinentry-mode loopback --passphrase-file $passFile -u $fpr --detach-sign (Join-Path $tmp "t.txt") 2>$null
    if ($LASTEXITCODE -ne 0) { throw "That passphrase does not unlock the key." }

    # ------------------------------------------------------------ 2. public key to keyservers
    Step "Publishing the public key"
    $sent = $false
    foreach ($ks in @("hkps://keyserver.ubuntu.com", "hkps://keys.openpgp.org")) {
        & $gpg --keyserver $ks --send-keys $fpr
        if ($LASTEXITCODE -eq 0) { Write-Host "Sent to $ks"; $sent = $true }
    }
    if (-not $sent) {
        $pub = Join-Path $PSScriptRoot "public-key.asc"
        & $gpg --armor --export $fpr | Out-File -Encoding ascii $pub
        Write-Warning "Could not reach the keyservers from gpg. Upload $pub by hand: open https://keyserver.ubuntu.com/#submitKey, paste the file's contents and press Submit."
        Read-Host "Press Enter once it is uploaded"
    }

    # ------------------------------------------------------------ 3. GitHub secrets
    Step "Storing GitHub Actions secrets in $Repo"
    $keyFile = Join-Path $tmp "k.asc"
    & $gpg --batch --pinentry-mode loopback --passphrase-file $passFile --armor --export-secret-keys $fpr | Out-File -Encoding ascii $keyFile
    $armored = Get-Content $keyFile -Raw
    if ($armored -notmatch "BEGIN PGP PRIVATE KEY BLOCK") { throw "Exporting the private key failed." }
    & $gh secret set GPG_PRIVATE_KEY --repo $Repo --body $armored;  if ($LASTEXITCODE -ne 0) { throw "gh secret set GPG_PRIVATE_KEY failed" }
    & $gh secret set GPG_PASSPHRASE --repo $Repo --body $p1;        if ($LASTEXITCODE -ne 0) { throw "gh secret set GPG_PASSPHRASE failed" }

    Write-Host "`nNow the Central Portal user token (https://central.sonatype.com > Account > Generate User Token)."
    Write-Host "Paste the token's username and password, not your login."
    $cu = Read-Host "Token username"
    $cp = Plain (Read-Host -AsSecureString "Token password")
    if (-not $cu -or -not $cp) { throw "Token username and password are both required." }
    & $gh secret set CENTRAL_USERNAME --repo $Repo --body $cu;  if ($LASTEXITCODE -ne 0) { throw "gh secret set CENTRAL_USERNAME failed" }
    & $gh secret set CENTRAL_PASSWORD --repo $Repo --body $cp;  if ($LASTEXITCODE -ne 0) { throw "gh secret set CENTRAL_PASSWORD failed" }
    & $gh secret list --repo $Repo

    # ------------------------------------------------------------ 4. re-run the release
    Step "Re-running the last failed Release workflow"
    $run = & $gh run list --repo $Repo --workflow release.yml --status failure --limit 1 --json databaseId --jq ".[0].databaseId"
    if ($run) {
        & $gh run rerun $run --repo $Repo
        Write-Host "Re-run started: https://github.com/$Repo/actions/runs/$run"
    } else {
        Write-Host "No failed release run found. Push a tag to release:  git tag v0.1.0; git push origin v0.1.0"
    }
    Write-Host "`nDone. When the run finishes, open https://central.sonatype.com/publishing and press Publish." -ForegroundColor Green
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue   # temporary passphrase and key copies
}

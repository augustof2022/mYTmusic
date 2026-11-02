param([string]$GitHubCliPath = '')

$ErrorActionPreference = 'Stop'
$Repo = 'augustof2022/mYTmusic'
$Report = Join-Path $PSScriptRoot 'SETUP-STATUS.txt'
$Stage = 'START'
$SafeHelp = 'Extract all files into a folder you can write to, then run START-WINDOWS.cmd again.'

function Write-Status([string]$Message) {
    Write-Host $Message
    Add-Content -LiteralPath $Report -Value $Message -Encoding UTF8
}

function Find-GitHubCli {
    if ($GitHubCliPath) {
        if (Test-Path -LiteralPath $GitHubCliPath -PathType Leaf) { return $GitHubCliPath }
        return $null
    }
    $Command = Get-Command gh.exe -ErrorAction SilentlyContinue
    if ($Command) { return $Command.Source }
    foreach ($Base in @($env:ProgramFiles, $env:LOCALAPPDATA)) {
        if (-not $Base) { continue }
        foreach ($Relative in @('GitHub CLI\gh.exe', 'Programs\GitHub CLI\gh.exe')) {
            $Candidate = Join-Path $Base $Relative
            if (Test-Path -LiteralPath $Candidate -PathType Leaf) { return $Candidate }
        }
    }
    return $null
}

# Capture native stderr outside PowerShell's error pipeline (Windows PowerShell
# 5.1 otherwise treats some CLI diagnostics as terminating errors). Never put
# CLI output, secret values, or raw exception messages in the status report.
function Invoke-GitHub([string]$Arguments, [string]$InputValue = $null) {
    $Start = New-Object Diagnostics.ProcessStartInfo
    $Start.FileName = $script:Gh
    $Start.Arguments = $Arguments
    $Start.UseShellExecute = $false
    $Start.CreateNoWindow = $true
    $Start.RedirectStandardInput = $true
    $Start.RedirectStandardOutput = $true
    $Start.RedirectStandardError = $true
    $Process = [Diagnostics.Process]::Start($Start)
    try {
        $OutputTask = $Process.StandardOutput.ReadToEndAsync()
        $ErrorTask = $Process.StandardError.ReadToEndAsync()
        if ($null -ne $InputValue) { $Process.StandardInput.Write($InputValue) }
        $Process.StandardInput.Close()
        $Process.WaitForExit()
        $OutputText = $OutputTask.GetAwaiter().GetResult()
        $null = $ErrorTask.GetAwaiter().GetResult()
        return [pscustomobject]@{ Code = $Process.ExitCode; Text = $OutputText }
    } finally {
        $Process.Dispose()
    }
}

try {
    Set-Content -LiteralPath $Report -Value "Segue signing setup`r`nRepository: $Repo" -Encoding UTF8
    Write-Status 'This report contains setup steps and secret NAMES only, never private signing values.'
    $Stage = 'PRIVATE_FILES'
    $SafeHelp = 'Right-click the private ZIP, choose Extract All, then run START-WINDOWS.cmd in the extracted folder. Keep signing.json and segue-release.jks beside it.'
    $KeyPath = Join-Path $PSScriptRoot 'segue-release.jks'
    $ConfigPath = Join-Path $PSScriptRoot 'signing.json'
    if (-not (Test-Path -LiteralPath $KeyPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) { throw 'Missing private files' }
    $Config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    foreach ($Field in @('store_password', 'alias', 'key_password')) {
        if ([string]::IsNullOrWhiteSpace([string]$Config.$Field)) { throw 'Incomplete signing settings' }
    }
    Write-Status 'Private setup files found.'

    $Stage = 'GITHUB_CLI'
    $SafeHelp = 'Install GitHub CLI for Windows from https://cli.github.com/ . Close this window after installation, then run START-WINDOWS.cmd again. No administrator launch is required.'
    $script:Gh = Find-GitHubCli
    if (-not $script:Gh) { throw 'GitHub CLI not found' }
    Write-Status 'GitHub CLI found.'

    $Stage = 'GITHUB_LOGIN'
    $SafeHelp = 'Run START-WINDOWS.cmd again and finish GitHub sign-in in your browser. Use augustof2022 or an account allowed to manage this repository.'
    $Result = Invoke-GitHub 'auth status --hostname github.com'
    if ($Result.Code -ne 0) {
        Write-Status 'GitHub sign-in is required. Follow the browser/device-code instructions in this window.'
        $PreviousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & $script:Gh auth login --hostname github.com --git-protocol https --web
            $LoginCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $PreviousPreference }
        if ($LoginCode -ne 0) { throw 'Sign-in did not finish' }
        $Result = Invoke-GitHub 'auth status --hostname github.com'
        if ($Result.Code -ne 0) { throw 'Sign-in was not confirmed' }
    }
    Write-Status 'GitHub sign-in confirmed.'

    $Stage = 'REPOSITORY_ACCESS'
    $SafeHelp = 'Check that the signed-in GitHub account can access augustof2022/mYTmusic and manage its Actions secrets. Re-run setup using the repository owner account if needed.'
    $Result = Invoke-GitHub "repo view $Repo --json nameWithOwner,viewerPermission"
    if ($Result.Code -ne 0) { throw 'Cannot access repository' }
    $RepoInfo = $Result.Text | ConvertFrom-Json
    if ($RepoInfo.nameWithOwner -ne $Repo -or $RepoInfo.viewerPermission -notin @('ADMIN', 'MAINTAIN', 'WRITE')) { throw 'Insufficient repository access' }
    Write-Status "Repository access confirmed: $Repo"
    $Values = [ordered]@{
        SEGUE_KEYSTORE_BASE64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($KeyPath))
        SEGUE_KEYSTORE_PASSWORD = [string]$Config.store_password
        SEGUE_KEY_ALIAS = [string]$Config.alias
        SEGUE_KEY_PASSWORD = [string]$Config.key_password
    }
    foreach ($Name in $Values.Keys) {
        $Stage = "SAVE_$Name"
        $SafeHelp = "Could not save $Name. Check your Internet connection and use a GitHub account allowed to manage Actions secrets for $Repo, then re-run setup."
        $Result = Invoke-GitHub "secret set $Name --repo $Repo" $Values[$Name]
        if ($Result.Code -ne 0) { throw 'Secret upload failed' }
        Write-Status "Saved $Name"
    }

    $Stage = 'CONFIRM_SECRET_NAMES'
    $SafeHelp = 'GitHub could not confirm all four secret names. Check repository Settings > Secrets and variables > Actions, then re-run setup. Do not share secret values.'
    $Result = Invoke-GitHub "secret list --repo $Repo --json name"
    if ($Result.Code -ne 0) { throw 'Cannot confirm secret names' }
    $Names = @($Result.Text | ConvertFrom-Json | ForEach-Object { $_.name })
    foreach ($Name in $Values.Keys) {
        if ($Name -notin $Names) { throw 'Secret name missing' }
    }
    Write-Status 'GitHub confirmed all four signing secret names are present.'

    $Stage = 'START_RELEASE'
    $SafeHelp = 'The signing secrets were saved and confirmed, but the release could not start. Open the workflow link below, select Run workflow on main, then Run workflow. Or re-run this setup.'
    $Result = Invoke-GitHub "workflow run segue-release.yml --repo $Repo --ref main -f validation_only=false"
    if ($Result.Code -ne 0) { throw 'Could not start release' }
    Write-Status 'SETUP SUCCEEDED: Signing/release workflow started.'
    Write-Status "https://github.com/$Repo/actions/workflows/segue-release.yml"
    Write-Status 'The APK becomes available when the GitHub workflow succeeds. Future newer main builds will be signed automatically.'
    exit 0
} catch {
    # Controlled messages only: exceptions/CLI responses can contain private data.
    try {
        Write-Status "SETUP STOPPED at $Stage."
        Write-Status $SafeHelp
        Write-Status "https://github.com/$Repo/actions/workflows/segue-release.yml"
        Write-Status 'You may share SETUP-STATUS.txt for help. Do not share signing.json or segue-release.jks.'
    } catch {
        Write-Host "SETUP STOPPED at $Stage."
        Write-Host $SafeHelp
        Write-Host 'Could not save SETUP-STATUS.txt. Extract the ZIP into a writable folder and try again.'
    }
    exit 1
} finally {
    $Values = $null
    $Config = $null
    $Result = $null
}

$ErrorActionPreference = 'Stop'
$Repo = 'augustof2022/mYTmusic'
try {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw 'Install GitHub CLI from https://cli.github.com/ and run this setup again.'
    }
    & gh auth status *> $null
    if ($LASTEXITCODE -ne 0) {
        & gh auth login --hostname github.com --web
        if ($LASTEXITCODE -ne 0) { throw 'GitHub sign-in did not finish.' }
    }
    $RepoJson = & gh repo view $Repo --json nameWithOwner,viewerPermission
    if ($LASTEXITCODE -ne 0) { throw 'Cannot access the repository using the signed-in GitHub account.' }
    $RepoInfo = $RepoJson | ConvertFrom-Json
    if ($RepoInfo.nameWithOwner -ne $Repo -or $RepoInfo.viewerPermission -notin @('ADMIN','MAINTAIN','WRITE')) {
        throw 'The signed-in account cannot manage this repository.'
    }
    $Config = Get-Content (Join-Path $PSScriptRoot 'signing.json') -Raw | ConvertFrom-Json
    $Values = [ordered]@{
        SEGUE_KEYSTORE_BASE64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'segue-release.jks')))
        SEGUE_KEYSTORE_PASSWORD = [string]$Config.store_password
        SEGUE_KEY_ALIAS = [string]$Config.alias
        SEGUE_KEY_PASSWORD = [string]$Config.key_password
    }
    foreach ($Name in $Values.Keys) {
        $Start = New-Object Diagnostics.ProcessStartInfo
        $Start.FileName = (Get-Command gh).Source
        $Start.Arguments = "secret set $Name --repo $Repo"
        $Start.UseShellExecute = $false
        $Start.RedirectStandardInput = $true
        $Start.RedirectStandardOutput = $true
        $Start.RedirectStandardError = $true
        $Process = [Diagnostics.Process]::Start($Start)
        $Process.StandardInput.Write($Values[$Name])
        $Process.StandardInput.Close()
        $null = $Process.StandardOutput.ReadToEnd()
        $null = $Process.StandardError.ReadToEnd()
        $Process.WaitForExit()
        if ($Process.ExitCode -ne 0) { throw "Could not save $Name. Check GitHub login and repository permissions." }
        Write-Host "Saved $Name"
    }
    & gh workflow run segue-release.yml --repo $Repo --ref main -f validation_only=false
    if ($LASTEXITCODE -ne 0) { throw 'Secrets were saved, but the signing test could not be started.' }
    Write-Host 'Signing/release run started. Open:'
    Write-Host "https://github.com/$Repo/actions/workflows/segue-release.yml"
    Write-Host 'After it passes, future newer main builds can be signed and published automatically.'
} catch {
    Write-Host "Setup stopped: $($_.Exception.Message)"
    exit 1
} finally {
    $Values = $null
    $Config = $null
}

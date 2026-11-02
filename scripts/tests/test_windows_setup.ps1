$ErrorActionPreference = 'Stop'
$Script = Join-Path $PSScriptRoot '../setup-release-secrets.ps1'
$Temp = Join-Path ([IO.Path]::GetTempPath()) ('Segue setup tests ' + [Guid]::NewGuid())
$null = New-Item -ItemType Directory -Path $Temp
$MockFolder = Join-Path $Temp 'mock gh'
$null = New-Item -ItemType Directory -Path $MockFolder
$Mock = Join-Path $MockFolder 'gh.exe'
$Source = @'
using System;
using System.IO;
using System.Text;
public class MockGh {
    public static int Main(string[] args) {
        string command = String.Join(" ", args);
        string mode = Environment.GetEnvironmentVariable("SEGUE_SETUP_TEST_MODE");
        string state = Environment.GetEnvironmentVariable("SEGUE_SETUP_TEST_STATE");
        if (command == "auth status --hostname github.com") {
            Console.Error.WriteLine("Mock authentication status on stderr");
            return mode == "auth_required" && !File.Exists(state + ".login") ? 1 : 0;
        }
        if (command == "auth login --hostname github.com --git-protocol https --web") {
            File.WriteAllText(state + ".login", "yes");
            return 0;
        }
        if (command == "repo view augustof2022/mYTmusic --json nameWithOwner,viewerPermission") {
            Console.WriteLine("{\"nameWithOwner\":\"augustof2022/mYTmusic\",\"viewerPermission\":\"" + (mode == "denied_repo" ? "READ" : "ADMIN") + "\"}");
            return 0;
        }
        if (args.Length == 5 && args[0] == "secret" && args[1] == "set" && args[3] == "--repo" && args[4] == "augustof2022/mYTmusic") {
            string input = Console.In.ReadToEnd();
            string expected;
            switch (args[2]) {
                case "SEGUE_KEYSTORE_BASE64": expected = Convert.ToBase64String(Encoding.ASCII.GetBytes("DUMMY-KEY-FOR-TEST-ONLY")); break;
                case "SEGUE_KEYSTORE_PASSWORD": expected = "dummy-store-password"; break;
                case "SEGUE_KEY_ALIAS": expected = "dummy-signing-alias"; break;
                case "SEGUE_KEY_PASSWORD": expected = "dummy-key-password"; break;
                default: return 91;
            }
            if (input != expected) return 92;
            if (mode == "secret_failure") {
                Console.Error.WriteLine("private CLI diagnostic: " + input);
                return 1;
            }
            File.AppendAllText(state + ".saved", args[2] + "\n");
            return 0;
        }
        if (command == "secret list --repo augustof2022/mYTmusic --json name") {
            Console.WriteLine(mode == "missing_secret" ? "[]" : "[{\"name\":\"SEGUE_KEYSTORE_BASE64\"},{\"name\":\"SEGUE_KEYSTORE_PASSWORD\"},{\"name\":\"SEGUE_KEY_ALIAS\"},{\"name\":\"SEGUE_KEY_PASSWORD\"}]");
            return 0;
        }
        if (command == "workflow run segue-release.yml --repo augustof2022/mYTmusic --ref main -f validation_only=false") {
            if (mode == "dispatch_failure") return 1;
            File.WriteAllText(state + ".dispatched", "yes");
            return 0;
        }
        Console.Error.WriteLine("Unexpected mock command");
        return 93;
    }
}
'@

function Test-Setup([string]$Mode, [string]$Expected, [bool]$Success = $false) {
    $Folder = Join-Path $Temp $Mode
    $null = New-Item -ItemType Directory -Path $Folder
    Copy-Item -LiteralPath $Script -Destination $Folder
    $Key = Join-Path $Folder 'segue-release.jks'
    [IO.File]::WriteAllBytes($Key, [Text.Encoding]::ASCII.GetBytes('DUMMY-KEY-FOR-TEST-ONLY'))
    $Config = Join-Path $Folder 'signing.json'
    Set-Content -LiteralPath $Config -Value '{"store_password":"dummy-store-password","alias":"dummy-signing-alias","key_password":"dummy-key-password"}' -Encoding ASCII
    if ($Mode -eq 'missing_private') { Remove-Item -LiteralPath $Key }
    if ($Mode -eq 'malformed_config') { Set-Content -LiteralPath $Config -Value '{bad-json' -Encoding ASCII }
    $Cli = $Mock
    if ($Mode -eq 'missing_cli') { $Cli = Join-Path $Temp 'not-installed.exe' }
    $env:SEGUE_SETUP_TEST_MODE = $Mode
    $env:SEGUE_SETUP_TEST_STATE = Join-Path $Folder 'state'
    $Start = New-Object Diagnostics.ProcessStartInfo
    $Start.FileName = Join-Path $PSHOME 'powershell.exe'
    $ScriptCopy = Join-Path $Folder 'setup-release-secrets.ps1'
    $Start.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$ScriptCopy`" -GitHubCliPath `"$Cli`""
    $Start.UseShellExecute = $false
    $Start.CreateNoWindow = $true
    $Start.RedirectStandardOutput = $true
    $Start.RedirectStandardError = $true
    $Process = [Diagnostics.Process]::Start($Start)
    try {
        $OutTask = $Process.StandardOutput.ReadToEndAsync()
        $ErrTask = $Process.StandardError.ReadToEndAsync()
        if (-not $Process.WaitForExit(60000)) { $Process.Kill(); throw "Setup test timed out: $Mode" }
        $Output = $OutTask.GetAwaiter().GetResult() + $ErrTask.GetAwaiter().GetResult()
        if (($Process.ExitCode -eq 0) -ne $Success) { throw "Unexpected exit for $Mode : $Output" }
    } finally { $Process.Dispose() }
    $Status = Get-Content -LiteralPath (Join-Path $Folder 'SETUP-STATUS.txt') -Raw
    if (-not $Status.Contains($Expected)) { throw "Expected status absent for $Mode : $Status" }
    foreach ($Private in @('DUMMY-KEY-FOR-TEST-ONLY', 'dummy-store-password', 'dummy-signing-alias', 'dummy-key-password', [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('DUMMY-KEY-FOR-TEST-ONLY')))) {
        if ($Status.Contains($Private) -or $Output.Contains($Private)) { throw "Private value leaked for $Mode" }
    }
    $Dispatched = Test-Path -LiteralPath ($env:SEGUE_SETUP_TEST_STATE + '.dispatched')
    if ($Dispatched -ne $Success) { throw "Unexpected dispatch for $Mode" }
    if ($Success) {
        $Saved = @(Get-Content -LiteralPath ($env:SEGUE_SETUP_TEST_STATE + '.saved'))
        if ($Saved.Count -ne 4) { throw "Not all four secrets sent for $Mode" }
    }
    Write-Host "PASS $Mode"
}

try {
    Add-Type -TypeDefinition $Source -OutputAssembly $Mock -OutputType ConsoleApplication
    Test-Setup 'happy' 'SETUP SUCCEEDED' $true
    Test-Setup 'auth_required' 'SETUP SUCCEEDED' $true
    Test-Setup 'missing_cli' 'SETUP STOPPED at GITHUB_CLI.'
    Test-Setup 'missing_private' 'SETUP STOPPED at PRIVATE_FILES.'
    Test-Setup 'malformed_config' 'SETUP STOPPED at PRIVATE_FILES.'
    Test-Setup 'denied_repo' 'SETUP STOPPED at REPOSITORY_ACCESS.'
    Test-Setup 'secret_failure' 'SETUP STOPPED at SAVE_SEGUE_KEYSTORE_BASE64.'
    Test-Setup 'missing_secret' 'SETUP STOPPED at CONFIRM_SECRET_NAMES.'
    Test-Setup 'dispatch_failure' 'SETUP STOPPED at START_RELEASE.'
    Write-Host 'All 9 Windows PowerShell setup scenarios passed using dummy credentials only.'
} finally {
    Remove-Item Env:SEGUE_SETUP_TEST_MODE -ErrorAction SilentlyContinue
    Remove-Item Env:SEGUE_SETUP_TEST_STATE -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $Temp -Recurse -Force
}

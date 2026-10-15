# ysmvr local release helper (ASCII only: Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI)
#
# Usage:
#   $env:GITHUB_TOKEN = '<fine-grained or classic PAT with repo scope>'   # once per shell
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\release.ps1 -Version 0.3.34-dev -NotesFile ..\tmp\release-0.3.34.md
#
# It will: bump gradle.properties -> build -> commit -> tag -> push -> create GitHub Release -> upload jar.
# Requirements: JDK 22 available (JAVA_HOME or default path), the two platform dev jars (see README).

param(
  [Parameter(Mandatory = $true)][string]$Version,          # e.g. 0.3.34-dev
  [string]$NotesFile = '',                                  # optional markdown file for the release body
  [string]$Remote = 'origin',
  [switch]$SkipBuild,
  [switch]$Draft
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
function Info($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }

if (-not (Test-Path (Join-Path $repoRoot 'gradle.properties'))) { Fail 'run from the repository (gradle.properties not found)' }
if (-not $env:GITHUB_TOKEN) { Fail 'GITHUB_TOKEN is not set. Create a token with repo scope and set $env:GITHUB_TOKEN first.' }

$propPath = Join-Path $repoRoot 'gradle.properties'
$props = Get-Content -LiteralPath $propPath -Raw
if ($props -notmatch 'mod_version=([^\r\n]+)') { Fail 'mod_version not found in gradle.properties' }
$current = $Matches[1].Trim()
Info "current mod_version = $current   ->   new = $Version"
if ($current -eq $Version) { Fail "gradle.properties already at $Version" }

# 1) bump version
$props = $props -replace 'mod_version=[^\r\n]+', "mod_version=$Version"
Set-Content -LiteralPath $propPath -Value $props -Encoding UTF8 -NoNewline
Info 'gradle.properties updated'

# 2) build
$jar = Join-Path $repoRoot ("build\libs\ysmvr-$Version.jar")
if (-not $SkipBuild) {
  $javaHome = $env:JAVA_HOME
  if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    $fallback = 'C:\Program Files\BellSoft\LibericaJDK-22'
    if (Test-Path (Join-Path $fallback 'bin\java.exe')) { $env:JAVA_HOME = $fallback; Info "JAVA_HOME -> $fallback" }
    else { Fail 'JAVA_HOME must point to a JDK 22 installation (createMinecraftArtifacts needs class 65+)' }
  }
  Info 'gradlew build (this may take a few minutes)'
  & (Join-Path $repoRoot 'gradlew.bat') build
  if ($LASTEXITCODE -ne 0) { Fail "gradle build failed (exit $LASTEXITCODE)" }
}
if (-not (Test-Path -LiteralPath $jar)) { Fail "jar not found: $jar" }
$sha = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
$sizeKB = [math]::Round((Get-Item -LiteralPath $jar).Length / 1KB, 1)
Info "jar: $jar  ($sizeKB KB)"
Info "sha256: $sha"

# 3) commit + tag + push
git add -A
git -c i18n.commitEncoding=UTF-8 commit -m "release: v$Version" | Out-Null
if ($LASTEXITCODE -ne 0) { Info 'nothing to commit (version already committed?)' }
$tag = "v$Version"
git tag -a $tag -m "ysmvr $tag"
Info "tagged $tag"
git push $Remote HEAD
git push $Remote $tag
if ($LASTEXITCODE -ne 0) { Fail 'git push failed (check proxy/credentials)' }

# 4) create release + upload jar
$headers = @{ 'User-Agent' = 'ysmvr-release'; 'Authorization' = "Bearer $env:GITHUB_TOKEN"; 'Accept' = 'application/vnd.github+json' }
$repoSlug = (git remote get-url $Remote) -replace '^.*github\.com[:/]', '' -replace '\.git$', ''
Info "repository: $repoSlug"

$bodyText = if ($NotesFile -and (Test-Path -LiteralPath $NotesFile)) { Get-Content -LiteralPath $NotesFile -Raw } else { "ysmvr $tag" }
$bodyText = $bodyText.TrimEnd() + "`n`n---`n`nSHA256: ``$sha``"

function ApiJson($method, $uri, $json) {
  $bytes = [Text.Encoding]::UTF8.GetBytes($json)
  return Invoke-WebRequest -Uri $uri -Method $method -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8' -TimeoutSec 30 -UseBasicParsing
}
$esc = $bodyText.Replace('\', '\\').Replace('"', '\"').Replace("`r`n", '\n').Replace("`n", '\n')
$payload = '{"tag_name":"' + $tag + '","name":"ysmvr ' + $tag + '","body":"' + $esc + '","draft":' + ($(if ($Draft) { 'true' } else { 'false' })) + ',"prerelease":' + ($(if ($Version -like '*-dev') { 'true' } else { 'false' })) + ',"target_commitish":"main"}'

try {
  $r = ApiJson 'POST' "https://api.github.com/repos/$repoSlug/releases" $payload
  $rel = $r.Content | ConvertFrom-Json
  Info "release created: $($rel.html_url)"
} catch {
  $code = 0; if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
  if ($code -eq 422) {
    Info 'release already exists, fetching it'
    $rel = (Invoke-WebRequest -Uri "https://api.github.com/repos/$repoSlug/releases/tags/$tag" -Headers $headers -TimeoutSec 30 -UseBasicParsing).Content | ConvertFrom-Json
  } else { Fail "release creation failed: HTTP $code" }
}

$upload = ($rel.upload_url -replace '\{.*\}', '') + "?name=$(Split-Path -Leaf $jar)"
try {
  $ur = Invoke-WebRequest -Uri $upload -Method POST -Headers @{ 'User-Agent' = 'ysmvr-release'; 'Authorization' = "Bearer $env:GITHUB_TOKEN"; 'Content-Type' = 'application/java-archive' } -InFile $jar -TimeoutSec 300 -UseBasicParsing
  $asset = $ur.Content | ConvertFrom-Json
  Info "asset uploaded: $($asset.name)  $([math]::Round($asset.size/1KB,1)) KB"
  Write-Host "download: $($asset.browser_download_url)"
} catch {
  $code = 0; if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
  if ($code -eq 422) { Info 'asset already exists on that release' } else { Fail "asset upload failed: HTTP $code" }
}

Info 'done'

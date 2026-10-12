param([switch]$History)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$findings = [System.Collections.Generic.List[string]]::new()
$rules = @{
    'personal filesystem path' = '(?i)(?:[a-z]:[\\/](?:users|documents and settings)[\\/][^\\/\s]+[\\/]|/(?:Users|home)/[^/\s]+/)'
    'personal email address' = '(?i)[a-z0-9._%+-]+@(?:gmail|yahoo|outlook|hotmail|icloud|protonmail)\.com'
    'GitHub token' = '(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})'
    'AWS access key' = '(?:AKIA|ASIA)[A-Z0-9]{16}'
    'Google API key' = 'AIza[A-Za-z0-9_-]{30,}'
    'private key' = '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
    'literal API credential' = '(?i)(?:api[_-]?key|Govee-API-Key)["\x27\s]*[:=,][\s"\x27]+[A-Za-z0-9_-]{24,}'
}
function Check-Content([string]$label, [string]$content) {
    foreach ($rule in $rules.GetEnumerator()) {
        if ($content -match $rule.Value) { $findings.Add("${label}: $($rule.Key)") }
    }
}
function Check-Path([string]$path) {
    if ($path -match '^(?:\.tools/|reference[s]?/|docs/evidence/|local\.properties$)|\.(?:apk|jks|keystore|pem|p12)$') {
        $findings.Add("${path}: private/local artifact must remain untracked")
    }
}
Push-Location $projectRoot
try {
    # Include new publishable files, but never inspect ignored personal configuration.
    $paths = @(git ls-files --cached --others --exclude-standard) | Sort-Object -Unique
    foreach ($path in $paths) {
        Check-Path $path
        if ((Test-Path -LiteralPath $path -PathType Leaf) -and $path -ne 'gradle/wrapper/gradle-wrapper.jar') {
            Check-Content $path ([IO.File]::ReadAllText((Join-Path $projectRoot $path)))
        }
    }
    if ($History) {
        foreach ($commit in @(git rev-list --all)) {
            $identities = @(git show -s --format='%ae%n%ce' $commit)
            if ($identities | Where-Object { $_ -notmatch '^[^@\s]+@users\.noreply\.github\.com$' }) {
                $findings.Add("${commit}: author/committer must use a public GitHub noreply identity")
            }
            foreach ($path in @(git ls-tree -r --name-only $commit)) {
                Check-Path $path
                if ($path -ne 'gradle/wrapper/gradle-wrapper.jar') { Check-Content "${commit}:$path" ((git show "${commit}:$path") -join "`n") }
            }
        }
    }
    if ($findings.Count) {
        # Report file/rule only. Never echo the matching secret or personal data.
        $findings | Sort-Object -Unique | Write-Output
        throw 'Public-file privacy checks failed. Sanitize before publishing.'
    }
    Write-Output 'Public-file privacy checks passed (no matching personal paths, personal email, credential patterns, or private artifacts).'
} finally { Pop-Location }

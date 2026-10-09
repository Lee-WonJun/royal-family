param(
    [string]$PlanPath = (Join-Path $PSScriptRoot '../collection-plan.json'),
    [string]$SnapshotDirectory
)

$ErrorActionPreference = 'Stop'
$plan = Get-Content -LiteralPath $PlanPath -Raw -Encoding utf8 | ConvertFrom-Json
if (-not $SnapshotDirectory) {
    $SnapshotDirectory = Join-Path $PSScriptRoot "../sources/$($plan.as_of_date_kst)"
}
if (Test-Path -LiteralPath $SnapshotDirectory) {
    throw 'Use a new snapshot directory. Existing official source snapshots must be preserved.'
}
$snapshotRoot = [IO.Path]::GetFullPath($SnapshotDirectory)
[void][IO.Directory]::CreateDirectory($snapshotRoot)

function Write-Utf8([string]$Path, [string]$Content) {
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path))
    [IO.File]::WriteAllText($Path, $Content, [Text.UTF8Encoding]::new($false))
}

function Get-OfficialPage([string]$Url, [string]$RelativePath) {
    if (([Uri]$Url).Host -ne 'www.law.go.kr') { throw 'Only the official law.go.kr source is allowed.' }
    $path = Join-Path $snapshotRoot $RelativePath
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    Invoke-WebRequest -Uri $Url -OutFile $path -TimeoutSec 40
    $content = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
    if ($content.Length -lt 100 -or $content -match '서비스 점검 중|접근이 차단') {
        throw "The response is not a usable official document: $Url"
    }
    return @{ content = $content; path = $RelativePath; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
}

function Convert-HtmlText([string]$Html) {
    $text = [regex]::Replace($Html, '(?is)<(script|style)\b[^>]*>.*?</\1>', '')
    $text = [regex]::Replace($text, '(?s)<!--.*?-->', '')
    $text = [regex]::Replace($text, '(?i)<br\s*/?>|</(?:p|div|h[1-6]|li|tr|td)>', "`n")
    # Quoted HTML attributes can themselves contain < and >, especially in addenda links.
    $text = [regex]::Replace($text, '<(?:"[^"]*"|''[^'']*''|[^''">])*>', '')
    $text = [Net.WebUtility]::HtmlDecode($text)
    $text = [regex]::Replace($text, '[\t\r\u00a0 ]+', ' ')
    return (($text -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ }) -join "`n").Trim()
}

function Get-DivInner([string]$Html, [string]$Id) {
    $start = [regex]::Match($Html, '<div\b[^>]*\bid="' + [regex]::Escape($Id) + '"[^>]*>')
    if (-not $start.Success) { throw "Missing source body element: $Id" }
    $depth = 1
    $offset = $start.Index + $start.Length
    foreach ($tag in [regex]::Matches($Html.Substring($offset), '(?i)</?div\b[^>]*>')) {
        if ($tag.Value -match '^</') { $depth-- } else { $depth++ }
        if ($depth -eq 0) { return $Html.Substring($offset, $tag.Index) }
    }
    throw "Unbalanced source body element: $Id"
}

$results = [ordered]@{
    schema_version = 1
    as_of_date_kst = $plan.as_of_date_kst
    collected_at_utc = [DateTimeOffset]::UtcNow.ToString('o')
    statutes = @()
    precedents = @()
    errors = @()
}
foreach ($law in $plan.statutes) {
    try {
        $url = 'https://www.law.go.kr/법령/' + [Uri]::EscapeDataString($law.name)
        $landing = Get-OfficialPage $url "statutes/$($law.id)-landing.html"
        $frame = [regex]::Match($landing.content, '<iframe\b[^>]*src="([^"]+)"')
        if (-not $frame.Success) { throw "No official law frame for $($law.name)" }
        $frameUrl = [Uri]::new([Uri]$url, [Net.WebUtility]::HtmlDecode($frame.Groups[1].Value)).AbsoluteUri
        $seq = [regex]::Match($frameUrl, 'lsiSeq=(\d+)').Groups[1].Value
        $efYd = [regex]::Match($frameUrl, 'efYd=(\d+)').Groups[1].Value
        if (-not $seq -or -not $efYd) { throw 'Missing official version or effective date.' }
        if ([int]$efYd -gt [int]($plan.as_of_date_kst -replace '-', '')) {
            throw "Official default version takes effect after the collection date: $efYd. Choose a historical version explicitly."
        }
        $bodyUrl = "https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=$seq&efYd=$efYd&chrClsCd=010202"
        $body = Get-OfficialPage $bodyUrl "statutes/$($law.id)-body.html"
        $versionText = Convert-HtmlText (Get-DivInner $body.content 'conTop')
        $text = $versionText + "`n`n" + (Convert-HtmlText (Get-DivInner $body.content 'conScroll'))
        if ($text -notmatch '제\d+조') { throw 'No statutory provisions in the source body.' }
        $textPath = "statutes/$($law.id)-body.txt"
        Write-Utf8 (Join-Path $snapshotRoot $textPath) ($text + "`n")
        $metadata = [regex]::Match($text, '\[시행[^\]]+\]\s*\[[^\]]+\]').Value
        if (-not $metadata) { throw 'Missing statutory version header.' }
        $results.statutes += [ordered]@{
            id=$law.id; name=$law.name; topic=$law.topic; articles=$law.articles
            source_type='statute'; official_url=$url; version_url=$frameUrl
            body_url=$bodyUrl; lsi_seq=$seq; effective_date="$($efYd.Substring(0,4))-$($efYd.Substring(4,2))-$($efYd.Substring(6,2))"
            version_header=$metadata; html_path=$body.path; html_sha256=$body.sha256
            landing_path=$landing.path; landing_sha256=$landing.sha256; text_path=$textPath
            text_sha256=(Get-FileHash -LiteralPath (Join-Path $snapshotRoot $textPath) -Algorithm SHA256).Hash.ToLowerInvariant()
            retrieval_status='official_body_captured'
        }
        Write-Output "$($law.id) $($law.name) $metadata"
    } catch {
        $results.errors += @{ id=$law.id; message=$_.Exception.Message }
        Write-Warning "$($law.id): $($_.Exception.Message)"
    }
}
foreach ($case in $plan.precedents) {
    try {
        $url = if ($case.prec_seq) { "https://www.law.go.kr/LSW/precInfoP.do?precSeq=$($case.prec_seq)" }
               else { 'https://www.law.go.kr/LSW/precInfoP.do?evtNo=' + [Uri]::EscapeDataString($case.case_number) }
        $page = Get-OfficialPage $url "precedents/$($case.id).html"
        $header = Convert-HtmlText ([regex]::Match($page.content, '<div class="subtit1">([\s\S]*?)</div>').Groups[1].Value)
        $normalizedHeader = $header -replace '\s', ''
        $normalizedNumber = $case.case_number -replace '\s', ''
        if (-not $header -or ($case.case_number -and -not $normalizedHeader.Contains($normalizedNumber))) { throw 'The official case header does not match the requested case.' }
        $seq = [regex]::Match($page.content, '<input[^>]*id="precSeq"[^>]*value="(\d+)"').Groups[1].Value
        $name = Convert-HtmlText ([regex]::Match((Get-DivInner $page.content 'contentBody'), '<h2[^>]*>([\s\S]*?)</h2>').Groups[1].Value)
        $body = Convert-HtmlText (Get-DivInner $page.content 'conScroll')
        if ($body -notmatch '【전 문】|【전문】|전 문|전문' -or $body -notmatch '주 문|주문') { throw 'The full published decision body was not found.' }
        $textPath = "precedents/$($case.id).txt"
        Write-Utf8 (Join-Path $snapshotRoot $textPath) ("$name`n$header`n`n$body`n")
        $dateMatch = [regex]::Match($header, '(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})\.')
        $date = '{0}-{1:d2}-{2:d2}' -f $dateMatch.Groups[1].Value, [int]$dateMatch.Groups[2].Value, [int]$dateMatch.Groups[3].Value
        if ($date -gt $plan.as_of_date_kst) { throw 'Decision date is later than collection date.' }
        $caseNumber = if ($case.case_number) { $case.case_number } else { [regex]::Match($header, '\d{2,4}[가-힣]+\d+').Value }
        $results.precedents += [ordered]@{
            id=$case.id; case_number=$caseNumber; name=$name; topic=$case.topic
            source_type='precedent'; court='대법원'; decision_date=$date; decision_header=$header
            official_url="https://www.law.go.kr/LSW/precInfoP.do?precSeq=$seq"; retrieved_url=$url; prec_seq=$seq
            html_path=$page.path; html_sha256=$page.sha256; text_path=$textPath
            text_sha256=(Get-FileHash -LiteralPath (Join-Path $snapshotRoot $textPath) -Algorithm SHA256).Hash.ToLowerInvariant()
            retrieval_status='official_full_published_body_captured'
        }
        Write-Output "$($case.id) $name $header"
    } catch {
        $results.errors += @{ id=$case.id; message=$_.Exception.Message }
        Write-Warning "$($case.id): $($_.Exception.Message)"
    }
}
Write-Utf8 (Join-Path $snapshotRoot 'capture-manifest.json') (($results | ConvertTo-Json -Depth 12) + "`n")
Write-Output "Captured $($results.statutes.Count) statutes and $($results.precedents.Count) precedents; $($results.errors.Count) errors."
if ($results.errors.Count) { exit 1 }

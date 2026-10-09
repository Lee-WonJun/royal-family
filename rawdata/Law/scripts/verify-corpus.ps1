param([string]$LawDirectory = (Join-Path $PSScriptRoot '..'))
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($LawDirectory)
$catalog = Get-Content -LiteralPath (Join-Path $root 'catalog.json') -Raw -Encoding utf8 | ConvertFrom-Json
$manifest = Get-Content -LiteralPath (Join-Path $root 'capture-manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
$plan = Get-Content -LiteralPath (Join-Path $root 'collection-plan.json') -Raw -Encoding utf8 | ConvertFrom-Json
$issues = [Collections.Generic.List[string]]::new()
$hashChecks = 0
$linkChecks = 0
$articleChecks = 0
$reportPath = Join-Path $root 'verification-result.json'
[IO.File]::WriteAllText($reportPath, '{"result":"running","verification_kind":"source_and_document_validation"}' + "`n", [Text.UTF8Encoding]::new($false))

function Fail([string]$Message) { $issues.Add($Message) }
function Check-Snapshot([string]$Path, [string]$Hash) {
    $full = [IO.Path]::GetFullPath((Join-Path $root $Path))
    if (-not $full.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Fail "Snapshot outside corpus: $Path"
        return
    }
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { Fail "Missing snapshot: $Path"; return }
    $actual = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Hash) { Fail "SHA-256 mismatch: $Path" }
    $script:hashChecks++
}

$records = @($catalog.statutes) + @($catalog.precedents)
$ids = @($records | ForEach-Object { $_.id })
if (@($ids | Select-Object -Unique).Count -ne $ids.Count) { Fail 'Duplicate source identifiers.' }
if ($catalog.as_of_date_kst -ne $manifest.as_of_date_kst -or $catalog.as_of_date_kst -ne $plan.as_of_date_kst) { Fail 'Collection date mismatch.' }
if ($manifest.errors.Count -ne 0) { Fail 'Unresolved capture errors in final manifest.' }
if ($catalog.statutes.Count -ne $plan.statutes.Count -or $catalog.precedents.Count -ne $plan.precedents.Count) { Fail 'Collection plan coverage mismatch.' }
if ($catalog.counts.statutes -ne $catalog.statutes.Count -or $catalog.counts.precedents -ne $catalog.precedents.Count) { Fail 'Catalogue counts differ from records.' }
if ($catalog.ingestion_status -ne 'not_ingested_into_service') { Fail 'Unexpected service ingestion status.' }

foreach ($record in $records) {
    if (([Uri]$record.official_url).Host -ne 'www.law.go.kr') { Fail "Unexpected publisher: $($record.id)" }
    foreach ($required in @('source_type','doc_path','text_path','html_path','summary','limit','questions','use_cases','retrieval_status')) {
        if (-not $record.$required) { Fail "Missing $required on $($record.id)" }
    }
    $captured = @($manifest.statutes) + @($manifest.precedents) | Where-Object { $_.id -eq $record.id }
    if (@($captured).Count -ne 1 -or $captured.html_sha256 -ne $record.html_sha256 -or $captured.text_sha256 -ne $record.text_sha256) {
        Fail "Capture provenance mismatch: $($record.id)"
    }
    Check-Snapshot $record.html_path $record.html_sha256
    Check-Snapshot $record.text_path $record.text_sha256
    $text = Get-Content -LiteralPath (Join-Path $root $record.text_path) -Raw -Encoding utf8
    if ($text.Contains([string][char]0xfffd)) { Fail "Unicode replacement character: $($record.id)" }
    if ($text -match '<script\b|onclick=|href="|src="') { Fail "Residual HTML markup: $($record.id)" }
    if ($record.source_type -eq 'statute') {
        Check-Snapshot $record.landing_path $record.landing_sha256
        $effective = [regex]::Match($record.version_header, '\[시행\s+(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})\.').Groups
        $date = '{0}-{1:d2}-{2:d2}' -f $effective[1].Value, [int]$effective[2].Value, [int]$effective[3].Value
        if ($date -ne $record.effective_date -or $date -gt $catalog.as_of_date_kst) { Fail "Wrong effective date: $($record.id)" }
        if (-not $text.Contains($record.version_header)) { Fail "Missing version header in body: $($record.id)" }
        if (([Uri]$record.version_url).Query -notmatch ('lsiSeq=' + $record.lsi_seq + '(?:&|$)')) { Fail "Wrong fixed statute version URL: $($record.id)" }
        $addenda = [regex]::Match($text, '(?m)^부\s*칙\s*<')
        $current = if ($addenda.Success) { $text.Substring(0, $addenda.Index) } else { '' }
        foreach ($article in $record.selected_articles) {
            if (-not $current.Contains($article.text)) { Fail "Selected passage is not in current main body: $($record.id)/$($article.number)" }
            $sourceLine = ($text -split "\r?\n")[$article.source_line - 1]
            if (($sourceLine -replace '\s+', ' ').Trim() -ne $article.heading) { Fail "Source line mismatch: $($record.id)/$($article.number)" }
            if (([Uri]$article.official_url).Query -notmatch ('lsiSeq=' + $record.lsi_seq + '(?:&|$)')) { Fail "Article URL has another version: $($record.id)/$($article.number)" }
            $articleChecks++
        }
        if ($record.articles.Count -ne $record.selected_articles.Count) { Fail "Article selection mismatch: $($record.id)" }
    } elseif ($record.source_type -eq 'precedent') {
        $firstLines = $text -split "\r?\n"
        if ($firstLines[0] -ne $record.name -or $firstLines[1] -ne $record.decision_header) { Fail "Case header mismatch: $($record.id)" }
        if (($record.decision_header -replace '\s', '') -notlike ('*' + ($record.case_number -replace '\s', '') + '*')) { Fail "Case number mismatch: $($record.id)" }
        $dateGroups = [regex]::Match($record.decision_header, '(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})\.').Groups
        $date = '{0}-{1:d2}-{2:d2}' -f $dateGroups[1].Value, [int]$dateGroups[2].Value, [int]$dateGroups[3].Value
        if ($date -ne $record.decision_date -or $date -gt $catalog.as_of_date_kst) { Fail "Wrong decision date: $($record.id)" }
        if ($text -notmatch '【판시사항】' -or $text -notmatch '【전문】' -or $text -notmatch '【주 문】') { Fail "Incomplete published case body: $($record.id)" }
        if (([Uri]$record.official_url).Query -ne ('?precSeq=' + $record.prec_seq)) { Fail "Wrong fixed precedent URL: $($record.id)" }
        $html = Get-Content -LiteralPath (Join-Path $root $record.html_path) -Raw -Encoding utf8
        $htmlId = [regex]::Match($html, '<input[^>]*id="precSeq"[^>]*value="(\d+)"').Groups[1].Value
        if ($htmlId -ne $record.prec_seq) { Fail "Precedent identifier mismatch in source HTML: $($record.id)" }
        foreach ($related in $record.related_laws) { if ($related -notin $ids) { Fail "Unknown related law: $($record.id)/$related" } }
    } else { Fail "Unrecognized source type: $($record.id)" }
    foreach ($uc in $record.use_cases) { if ($uc -notmatch '^UC-0[1-8]$') { Fail "Unknown use case: $($record.id)/$uc" } }
}
if ($catalog.counts.selected_articles -ne $articleChecks) { Fail 'Selected article total mismatch.' }

$markdownFiles = Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.md'
foreach ($file in $markdownFiles) {
    $markdown = Get-Content -LiteralPath $file.FullName -Raw -Encoding utf8
    foreach ($link in [regex]::Matches($markdown, '\[[^\]]+\]\(([^\s)]+)\)')) {
        $target = $link.Groups[1].Value
        if ($target -match '^https?://') { continue }
        $parts = $target.Split('#', 2)
        $targetPath = [IO.Path]::GetFullPath((Join-Path $file.DirectoryName ([Uri]::UnescapeDataString($parts[0]))))
        if (-not (Test-Path -LiteralPath $targetPath)) { Fail "Broken local link: $($file.Name) -> $target"; continue }
        if ($parts.Count -eq 2) {
            $linkedText = Get-Content -LiteralPath $targetPath -Raw -Encoding utf8
            if (-not $linkedText.Contains('id="' + $parts[1] + '"')) { Fail "Missing explicit project anchor: $($file.Name) -> $target" }
        }
        $linkChecks++
    }
}
$result = [ordered]@{
    as_of_date_kst=$catalog.as_of_date_kst
    verification_kind='source_and_document_validation'
    statutes=$catalog.statutes.Count
    selected_articles=$articleChecks
    precedents=$catalog.precedents.Count
    sha256_checks=$hashChecks
    local_link_checks=$linkChecks
    external_network_calls=0
    issues=@($issues)
    result= $(if ($issues.Count) { 'failed' } else { 'passed' })
}
[IO.File]::WriteAllText($reportPath, ($result | ConvertTo-Json -Depth 6) + "`n", [Text.UTF8Encoding]::new($false))
$result | ConvertTo-Json -Depth 6 | Write-Output
if ($issues.Count) { exit 1 }

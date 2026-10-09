param([string]$LawDirectory = (Join-Path $PSScriptRoot '..'))
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($LawDirectory)
$manifest = Get-Content -LiteralPath (Join-Path $root 'capture-manifest.json') -Raw -Encoding utf8 | ConvertFrom-Json
$annotations = Get-Content -LiteralPath (Join-Path $root 'annotations.json') -Raw -Encoding utf8 | ConvertFrom-Json
$plan = Get-Content -LiteralPath (Join-Path $root 'collection-plan.json') -Raw -Encoding utf8 | ConvertFrom-Json

function Save-Text([string]$RelativePath, [string]$Text) {
    $path = Join-Path $root $RelativePath
    [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
    [IO.File]::WriteAllText($path, $Text.Replace("`r`n", "`n").TrimEnd([char[]]"`r`n") + "`n", [Text.UTF8Encoding]::new($false))
}
function Safe-Name([string]$Name) { return ($Name -replace '[<>:"/\\|?*]', '' -replace '\s+', '_') }
function Quote-Text([string]$Text) { return (($Text.Trim() -split "\r?\n" | ForEach-Object { '> ' + $_ }) -join "`n") }
function Question-List($Questions) { return (($Questions | ForEach-Object { '- ' + $_ }) -join "`n") }

$catalog = [ordered]@{
    schema_version = 1
    as_of_date_kst = $manifest.as_of_date_kst
    publisher = $manifest.publisher
    scope = 'selected_official_statutes_and_real_precedents'
    annotation_type = $annotations.annotation_type
    ingestion_status = 'not_ingested_into_service'
    statutes = @()
    precedents = @()
}
$lawIndex = @('# 종중 관련 법령 목록', '', "조회 기준일 $($manifest.as_of_date_kst) · 법률과 하위 규정을 합쳐 $($manifest.statutes.Count)종", '',
    '수집 요약과 확인 질문은 자료를 찾기 위한 정리다. 각 항목의 시행일과 고정 버전 링크를 먼저 확인하고, 사건 당시 적용 법령과 부칙을 별도로 대조한다.', '',
    '| 자료 | 법령 | 시행일 | 관련 조문 | 확인 주제 |', '| --- | --- | --- | --- | --- |')
$caseIndex = @('# 종중 관련 실제 판례 목록', '', "조회 기준일 $($manifest.as_of_date_kst) · 대법원 판결과 결정 $($manifest.precedents.Count)건", '',
    '종중 직접 사건과 다른 단체·일반 토지·상속의 참고 법리를 구분했다. 선고일은 게시일과 다르며, 파기환송 사건의 환송 후 결과는 포함하지 않는다. 전원합의체의 다수의견과 별개·반대의견도 구분하여 읽는다.', '',
    '| 자료 | 사건번호 | 선고 또는 결정일 | 주제 | 적용 범위 |', '| --- | --- | --- | --- | --- |')
$articleCount = 0
foreach ($law in $manifest.statutes) {
    $note = $annotations.statutes.($law.id)
    $lawPlan = $plan.statutes | Where-Object { $_.id -eq $law.id }
    if (-not $note -or -not $lawPlan) { throw "Missing law annotation or plan: $($law.id)" }
    $law.articles = $lawPlan.articles
    $text = Get-Content -LiteralPath (Join-Path $root $law.text_path) -Raw -Encoding utf8
    # Only the current main body is selected. Historical amendment addenda remain in the complete source.
    $addenda = [regex]::Match($text, '(?m)^부\s*칙\s*<')
    if (-not $addenda.Success) { throw "No addenda boundary: $($law.id)" }
    $current = $text.Substring(0, $addenda.Index)
    $heads = [regex]::Matches($current, '(?m)^제(?<main>\d+)조(?:의(?<sub>\d+))?\s*(?:\([^\n]*|삭제[^\n]*)')
    $selected = @()
    foreach ($wanted in $law.articles) {
        $found = $false
        for ($i = 0; $i -lt $heads.Count; $i++) {
            $heading = $heads[$i]
            $number = $heading.Groups['main'].Value
            if ($heading.Groups['sub'].Success) { $number += '-' + $heading.Groups['sub'].Value }
            if ($number -ne $wanted) { continue }
            $end = if ($i + 1 -lt $heads.Count) { $heads[$i + 1].Index } else { $current.Length }
            $content = $current.Substring($heading.Index, $end - $heading.Index).Trim()
            $section = [regex]::Match($content, '(?m)^제\d+(?:편|장|절|관)\s')
            if ($section.Success) { $content = $content.Substring(0, $section.Index).Trim() }
            $joNo = '{0:d4}' -f [int]$heading.Groups['main'].Value
            $joBrNo = if ($heading.Groups['sub'].Success) { '{0:d2}' -f [int]$heading.Groups['sub'].Value } else { '00' }
            $articleUrl = "https://www.law.go.kr/LSW/lsSideInfoP.do?docCls=jo&joBrNo=$joBrNo&joNo=$joNo&lsiSeq=$($law.lsi_seq)&urlMode=lsScJoRltInfoR"
            $line = ($text.Substring(0, $heading.Index) -split "`n").Count
            $selected += [ordered]@{ number=$number; heading=($heading.Value -replace '\s+', ' ').Trim(); official_url=$articleUrl; source_line=$line; text=$content }
            $found = $true
            break
        }
        if (-not $found) { throw "Current article missing: $($law.id) article $wanted" }
    }
    $path = 'statutes/' + $law.id + '-' + (Safe-Name $law.name) + '.md'
    $lawNote = @("# $($law.name)", '', $law.version_header, '',
        "- 자료 ID: $($law.id) · 출처 유형: 법령 · 조회일: $($manifest.as_of_date_kst)",
        "- 법령 버전: lsiSeq $($law.lsi_seq) · [공식 버전 원문]($($law.version_url))",
        "- 보관 자료: [HTML 응답 원본](../$($law.html_path)) · [전체 본문 추출 텍스트](../$($law.text_path))",
        "- 원본 SHA-256: $($law.html_sha256)",
        "- 검색 태그: $($note.tags -join ', ')", '',
        '## 수집 요약', '', $note.summary, '', '## 적용 조건과 해석 범위', '', $note.limit, '',
        '## 자료를 확인할 질문', '', (Question-List $note.questions), '',
        "관련 사용자 흐름: $($note.use_cases -join ', '). [PRD](../../../docs/prd/hackathon-prd.md)와 [QA 시나리오](../../../qa/scenarios/hackathon-use-cases.md)의 현재 범위에 연결한 자료 분류이며 새로운 구현 완료 기록이 아니다.", '',
        '## 관련 조문 원문', '',
        '다음은 보관된 법령 버전의 본문에서 발췌한 조문이다. 아래 원문과 위의 수집 요약·질문을 구분한다. 부칙과 미선정 조문은 전체 본문 자료에서 확인할 수 있다.', '')
    foreach ($article in $selected) {
        $label = '제' + ($article.number -replace '-', '조의')
        if (-not $article.number.Contains('-')) { $label += '조' }
        $lawNote += @("### $label", '', "[공식 조문]($($article.official_url)) · 추출 텍스트 $($article.source_line)행", '', (Quote-Text $article.text), '')
    }
    Save-Text $path (($lawNote -join "`n") + "`n")
    $record = [ordered]@{}
    foreach ($property in $law.PSObject.Properties) { $record[$property.Name] = $property.Value }
    $record['doc_path'] = $path
    $record['selected_articles'] = $selected
    foreach ($property in $note.PSObject.Properties) { $record[$property.Name] = $property.Value }
    $catalog.statutes += $record
    $lawIndex += "| $($law.id) | [$($law.name)]($([IO.Path]::GetFileName($path))) | $($law.effective_date) | $($law.articles -join ', ') | $($law.topic) |"
    $articleCount += $selected.Count
}
foreach ($case in $manifest.precedents) {
    $note = $annotations.precedents.($case.id)
    $casePlan = $plan.precedents | Where-Object { $_.id -eq $case.id }
    if (-not $note -or -not $casePlan) { throw "Missing precedent annotation or plan: $($case.id)" }
    $case.topic = $casePlan.topic
    $text = Get-Content -LiteralPath (Join-Path $root $case.text_path) -Raw -Encoding utf8
    $issues = [regex]::Match($text, '(?s)【판시사항】\s*(.*?)\s*(?=【)').Groups[1].Value.Trim()
    if (-not $issues) { throw "Missing published issues: $($case.id)" }
    $kind = if ($case.decision_header -match '전원합의체 판결') { '전원합의체 판결' } elseif ($case.decision_header -match '결정') { '결정' } else { '판결' }
    $path = 'precedents/' + $case.id + '-' + (Safe-Name $case.case_number) + '.md'
    $caseNote = @("# $($case.case_number) $($case.topic)", '', $case.decision_header, '',
        "- 사건명: $($case.name)",
        "- 자료 ID: $($case.id) · 출처 유형: 판례 · 재판 종류: $kind · 조회일: $($manifest.as_of_date_kst)",
        "- 적용 범위: $($note.applicability)",
        "- 출처: [국가법령정보센터 공식 원문]($($case.official_url)) · precSeq $($case.prec_seq)",
        "- 보관 자료: [HTML 응답 원본](../$($case.html_path)) · [공개된 판결 본문 전체 추출 텍스트](../$($case.text_path))",
        "- 원본 SHA-256: $($case.html_sha256)",
        "- 검색 태그: $($note.tags -join ', ')", '',
        '## 수집 요약', '', $note.summary, '', '## 적용 조건과 해석 범위', '', $note.limit, '',
        '## 자료를 확인할 질문', '', (Question-List $note.questions), '',
        "관련 법령 자료: $($note.related_laws -join ', '). 관련 사용자 흐름: $($note.use_cases -join ', ').", '',
        '## 판시사항 원문', '', (Quote-Text $issues), '',
        '판결요지·주문·이유와 별개·반대의견이 있는 경우 그 내용은 위의 전체 추출 텍스트에서 확인한다. 이 요약은 시연 종중의 사실관계나 개별 사건의 법률 결론을 확정하지 않는다.', '')
    Save-Text $path (($caseNote -join "`n") + "`n")
    $record = [ordered]@{}
    foreach ($property in $case.PSObject.Properties) { $record[$property.Name] = $property.Value }
    $record['doc_path'] = $path
    $record['decision_kind'] = $kind
    foreach ($property in $note.PSObject.Properties) { $record[$property.Name] = $property.Value }
    $catalog.precedents += $record
    $caseIndex += "| $($case.id) | [$($case.case_number)]($([IO.Path]::GetFileName($path))) | $($case.decision_date) | $($case.topic) | $($note.applicability) |"
}
$catalog['counts'] = @{ statutes=$catalog.statutes.Count; selected_articles=$articleCount; precedents=$catalog.precedents.Count }
Save-Text 'catalog.json' (($catalog | ConvertTo-Json -Depth 18) + "`n")
Save-Text 'statutes/README.md' (($lawIndex -join "`n") + "`n")
Save-Text 'precedents/README.md' (($caseIndex -join "`n") + "`n")
Write-Output "Built $($catalog.statutes.Count) law notes, $articleCount selected articles, and $($catalog.precedents.Count) precedent notes."

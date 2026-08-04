param(
    [Parameter(Mandatory = $true)]
    [string]$File,
    [string]$Pages = "",
    [string]$BaseUrl = "http://localhost:8866"
)

$ErrorActionPreference = "Stop"
$resolvedFile = (Resolve-Path -LiteralPath $File).Path
$normalizedBaseUrl = $BaseUrl.TrimEnd('/')

Write-Host "Checking PaddleOCR service..."
$health = Invoke-RestMethod -Method Get -Uri "$normalizedBaseUrl/health"
$health | ConvertTo-Json -Depth 5

$arguments = @(
    "-sS",
    "-X", "POST",
    "$normalizedBaseUrl/v1/ocr",
    "-F", "file=@$resolvedFile"
)
if (-not [string]::IsNullOrWhiteSpace($Pages)) {
    $arguments += @("-F", "pages=$Pages")
}

Write-Host "Recognizing $resolvedFile ..."
$raw = & curl.exe @arguments
if ($LASTEXITCODE -ne 0) {
    throw "curl failed with exit code $LASTEXITCODE"
}

$result = $raw | ConvertFrom-Json
$result | ConvertTo-Json -Depth 10

Write-Host "Summary: engine=$($result.engine), pages=$($result.pages.Count), elapsedMs=$($result.elapsedMs)"
foreach ($page in $result.pages) {
    Write-Host "Page $($page.pageNumber), confidence=$($page.confidence)"
    Write-Host $page.text
}

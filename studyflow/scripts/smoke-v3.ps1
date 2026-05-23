param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

function Wait-TaskSuccess {
    param(
        [string]$BaseUrl,
        [hashtable]$Headers,
        [string]$TaskId
    )

    for ($i = 0; $i -lt 30; $i++) {
        $task = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/tasks/$TaskId" -Headers $Headers
        $status = $task.data.status
        if ($status -eq "SUCCESS") {
            return $task
        }
        if ($status -eq "FAILED") {
            throw "Parse task failed: $($task.data.errorMessage)"
        }
        Start-Sleep -Seconds 2
    }
    throw "Parse task did not finish in time: $TaskId"
}

$username = "v3test$([DateTimeOffset]::Now.ToUnixTimeMilliseconds())"
$password = "123456"
$samplePath = Join-Path (Split-Path -Parent $PSScriptRoot) "samples\v3-rag-demo.md"

Write-Host "Register user: $username"
$registerBody = @{
    username = $username
    password = $password
    nickname = "V3 Smoke Test"
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/register" -ContentType "application/json" -Body $registerBody | Out-Null

Write-Host "Login"
$loginBody = @{
    username = $username
    password = $password
} | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" -Body $loginBody
$headers = @{ Authorization = $login.data.tokenValue }

Write-Host "Create knowledge base"
$kbBody = @{
    name = "V3 RAG Demo"
    description = "Smoke test knowledge base"
} | ConvertTo-Json
$kb = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/knowledge-bases" -Headers $headers -ContentType "application/json" -Body $kbBody
$knowledgeBaseId = $kb.data.id

Write-Host "Upload sample document"
$uploadJson = & curl.exe -s -X POST "$BaseUrl/api/documents/upload?knowledgeBaseId=$knowledgeBaseId" -H "Authorization: $($headers.Authorization)" -F "file=@$samplePath"
$upload = $uploadJson | ConvertFrom-Json
$documentId = $upload.data.documentId
$taskId = $upload.data.taskId

Write-Host "Wait parse task: $taskId"
Wait-TaskSuccess -BaseUrl $BaseUrl -Headers $headers -TaskId $taskId | Out-Null

Write-Host "Retrieve RAG chunks"
$retrieveBody = @{
    knowledgeBaseId = $knowledgeBaseId
    question = "Why is Redis fast?"
} | ConvertTo-Json
$retrieve = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/qa/retrieve" -Headers $headers -ContentType "application/json" -Body $retrieveBody

Write-Host "Ask RAG question"
$askBody = @{
    knowledgeBaseId = $knowledgeBaseId
    question = "Why is Redis fast, and how does StudyFlow use Redis?"
} | ConvertTo-Json
$ask = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/qa/ask" -Headers $headers -ContentType "application/json" -Body $askBody

[pscustomobject]@{
    username = $username
    knowledgeBaseId = $knowledgeBaseId
    documentId = $documentId
    taskId = $taskId
    retrieveHitCount = $retrieve.data.hitCount
    sessionId = $ask.data.sessionId
    assistantMessageId = $ask.data.assistantMessageId
    citationCount = $ask.data.citations.Count
    modelName = $ask.data.modelName
    latencyMs = $ask.data.latencyMs
    answerPreview = if ($ask.data.answer.Length -gt 160) { $ask.data.answer.Substring(0, 160) + "..." } else { $ask.data.answer }
} | Format-List

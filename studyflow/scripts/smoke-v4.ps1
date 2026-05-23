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

function Read-SseEvents {
    param(
        [string]$BaseUrl,
        [hashtable]$Headers,
        [object]$Body
    )

    $json = $Body | ConvertTo-Json
    $request = [System.Net.HttpWebRequest]::Create("$BaseUrl/api/qa/ask/stream")
    $request.Method = "POST"
    $request.ContentType = "application/json"
    $request.Accept = "text/event-stream"
    $request.Headers.Add("Authorization", $Headers.Authorization)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $request.ContentLength = $bytes.Length
    $stream = $request.GetRequestStream()
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Close()

    $response = $request.GetResponse()
    $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
    $events = New-Object System.Collections.Generic.List[string]
    while (-not $reader.EndOfStream) {
        $line = $reader.ReadLine()
        if ($line -and $line.StartsWith("event:")) {
            $events.Add($line.Substring(6).Trim())
        }
    }
    $reader.Close()
    $response.Close()
    return $events
}

$username = "v4test$([DateTimeOffset]::Now.ToUnixTimeMilliseconds())"
$password = "123456"
$samplePath = Join-Path (Split-Path -Parent $PSScriptRoot) "samples\v4-rag-demo.md"

Write-Host "Register user: $username"
$registerBody = @{
    username = $username
    password = $password
    nickname = "V4 Smoke Test"
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
    name = "V4 RAG Demo"
    description = "Frontend and streaming QA smoke test"
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
    question = "Why is Redis fast and how does StudyFlow use Redis?"
} | ConvertTo-Json
$retrieve = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/qa/retrieve" -Headers $headers -ContentType "application/json" -Body $retrieveBody

Write-Host "Ask RAG question"
$askBody = @{
    knowledgeBaseId = $knowledgeBaseId
    question = "Why is Redis fast, and where is Redis used in StudyFlow?"
} | ConvertTo-Json
$ask = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/qa/ask" -Headers $headers -ContentType "application/json" -Body $askBody

Write-Host "Get citation detail"
$citationId = $ask.data.citations[0].citationId
$citationDetail = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/qa/citations/$citationId/chunk" -Headers $headers

Write-Host "Ask streaming question"
$streamEvents = Read-SseEvents -BaseUrl $BaseUrl -Headers $headers -Body @{
    knowledgeBaseId = $knowledgeBaseId
    sessionId = $ask.data.sessionId
    question = "Summarize StudyFlow RAG flow."
}

[pscustomobject]@{
    username = $username
    knowledgeBaseId = $knowledgeBaseId
    documentId = $documentId
    taskId = $taskId
    retrieveHitCount = $retrieve.data.hitCount
    includedCount = $retrieve.data.includedCount
    sessionId = $ask.data.sessionId
    assistantMessageId = $ask.data.assistantMessageId
    citationCount = $ask.data.citations.Count
    firstCitationId = $citationId
    citationChunkLength = $citationDetail.data.content.Length
    streamEvents = ($streamEvents -join ",")
    modelName = $ask.data.modelName
    latencyMs = $ask.data.latencyMs
} | Format-List

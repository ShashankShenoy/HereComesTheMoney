param([string]$DocumentName='MODULE-1-AND-2-DETAILED-TEST-PLAN')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskDocPath = Join-Path $taskRoot ('Downloads/integrated/docs/' + $DocumentName + '.docx')
$taskRenderDir = Join-Path $PSScriptRoot 'render'
New-Item -ItemType Directory -Path $taskRenderDir -Force | Out-Null
$taskPdfPath = Join-Path $taskRenderDir ($DocumentName + '.pdf')
$taskWord = $null
$taskDocument = $null
try {
    $taskWord = New-Object -ComObject Word.Application
    $taskWord.Visible = $false
    $taskWord.DisplayAlerts = 0
    $taskDocument = $taskWord.Documents.Open($taskDocPath, $false, $false)
    foreach ($taskStyleName in @('Title','Subtitle','Heading 1','Heading 2','Heading 3')) {
        $taskStyle = $taskDocument.Styles.Item($taskStyleName)
        $taskStyle.Font.Color = 0
        $taskStyle.Font.Name = 'Arial'
        $taskStyle.Borders.Enable = 0
    }
    $taskDocument.Paragraphs.Item(1).Range.Borders.Enable = 0
    $taskDocument.Paragraphs.Item(1).Range.Font.Color = 0
    $taskDocument.Fields.Update() | Out-Null
    foreach ($taskToc in $taskDocument.TablesOfContents) { $taskToc.Update() }
    $taskDocument.Repaginate()
    foreach ($taskToc in $taskDocument.TablesOfContents) { $taskToc.UpdatePageNumbers() }
    $taskDocument.Save()
    $taskDocument.ExportAsFixedFormat($taskPdfPath, 17)
    Write-Output ('Rendered pages: ' + $taskDocument.ComputeStatistics(2))
    Write-Output ('PDF: ' + $taskPdfPath)
} finally {
    if ($null -ne $taskDocument) { $taskDocument.Close(0) }
    if ($null -ne $taskWord) { $taskWord.Quit() }
    if ($null -ne $taskDocument) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($taskDocument) }
    if ($null -ne $taskWord) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($taskWord) }
}

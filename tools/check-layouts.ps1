# Scan every layout for view elements missing layout_width / layout_height.
# Both may come from the element itself OR from the style it references.
$ns = 'http://schemas.android.com/apk/res/android'
$resDir = 'D:\DSH\OurSpace\android\app\src\main\res'

$styles = @{}
$styleDoc = New-Object System.Xml.XmlDocument
$styleDoc.Load((Join-Path $resDir 'values\styles.xml'))
foreach ($s in $styleDoc.resources.style) {
    $name = $s.GetAttribute('name')
    $hasW = $false; $hasH = $false
    foreach ($i in $s.item) {
        $n = $i.GetAttribute('name')
        if ($n -eq 'android:layout_width')  { $hasW = $true }
        if ($n -eq 'android:layout_height') { $hasH = $true }
    }
    $styles[$name] = @{ W = $hasW; H = $hasH }
}
Write-Host "styles providing layout params:"
foreach ($k in $styles.Keys | Sort-Object) {
    Write-Host ("  {0,-16} width={1,-5} height={2}" -f $k, $styles[$k].W, $styles[$k].H)
}
Write-Host ""

$skip = @('merge', 'include', 'requestFocus', 'item', 'fragment')
$problems = 0
foreach ($f in Get-ChildItem (Join-Path $resDir 'layout') -Filter *.xml | Sort-Object Name) {
    $doc = New-Object System.Xml.XmlDocument
    $doc.Load($f.FullName)
    foreach ($e in $doc.SelectNodes('//*')) {
        if ($skip -contains $e.Name) { continue }
        $w = $e.GetAttribute('layout_width', $ns)
        $h = $e.GetAttribute('layout_height', $ns)
        $styleAttr = $e.GetAttribute('style')
        $sName = $null
        if ($styleAttr -match '@style/(.+)$') { $sName = $Matches[1] }
        $viaStyle = ''
        if (-not $w -and $sName -and $styles.ContainsKey($sName) -and $styles[$sName].W) { $w = 'x'; $viaStyle += 'W' }
        if (-not $h -and $sName -and $styles.ContainsKey($sName) -and $styles[$sName].H) { $h = 'x'; $viaStyle += 'H' }

        $missing = @()
        if (-not $w) { $missing += 'layout_width' }
        if (-not $h) { $missing += 'layout_height' }
        if ($missing.Count -gt 0) {
            $li = [System.Xml.IXmlLineInfo]$e
            $line = if ($li.HasLineInfo()) { $li.LineNumber } else { '?' }
            $sTxt = if ($styleAttr) { $styleAttr } else { '-' }
            $problems++
            Write-Host ("[MISSING] {0}:{1}  <{2}>  needs {3}   style={4}" -f $f.Name, $line, $e.Name, ($missing -join '+'), $sTxt)
        }
    }
}
Write-Host ""
if ($problems -eq 0) { Write-Host "OK: no problems found" } else { Write-Host ("TOTAL problems: " + $problems) }

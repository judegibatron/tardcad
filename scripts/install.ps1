$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$VenvPath = Join-Path $ProjectRoot ".venv"

$PythonCommand = Get-Command python -ErrorAction SilentlyContinue
$PythonArgs = @()
if (-not $PythonCommand) {
    $PythonCommand = Get-Command py -ErrorAction SilentlyContinue
    $PythonArgs = @("-3.11")
}
if (-not $PythonCommand) {
    throw "Python 3.11 or newer is required. Install it from python.org, then rerun this script."
}

& $PythonCommand.Source @PythonArgs -c "import sys; assert sys.version_info >= (3, 11), 'Python 3.11 or newer is required'"
& $PythonCommand.Source @PythonArgs -m venv $VenvPath
$PythonPath = Join-Path $VenvPath "Scripts\python.exe"
& $PythonPath -m pip install --upgrade pip
& $PythonPath -m pip install -e "$ProjectRoot[all]"
Write-Host "TardCAD installed. Run: $PythonPath -m tardcad"

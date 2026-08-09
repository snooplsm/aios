$ErrorActionPreference = "Stop"

python "$PSScriptRoot\..\tools\validate_config.py"
python -m unittest discover -s "$PSScriptRoot\..\tests" -v

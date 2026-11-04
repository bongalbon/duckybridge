param(
    [Parameter(Mandatory=$true)][string]$Address,
    [string]$Agent = "$PSScriptRoot\duckybridge-agent.exe",
    [switch]$Pairing
)
$ErrorActionPreference = 'Stop'
$agentArguments = @('serve', '--bind', $Address)
if ($Pairing) { $agentArguments += '--pairing' }
& $Agent @agentArguments
exit $LASTEXITCODE

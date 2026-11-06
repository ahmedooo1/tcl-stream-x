# Diagnostic of the remote Samsung path (docs/SAMSUNG-JOUR-J.md): PC, Tailscale, phone relay, TV.
# Read-only on the TV: it connects with sdb and reads information. Each step says where it stopped.
#   pwsh tools/tv-relay-check.ps1 -Phone <phone Tailscale IP> [-Port 26101]
param(
  [Parameter(Mandatory)][string]$Phone,
  [int]$Port = 26101
)
$tailscale = 'C:\Program Files\Tailscale\tailscale.exe'
$sdb = 'C:\tizen-studio\tools\sdb.exe'
$target = "${Phone}:$Port"

function Stop-At([string]$message) { "STOP: $message"; exit 1 }

'1. Tailscale'
& $tailscale ping --c 2 --timeout 5s $Phone 2>&1 | Select-Object -Last 2
if ($LASTEXITCODE -ne 0) { Stop-At 'phone unreachable: open Tailscale on the phone, check mobile data or Wi-Fi.' }

'2. Relay on the phone'
$tcp = Test-NetConnection -ComputerName $Phone -Port $Port -WarningAction SilentlyContinue
if (-not $tcp.TcpTestSucceeded) { Stop-At "nothing listens on ${target}: start socat in Termux, keep Termux open." }
"port $Port open (this only proves the relay listens, not that the TV answers)"

'3. sdb'
& $sdb disconnect $target 2>&1 | Out-Null
$connect = (& $sdb connect $target 2>&1 | Out-String).Trim()
$connect
Start-Sleep -Seconds 2
$devices = (& $sdb devices 2>&1 | Out-String).Trim()
$devices
$line = $devices -split "`r?`n" | Where-Object { $_ -like "$target*" } | Select-Object -First 1
if (-not $line) {
  Stop-At 'relay open but no TV session. Read the socat -d -d output in Termux: "Connection refused" or a timeout means wrong TV IP or TV not in developer mode; a connection closed at once means the IP declared on the TV is not the phone Wi-Fi IP. Fix it, then restart the TV.'
}
if ($line -match 'offline') { Stop-At 'TV seen but offline: restart the TV, then run this check again.' }

'4. TV'
$line
'capability:'
& $sdb -s $target capability 2>&1 | Select-String -Pattern 'platform_version|profile_name|sdbd_version|secure_protocol' | ForEach-Object { $_.Line.Trim() }
'DUID:'
& $sdb -s $target shell 0 getduid 2>&1
'OK: the relay reaches the TV. Next: distributor certificate with this DUID.'

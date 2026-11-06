# Windows helper for the Samsung signing secrets (docs/INSTALL-SAMSUNG.md). Run with pwsh.
# Values stay in the Windows Credential Manager, and go through memory and stdin only: this
# script never prints them and never passes them as a command argument.
#   status                          which targets exist (yes / no)
#   new <target>                    random password, refuses to replace an existing one
#   clip <target> [seconds]         clipboard outside Windows history, then cleared (pwsh -STA)
#   gh-set <owner/repo> <certDir>   the five GitHub secrets
#   decrypt <in.wgt.enc> <out.wgt>  checks and decrypts the CI package
param(
  [Parameter(Mandatory, Position = 0)][ValidateSet('new', 'status', 'clip', 'gh-set', 'decrypt')][string]$Action,
  [Parameter(Position = 1)][string]$A,
  [Parameter(Position = 2)][string]$B
)
$ErrorActionPreference = 'Stop'
$Targets = 'TizenAuthorCert', 'TizenDistributorCert', 'TizenArtifactPassphrase'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class WinCred {
  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
  struct CREDENTIAL {
    public int Flags, Type; public string TargetName, Comment;
    public long LastWritten; public int CredentialBlobSize; public IntPtr CredentialBlob;
    public int Persist, AttributeCount; public IntPtr Attributes;
    public string TargetAlias, UserName;
  }
  [DllImport("advapi32", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
  static extern bool CredWrite(ref CREDENTIAL c, int flags);
  [DllImport("advapi32", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
  static extern bool CredRead(string target, int type, int flags, out IntPtr c);
  [DllImport("advapi32")] static extern void CredFree(IntPtr p);
  public static void Write(string target, string user, string value) {
    IntPtr blob = Marshal.StringToCoTaskMemUni(value);
    try {
      var c = new CREDENTIAL { Type = 1, TargetName = target, UserName = user, Persist = 2,
        CredentialBlob = blob, CredentialBlobSize = value.Length * 2 };
      if (!CredWrite(ref c, 0)) throw new System.ComponentModel.Win32Exception();
    } finally { Marshal.ZeroFreeCoTaskMemUnicode(blob); }
  }
  public static string Read(string target) {
    IntPtr p;
    if (!CredRead(target, 1, 0, out p)) return null;
    try {
      var c = Marshal.PtrToStructure<CREDENTIAL>(p);
      return Marshal.PtrToStringUni(c.CredentialBlob, c.CredentialBlobSize / 2);
    } finally { CredFree(p); }
  }
}
'@

function Need([string]$target) {
  $v = [WinCred]::Read($target)
  if (-not $v) { throw "$target is not in the Credential Manager" }
  $v
}

function New-Password {
  $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
  $out = [Text.StringBuilder]::new()
  while ($out.Length -lt 32) {
    [void]$out.Append($alphabet[[Security.Cryptography.RandomNumberGenerator]::GetInt32(0, $alphabet.Length)])
  }
  $out.ToString()
}

function Set-GhSecret([string]$name, [string]$value, [string]$repo) {
  $i = [Diagnostics.ProcessStartInfo]::new('gh')
  foreach ($arg in 'secret', 'set', $name, '--repo', $repo) { $i.ArgumentList.Add($arg) }
  $i.RedirectStandardInput = $true; $i.RedirectStandardOutput = $true; $i.RedirectStandardError = $true
  $i.UseShellExecute = $false
  $p = [Diagnostics.Process]::Start($i)
  $p.StandardInput.Write($value); $p.StandardInput.Close()
  $p.WaitForExit()
  if ($p.ExitCode -ne 0) { throw "gh secret set $name failed ($($p.ExitCode))" }
  "$name : set"
}

function Stream0 { [IO.MemoryStream]::new([byte[]](0, 0, 0, 0)) }

switch ($Action) {
  'status' { foreach ($t in $Targets) { "$t : $(if ([WinCred]::Read($t)) { 'yes' } else { 'no' })" } }
  'new' {
    if ($A -notin $Targets) { throw "target: $($Targets -join ', ')" }
    if ([WinCred]::Read($A)) { throw "$A already exists, it is not replaced" }
    [WinCred]::Write($A, 'streamx', (New-Password))
    "$A : created"
  }
  'clip' {
    $value = Need $A
    $seconds = if ($B) { [int]$B } else { 180 }
    if ([Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') { throw 'run with: pwsh -STA' }
    Add-Type -AssemblyName System.Windows.Forms
    $key = 'HKCU:\Software\Microsoft\Clipboard'
    $old = (Get-ItemProperty $key -ErrorAction SilentlyContinue).EnableClipboardHistory
    try {
      Set-ItemProperty $key EnableClipboardHistory 0 -Type DWord
      $data = [Windows.Forms.DataObject]::new()
      $data.SetText($value)
      # Documented formats: keep this content out of the clipboard history and of cloud sync.
      $data.SetData('ExcludeClipboardContentFromMonitorProcessing', (Stream0))
      $data.SetData('CanIncludeInClipboardHistory', (Stream0))
      $data.SetData('CanUploadToCloudClipboard', (Stream0))
      [Windows.Forms.Clipboard]::SetDataObject($data, $true)
      "$A : in the clipboard for $seconds s"
      Start-Sleep -Seconds $seconds
    } finally {
      [Windows.Forms.Clipboard]::Clear()
      if ($null -ne $old) { Set-ItemProperty $key EnableClipboardHistory $old -Type DWord }
      else { Remove-ItemProperty $key EnableClipboardHistory -ErrorAction SilentlyContinue }
      'clipboard cleared, history setting restored'
    }
  }
  'gh-set' {
    if (-not $A -or -not $B) { throw 'usage: gh-set <owner/repo> <certificateDir>' }
    $author = Join-Path $B 'author.p12'; $distributor = Join-Path $B 'distributor.p12'
    foreach ($f in $author, $distributor) { if (-not (Test-Path $f)) { throw "missing $f" } }
    $authorPw = Need 'TizenAuthorCert'; $distributorPw = Need 'TizenDistributorCert'; $key = Need 'TizenArtifactPassphrase'
    Set-GhSecret 'TIZEN_AUTHOR_P12' ([Convert]::ToBase64String([IO.File]::ReadAllBytes($author))) $A
    Set-GhSecret 'TIZEN_AUTHOR_PASSWORD' $authorPw $A
    Set-GhSecret 'TIZEN_DISTRIBUTOR_P12' ([Convert]::ToBase64String([IO.File]::ReadAllBytes($distributor))) $A
    Set-GhSecret 'TIZEN_DISTRIBUTOR_PASSWORD' $distributorPw $A
    Set-GhSecret 'TIZEN_ARTIFACT_PASSPHRASE' $key $A
  }
  'decrypt' {
    if (-not $A -or -not $B) { throw 'usage: decrypt <in.wgt.enc> <out.wgt>' }
    $env:WGT_PASSPHRASE = Need 'TizenArtifactPassphrase'
    try {
      node (Join-Path $PSScriptRoot 'wgt-seal.mjs') unseal $A $B
      if ($LASTEXITCODE -ne 0) { throw 'decryption refused' }
    } finally { Remove-Item Env:WGT_PASSPHRASE -ErrorAction SilentlyContinue }
  }
}

Option Explicit

Dim shell, fso, root, ps, script
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

root = fso.GetParentFolderName(WScript.ScriptFullName)
ps = shell.ExpandEnvironmentStrings("%SystemRoot%") & "\System32\WindowsPowerShell\v1.0\powershell.exe"
script = root & "\start-hidden.ps1"

shell.Run Q(ps) & " -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File " & Q(script), 0, False

Function Q(value)
  Q = """" & Replace(CStr(value), """", """""") & """"
End Function

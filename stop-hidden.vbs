Option Explicit

Dim shell, fso, root, configFile, comspec, stopLog
Dim backendPort, frontendPort

Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

root = fso.GetParentFolderName(WScript.ScriptFullName)
configFile = root & "\config.env"
comspec = shell.ExpandEnvironmentStrings("%ComSpec%")
stopLog = root & "\atlas-stop.log"

LoadConfig configFile
backendPort = EnvOrDefault("ATLAS_SERVER_PORT", "8080")
frontendPort = EnvOrDefault("VITE_DEV_PORT", "5173")

WriteLine stopLog, Now & " Atlas hidden stop requested."
StopPort backendPort
StopPort frontendPort
WScript.Quit 0

Sub LoadConfig(path)
  Dim file, line, trimmed, pos, key, value
  If Not fso.FileExists(path) Then Exit Sub
  Set file = fso.OpenTextFile(path, 1, False)
  Do Until file.AtEndOfStream
    line = file.ReadLine
    trimmed = Trim(line)
    If Len(trimmed) > 0 And Left(trimmed, 1) <> "#" Then
      pos = InStr(trimmed, "=")
      If pos > 1 Then
        key = Trim(Left(trimmed, pos - 1))
        value = Mid(trimmed, pos + 1)
        shell.Environment("PROCESS")(key) = value
      End If
    End If
  Loop
  file.Close
End Sub

Function EnvOrDefault(name, fallback)
  Dim value
  value = shell.Environment("PROCESS")(name)
  If Len(value) = 0 Then value = shell.ExpandEnvironmentStrings("%" & name & "%")
  If value = "%" & name & "%" Then value = ""
  If Len(value) = 0 Then
    EnvOrDefault = fallback
  Else
    EnvOrDefault = value
  End If
End Function

Sub StopPort(port)
  Dim tempPath, file, line, parts, pid
  tempPath = shell.ExpandEnvironmentStrings("%TEMP%") & "\atlas-stop-port-" & port & ".txt"
  On Error Resume Next
  If fso.FileExists(tempPath) Then fso.DeleteFile tempPath, True
  shell.Run Q(comspec) & " /d /s /c netstat -ano | findstr "":" & port & """ | findstr ""LISTENING"" > " & Q(tempPath), 0, True
  If fso.FileExists(tempPath) Then
    Set file = fso.OpenTextFile(tempPath, 1, False)
    Do Until file.AtEndOfStream
      line = Trim(file.ReadLine)
      If Len(line) > 0 Then
        parts = SplitBySpaces(line)
        pid = parts(UBound(parts))
        If IsNumeric(pid) Then
          shell.Run Q(comspec) & " /d /s /c taskkill /F /PID " & pid & " >nul 2>nul", 0, True
          WriteLine stopLog, Now & " Stopped port " & port & " PID=" & pid & "."
        End If
      End If
    Loop
    file.Close
    fso.DeleteFile tempPath, True
  End If
  On Error GoTo 0
End Sub

Function SplitBySpaces(value)
  Dim re
  Set re = CreateObject("VBScript.RegExp")
  re.Pattern = "\s+"
  re.Global = True
  SplitBySpaces = Split(re.Replace(Trim(value), " "), " ")
End Function

Sub WriteLine(path, text)
  Dim file
  Set file = fso.OpenTextFile(path, 8, True)
  file.WriteLine text
  file.Close
End Sub

Function Q(value)
  Q = """" & Replace(CStr(value), """", """""") & """"
End Function

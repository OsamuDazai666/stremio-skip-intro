' Runs the Skip Intro watcher without a console window.
Set fso = CreateObject("Scripting.FileSystemObject")
here = fso.GetParentFolderName(WScript.ScriptFullName)
cmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & here & "\Start-StremioSkipIntro.ps1"""
CreateObject("WScript.Shell").Run cmd, 0, False

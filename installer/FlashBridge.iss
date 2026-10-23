#define MyAppName "FlashBridge"
#define MyAppVersion "0.1.0"
#define MyAppExeName "FlashBridge.exe"

[Setup]
AppId={{E5264EBF-2702-4DA3-818D-9D98C6A38043}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=AbhayShah06
DefaultDirName={localappdata}\Programs\FlashBridge
DefaultGroupName=FlashBridge
PrivilegesRequired=lowest
DisableProgramGroupPage=yes
OutputDir=..\dist
OutputBaseFilename=FlashBridge-Setup
Compression=lzma
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\{#MyAppExeName}
CloseApplications=yes

[Files]
Source: "..\dist\{#MyAppExeName}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\FlashBridge"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\FlashBridge"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch FlashBridge"; Flags: nowait postinstall skipifsilent

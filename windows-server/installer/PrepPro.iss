#define MyAppName "PrepPro"
#define MyAppVersion "1.0.1"

[Setup]
AppId={{D6F4B93A-4A1D-4F61-8E43-EA7FC2F0A8AD}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=PrepPro
DefaultDirName={localappdata}\Programs\PrepPro
DefaultGroupName=PrepPro
DisableProgramGroupPage=yes
SetupIconFile=..\image\icon.ico
UninstallDisplayIcon={app}\image\icon.ico
OutputDir=..\dist-installer
OutputBaseFilename=PrepPro-Setup
Compression=lzma
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupLogging=yes

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务:"; Flags: unchecked

[Files]
Source: "..\*.py"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\*.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\*.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\requirements.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\.venv\*"; DestDir: "{app}\.venv"; Flags: recursesubdirs ignoreversion createallsubdirs
Source: "bundled-venv.marker"; DestDir: "{app}\.venv"; DestName: ".preppro_bundled"; Flags: ignoreversion
Source: "..\image\*"; DestDir: "{app}\image"; Flags: recursesubdirs ignoreversion createallsubdirs
Source: "..\RapidOCR-json_v0.2.0\*"; DestDir: "{app}\RapidOCR-json_v0.2.0"; Flags: recursesubdirs ignoreversion createallsubdirs

[Icons]
Name: "{group}\启动 PrepPro"; Filename: "{app}\get-start.bat"; IconFilename: "{app}\image\icon.ico"
Name: "{group}\卸载 PrepPro"; Filename: "{uninstallexe}"
Name: "{autodesktop}\PrepPro"; Filename: "{app}\get-start.bat"; IconFilename: "{app}\image\icon.ico"; Tasks: desktopicon

[Run]
Filename: "{app}\get-start.bat"; Description: "安装完成后立即启动 PrepPro"; Flags: nowait postinstall shellexec skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}\.venv"
Type: filesandordirs; Name: "{app}\log"
Type: files; Name: "{app}\server.log"

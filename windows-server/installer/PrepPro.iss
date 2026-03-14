#define MyAppName "PrepPro"
#define MyAppVersion "1.0.4"

#ifexist "..\..\.venv\Scripts\python.exe"
	#define BundledVenvSource "..\..\.venv"
#else
	#define BundledVenvSource "..\.venv"
#endif

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
ChangesEnvironment=yes

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务:"; Flags: unchecked

[Files]
Source: "..\*.py"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\*.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\*.bat"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\requirements.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "bundled-python\*"; DestDir: "{app}\python-runtime"; Flags: recursesubdirs ignoreversion createallsubdirs; Check: NeedInstallBundledPython
Source: "{#BundledVenvSource}\*"; DestDir: "{app}\.venv"; Flags: recursesubdirs ignoreversion createallsubdirs
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
Type: filesandordirs; Name: "{app}\__pycache__"
Type: filesandordirs; Name: "{app}\tests\__pycache__"
Type: files; Name: "{app}\.preppro_python_runtime"
Type: files; Name: "{app}\server.log"
Type: files; Name: "{app}\model_profiles.json"
Type: files; Name: "{app}\app_settings.json"

[Code]
var
	HadPythonBeforeInstall: Boolean;

function GetBundledPythonRuntimeDir: string;
begin
	Result := ExpandConstant('{app}\python-runtime');
end;

function GetBundledPythonScriptsDir: string;
begin
	Result := AddBackslash(GetBundledPythonRuntimeDir) + 'Scripts';
end;

function GetBundledPythonMarkerPath: string;
begin
	Result := ExpandConstant('{app}\.preppro_python_runtime');
end;

function NormalizePathEntry(const Value: string): string;
begin
	Result := RemoveBackslashUnlessRoot(Trim(RemoveQuotes(Value)));
end;

function NextPathToken(var Remaining: string): string;
var
	SepPos: Integer;
begin
	SepPos := Pos(';', Remaining);
	if SepPos > 0 then
	begin
		Result := Copy(Remaining, 1, SepPos - 1);
		Remaining := Copy(Remaining, SepPos + 1, MaxInt);
	end
	else
	begin
		Result := Remaining;
		Remaining := '';
	end;
end;

function PathContainsEntry(const FullPath: string; const Entry: string): Boolean;
var
	Remaining: string;
	Token: string;
	NormalizedEntry: string;
begin
	Result := False;
	NormalizedEntry := Lowercase(NormalizePathEntry(Entry));
	if NormalizedEntry = '' then
		Exit;

	Remaining := FullPath;
	while Remaining <> '' do
	begin
		Token := NormalizePathEntry(NextPathToken(Remaining));
		if Lowercase(Token) = NormalizedEntry then
		begin
			Result := True;
			Exit;
		end;
	end;
end;

function AddEntryToPath(const FullPath: string; const Entry: string): string;
var
	TrimmedPath: string;
	NormalizedEntry: string;
begin
	TrimmedPath := Trim(FullPath);
	NormalizedEntry := NormalizePathEntry(Entry);
	if NormalizedEntry = '' then
	begin
		Result := TrimmedPath;
		Exit;
	end;

	if PathContainsEntry(TrimmedPath, NormalizedEntry) then
	begin
		Result := TrimmedPath;
		Exit;
	end;

	if TrimmedPath = '' then
		Result := NormalizedEntry
	else
		Result := TrimmedPath + ';' + NormalizedEntry;
end;

function RemoveEntryFromPath(const FullPath: string; const Entry: string): string;
var
	Remaining: string;
	NormalizedEntry: string;
	Token: string;
begin
	Result := '';
	NormalizedEntry := Lowercase(NormalizePathEntry(Entry));
	if NormalizedEntry = '' then
	begin
		Result := FullPath;
		Exit;
	end;

	Remaining := FullPath;
	while Remaining <> '' do
	begin
		Token := NormalizePathEntry(NextPathToken(Remaining));
		if (Token <> '') and (Lowercase(Token) <> NormalizedEntry) then
		begin
			if Result = '' then
				Result := Token
			else
				Result := Result + ';' + Token;
		end;
	end;
end;

function TryReadUserPath(var Value: string): Boolean;
begin
	Result := RegQueryStringValue(HKCU, 'Environment', 'Path', Value);
	if not Result then
		Value := '';
end;

procedure WriteUserPath(const Value: string);
begin
	if Trim(Value) = '' then
		RegDeleteValue(HKCU, 'Environment', 'Path')
	else
		RegWriteExpandStringValue(HKCU, 'Environment', 'Path', Value);
end;

procedure AddBundledPythonToUserPath;
var
	CurrentPath: string;
	NewPath: string;
begin
	TryReadUserPath(CurrentPath);
	NewPath := AddEntryToPath(CurrentPath, GetBundledPythonRuntimeDir);
	NewPath := AddEntryToPath(NewPath, GetBundledPythonScriptsDir);
	if NewPath <> CurrentPath then
		WriteUserPath(NewPath);
end;

procedure RemoveBundledPythonFromUserPath;
var
	CurrentPath: string;
	NewPath: string;
begin
	TryReadUserPath(CurrentPath);
	NewPath := RemoveEntryFromPath(CurrentPath, GetBundledPythonRuntimeDir);
	NewPath := RemoveEntryFromPath(NewPath, GetBundledPythonScriptsDir);
	if NewPath <> CurrentPath then
		WriteUserPath(NewPath);
end;

function IsPythonAvailable: Boolean;
var
	ResultCode: Integer;
begin
	Result := Exec(
		ExpandConstant('{cmd}'),
		'/C "python --version"',
		'',
		SW_HIDE,
		ewWaitUntilTerminated,
		ResultCode
	) and (ResultCode = 0);
end;

function InitializeSetup: Boolean;
begin
	HadPythonBeforeInstall := IsPythonAvailable;
	Result := True;
end;

function NeedInstallBundledPython: Boolean;
begin
	Result := not HadPythonBeforeInstall;
end;

procedure InstallBundledPythonIfNeeded;
var
	RuntimeDir: string;
begin
	if HadPythonBeforeInstall then
		Exit;

	RuntimeDir := GetBundledPythonRuntimeDir;
	if not FileExists(AddBackslash(RuntimeDir) + 'python.exe') then
		RaiseException('内置 Python 运行时缺失: python-runtime\\python.exe');

	AddBundledPythonToUserPath;
	SaveStringToFile(GetBundledPythonMarkerPath, RuntimeDir + #13#10 + GetBundledPythonScriptsDir, False);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
	if CurStep = ssPostInstall then
		InstallBundledPythonIfNeeded;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
	if CurUninstallStep <> usUninstall then
		Exit;

	RemoveBundledPythonFromUserPath;
	if FileExists(GetBundledPythonMarkerPath) then
		DelTree(GetBundledPythonRuntimeDir, True, True, True);
end;

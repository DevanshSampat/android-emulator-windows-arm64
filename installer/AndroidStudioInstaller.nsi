; ============================================================================
; Android Studio 2026.2.1 (Windows ARM64) NSIS Setup Script
; Built for Windows on ARM (Snapdragon X Elite / X Plus / ARM64 PCs)
; Includes Native ARM64 Layoutlib & Compose Previews Pre-Configured
; Full Official Windows App & Start Menu Search Integration
; Includes the Windows ARM64 Android Emulator (WHPX)
; ============================================================================

!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "LogicLib.nsh"

; General Configuration
Name "Android Studio"
Caption "Android Studio 2026.2.1 (ARM64 Windows) Setup"
OutFile "AndroidStudio-2026.2.1-Windows-ARM64-Setup.exe"
InstallDir "$PROGRAMFILES64\Android\Android Studio"
InstallDirRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\App Paths\studio64.exe" "Path"
!ifdef TESTBUILD
  ; Payload test only: unprivileged, and never touches the real SDK.
  RequestExecutionLevel user
  !define SDK_ROOT "$INSTDIR\test-sdk"
!else
  RequestExecutionLevel admin
  !define SDK_ROOT "$LOCALAPPDATA\Android\Sdk"
!endif

; Fast solid zlib container compression (studio.7z is already LZMA-compressed)
SetCompressor /SOLID zlib

; Branding Icons
!define MUI_ICON "../android-studio-dist/android-studio/bin/studio.ico"
!define MUI_UNICON "../android-studio-dist/android-studio/bin/studio.ico"
!define MUI_ABORTWARNING

; ============================================================================
; Wizard Pages
; ============================================================================
; Welcome page
!define MUI_WELCOMEPAGE_TITLE "Welcome to Android Studio Setup (Windows ARM64)"
!define MUI_WELCOMEPAGE_TEXT "This wizard will install Android Studio 2026.2.1 compiled and optimized for Windows on ARM (Snapdragon X Elite / X Plus).\r\n\r\nFeatures pre-installed native ARM64 Layoutlib and Jetpack Compose renderers with zero emulation overhead!\r\n\r\nClick Next to continue."
!insertmacro MUI_PAGE_WELCOME

; License page
!insertmacro MUI_PAGE_LICENSE "../android-studio-dist/android-studio/LICENSE.txt"

; Directory selection
!insertmacro MUI_PAGE_DIRECTORY

; Installation files
!insertmacro MUI_PAGE_INSTFILES

; Finish page
!define MUI_FINISHPAGE_RUN "$INSTDIR\bin\studio64.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Start Android Studio"
!define MUI_FINISHPAGE_LINK "Android Studio for Windows ARM64 releases"
!define MUI_FINISHPAGE_LINK_LOCATION "https://github.com/DevanshSampat/android-studio-arm64-releases"
!insertmacro MUI_PAGE_FINISH

; Uninstaller Pages
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

; Language
!insertmacro MUI_LANGUAGE "English"

; ============================================================================
; Initialization
; ============================================================================
Function .onInit
  SetShellVarContext all
  SetRegView 64
FunctionEnd

Function un.onInit
  SetShellVarContext all
  SetRegView 64
FunctionEnd

; ============================================================================
; Installer Section
; ============================================================================
Section "Android Studio Core" SecCore
  SectionIn RO ; Mandatory
  
  SetShellVarContext all
  SetRegView 64
  
  SetOutPath "$INSTDIR"
  
  ; Write uninstaller binary into target installation directory
  WriteUninstaller "$INSTDIR\uninstall.exe"
  
  ; Extract native ARM64 unpacker tools to secure temporary directory
  InitPluginsDir
  SetCompress off
  SetOutPath "$PLUGINSDIR"
  File "arm64_7z/7z.exe"
  File "arm64_7z/7z.dll"
  File "studio.7z"
  File "emulator.7z"
  SetCompress auto
  
  DetailPrint "Extracting Android Studio 2026.2.1 ARM64 distribution..."
  DetailPrint "Installation path: $INSTDIR"
  
  ; Execute multi-threaded native ARM64 7-zip decompression directly to $INSTDIR
  nsExec::ExecToLog '"$PLUGINSDIR\7z.exe" x -y -o"$INSTDIR" "$PLUGINSDIR\studio.7z"'
  Pop $0
  
  ${If} $0 != 0
    MessageBox MB_OK|MB_ICONSTOP "Extraction failed with exit code $0. Please ensure you have at least 6 GB free disk space."
    Abort
  ${EndIf}
  
  ; ==========================================================================
  ; Android Emulator for Windows ARM64, into the SDK Android Studio uses by default
  ; ==========================================================================
  SetShellVarContext current
  DetailPrint "Installing Android Emulator to ${SDK_ROOT}\emulator..."
  CreateDirectory "${SDK_ROOT}\emulator"
  nsExec::ExecToLog '"$PLUGINSDIR\7z.exe" x -y -o"${SDK_ROOT}\emulator" "$PLUGINSDIR\emulator.7z"'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_OK|MB_ICONSTOP "Android Emulator extraction failed with exit code $0."
    Abort
  ${EndIf}
  SetShellVarContext all

  ; Updates come from this project's releases, not Google's update feed.
  DetailPrint "Disabling Android Studio update checks..."
  FileOpen $1 "$INSTDIR\bin\idea.properties" a
  FileSeek $1 0 END
  FileWrite $1 "$\r$\n# Updates are published at github.com/DevanshSampat/android-studio-arm64-releases$\r$\nide.no.platform.update=true$\r$\n"
  FileClose $1

!ifndef TESTBUILD
  ; The emulator runs on Windows Hypervisor Platform.
  DetailPrint "Enabling Windows Hypervisor Platform..."
  nsExec::ExecToLog '"$WINDIR\sysnative\dism.exe" /online /enable-feature /featurename:HypervisorPlatform /all /norestart'
  Pop $0
  ${If} $0 == 3010
    SetRebootFlag true
  ${ElseIf} $0 != 0
    MessageBox MB_OK|MB_ICONEXCLAMATION "Could not enable Windows Hypervisor Platform (code $0). Turn it on under Windows Features before starting an emulator."
  ${EndIf}
!endif

  DetailPrint "All files extracted and verified successfully!"
  
  ; Set working directory for shortcuts
  SetOutPath "$INSTDIR\bin"
  
!ifndef TESTBUILD
  ; ==========================================================================
  ; 1. Start Menu & Desktop Shortcuts (All Users - C:\ProgramData)
  ; ==========================================================================
  SetShellVarContext all
  DetailPrint "Registering Start Menu shortcuts (All Users)..."
  CreateDirectory "$SMPROGRAMS"
  ; Primary top-level shortcut indexed directly by Windows Search
  CreateShortcut "$SMPROGRAMS\Android Studio.lnk" "$INSTDIR\bin\studio64.exe" "" "$INSTDIR\bin\studio.ico" 0 "" "" "Android Studio"
  
  ; Subfolder shortcut
  CreateDirectory "$SMPROGRAMS\Android Studio"
  CreateShortcut "$SMPROGRAMS\Android Studio\Android Studio.lnk" "$INSTDIR\bin\studio64.exe" "" "$INSTDIR\bin\studio.ico" 0 "" "" "Android Studio"
  CreateShortcut "$SMPROGRAMS\Android Studio\Uninstall Android Studio.lnk" "$INSTDIR\uninstall.exe" "" "" 0 "" "" "Uninstall Android Studio"
  
  ; Desktop shortcut (Public Desktop)
  CreateShortcut "$DESKTOP\Android Studio.lnk" "$INSTDIR\bin\studio64.exe" "" "$INSTDIR\bin\studio.ico" 0 "" "" "Android Studio"
  
  ; ==========================================================================
  ; 2. Current User Shortcuts (AppData / Roaming / User Desktop)
  ; ==========================================================================
  SetShellVarContext current
  DetailPrint "Registering Start Menu shortcuts (Current User)..."
  CreateDirectory "$SMPROGRAMS"
  CreateShortcut "$SMPROGRAMS\Android Studio.lnk" "$INSTDIR\bin\studio64.exe" "" "$INSTDIR\bin\studio.ico" 0 "" "" "Android Studio"
  CreateDirectory "$SMPROGRAMS\Android Studio"
  CreateShortcut "$SMPROGRAMS\Android Studio\Android Studio.lnk" "$INSTDIR\bin\studio64.exe" "" "$INSTDIR\bin\studio.ico" 0 "" "" "Android Studio"
  CreateShortcut "$DESKTOP\Android Studio.lnk" "$INSTDIR\bin\studio64.exe" "" "$INSTDIR\bin\studio.ico" 0 "" "" "Android Studio"
  
  ; Restore all-users context
  SetShellVarContext all
  
!endif

  ; ==========================================================================
  ; 3. Windows App Paths Registration (Search & Run Dialog)
  ; ==========================================================================
  DetailPrint "Registering Windows App Paths & System Capabilities..."
  WriteRegStr HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\studio64.exe" "" "$INSTDIR\bin\studio64.exe"
  WriteRegStr HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\studio64.exe" "Path" "$INSTDIR\bin"
  WriteRegStr HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\studio.exe" "" "$INSTDIR\bin\studio64.exe"
  WriteRegStr HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\studio.exe" "Path" "$INSTDIR\bin"
  
  ; ==========================================================================
  ; 4. Windows Application Capabilities & RegisteredApplications
  ; ==========================================================================
  WriteRegStr HKLM "SOFTWARE\Classes\Applications\studio64.exe" "FriendlyAppName" "Android Studio"
  WriteRegStr HKLM "SOFTWARE\Classes\Applications\studio64.exe" "ApplicationCompany" "Google LLC"
  WriteRegStr HKLM "SOFTWARE\Classes\Applications\studio64.exe\DefaultIcon" "" "$INSTDIR\bin\studio.ico,0"
  WriteRegStr HKLM "SOFTWARE\Classes\Applications\studio64.exe\shell\open\command" "" '"$INSTDIR\bin\studio64.exe" "%1"'
  
  WriteRegStr HKLM "SOFTWARE\Android\Android Studio\Capabilities" "ApplicationName" "Android Studio"
  WriteRegStr HKLM "SOFTWARE\Android\Android Studio\Capabilities" "ApplicationDescription" "Android Studio IDE"
  WriteRegStr HKLM "SOFTWARE\Android\Android Studio\Capabilities" "ApplicationIcon" "$INSTDIR\bin\studio.ico,0"
  WriteRegStr HKLM "SOFTWARE\RegisteredApplications" "Android Studio" "SOFTWARE\Android\Android Studio\Capabilities"
  
  ; ==========================================================================
  ; 5. Native 64-bit Windows Installed Apps (Control Panel / Settings)
  ; ==========================================================================
  WriteRegStr HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AndroidStudioARM64" "DisplayName" "Android Studio"
  WriteRegStr HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AndroidStudioARM64" "DisplayVersion" "2026.2.1"
  WriteRegStr HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AndroidStudioARM64" "Publisher" "Google LLC"
  WriteRegStr HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AndroidStudioARM64" "DisplayIcon" "$INSTDIR\bin\studio.ico,0"
  WriteRegStr HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AndroidStudioARM64" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AndroidStudioARM64" "InstallLocation" "$INSTDIR"
  WriteRegDWORD HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AndroidStudioARM64" "NoModify" 1
  WriteRegDWORD HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AndroidStudioARM64" "NoRepair" 1
  WriteRegDWORD HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AndroidStudioARM64" "EstimatedSize" 3630000 ; ~3.5 GB
  
  ; ==========================================================================
  ; 6. Refresh Windows Explorer & Search Indexer Cache
  ; ==========================================================================
  DetailPrint "Updating Windows Shell & Search index..."
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, i 0, i 0)'
SectionEnd

; ============================================================================
; Uninstaller Section
; ============================================================================
Section "Uninstall"
  SetShellVarContext all
  SetRegView 64
  
  ; Remove All Users Shortcuts
  Delete "$SMPROGRAMS\Android Studio.lnk"
  Delete "$SMPROGRAMS\Android Studio\Android Studio.lnk"
  Delete "$SMPROGRAMS\Android Studio\Uninstall Android Studio.lnk"
  RMDir "$SMPROGRAMS\Android Studio"
  Delete "$DESKTOP\Android Studio.lnk"
  
  ; Remove Current User Shortcuts
  SetShellVarContext current
  Delete "$SMPROGRAMS\Android Studio.lnk"
  Delete "$SMPROGRAMS\Android Studio\Android Studio.lnk"
  RMDir "$SMPROGRAMS\Android Studio"
  Delete "$DESKTOP\Android Studio.lnk"
  SetShellVarContext all
  
  ; Remove Registry Keys
  DeleteRegKey HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\studio64.exe"
  DeleteRegKey HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\studio.exe"
  DeleteRegKey HKLM "SOFTWARE\Classes\Applications\studio64.exe"
  DeleteRegValue HKLM "SOFTWARE\RegisteredApplications" "Android Studio"
  DeleteRegKey HKLM "SOFTWARE\Android\Android Studio"
  DeleteRegKey HKLM "SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\AndroidStudioARM64"
  
  ; Remove the bundled emulator; never the SDK, its system images or the user's AVDs.
  SetShellVarContext current
  ${If} ${FileExists} "$LOCALAPPDATA\Android\Sdk\emulator\whpx_bridge\*.*"
    RMDir /r "$LOCALAPPDATA\Android\Sdk\emulator"
  ${EndIf}
  SetShellVarContext all

  ; Remove Installation Files
  RMDir /r "$INSTDIR\bin"
  RMDir /r "$INSTDIR\jbr"
  RMDir /r "$INSTDIR\lib"
  RMDir /r "$INSTDIR\license"
  RMDir /r "$INSTDIR\plugins"
  Delete "$INSTDIR\LICENSE.txt"
  Delete "$INSTDIR\NOTICE.txt"
  Delete "$INSTDIR\build.txt"
  Delete "$INSTDIR\product-info.json"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  
  ; Refresh Shell
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, i 0, i 0)'
SectionEnd

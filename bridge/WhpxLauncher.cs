using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

class WhpxLauncher {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    static extern IntPtr CreateJobObject(IntPtr lpJobAttributes, string lpName);

    [DllImport("kernel32.dll")]
    static extern bool SetInformationJobObject(IntPtr hJob, int JobObjectInfoClass, IntPtr lpJobObjectInfo, uint cbJobObjectInfoLength);

    [DllImport("kernel32.dll")]
    static extern bool AssignProcessToJobObject(IntPtr hJob, IntPtr hProcess);

    [StructLayout(LayoutKind.Sequential)]
    struct JOBOBJECT_BASIC_LIMIT_INFORMATION {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct IO_COUNTERS {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    static void SetupKillOnCloseJob() {
        try {
            IntPtr hJob = CreateJobObject(IntPtr.Zero, null);
            if (hJob != IntPtr.Zero) {
                JOBOBJECT_EXTENDED_LIMIT_INFORMATION info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
                info.BasicLimitInformation.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                int len = Marshal.SizeOf(typeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION));
                IntPtr ptr = Marshal.AllocHGlobal(len);
                Marshal.StructureToPtr(info, ptr, false);
                SetInformationJobObject(hJob, 9, ptr, (uint)len);
                Marshal.FreeHGlobal(ptr);
                AssignProcessToJobObject(hJob, Process.GetCurrentProcess().Handle);
            }
        } catch {}
    }

    static string QuoteForArgFile(string arg) {
        if (arg == null) return "\"\"";
        return "\"" + arg.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";
    }

    static string FindStudio() {
        string fromEnv = Environment.GetEnvironmentVariable("STUDIO_HOME");
        if (!string.IsNullOrEmpty(fromEnv) && Directory.Exists(fromEnv)) return fromEnv;
        try {
            // Written by the Android Studio installer: <install>\bin\studio64.exe
            string exe = Microsoft.Win32.Registry.GetValue(
                @"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\studio64.exe", "", null) as string;
            if (!string.IsNullOrEmpty(exe)) {
                string home = Path.GetDirectoryName(Path.GetDirectoryName(exe.Trim('"')));
                if (Directory.Exists(home)) return home;
            }
        } catch {}
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Android\Android Studio");
    }

    static int Main(string[] args) {
        SetupKillOnCloseJob();

        int myPid = Process.GetCurrentProcess().Id;
        string studio = FindStudio();
        string javaExe = Path.Combine(studio, "jbr", "bin", "java.exe");
        if (!File.Exists(javaExe)) {
            string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrEmpty(javaHome)) javaExe = Path.Combine(javaHome, "bin", "java.exe");
            if (!File.Exists(javaExe)) javaExe = "java.exe";
        }

        // emulator.exe sits in <sdk>\emulator, so this works for any SDK location.
        string emulatorDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
        string bridgeDir = Path.Combine(emulatorDir, "whpx_bridge");

        string[] jars = new string[] {
            Path.Combine(studio, "plugins", "android", "lib", "studio-grpc.jar"),
            Path.Combine(studio, "plugins", "android", "lib", "studio-proto.jar"),
            Path.Combine(studio, "plugins", "android", "lib", "emulator_java_proto.jar"),
            Path.Combine(studio, "lib", "intellij.libraries.protobuf.jar"),
            Path.Combine(studio, "lib", "intellij.libraries.guava.jar"),
            Path.Combine(studio, "lib", "app.jar"),
            Path.Combine(studio, "lib", "util-8.jar"),
            bridgeDir
        };
        string cp = string.Join(";", jars).Replace("\\", "/");
        string runArgsFile = Path.Combine(Path.GetTempPath(), "java_run_args_" + myPid + ".txt");

        List<string> lines = new List<string>();
        lines.Add("-cp");
        lines.Add("\"" + cp + "\"");
        lines.Add("WhpxEmulatorBridge");
        lines.Add("--parent-pid");
        lines.Add(myPid.ToString());
        if (args != null) {
            foreach (string a in args) {
                lines.Add(QuoteForArgFile(a));
            }
        }
        File.WriteAllLines(runArgsFile, lines.ToArray());

        try {
            ProcessStartInfo psi = new ProcessStartInfo();
            psi.FileName = javaExe;
            psi.Arguments = "@\"" + runArgsFile + "\"";
            psi.UseShellExecute = false;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;
            psi.CreateNoWindow = true;
            psi.EnvironmentVariables["ANDROID_HOME"] = Path.GetDirectoryName(emulatorDir);

            Process p = Process.Start(psi);
            p.OutputDataReceived += (s, e) => { if (e.Data != null) Console.WriteLine(e.Data); };
            p.ErrorDataReceived += (s, e) => { if (e.Data != null) Console.Error.WriteLine(e.Data); };
            p.BeginOutputReadLine();
            p.BeginErrorReadLine();
            p.WaitForExit();
            return p.ExitCode;
        } finally {
            try { File.Delete(runArgsFile); } catch {}
        }
    }
}

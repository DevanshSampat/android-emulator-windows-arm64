#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <sched.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <net/if.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <linux/sockios.h>
#include <linux/route.h>
#include <termios.h>
#include <errno.h>
#include <signal.h>
#include <dirent.h>
#include <sys/xattr.h>
#include <sys/time.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/fib_rules.h>

/* Persistent fds opened before first-stage switch_root. First-stage init
 * MS_MOVEs /dev and /proc into the new root and frees the ramdisk, so any
 * path-based access from this daemon breaks afterwards. Keeping fds open
 * makes logging and PID 1 inspection immune to that. */
static int g_kmsg_fd = -1;
static int g_proc_fd = -1;

static void klog(const char* msg) {
    int fd = g_kmsg_fd;
    int opened = 0;
    if (fd < 0) { fd = open("/dev/kmsg", O_WRONLY | O_CLOEXEC); opened = 1; }
    if (fd >= 0) {
        char buf[256];
        snprintf(buf, sizeof(buf), "<14>[whpx_init] %s\n", msg);
        write(fd, buf, strlen(buf));
        if (opened) close(fd);
    }
}

/* The supervisor drives adbd and networking, so one wedged helper must not stop it:
 * "ip" in particular can block on netlink while netd starts, and with an unbounded
 * wait that silently ended every later adbd start, leaving a running guest without adb. */
#define CHILD_TIMEOUT_MS 15000

static int argc_last(char* const argv[]) {
    int n = 0;
    while (argv[n + 1]) n++;
    return n;
}

static int wait_child(pid_t p, char* const argv[]) {
    for (int waited = 0; waited < CHILD_TIMEOUT_MS; waited += 50) {
        int st = 0;
        pid_t r = waitpid(p, &st, WNOHANG);
        if (r == p) return WIFEXITED(st) ? WEXITSTATUS(st) : -1;
        if (r < 0 && errno != EINTR) return -1;
        usleep(50000);
    }
    char msg[192];
    snprintf(msg, sizeof(msg), "child timed out, killed: %s %.120s", argv[0], argv[1] ? argv[argc_last(argv)] : "");
    klog(msg);
    kill(-p, SIGKILL);
    kill(p, SIGKILL);
    waitpid(p, NULL, 0);
    return -1;
}

static int run_cmd_status(const char* bin, char* const argv[]) {
    pid_t p = fork();
    if (p == 0) {
        setpgid(0, 0);
        execv(bin, argv);
        _exit(127);
    }
    if (p < 0) return -1;
    setpgid(p, p);
    return wait_child(p, argv);
}

static void run_cmd(const char* bin, char* const argv[]) {
    run_cmd_status(bin, argv);
}

/* Some images were modified in place by older tooling so their composer
 * service is "disabled" (to be started late, after a post-boot shim). Then
 * nothing starts it and SurfaceFlinger waits forever. Once SurfaceFlinger has
 * started, start the composer if no composer service has ever run. On stock
 * images the composer is already running, so this is a no-op.
 * Returns 1 when nothing more needs doing. */
static int kick_composer_once(void) {
    char* a[] = { "/system/bin/sh", "-c",
        "[ -n \"$(getprop init.svc.surfaceflinger)\" ] || exit 2; "
        "for s in vendor.hwcomposer-3 vendor.hwcomposer-2-4 vendor.hwcomposer-2-3 "
        "vendor.hwcomposer-2-2 vendor.hwcomposer-2-1; do "
        "[ -n \"$(getprop init.svc.$s)\" ] && exit 0; done; "
        "for s in vendor.hwcomposer-3 vendor.hwcomposer-2-4 vendor.hwcomposer-2-3; do "
        "setprop ctl.start $s 2>/dev/null; done; exit 3", NULL };
    int rc = run_cmd_status("/system/bin/sh", a);
    if (rc == 3) klog("composer was never started (disabled in image); started it");
    return rc == 0 || rc == 3;
}

static void setup_interface(const char* ifname, const char* ip, const char* netmask) {
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) return;

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);

    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    inet_pton(AF_INET, ip, &sin.sin_addr);
    memcpy(&ifr.ifr_addr, &sin, sizeof(sin));
    ioctl(s, SIOCSIFADDR, &ifr);

    if (netmask) {
        memset(&sin, 0, sizeof(sin));
        sin.sin_family = AF_INET;
        inet_pton(AF_INET, netmask, &sin.sin_addr);
        memcpy(&ifr.ifr_netmask, &sin, sizeof(sin));
        ioctl(s, SIOCSIFNETMASK, &ifr);
    }

    if (ioctl(s, SIOCGIFFLAGS, &ifr) == 0) {
        ifr.ifr_flags |= (IFF_UP | IFF_RUNNING);
        ioctl(s, SIOCSIFFLAGS, &ifr);
    }

    close(s);
}

static void add_default_gateway(const char* ifname, const char* gw) {
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) return;

    struct rtentry rt;
    memset(&rt, 0, sizeof(rt));

    struct sockaddr_in* sin = (struct sockaddr_in*)&rt.rt_gateway;
    sin->sin_family = AF_INET;
    inet_pton(AF_INET, gw, &sin->sin_addr);

    sin = (struct sockaddr_in*)&rt.rt_dst;
    sin->sin_family = AF_INET;
    sin->sin_addr.s_addr = INADDR_ANY;

    sin = (struct sockaddr_in*)&rt.rt_genmask;
    sin->sin_family = AF_INET;
    sin->sin_addr.s_addr = INADDR_ANY;

    rt.rt_dev = (char*)ifname;
    rt.rt_flags = RTF_UP | RTF_GATEWAY;

    ioctl(s, SIOCADDRT, &rt);
    close(s);
}

/* "ip rule add from all lookup main pref 5000" over rtnetlink. Returns 0 if
 * added, 1 if it already existed, -1 on error. */
static int ensure_main_rule(int nl) {
    struct {
        struct nlmsghdr nh;
        struct fib_rule_hdr fr;
        char attrs[32];
    } req;
    memset(&req, 0, sizeof(req));
    req.nh.nlmsg_len = NLMSG_LENGTH(sizeof(struct fib_rule_hdr));
    req.nh.nlmsg_type = RTM_NEWRULE;
    req.nh.nlmsg_flags = NLM_F_REQUEST | NLM_F_CREATE | NLM_F_EXCL | NLM_F_ACK;
    req.fr.family = AF_INET;
    req.fr.table = RT_TABLE_MAIN;
    req.fr.action = FR_ACT_TO_TBL;
    struct rtattr* a = (struct rtattr*)((char*)&req + NLMSG_ALIGN(req.nh.nlmsg_len));
    a->rta_type = FRA_PRIORITY;
    a->rta_len = RTA_LENGTH(sizeof(unsigned int));
    *(unsigned int*)RTA_DATA(a) = 5000;
    req.nh.nlmsg_len = NLMSG_ALIGN(req.nh.nlmsg_len) + RTA_ALIGN(a->rta_len);
    if (send(nl, &req, req.nh.nlmsg_len, 0) < 0) return -1;
    char buf[512];
    ssize_t n = recv(nl, buf, sizeof(buf), 0);
    if (n < (ssize_t)NLMSG_LENGTH(sizeof(struct nlmsgerr))) return -1;
    struct nlmsghdr* h = (struct nlmsghdr*)buf;
    if (h->nlmsg_type != NLMSG_ERROR) return -1;
    int err = ((struct nlmsgerr*)NLMSG_DATA(h))->error;
    return err == 0 ? 0 : (err == -EEXIST ? 1 : -1);
}

/* netd flushes all policy-routing rules whenever it (re)starts (and zygote
 * restarts restart netd), ending in "from all unreachable". eth0 isn't owned
 * by ConnectivityService, so without the main-table rule the guest can't
 * reach the host renderer/adb. Re-assert it every 100 ms so the outage is
 * never long enough to fail a graphics connection. */
static void start_route_keeper(void) {
    pid_t p = fork();
    if (p != 0) return;
    int nl = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
    if (nl < 0) _exit(0);
    struct timeval tv = { 0, 500000 };
    setsockopt(nl, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    int restored = 0;
    for (;;) {
        if (ensure_main_rule(nl) == 0 && restored++ < 20)
            klog("route keeper: restored 'from all lookup main pref 5000'");
        usleep(100000);
    }
}

static void write_file(const char* path, const char* buf, int len, mode_t mode, uid_t uid, gid_t gid) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, mode);
    if (fd >= 0) {
        if (len > 0) {
            write(fd, buf, len);
        }
        close(fd);
        chmod(path, mode);
        chown(path, uid, gid);
    }
}

static const char* find_adbd_binary(void) {
    static const char* paths[] = {
        "/apex/com.google.android.adbd/bin/adbd",
        "/apex/com.android.adbd/bin/adbd",
        "/system/bin/adbd",
        NULL
    };
    for (int i = 0; paths[i]; i++) {
        if (access(paths[i], X_OK) == 0) {
            return paths[i];
        }
    }
    return NULL;
}

/* Android has two mount namespaces: "bootstrap" (only the early APEXes) and
 * "default" (all of them, adbd included). PID 1 moves to default in
 * post-fs-data, long after the supervisor joined it, so follow it there.
 * Stays silent and does nothing while the namespaces already match. */
static void follow_pid1_mntns(void) {
    char ours[64] = {0}, theirs[64] = {0};
    if (readlinkat(g_proc_fd, "self/ns/mnt", ours, sizeof(ours) - 1) <= 0) return;
    if (readlinkat(g_proc_fd, "1/ns/mnt", theirs, sizeof(theirs) - 1) <= 0) return;
    if (strcmp(ours, theirs) == 0) return;
    int fd = openat(g_proc_fd, "1/ns/mnt", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    char msg[192];
    int rc = setns(fd, CLONE_NEWNS);
    close(fd);
    if (rc == 0) chdir("/");
    snprintf(msg, sizeof(msg), "mount namespace %s -> %s: %s", ours, theirs,
             rc == 0 ? "followed PID 1" : strerror(errno));
    klog(msg);
}

/* androidboot.lcd_density comes from the AVD. No stock rc reads it: the emulator
 * normally hands density over through qemu-props, which never runs here. The
 * framework reads qemu.sf.lcd_density first, so set it before zygote starts;
 * otherwise every app lays out at the default density ("zoomed out"). */
static void apply_boot_density(void) {
    char* args[] = { "/system/bin/sh", "-c",
        "d=$(getprop ro.boot.lcd_density); [ -n \"$d\" ] || exit 0; "
        "setprop qemu.sf.lcd_density $d; setprop ro.sf.lcd_density $d 2>/dev/null", NULL };
    run_cmd("/system/bin/sh", args);
}

/* Returns 1 if any process named "adbd" is running (scans /proc/<pid>/comm). */
static char g_android_env_buf[32768];
static char* g_android_env[160];
static int g_zygote_pid = 0;
static int g_zygote_seen = 0;

/* This process inherits only the kernel's environment. A shell started from an adbd
 * with that environment cannot start app_process: the VM fails silently without
 * BOOTCLASSPATH and ANDROID_*, so the network agent and input daemon never run.
 * zygote has the complete set (init cannot be used: /proc/1/environ predates its
 * exports). Returns 1 once g_android_env is filled in. */
static int android_environment(void) {
    DIR* d = opendir("/proc");
    if (!d) return 0;
    struct dirent* e;
    int ok = 0;
    while (!ok && (e = readdir(d)) != NULL) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        char path[64], comm[32] = {0};
        snprintf(path, sizeof(path), "/proc/%s/comm", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        int n = read(fd, comm, sizeof(comm) - 1);
        close(fd);
        if (n <= 0) continue;
        if (strncmp(comm, "zygote", 6) != 0) {
            /* ART can rename the main thread, so comm alone misses zygote at random and
             * the wait for its environment then never ends. cmdline keeps "zygote64". */
            char cmd[32] = {0};
            snprintf(path, sizeof(path), "/proc/%s/cmdline", e->d_name);
            fd = open(path, O_RDONLY | O_CLOEXEC);
            if (fd < 0) continue;
            n = read(fd, cmd, sizeof(cmd) - 1);
            close(fd);
            if (n <= 0 || strncmp(cmd, "zygote", 6) != 0) continue;
        }
        g_zygote_seen++;
        snprintf(path, sizeof(path), "/proc/%s/environ", e->d_name);
        fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        n = read(fd, g_android_env_buf, sizeof(g_android_env_buf) - 1);
        close(fd);
        if (n <= 0) continue;
        g_android_env_buf[n] = 0;
        int count = 0, boot = 0;
        for (char* p = g_android_env_buf; p < g_android_env_buf + n && count < 159; p += strlen(p) + 1) {
            if (strncmp(p, "ANDROID_SOCKET_", 15) == 0 || !strchr(p, '=')) continue;
            if (strncmp(p, "BOOTCLASSPATH=", 14) == 0) boot = 1;
            g_android_env[count++] = p;
        }
        g_android_env[count] = NULL;
        ok = boot;
        if (ok) g_zygote_pid = atoi(e->d_name);
    }
    closedir(d);
    return ok;
}

/* Fills out with the process's arguments, separated by spaces. Returns its uid or -1. */
static int proc_args(const char* pid, char* out, size_t len) {
    char path[64];
    struct stat st;
    snprintf(path, sizeof(path), "/proc/%s", pid);
    if (stat(path, &st) != 0) return -1;
    snprintf(path, sizeof(path), "/proc/%s/cmdline", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    int n = read(fd, out, len - 1);
    close(fd);
    if (n <= 0) return -1;
    for (int i = 0; i < n - 1; i++) if (out[i] == 0) out[i] = ' ';
    out[n] = 0;
    return (int)st.st_uid;
}

static int is_loop_shell(const char* args) {
    const char* sp = strchr(args, ' ');
    return sp && strcmp(sp + 1, "/data/local/tmp/whpx_loop.sh") == 0;
}

/* The bridge starts the guest loop through adb, which is only root where the image lets
 * adb root work (ro.debuggable). Elsewhere the loop and its agents run as shell, and the
 * network agent is refused with no NETWORK_FACTORY: no default network, so the image has
 * no internet and Google sign-in reports no connection. This process is always root, so
 * replace such a loop and let it restart the agents. */
static void root_guest_agents(void) {
    static const char* const agents[] = { "run_net_agent", "DefaultNetworkAgent",
                                          "run_input_daemon", "WhpxInputDaemon" };
    static char args[512];
    int as_root = 0, as_user = 0;
    DIR* d = opendir("/proc");
    if (!d) return;
    struct dirent* e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        int uid = proc_args(e->d_name, args, sizeof(args));
        if (uid < 0 || !is_loop_shell(args)) continue;
        if (uid == 0) as_root = 1; else as_user = 1;
    }
    if (as_root || !as_user || !android_environment()) {
        closedir(d);
        return;
    }
    rewinddir(d);
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        int uid = proc_args(e->d_name, args, sizeof(args));
        if (uid <= 0) continue;
        int hit = is_loop_shell(args);
        for (size_t i = 0; !hit && i < sizeof(agents) / sizeof(agents[0]); i++) hit = strstr(args, agents[i]) != NULL;
        if (hit) kill(atoi(e->d_name), SIGKILL);
    }
    closedir(d);
    klog("guest loop was not root; restarting it as root");
    pid_t p = fork();
    if (p == 0) {
        char ns[64];
        setsid();
        snprintf(ns, sizeof(ns), "/proc/%d/ns/mnt", g_zygote_pid);
        int nfd = open(ns, O_RDONLY | O_CLOEXEC);
        if (nfd >= 0) setns(nfd, CLONE_NEWNS);
        int null_fd = open("/dev/null", O_RDWR);
        if (null_fd >= 0) { dup2(null_fd, 0); dup2(null_fd, 1); dup2(null_fd, 2); }
        char* argv[] = { "/system/bin/sh", "/data/local/tmp/whpx_loop.sh", NULL };
        execve(argv[0], argv, g_android_env);
        _exit(1);
    }
}

static int adbd_running(void) {
    DIR* d = opendir("/proc");
    if (!d) return 0;
    struct dirent* e;
    int found = 0;
    while (!found && (e = readdir(d)) != NULL) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        char path[64], comm[32] = {0};
        snprintf(path, sizeof(path), "/proc/%s/comm", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        int n = read(fd, comm, sizeof(comm) - 1);
        close(fd);
        if (n > 0 && strncmp(comm, "adbd", 4) == 0) found = 1;
    }
    closedir(d);
    return found;
}

static void run_serial_console_loop(void) {
    while (1) {
        int fd = open("/dev/ttyAMA0", O_RDWR);
        if (fd < 0) {
            fd = open("/dev/console", O_RDWR);
        }
        if (fd < 0) {
            sleep(1);
            continue;
        }

        pid_t p = fork();
        if (p == 0) {
            dup2(fd, 0);
            dup2(fd, 1);
            dup2(fd, 2);
            if (fd > 2) close(fd);
            setsid();
            ioctl(0, TIOCSCTTY, 1);

            char* args[] = { "/system/bin/sh", "-i", NULL };
            char* env[] = {
                "PATH=/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin",
                "TERM=vt100",
                "HOME=/data",
                NULL
            };
            execve("/system/bin/sh", args, env);
            exit(1);
        }

        close(fd);
        if (p > 0) {
            int st;
            waitpid(p, &st, 0);
        }
        sleep(1);
    }
}

/* ---------------------------------------------------------------------------
 * Universal SELinux relaxation (works for user AND userdebug images).
 * User builds ignore androidboot.selinux=permissive, so once the policy loads
 * this daemon becomes a confined "kernel" domain process and cannot drive
 * adbd / properties. During first stage (before policy load) we append
 * (typepermissive X) for every type to the plat + vendor CIL, bind-mount the
 * patched copies over the originals (stock image files are never modified)
 * and invalidate the precompiled-policy hash so init compiles our policy.
 * ------------------------------------------------------------------------- */
static char* read_all(const char* path, size_t* out_len) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) { close(fd); return NULL; }
    char* buf = malloc(st.st_size + 1);
    if (!buf) { close(fd); return NULL; }
    size_t got = 0;
    while (got < (size_t)st.st_size) {
        ssize_t r = read(fd, buf + got, st.st_size - got);
        if (r <= 0) break;
        got += r;
    }
    close(fd);
    buf[got] = 0;
    *out_len = got;
    return buf;
}

static int patch_cil_permissive(const char* src, const char* tmp) {
    size_t len = 0;
    char* in = read_all(src, &len);
    if (!in) { char m[200]; snprintf(m, sizeof(m), "read %s failed errno=%d", src, errno); klog(m); return -1; }
    if (strstr(in, ";; whpx-permissive")) { free(in); return 0; }
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) { char m[200]; snprintf(m, sizeof(m), "create %s failed errno=%d", tmp, errno); klog(m); free(in); return -1; }
    write(fd, in, len);
    const char* hdr = "\n;; whpx-permissive\n";
    write(fd, hdr, strlen(hdr));
    int count = 0;
    /* Only do (slow) duplicate probing if the file already has permissive types */
    int has_tp = strstr(in, "(typepermissive ") != NULL;
    const char* p = in;
    while ((p = strstr(p, "(type ")) != NULL) {
        if (p != in && p[-1] != '\n' && p[-1] != ' ' && p[-1] != '\t') { p += 6; continue; }
        const char* s = p + 6;
        const char* e = s;
        while (*e && *e != ')' && *e != ' ' && *e != '\n') e++;
        if (*e == ')' && e > s && (e - s) < 200) {
            char name[256], line[300];
            memcpy(name, s, e - s); name[e - s] = 0;
            char probe[300];
            snprintf(probe, sizeof(probe), "(typepermissive %s)", name);
            if (!has_tp || !strstr(in, probe)) {
                snprintf(line, sizeof(line), "%s\n", probe);
                write(fd, line, strlen(line));
                count++;
            }
        }
        p = e;
    }
    close(fd);
    free(in);
    chmod(tmp, 0644);
    if (mount(tmp, src, NULL, MS_BIND, NULL) != 0) { char m[200]; snprintf(m, sizeof(m), "bind %s failed errno=%d", src, errno); klog(m); return -1; }
    char msg[160];
    snprintf(msg, sizeof(msg), "Permissive CIL overlay on %s (%d types)", src, count);
    klog(msg);
    return count;
}

static void invalidate_precompiled(const char* sha_path, const char* tmp) {
    if (access(sha_path, F_OK) != 0) return;
    write_file(tmp, "whpx-permissive\n", 16, 0644, 0, 0);
    if (mount(tmp, sha_path, NULL, MS_BIND, NULL) == 0) {
        char msg[200];
        snprintf(msg, sizeof(msg), "Invalidated precompiled policy hash %s", sha_path);
        klog(msg);
    }
}

static void relax_selinux_first_stage(void) {
    const char* plat = "/proc/1/root/system/etc/selinux/plat_sepolicy.cil";
    const char* vend = "/proc/1/root/vendor/etc/selinux/vendor_sepolicy.cil";
    /* Tight poll: window between system mount and policy load is short */
    int i;
    for (i = 0; i < 30000; i++) {
        if (access(plat, R_OK) == 0) break;
        usleep(1000);
    }
    if (i >= 30000) { klog("plat_sepolicy.cil never appeared; skipping SELinux relax"); return; }
    patch_cil_permissive(plat, "/proc/1/root/dev/.whpx_plat.cil");
    for (i = 0; i < 500 && access(vend, R_OK) != 0; i++) usleep(1000);
    invalidate_precompiled("/proc/1/root/vendor/etc/selinux/precompiled_sepolicy.plat_sepolicy_and_mapping.sha256",
                           "/proc/1/root/dev/.whpx_sha_v");
    invalidate_precompiled("/proc/1/root/odm/etc/selinux/precompiled_sepolicy.plat_sepolicy_and_mapping.sha256",
                           "/proc/1/root/dev/.whpx_sha_o");
    if (access(vend, R_OK) == 0)
        patch_cil_permissive(vend, "/proc/1/root/dev/.whpx_vendor.cil");
    klog("SELinux relax step complete");
}

/* ---------------------------------------------------------------------------
 * Graphics/HAL overlay (built on the host by prepare_whpx_initrd.py from the
 * image's own vendor.img via vendor_extract.py + gfx_autopatch.py).
 *
 * Ramdisk layout:  /whpx_gfx/manifest   lines "<idx> <vendor-relative path>"
 *                  /whpx_gfx/<idx>      replacement file (empty = disable)
 *                  /whpx_gfx/gfx_forwarder
 *
 * The ramdisk is freed at first-stage switch_root, so everything is read into
 * memory at supervisor start and applied as soon as second-stage init exists
 * (property service up), i.e. before any HAL, SurfaceFlinger or zygote starts.
 * Nothing in the guest is restarted and no image is modified.
 * ------------------------------------------------------------------------- */
#define GFX_MAX 64
struct gfx_file { char rel[192]; char* data; size_t len; };
static struct gfx_file g_gfx[GFX_MAX];
static int g_gfx_n = 0;
static char* g_fwd = NULL;
static size_t g_fwd_len = 0;

static void load_gfx_overlay(void) {
    size_t mlen = 0;
    char* man = read_all("/whpx_gfx/manifest", &mlen);
    if (!man) { klog("no /whpx_gfx overlay in ramdisk"); return; }
    char* save = NULL;
    for (char* line = strtok_r(man, "\n", &save); line && g_gfx_n < GFX_MAX;
         line = strtok_r(NULL, "\n", &save)) {
        char idx[16], rel[192];
        if (sscanf(line, "%15s %191s", idx, rel) != 2) continue;
        char p[64];
        snprintf(p, sizeof(p), "/whpx_gfx/%s", idx);
        size_t n = 0;
        char* d = read_all(p, &n);
        struct stat est;
        if (!d && stat(p, &est) == 0 && est.st_size == 0) {
            d = calloc(1, 1);  /* empty replacement = component disabled */
            n = 0;
        }
        if (!d) continue;
        snprintf(g_gfx[g_gfx_n].rel, sizeof(g_gfx[0].rel), "%s", rel);
        g_gfx[g_gfx_n].data = d;
        g_gfx[g_gfx_n].len = n;
        g_gfx_n++;
    }
    free(man);
    g_fwd = read_all("/whpx_gfx/gfx_forwarder", &g_fwd_len);
    char m[96];
    snprintf(m, sizeof(m), "cached %d overlay file(s)%s", g_gfx_n, g_fwd ? " + gfx_forwarder" : "");
    klog(m);
}

static int stage_file(const char* path, const char* data, size_t len, mode_t mode) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, mode);
    if (fd < 0) return -1;
    size_t off = 0;
    while (off < len) {
        ssize_t w = write(fd, data + off, len - off);
        if (w <= 0) { close(fd); return -1; }
        off += (size_t)w;
    }
    fchmod(fd, mode);
    return fd;
}

/* Runs inside Android's root + mount namespace (second stage). */
static void apply_gfx_overlay(void) {
    if (g_gfx_n == 0) return;
    mkdir("/dev/.whpx_gfx", 0755);
    chmod("/dev/.whpx_gfx", 0755);
    int done = 0;
    for (int i = 0; i < g_gfx_n; i++) {
        char target[256], staged[64], m[300];
        snprintf(target, sizeof(target), "/vendor/%s", g_gfx[i].rel);
        snprintf(staged, sizeof(staged), "/dev/.whpx_gfx/%d", i);
        struct stat st;
        if (stat(target, &st) != 0 || !S_ISREG(st.st_mode)) continue;
        int fd = stage_file(staged, g_gfx[i].data, g_gfx[i].len, st.st_mode & 07777);
        if (fd < 0) { snprintf(m, sizeof(m), "stage %s failed errno=%d", target, errno); klog(m); continue; }
        fchown(fd, st.st_uid, st.st_gid);
        /* Same SELinux label as the stock file: init derives a service's
         * domain from its executable's label, and libraries keep their
         * same_process_hal_file / vendor_file type. */
        char ctx[256];
        ssize_t cl = lgetxattr(target, "security.selinux", ctx, sizeof(ctx));
        if (cl > 0) fsetxattr(fd, "security.selinux", ctx, (size_t)cl, 0);
        close(fd);
        if (mount(staged, target, NULL, MS_BIND, NULL) == 0) {
            done++;
        } else {
            snprintf(m, sizeof(m), "bind %s failed errno=%d", target, errno);
            klog(m);
        }
        free(g_gfx[i].data);
        g_gfx[i].data = NULL;
    }
    char m[96];
    snprintf(m, sizeof(m), "graphics overlay: %d/%d vendor file(s) bound", done, g_gfx_n);
    klog(m);
    if (done) {
        int t = open("/dev/whpx_gfxshim_mounted", O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
        if (t >= 0) close(t);
    }
}

/* There is no modem behind the radio HAL, so it can never register. Newer
 * telephony (API 37) waits forever for any service the device declares, and
 * com.android.phone is killed and restarted every 15 s. Declare no radio, like a
 * Wi-Fi-only device: keep the fragment's own <manifest> tag so it still parses. */
static void hide_radio_hal(void) {
    static const char target[] = "/vendor/etc/vintf/manifest/android.hardware.radio.ranchu.xml";
    static const char staged[] = "/dev/.whpx_gfx/no_radio.xml";
    char buf[8192], out[512], m[128];
    int fd = open(target, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return;
    buf[n] = 0;
    char* tag = strstr(buf, "<manifest");
    char* end = tag ? strchr(tag, '>') : NULL;
    if (!end || end[-1] == '/' || end - tag > 200) return;
    int len = snprintf(out, sizeof(out), "%.*s</manifest>\n", (int)(end - tag + 1), tag);
    mkdir("/dev/.whpx_gfx", 0755);
    chmod("/dev/.whpx_gfx", 0755);
    int sf = stage_file(staged, out, len, 0644);
    if (sf < 0) return;
    char ctx[256];
    ssize_t cl = lgetxattr(target, "security.selinux", ctx, sizeof(ctx));
    if (cl > 0) fsetxattr(sf, "security.selinux", ctx, (size_t)cl, 0);
    close(sf);
    if (mount(staged, target, NULL, MS_BIND, NULL) == 0) {
        klog("radio HAL undeclared (no modem in this VM)");
    } else {
        snprintf(m, sizeof(m), "radio manifest bind failed errno=%d", errno);
        klog(m);
    }
}

/* Must beat the sensor HAL. Swapping this config later means restarting the HAL, and
 * if system_server has already connected it aborts with "Sensor handles changed",
 * taking the framework down on any boot slow enough to lose that race. */
static void empty_sensor_config(void) {
    static const char target[] = "/vendor/etc/sensors/hals.conf";
    static const char staged[] = "/dev/.whpx_gfx/no_subhals.conf";
    if (access(target, F_OK) != 0) return;
    mkdir("/dev/.whpx_gfx", 0755);
    chmod("/dev/.whpx_gfx", 0755);
    int sf = stage_file(staged, "", 0, 0644);
    if (sf < 0) return;
    char ctx[256];
    ssize_t cl = lgetxattr(target, "security.selinux", ctx, sizeof(ctx));
    if (cl > 0) fsetxattr(sf, "security.selinux", ctx, (size_t)cl, 0);
    close(sf);
    if (mount(staged, target, NULL, MS_BIND, NULL) == 0) klog("sensor sub-HAL config emptied");
}

/* Images declaring Ethernet (TV) or USB host run EthernetService, which claims eth0:
 * it clears the address and netd destroys every socket on it. All graphics traffic to
 * the host uses those sockets, so every graphics client dies and the framework loops.
 * eth0 is this VM's private link to the host, so declare neither feature. */
static void hide_ethernet_feature(void) {
    static const char* const dirs[] = { "/vendor", "/system", "/product", "/odm", "/system_ext" };
    static const char* const files[] = { "android.hardware.ethernet.xml", "android.hardware.usb.host.xml" };
    static const char staged[] = "/dev/.whpx_gfx/no_feature.xml";
    static const char body[] = "<permissions/>";
    int ready = 0;
    for (size_t d = 0; d < sizeof(dirs) / sizeof(dirs[0]); d++) {
        for (size_t f = 0; f < sizeof(files) / sizeof(files[0]); f++) {
            char target[160], ctx[256], m[200];
            snprintf(target, sizeof(target), "%s/etc/permissions/%s", dirs[d], files[f]);
            if (access(target, F_OK) != 0) continue;
            if (!ready) {
                mkdir("/dev/.whpx_gfx", 0755);
                chmod("/dev/.whpx_gfx", 0755);
                int sf = stage_file(staged, body, sizeof(body) - 1, 0644);
                if (sf < 0) return;
                ssize_t cl = lgetxattr(target, "security.selinux", ctx, sizeof(ctx));
                if (cl > 0) fsetxattr(sf, "security.selinux", ctx, (size_t)cl, 0);
                close(sf);
                ready = 1;
            }
            int rc = mount(staged, target, NULL, MS_BIND, NULL);
            snprintf(m, sizeof(m), "%s %s", rc == 0 ? "feature hidden:" : "feature hide failed:", target);
            klog(m);
        }
    }
}

/* Stock clients reach the host through /dev/goldfish_pipe with read and write. Here that
 * pipe is an AF_UNIX socket, so the graphics libraries loaded into sandboxed codec
 * processes make socket calls their seccomp policies refuse: the first recvfrom kills
 * media.swcodec (SIGSYS) and every decoder in it with DEAD_OBJECT. Android reads optional
 * vendor additions from this directory, so add only what the transport uses. */
static void allow_pipe_syscalls(void) {
    static const char dir[] = "/vendor/etc/seccomp_policy";
    static const char staged[] = "/dev/.whpx_gfx/seccomp_policy";
    static const char* const calls[] = { "socket", "connect", "fcntl", "sendto", "recvfrom",
                                         "sendmsg", "recvmsg", "ppoll", "getsockopt", "setsockopt", "shutdown", "sysinfo" };
    static char buf[65536];
    char path[256], ctx[256], m[160];
    DIR* d = opendir(dir);
    if (!d) return;
    mkdir("/dev/.whpx_gfx", 0755);
    chmod("/dev/.whpx_gfx", 0755);
    mkdir(staged, 0755);
    chmod(staged, 0755);
    ssize_t dcl = lgetxattr(dir, "security.selinux", ctx, sizeof(ctx));
    if (dcl > 0) lsetxattr(staged, "security.selinux", ctx, (size_t)dcl, 0);

    const char* names[32];
    int count = 0, have_swcodec = 0;
    struct dirent* e;
    while ((e = readdir(d)) != NULL && count < 31) {
        if (e->d_name[0] == '.') continue;
        names[count++] = strdup(e->d_name);
        if (strcmp(e->d_name, "mediaswcodec.policy") == 0) have_swcodec = 1;
    }
    closedir(d);
    if (!have_swcodec) names[count++] = "mediaswcodec.policy";

    for (int i = 0; i < count; i++) {
        size_t len = 0;
        snprintf(path, sizeof(path), "%s/%s", dir, names[i]);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) {
            ssize_t n = read(fd, buf, sizeof(buf) - 2048);
            close(fd);
            if (n > 0) len = (size_t)n;
        }
        buf[len] = 0;
        if (len > 0 && buf[len - 1] != '\n') buf[len++] = '\n';
        for (size_t c = 0; c < sizeof(calls) / sizeof(calls[0]); c++) {
            char at_start[32], mid[34];
            snprintf(at_start, sizeof(at_start), "%s:", calls[c]);
            snprintf(mid, sizeof(mid), "\n%s:", calls[c]);
            if (strncmp(buf, at_start, strlen(at_start)) == 0 || strstr(buf, mid)) continue;
            len += (size_t)snprintf(buf + len, sizeof(buf) - len, "%s: 1\n", calls[c]);
        }
        snprintf(path, sizeof(path), "%s/%s", staged, names[i]);
        int sf = stage_file(path, buf, len, 0644);
        if (sf < 0) return;
        if (dcl > 0) fsetxattr(sf, "security.selinux", ctx, (size_t)dcl, 0);
        close(sf);
    }
    if (mount(staged, dir, NULL, MS_BIND, NULL) == 0) {
        klog("codec seccomp policy extended for the pipe socket");
    } else {
        snprintf(m, sizeof(m), "seccomp policy bind failed errno=%d", errno);
        klog(m);
    }
}

/* MultiDisplayProvider needs a host "multidisplay" pipe this VM does not have and retries in
 * a loop with no delay: a full core plus half a core of logd, on every AVD, permanently.
 * It is a persistent app, so disabling or killing it only restarts it. Multi-display cannot
 * work here anyway, so hide the app before Android scans packages. */
static void hide_multidisplay(void) {
    static const char* const dirs[] = { "/system_ext/priv-app/MultiDisplayProvider",
                                        "/system/priv-app/MultiDisplayProvider",
                                        "/product/priv-app/MultiDisplayProvider" };
    static const char staged[] = "/dev/.whpx_gfx/empty";
    for (size_t i = 0; i < sizeof(dirs) / sizeof(dirs[0]); i++) {
        if (access(dirs[i], F_OK) != 0) continue;
        mkdir("/dev/.whpx_gfx", 0755);
        chmod("/dev/.whpx_gfx", 0755);
        mkdir(staged, 0755);
        chmod(staged, 0755);
        if (mount(staged, dirs[i], NULL, MS_BIND, NULL) == 0) klog("multi-display helper hidden");
    }
}

/* The WHPX VM has no goldfish battery device, so /sys/class/power_supply is
 * empty and the health HAL reports 0% / unplugged. system_server's
 * BatteryService then powers the device off ("shutdown,battery") as soon as it
 * starts. Provide a mains-powered, full battery before the health HAL starts.
 * Skipped if the platform exposes any real power supply. */
static void put_attr(const char* dir, const char* name, const char* val) {
    char p[160];
    snprintf(p, sizeof(p), "/sys/class/power_supply/%s/%s", dir, name);
    int fd = open(p, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return;
    write(fd, val, strlen(val));
    write(fd, "\n", 1);
    static const char ctx[] = "u:object_r:sysfs_batteryinfo:s0";
    fsetxattr(fd, "security.selinux", ctx, sizeof(ctx), 0);
    close(fd);
}

static void mock_power_supply(void) {
    const char* base = "/sys/class/power_supply";
    DIR* d = opendir(base);
    if (d) {
        struct dirent* e;
        int real = 0;
        while ((e = readdir(d)) != NULL)
            if (e->d_name[0] != '.') { real = 1; break; }
        closedir(d);
        if (real) { klog("real power_supply present; no battery mock"); return; }
    }
    if (mount("tmpfs", base, "tmpfs", MS_NOSUID | MS_NODEV | MS_NOEXEC, "mode=0755") != 0) {
        klog("power_supply mock: tmpfs mount failed");
        return;
    }
    static const char ctx[] = "u:object_r:sysfs_batteryinfo:s0";
    setxattr(base, "security.selinux", ctx, sizeof(ctx), 0);
    static const char* const ac[][2] = { {"type", "Mains"}, {"online", "1"} };
    static const char* const bat[][2] = {
        {"type", "Battery"}, {"present", "1"}, {"capacity", "100"},
        {"capacity_level", "Full"}, {"status", "Charging"}, {"health", "Good"},
        {"technology", "Li-ion"}, {"voltage_now", "4200000"}, {"voltage_max", "4200000"},
        {"current_now", "500000"}, {"current_avg", "500000"}, {"current_max", "1000000"},
        {"temp", "250"}, {"charge_full", "5000000"}, {"charge_full_design", "5000000"},
        {"charge_counter", "5000000"}, {"cycle_count", "1"},
    };
    mkdir("/sys/class/power_supply/ac", 0755);
    mkdir("/sys/class/power_supply/battery", 0755);
    setxattr("/sys/class/power_supply/ac", "security.selinux", ctx, sizeof(ctx), 0);
    setxattr("/sys/class/power_supply/battery", "security.selinux", ctx, sizeof(ctx), 0);
    for (size_t i = 0; i < sizeof(ac) / sizeof(ac[0]); i++) put_attr("ac", ac[i][0], ac[i][1]);
    for (size_t i = 0; i < sizeof(bat) / sizeof(bat[0]); i++) put_attr("battery", bat[i][0], bat[i][1]);
    int t = open("/dev/whpx_power_mocked", O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
    if (t >= 0) close(t);
    klog("power_supply mock: AC online, battery 100%");
}

/* gfx_forwarder bridges guest "@whpx_pipe" (AF_UNIX) to the host renderer.
 * It must be listening before SurfaceFlinger/HALs open their first pipe. */
/* Each emulator has its own renderer on the host; the bridge names its port. */
static void host_gfx_port(char* out, size_t len) {
    static char cfg[16384];
    snprintf(out, len, "5000");
    int fd = openat(g_proc_fd, "bootconfig", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    int n = read(fd, cfg, sizeof(cfg) - 1);
    close(fd);
    if (n <= 0) return;
    cfg[n] = 0;
    char* p = strstr(cfg, "androidboot.whpx.gfx_port");
    if (!p) return;
    p = strchr(p, '"');
    if (!p) return;
    int port = atoi(p + 1);
    if (port > 0) snprintf(out, len, "%d", port);
}

static void start_gfx_forwarder(void) {
    if (!g_fwd) return;
    const char* path = "/dev/.whpx_gfx/gfx_forwarder";
    int fd = stage_file(path, g_fwd, g_fwd_len, 0755);
    free(g_fwd);
    g_fwd = NULL;
    if (fd < 0) { klog("gfx_forwarder staging failed"); return; }
    close(fd);
    pid_t p = fork();
    if (p == 0) {
        static char port[16];
        host_gfx_port(port, sizeof(port));
        char* args[] = { (char*)path, "5000", "10.0.2.2", port, NULL };
        execv(path, args);
        _exit(1);
    }
    if (p > 0) {
        int st;
        waitpid(p, &st, 0);  /* it daemonizes itself (double fork) */
        klog("gfx_forwarder started (@whpx_pipe -> 10.0.2.2:5000)");
    }
}

static void run_supervisor(void) {
    // Mount proc and devtmpfs in daemon's early rootfs
    mkdir("/proc", 0755);
    mount("proc", "/proc", "proc", 0, NULL);
    mkdir("/dev", 0755);
    mount("devtmpfs", "/dev", "devtmpfs", 0, NULL);
    g_kmsg_fd = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);
    g_proc_fd = open("/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC);

    klog("Supervisor starting in background daemon");

    // Cache adb keys if available in early rootfs
    char adb_keys[8192];
    int adb_keys_len = 0;
    int kfd = open("/adb_keys", O_RDONLY);
    if (kfd >= 0) {
        adb_keys_len = read(kfd, adb_keys, sizeof(adb_keys) - 1);
        if (adb_keys_len < 0) adb_keys_len = 0;
        close(kfd);
        klog("Cached /adb_keys from early ramdisk");
    }

    // Read the graphics overlay into memory before the ramdisk is freed
    load_gfx_overlay();

    // Relax SELinux before PID 1 loads the policy (must run first: time critical)
    relax_selinux_first_stage();

    // Bring up loopback immediately
    setup_interface("lo", "127.0.0.1", "255.0.0.0");

    // Wait for Android PID 1 to enter second-stage init (property service socket appears)
    klog("Waiting for Android second-stage init (/proc/1/root/dev/socket/property_service)...");
    for (int i = 0; i < 3000; i++) {
        if (faccessat(g_proc_fd, "1/root/dev/socket/property_service", F_OK, 0) == 0) {
            klog("Android property service socket detected!");
            break;
        }
        usleep(100000);
    }

    // Synchronize mount namespace to PID 1
    int mnt_fd = openat(g_proc_fd, "1/ns/mnt", O_RDONLY | O_CLOEXEC);
    if (mnt_fd >= 0) {
        if (setns(mnt_fd, CLONE_NEWNS) == 0) {
            klog("setns(PID 1 mnt) succeeded - joined Android second-stage namespace");
        } else {
            klog("setns(PID 1 mnt) failed");
        }
        close(mnt_fd);
    }
    int root_fd = openat(g_proc_fd, "1/root", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (root_fd >= 0 && fchdir(root_fd) == 0 && chroot(".") == 0) {
        chdir("/");
        klog("Entered Android system root!");
    } else {
        klog("Failed to enter PID 1 root");
    }
    if (root_fd >= 0) close(root_fd);
    // Re-open kmsg inside Android's /dev so logging keeps working
    int nk = open("/dev/kmsg", O_WRONLY | O_CLOEXEC);
    if (nk >= 0) { if (g_kmsg_fd >= 0) close(g_kmsg_fd); g_kmsg_fd = nk; }

    // Time critical: vendor overlay + host bridge before any HAL starts.
    // eth0 first, so the forwarder can reach the host on the first connect.
    setup_interface("eth0", "10.0.2.15", "255.255.255.0");
    add_default_gateway("eth0", "10.0.2.2");
    start_route_keeper();
    mock_power_supply();
    apply_gfx_overlay();
    hide_radio_hal();
    empty_sensor_config();
    hide_ethernet_feature();
    hide_multidisplay();
    allow_pipe_syscalls();
    start_gfx_forwarder();

    apply_boot_density();

    // Configure system properties via /system/bin/setprop directly
    if (access("/system/bin/setprop", X_OK) == 0) {
        char* a1[] = { "/system/bin/setprop", "service.adb.tcp.port", "5555", NULL };
        run_cmd("/system/bin/setprop", a1);
        char* a2[] = { "/system/bin/setprop", "service.adb.listen_addrs", "tcp:5555", NULL };
        run_cmd("/system/bin/setprop", a2);
        char* a3[] = { "/system/bin/setprop", "persist.sys.usb.config", "adb", NULL };
        run_cmd("/system/bin/setprop", a3);
        char* a4[] = { "/system/bin/setprop", "sys.usb.config", "adb", NULL };
        run_cmd("/system/bin/setprop", a4);
        char* a5[] = { "/system/bin/setprop", "ro.debuggable", "1", NULL };
        run_cmd("/system/bin/setprop", a5);
        klog("Set adb properties via /system/bin/setprop");
    }

    // Wait for /data/misc to appear (when /data partition is mounted)
    for (int i = 0; i < 300; i++) {
        if (access("/data/misc", F_OK) == 0) break;
        usleep(100000);
    }
    mkdir("/data/misc/adb", 0750);
    mkdir("/data/vendor/adb", 0750);
    if (adb_keys_len > 0) {
        write_file("/data/misc/adb/adb_keys", adb_keys, adb_keys_len, 0640, 1000, 2000);
        write_file("/data/vendor/adb/adb_keys", adb_keys, adb_keys_len, 0640, 1000, 2000);
        klog("Deployed adb_keys into /data/misc/adb and /data/vendor/adb");
    }

    // Spawn root serial shell once runtime APEX / bionic libraries are mounted
    for (int i = 0; i < 300; i++) {
        if (access("/apex/com.android.runtime/bin", F_OK) == 0 ||
            access("/system/lib64/libc.so", F_OK) == 0) {
            break;
        }
        usleep(100000);
    }
    pid_t serial_pid = fork();
    if (serial_pid == 0) {
        klog("Spawning root shell loop on serial console");
        run_serial_console_loop();
        exit(0);
    }

    // Supervisor loop: maintain eth0 and adbd continuously
    int adbd_kicks = 0;
    int no_adbd_ticks = 0;
    int eth0_configured = 0;
    int tick = 0;
    int composer_ok = 0;
    while (1) {
        tick++;
        if (!composer_ok && tick <= 150 && access("/system/bin/sh", X_OK) == 0)
            composer_ok = kick_composer_once();
        // Configure eth0 if available
        int s = socket(AF_INET, SOCK_DGRAM, 0);
        if (s >= 0) {
            struct ifreq ifr;
            memset(&ifr, 0, sizeof(ifr));
            strncpy(ifr.ifr_name, "eth0", IFNAMSIZ - 1);
            if (ioctl(s, SIOCGIFFLAGS, &ifr) == 0) {
                /* Only (re)configure when eth0 is down or has no IPv4 address.
                 * Re-applying SIOCSIFADDR re-adds the address, flushing routes
                 * and resetting every live TCP session (adb included). */
                int up = (ifr.ifr_flags & IFF_UP) != 0;
                struct ifreq ar;
                memset(&ar, 0, sizeof(ar));
                strncpy(ar.ifr_name, "eth0", IFNAMSIZ - 1);
                int has_addr = (ioctl(s, SIOCGIFADDR, &ar) == 0) &&
                               ((struct sockaddr_in*)&ar.ifr_addr)->sin_addr.s_addr != 0;
                if (!up || !has_addr) {
                    setup_interface("eth0", "10.0.2.15", "255.255.255.0");
                    add_default_gateway("eth0", "10.0.2.2");
                }
                if (!eth0_configured) {
                    klog("eth0 configured: 10.0.2.15/24 gw 10.0.2.2");
                    eth0_configured = 1;
                }
            }
            close(s);
        }

        /* netd installs policy-routing rules ending in "from all unreachable"
         * that never consult the main table, where our static eth0 routes
         * live (the interface isn't owned by ConnectivityService). Without
         * this rule the guest cannot reply to adb / host traffic at all.
         * Re-check every cycle because netd (re)creates its rules on start. */
        if (eth0_configured && access("/system/bin/ip", X_OK) == 0) {
            char* rargs[] = { "/system/bin/sh", "-c",
                "ip rule show pref 5000 2>/dev/null | grep -q 'lookup main' || "
                "ip rule add from all lookup main pref 5000 2>/dev/null", NULL };
            run_cmd("/system/bin/sh", rargs);
        }

        if (tick % 3 == 0) root_guest_agents();

        // Never restart a running adbd (that would kill live adb sessions).
        // Only start it if no adbd process exists at all.
        while (waitpid(-1, NULL, WNOHANG) > 0) { }  // a dead adbd must not look alive
        if (!adbd_running()) {
            follow_pid1_mntns();
            /* Starting adbd before PID 1 can see it only burns attempts. tick
             * bound: a layout we don't recognise must still get a start. */
            const char* adbd_bin = find_adbd_binary();
            if ((adbd_bin || tick > 45) && access("/system/bin/setprop", X_OK) == 0) {
                char* kick[] = { "/system/bin/setprop", "ctl.start", "adbd", NULL };
                run_cmd("/system/bin/setprop", kick);
                adbd_kicks++;
                sleep(3);
            }
            /* init keeps a service in the namespace of its first start, and PID 1 itself
             * can still be in bootstrap, where the adbd APEX does not exist: then init
             * fails with ENOENT forever and this process cannot see the binary either.
             * zygote always has the complete namespace and the complete environment, so
             * take both from it. Everything the bridge does depends on adb. */
            int have_env = android_environment();
            no_adbd_ticks++;
            if (no_adbd_ticks % 10 == 1) {
                char msg[128];
                snprintf(msg, sizeof(msg), "adbd absent: ticks=%d kicks=%d visible=%d zygote=%d seen=%d", no_adbd_ticks,
                         adbd_kicks, adbd_bin != NULL, have_env ? g_zygote_pid : 0, g_zygote_seen);
                klog(msg);
            }
            int due = have_env ? no_adbd_ticks >= 3 : (adbd_bin && tick > 150);
            if (due && !adbd_running()) {
                klog("init did not start adbd; spawning directly");
                pid_t apid = fork();
                if (apid == 0) {
                    setsid();
                    if (have_env) {
                        char ns[64];
                        snprintf(ns, sizeof(ns), "/proc/%d/ns/mnt", g_zygote_pid);
                        int nfd = open(ns, O_RDONLY | O_CLOEXEC);
                        if (nfd < 0 || setns(nfd, CLONE_NEWNS) != 0) klog("adbd: cannot enter zygote namespace");
                    }
                    const char* bin = find_adbd_binary();
                    if (!bin) {
                        klog("adbd: binary not found");
                        _exit(1);
                    }
                    char* args[] = { (char*)bin, "--root_seclabel=u:r:su:s0", NULL };
                    if (have_env) {
                        execve(bin, args, g_android_env);
                    }
                    execv(bin, args);
                    char* args_fallback[] = { (char*)bin, NULL };
                    execv(bin, args_fallback);
                    _exit(1);
                }
            }
        } else {
            no_adbd_ticks = 0;
        }
        sleep(2);
    }
}

int main(int argc, char** argv) {
    pid_t pid = fork();
    if (pid == 0) {
        // Child daemon: supervisor
        setsid();
        run_supervisor();
        _exit(0);
    }

    // Main process (PID 1): exec original stock init
    execv("/init.stock", argv);

    // Fallback if /init.stock not found
    execv("/system/bin/init", argv);
    perror("execv /init.stock failed");
    return 1;
}

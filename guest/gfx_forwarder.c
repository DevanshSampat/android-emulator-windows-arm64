#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/epoll.h>
#include <sys/resource.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <signal.h>
#include <time.h>

#define MAX_EVENTS 64
#define BUF_SIZE 65536
#define MAX_CONNS 256

struct Connection {
    int client_fd;
    int host_fd;
    char c2h_buf[BUF_SIZE];
    int c2h_len;
    int c2h_pos;
    char h2c_buf[BUF_SIZE];
    int h2c_len;
    int h2c_pos;
    uint32_t client_ev;
    uint32_t host_ev;
    int closed;
};

static struct Connection conns[1024];

/* Colour buffer lifetime. The ranchu allocator ties each buffer to one "refcount" pipe
 * fd, shared by dup across every process using the buffer, so EOF here means the last
 * user is gone. These stay in the guest: one host connection per buffer would exhaust
 * slirp, and only the death of a buffer is of interest to the host. */
#define REFS_CID 1021
#define MAX_REF_FDS 65536

struct RefPipe {
    char data[32];  /* "pipe:refcount\0" + uint32 handle */
    int len;
    uint32_t gen;   /* renderer instance the buffer was created on */
};

/* Handles are numbered per renderer instance, and the bridge restarts a renderer that
 * dies. A pipe from the previous instance would then report an old handle number that
 * the new renderer has reused for a live buffer, which it would free while on screen.
 * The control connection stays open so a renderer's death is seen at once (EOF); each
 * death starts a new generation, and only pipes of the current one are reported. */
#define CTRL_CID 1020
static struct RefPipe ref_pipes[MAX_REF_FDS];
static int ctrl_fd = -1;
static struct sockaddr_in* ctrl_addr;
static int g_epoll_fd = -1;
static uint32_t ref_gen = 1;
static unsigned long stale_reports = 0;

static void ctrl_lost(void) {
    if (ctrl_fd >= 0) {
        if (g_epoll_fd >= 0) epoll_ctl(g_epoll_fd, EPOLL_CTL_DEL, ctrl_fd, NULL);
        close(ctrl_fd);
        ctrl_fd = -1;
    }
    ref_gen++;
    printf("[gfx_forwarder] renderer connection lost: buffer generation %u\n", ref_gen);
    fflush(stdout);
}

static void ctrl_connect(void) {
    if (ctrl_fd >= 0) return;
    /* connect() can block for up to a second while the renderer is away, and this one
     * thread carries every guest graphics connection: try at most once a second. */
    static struct timespec last;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (last.tv_sec && now.tv_sec - last.tv_sec < 1) return;
    last = now;
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return;
    struct timeval tv = { 1, 0 };
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    static const char hello[] = "whpx:refs";
    if (connect(fd, (struct sockaddr*)ctrl_addr, sizeof(*ctrl_addr)) != 0 ||
        write(fd, hello, sizeof(hello)) != (ssize_t)sizeof(hello)) {
        close(fd);
        return;
    }
    ctrl_fd = fd;
    if (g_epoll_fd >= 0) {
        struct epoll_event ev;
        ev.events = EPOLLIN | EPOLLRDHUP;
        ev.data.u64 = ((uint64_t)CTRL_CID << 32) | (uint32_t)fd;
        epoll_ctl(g_epoll_fd, EPOLL_CTL_ADD, fd, &ev);
    }
}

/* The renderer never writes on the control connection: readable means closed. */
static void service_ctrl(void) {
    char b[16];
    ssize_t n = read(ctrl_fd, b, sizeof(b));
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) return;
    ctrl_lost();
}

static void report_buffer_gone(uint32_t handle, uint32_t gen) {
    if (gen != ref_gen) {
        if (++stale_reports <= 20) {
            printf("[gfx_forwarder] not reporting handle %u from buffer generation %u (now %u)\n", handle, gen, ref_gen);
            fflush(stdout);
        }
        return;
    }
    ctrl_connect();
    if (ctrl_fd < 0) return;  /* the host keeps the buffer: a leak, never a wrong free */
    if (write(ctrl_fd, &handle, 4) != 4) ctrl_lost();
}

static void accept_ref_pipes(int epoll_fd, int listen_fd) {
    while (1) {
        int fd = accept4(listen_fd, NULL, NULL, SOCK_NONBLOCK | SOCK_CLOEXEC);
        if (fd < 0) return;
        if (fd >= MAX_REF_FDS) {
            close(fd);
            continue;
        }
        ref_pipes[fd].len = 0;
        ctrl_connect();  /* so a renderer death is noticed before its buffers die */
        ref_pipes[fd].gen = ref_gen;
        struct epoll_event ev;
        ev.events = EPOLLIN;
        ev.data.u64 = ((uint64_t)REFS_CID << 32) | (uint32_t)fd;
        epoll_ctl(epoll_fd, EPOLL_CTL_ADD, fd, &ev);
    }
}

static void service_ref_pipe(int epoll_fd, int fd) {
    struct RefPipe* p = &ref_pipes[fd];
    char sink[64];
    while (1) {
        int room = (int)sizeof(p->data) - p->len;
        ssize_t n = room > 0 ? read(fd, p->data + p->len, room) : read(fd, sink, sizeof(sink));
        if (n > 0) {
            if (room > 0) p->len += (int)n;
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return;
        if (n < 0 && errno == EINTR) continue;
        break;
    }
    epoll_ctl(epoll_fd, EPOLL_CTL_DEL, fd, NULL);
    close(fd);
    char* nul = memchr(p->data, 0, p->len);
    if (nul && p->len - (nul + 1 - p->data) >= 4) {
        uint32_t handle;
        memcpy(&handle, nul + 1, 4);
        if (handle) report_buffer_gone(handle, p->gen);
    }
}

static int set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return -1;
    return fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

static void set_sock_opts(int fd) {
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    int bufsize = 262144;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &bufsize, sizeof(bufsize));
    setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &bufsize, sizeof(bufsize));
}

static void close_connection(int epoll_fd, int cid) {
    struct Connection* c = &conns[cid];
    if (c->client_fd >= 0) {
        epoll_ctl(epoll_fd, EPOLL_CTL_DEL, c->client_fd, NULL);
        close(c->client_fd);
        c->client_fd = -1;
    }
    if (c->host_fd >= 0) {
        epoll_ctl(epoll_fd, EPOLL_CTL_DEL, c->host_fd, NULL);
        close(c->host_fd);
        c->host_fd = -1;
    }
    c->closed = 1;
}

/* Everything in the guest shares this one thread, so it must never wait for one
 * connection: a client's 4-byte reply would sit behind another client's texture
 * upload, freezing gralloc, SurfaceFlinger and the window manager while the host
 * has already answered. A full destination parks the data here and pauses only
 * that direction of that connection until EPOLLOUT.
 * Returns -1 if the connection should be closed. */
static int flush_pending(int dst, const char* buf, int* len, int* pos) {
    ssize_t s = write(dst, buf + *pos, *len - *pos);
    if (s < 0) return (errno == EAGAIN || errno == EWOULDBLOCK) ? 0 : -1;
    *pos += s;
    if (*pos == *len) *pos = *len = 0;
    return 0;
}

static int pump(int src, int dst, char* buf, int* len, int* pos) {
    if (*len > 0) {
        if (flush_pending(dst, buf, len, pos) < 0) return -1;
        if (*len > 0) return 0;
    }
    ssize_t n = read(src, buf, BUF_SIZE);
    if (n == 0) return -1;
    if (n < 0) return (errno == EAGAIN || errno == EWOULDBLOCK) ? 0 : -1;
    *len = (int)n;
    *pos = 0;
    return flush_pending(dst, buf, len, pos);
}

static void watch_fd(int epoll_fd, int cid, int fd, uint32_t* cur, uint32_t want) {
    if (*cur == want) return;
    struct epoll_event ev;
    ev.events = want;
    ev.data.u64 = ((uint64_t)(uint32_t)cid << 32) | (uint32_t)fd;
    epoll_ctl(epoll_fd, EPOLL_CTL_MOD, fd, &ev);
    *cur = want;
}

/* Read a side only while its buffer is free; watch the other side while it isn't. */
static void update_watches(int epoll_fd, int cid) {
    struct Connection* c = &conns[cid];
    const uint32_t base = EPOLLERR | EPOLLHUP;
    watch_fd(epoll_fd, cid, c->client_fd, &c->client_ev,
             base | (c->c2h_len ? 0 : EPOLLIN) | (c->h2c_len ? EPOLLOUT : 0));
    watch_fd(epoll_fd, cid, c->host_fd, &c->host_ev,
             base | (c->h2c_len ? 0 : EPOLLIN) | (c->c2h_len ? EPOLLOUT : 0));
}

int main(int argc, char** argv) {
    signal(SIGPIPE, SIG_IGN);

    int listen_port = (argc > 1) ? atoi(argv[1]) : 5000;
    const char* target_ip = (argc > 2) ? argv[2] : "10.0.2.2";
    int target_port = (argc > 3) ? atoi(argv[3]) : 5000;

    int srv_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (srv_fd < 0) {
        perror("socket");
        return 1;
    }

    int opt = 1;
    setsockopt(srv_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    set_nonblocking(srv_fd);

    struct sockaddr_in srv_addr;
    memset(&srv_addr, 0, sizeof(srv_addr));
    srv_addr.sin_family = AF_INET;
    srv_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); // 127.0.0.1
    srv_addr.sin_port = htons(listen_port);

    if (bind(srv_fd, (struct sockaddr*)&srv_addr, sizeof(srv_addr)) < 0) {
        perror("bind");
        return 1;
    }

    if (listen(srv_fd, 64) < 0) {
        perror("listen");
        return 1;
    }

    int unix_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (unix_fd >= 0) {
        set_nonblocking(unix_fd);
        struct sockaddr_un un_addr;
        memset(&un_addr, 0, sizeof(un_addr));
        un_addr.sun_family = AF_UNIX;
        un_addr.sun_path[0] = '\0';
        strncpy(un_addr.sun_path + 1, "whpx_pipe", sizeof(un_addr.sun_path) - 2);
        socklen_t un_len = sizeof(sa_family_t) + 1 + strlen("whpx_pipe");
        if (bind(unix_fd, (struct sockaddr*)&un_addr, un_len) < 0) {
            perror("bind AF_UNIX @whpx_pipe");
            close(unix_fd);
            unix_fd = -1;
        } else {
            listen(unix_fd, 64);
        }
    }

    int refs_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (refs_fd >= 0) {
        struct sockaddr_un un_addr;
        memset(&un_addr, 0, sizeof(un_addr));
        un_addr.sun_family = AF_UNIX;
        memcpy(un_addr.sun_path + 1, "whpx_refs", 9);
        socklen_t un_len = sizeof(sa_family_t) + 1 + 9;
        if (bind(refs_fd, (struct sockaddr*)&un_addr, un_len) < 0 || listen(refs_fd, 256) < 0) {
            perror("bind AF_UNIX @whpx_refs");
            close(refs_fd);
            refs_fd = -1;
        }
    }

    // Double-fork daemonize: detach from controlling session & adopt PID 1 (init)
    pid_t dpid = fork();
    if (dpid < 0) {
        perror("fork");
        return 1;
    }
    if (dpid > 0) {
        // Parent exits immediately, caller unblocks instantly
        return 0;
    }

    setsid();
    dpid = fork();
    if (dpid < 0) return 1;
    if (dpid > 0) return 0;

    // Redirect stdio to /data/local/tmp/fwd.log
    int log_fd = open("/data/local/tmp/fwd.log", O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (log_fd >= 0) {
        dup2(log_fd, STDOUT_FILENO);
        dup2(log_fd, STDERR_FILENO);
        close(log_fd);
    }
    int null_fd = open("/dev/null", O_RDONLY);
    if (null_fd >= 0) {
        dup2(null_fd, STDIN_FILENO);
        close(null_fd);
    }

    int epoll_fd = epoll_create1(0);
    if (epoll_fd < 0) {
        perror("epoll_create1");
        return 1;
    }
    g_epoll_fd = epoll_fd;

    struct epoll_event ev;
    ev.events = EPOLLIN;
    ev.data.u64 = ((uint64_t)1023 << 32) | (uint32_t)srv_fd;
    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, srv_fd, &ev);

    if (unix_fd >= 0) {
        struct epoll_event ev_un;
        ev_un.events = EPOLLIN;
        ev_un.data.u64 = ((uint64_t)1022 << 32) | (uint32_t)unix_fd;
        epoll_ctl(epoll_fd, EPOLL_CTL_ADD, unix_fd, &ev_un);
        printf("[gfx_forwarder] Listening on abstract AF_UNIX @whpx_pipe\n");
    }

    /* One fd per live graphics buffer. */
    struct rlimit nofile = { MAX_REF_FDS, MAX_REF_FDS };
    setrlimit(RLIMIT_NOFILE, &nofile);

    if (refs_fd >= 0) {
        struct epoll_event ev_refs;
        ev_refs.events = EPOLLIN;
        ev_refs.data.u64 = ((uint64_t)REFS_CID << 32) | (uint32_t)refs_fd;
        epoll_ctl(epoll_fd, EPOLL_CTL_ADD, refs_fd, &ev_refs);
    }

    for (int i = 0; i < 1024; i++) {
        conns[i].client_fd = -1;
        conns[i].host_fd = -1;
    }

    struct sockaddr_in host_addr;
    memset(&host_addr, 0, sizeof(host_addr));
    host_addr.sin_family = AF_INET;
    inet_pton(AF_INET, target_ip, &host_addr.sin_addr);
    host_addr.sin_port = htons(target_port);
    ctrl_addr = &host_addr;

    printf("[gfx_forwarder] Listening on 127.0.0.1:%d -> %s:%d (epoll+TCP_NODELAY)\n",
           listen_port, target_ip, target_port);
    fflush(stdout);

    struct epoll_event events[MAX_EVENTS];
    while (1) {
        int nfds = epoll_wait(epoll_fd, events, MAX_EVENTS, -1);
        if (nfds < 0) {
            if (errno == EINTR) continue;
            perror("epoll_wait");
            break;
        }

        for (int i = 0; i < nfds; i++) {
            uint32_t cid = (uint32_t)(events[i].data.u64 >> 32);
            int fd = (int)(events[i].data.u64 & 0xFFFFFFFF);

            if (cid == CTRL_CID) {
                if (fd == ctrl_fd) service_ctrl();
                continue;
            }
            if (cid == REFS_CID) {
                if (fd == refs_fd) accept_ref_pipes(epoll_fd, refs_fd);
                else service_ref_pipe(epoll_fd, fd);
                continue;
            }

            if (fd == srv_fd || (unix_fd >= 0 && fd == unix_fd)) {
                while (1) {
                    int cl_fd = accept(fd, NULL, NULL);
                    if (cl_fd < 0) {
                        if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                        break;
                    }

                    /* Connect to the host renderer. During boot, netd (re)starts
                     * flush the policy-routing rules (and zygote restarts restart
                     * netd), so the route to the host can vanish for a moment and
                     * the host renderer may still be coming up. Retry transient
                     * failures for up to 5 s instead of failing the guest's
                     * EGL init (which would crash SurfaceFlinger and cascade
                     * into zygote/system_server restarts). */
                    int h_fd = -1;
                    for (int attempt = 0; attempt < 100; attempt++) {
                        h_fd = socket(AF_INET, SOCK_STREAM, 0);
                        if (h_fd < 0) break;
                        if (connect(h_fd, (struct sockaddr*)&host_addr, sizeof(host_addr)) == 0) break;
                        int e = errno;
                        close(h_fd);
                        h_fd = -1;
                        if (e != ENETUNREACH && e != EHOSTUNREACH && e != ECONNREFUSED &&
                            e != ETIMEDOUT && e != EINTR && e != EAGAIN) break;
                        usleep(50000);
                    }
                    if (h_fd < 0) {
                        close(cl_fd);
                        continue;
                    }

                    if (fd == srv_fd) {
                        set_sock_opts(cl_fd);
                    }
                    set_sock_opts(h_fd);
                    set_nonblocking(cl_fd);
                    set_nonblocking(h_fd);

                    int slot = -1;
                    for (int s = 0; s < 1000; s++) {
                        if (conns[s].client_fd == -1 && conns[s].host_fd == -1) {
                            slot = s;
                            break;
                        }
                    }
                    if (slot < 0) {
                        close(cl_fd);
                        close(h_fd);
                        continue;
                    }

                    struct Connection* c = &conns[slot];
                    c->client_fd = cl_fd;
                    c->host_fd = h_fd;
                    c->c2h_len = c->c2h_pos = 0;
                    c->h2c_len = c->h2c_pos = 0;
                    c->closed = 0;

                    struct epoll_event ev_cl, ev_h;
                    ev_cl.events = EPOLLIN | EPOLLERR | EPOLLHUP;
                    ev_cl.data.u64 = ((uint64_t)slot << 32) | (uint32_t)cl_fd;
                    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, cl_fd, &ev_cl);

                    ev_h.events = EPOLLIN | EPOLLERR | EPOLLHUP;
                    ev_h.data.u64 = ((uint64_t)slot << 32) | (uint32_t)h_fd;
                    epoll_ctl(epoll_fd, EPOLL_CTL_ADD, h_fd, &ev_h);
                    c->client_ev = ev_cl.events;
                    c->host_ev = ev_h.events;
                }
                continue;
            }

            if (cid >= 1000) continue;
            struct Connection* c = &conns[cid];
            if (c->closed) continue;

            uint32_t ev_flags = events[i].events;

            if (ev_flags & (EPOLLERR | EPOLLHUP)) {
                close_connection(epoll_fd, cid);
                continue;
            }

            int rc = 0;
            if (fd == c->client_fd) {
                if (ev_flags & EPOLLIN) rc = pump(c->client_fd, c->host_fd, c->c2h_buf, &c->c2h_len, &c->c2h_pos);
                if (rc == 0 && (ev_flags & EPOLLOUT)) rc = pump(c->host_fd, c->client_fd, c->h2c_buf, &c->h2c_len, &c->h2c_pos);
            } else if (fd == c->host_fd) {
                if (ev_flags & EPOLLIN) rc = pump(c->host_fd, c->client_fd, c->h2c_buf, &c->h2c_len, &c->h2c_pos);
                if (rc == 0 && (ev_flags & EPOLLOUT)) rc = pump(c->client_fd, c->host_fd, c->c2h_buf, &c->c2h_len, &c->c2h_pos);
            } else {
                continue;  // stale event for a slot reused earlier in this batch
            }
            if (rc < 0) {
                close_connection(epoll_fd, cid);
                continue;
            }
            update_watches(epoll_fd, cid);
        }
    }

    close(srv_fd);
    close(epoll_fd);
    return 0;
}

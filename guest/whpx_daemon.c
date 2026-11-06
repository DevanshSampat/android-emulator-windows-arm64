#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <signal.h>

int main(int argc, char** argv) {
    if (argc < 2) return 1;
    signal(SIGPIPE, SIG_IGN);
    signal(SIGHUP, SIG_IGN);

    pid_t pid = fork();
    if (pid < 0) return 1;
    if (pid > 0) return 0;

    setsid();
    pid = fork();
    if (pid < 0) return 1;
    if (pid > 0) return 0;

    int log_fd = open("/data/local/tmp/loop.log", O_WRONLY | O_CREAT | O_APPEND, 0666);
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

    char* sh_argv[16];
    sh_argv[0] = "/system/bin/sh";
    int i = 1;
    for (int j = 1; j < argc && i < 15; j++) {
        sh_argv[i++] = argv[j];
    }
    sh_argv[i] = NULL;

    execv("/system/bin/sh", sh_argv);
    return 0;
}

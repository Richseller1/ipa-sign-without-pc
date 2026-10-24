/*
 * usbmuxd_embedded.c
 *
 * NEW file for this project (not part of upstream usbmuxd). Written
 * 2026-09-27 against the real usbmuxd/src/main.c the user uploaded.
 * NOT compiled, NOT tested.
 *
 * Upstream usbmuxd is a standalone system daemon: it daemonizes, writes
 * a pidfile, binds a Unix socket at a filesystem path like
 * /var/run/usbmuxd, and installs Unix signal handlers. None of that is
 * appropriate (or, for the filesystem-path socket and pidfile, even
 * possible without root) inside a normal Android app.
 *
 * This file re-implements only the part of main.c's startup sequence
 * that actually matters for us (usb_init, device_init, client_init,
 * then main_loop) and runs it on its own pthread, using an ABSTRACT
 * Unix domain socket (a Linux/Android feature: a socket name that
 * exists only in memory, no filesystem entry, no permission bits to
 * fight with). Kotlin's own android.net.LocalSocket class can connect
 * to the exact same abstract socket by name — see UsbBridge.kt.
 *
 * usbmuxd's own client-facing protocol on that socket is completely
 * unmodified: any code (including the real libusbmuxd, unmodified, if
 * you choose to link it inside the same process instead of going
 * through your own Kotlin socket client) can talk to it exactly as it
 * would talk to the real system daemon on Linux/macOS.
 *
 * WHAT IS VERIFIED vs NOT in this file:
 *  - The function names/signatures used here (usb_init, device_init,
 *    client_init, client_get_fds, client_accept, client_process,
 *    device_get_timeout, device_check_timeouts, fdlist_*) were read
 *    directly out of the uploaded source (main.c, device.h, client.h,
 *    utils.h) — they are not guessed.
 *  - The abstract-socket bind() logic below is standard Linux/Android
 *    sockaddr_un usage and should work as written, but has not been
 *    compiled or run.
 *  - Running usbmuxd's poll loop on a plain pthread (rather than the
 *    process main.c normally runs as) has not been tested for any
 *    hidden assumption elsewhere in the codebase (e.g. use of global/
 *    static state that assumed single-process, single-instance
 *    lifetime — which is true here too, so this SHOULD be fine, but
 *    say so rather than assume it).
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <android/log.h>

#include "usb.h"     /* usb_android.h is a drop-in rename of this */
#include "device.h"
#include "client.h"
#include "utils.h"
#include "log.h"
#include "conf.h"
#include "usbmuxd_embedded.h"

#define LOG_TAG "usbmuxd-embedded"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern unsigned int log_level; /* defined in log.c, declared in log.h */
volatile int should_exit = 0;
int no_preflight = 0;

static char g_android_storage_dir[1024] = {0};

const char *usbmuxd_get_storage_dir(void)
{
    return g_android_storage_dir;
}

static pthread_t g_daemon_thread;
static volatile int g_running = 0;
static int g_listenfd = -1;
static int g_tcp_listenfd = -1;

/* Create an ABSTRACT Unix domain socket (leading NUL byte in sun_path).
 * No filesystem path, no root, no permission bits — matches what
 * android.net.LocalSocketAddress.Namespace.ABSTRACT produces on the
 * Kotlin side when given the same name. */
static int create_abstract_socket(const char *name)
{
    struct sockaddr_un addr;
    int fd;
    size_t namelen = strlen(name);

    if (namelen + 1 >= sizeof(addr.sun_path) - 1) {
        LOGE("socket name too long: %s", name);
        return -1;
    }

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return -1;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0'; /* abstract namespace marker */
    memcpy(addr.sun_path + 1, name, namelen);
    socklen_t addrlen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + namelen);

    if (bind(fd, (struct sockaddr*)&addr, addrlen) != 0) {
        LOGE("bind() to abstract socket '%s' failed: %s", name, strerror(errno));
        close(fd);
        return -1;
    }

    if (listen(fd, 5) != 0) {
        LOGE("listen() failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    int flags = fcntl(fd, F_GETFL, 0);
    if (flags == -1 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) == -1) {
        LOGE("failed to set socket non-blocking: %s", strerror(errno));
        close(fd);
        return -1;
    }

    return fd;
}

/* Create a TCP socket listening on 127.0.0.1:port so that libusbmuxd
 * (which cannot connect to abstract Unix sockets because its internal
 * socket_connect_unix() does stat() on the path and checks S_IFSOCK)
 * can communicate with the embedded daemon via USBMUXD_SOCKET_ADDRESS=127.0.0.1:port. */
static int create_tcp_socket(int port)
{
    struct sockaddr_in addr;
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("TCP socket() failed: %s", strerror(errno));
        return -1;
    }

    int yes = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(int));

    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(port);

    if (bind(fd, (struct sockaddr*)&addr, sizeof(addr)) != 0) {
        LOGE("TCP bind(127.0.0.1:%d) failed: %s", port, strerror(errno));
        close(fd);
        return -1;
    }

    if (listen(fd, 5) != 0) {
        LOGE("TCP listen() failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    int flags = fcntl(fd, F_GETFL, 0);
    if (flags != -1) {
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }

    LOGI("TCP socket listening on 127.0.0.1:%d (fd %d)", port, fd);
    return fd;
}

/* This is a direct, minimal re-implementation of main.c's main_loop(),
 * copied function-call-for-function-call from the source you uploaded
 * (main.c lines ~318-394), with only the shutdown condition changed to
 * check g_running instead of the daemon's should_exit signal-handler
 * flag. If upstream main_loop() changes in a future usbmuxd version,
 * re-diff this against it by hand. */
static void embedded_main_loop(int listenfd, int tcp_listenfd)
{
    struct fdlist pollfds;
    fdlist_create(&pollfds);

    while (g_running && !should_exit) {
        int to = usb_get_timeout();
        int dto = device_get_timeout();
        if (dto < to) to = dto;

        fdlist_reset(&pollfds);
        fdlist_add(&pollfds, FD_LISTEN, listenfd, POLLIN);
        if (tcp_listenfd >= 0) {
            fdlist_add(&pollfds, FD_LISTEN, tcp_listenfd, POLLIN);
        }
        usb_get_fds(&pollfds);
        client_get_fds(&pollfds);

        struct timespec tspec;
        tspec.tv_sec = to / 1000;
        tspec.tv_nsec = (to % 1000) * 1000000;

        sigset_t empty_sigset;
        sigemptyset(&empty_sigset);
        int cnt = ppoll(pollfds.fds, pollfds.count, &tspec, &empty_sigset);

        if (cnt < 0) {
            if (errno == EINTR) continue;
            LOGE("ppoll() failed: %s", strerror(errno));
            break;
        } else if (cnt == 0) {
            if (usb_process() < 0) { LOGE("usb_process failed"); break; }
            device_check_timeouts();
        } else {
            int done_usb = 0;
            for (int i = 0; i < pollfds.count; i++) {
                if (!pollfds.fds[i].revents) continue;
                if (!done_usb && pollfds.owners[i] == FD_USB) {
                    if (usb_process() < 0) { LOGE("usb_process failed"); goto out; }
                    done_usb = 1;
                }
                if (pollfds.owners[i] == FD_LISTEN) {
                    if (client_accept(pollfds.fds[i].fd) < 0) { LOGE("client_accept failed"); goto out; }
                }
                if (pollfds.owners[i] == FD_CLIENT) {
                    client_process(pollfds.fds[i].fd, pollfds.fds[i].revents);
                }
            }
        }
    }
out:
    fdlist_free(&pollfds);
}

static void *daemon_thread_main(void *arg)
{
    (void)arg;
    LOGI("embedded usbmuxd thread starting");

    log_level = LL_INFO; /* turn down to LL_DEBUG/LL_SPEW while bringing this up */

    if (usb_init() < 0) {
        LOGE("usb_init failed");
        g_running = 0;
        return NULL;
    }
    device_init();
    client_init();

    LOGI("embedded usbmuxd entering main loop (UNIX abstract fd %d, TCP fd %d)", g_listenfd, g_tcp_listenfd);
    embedded_main_loop(g_listenfd, g_tcp_listenfd);

    LOGI("embedded usbmuxd thread exiting, cleaning up");
    client_shutdown();
    device_shutdown();
    usb_shutdown();
    if (g_listenfd >= 0) {
        close(g_listenfd);
        g_listenfd = -1;
    }
    if (g_tcp_listenfd >= 0) {
        close(g_tcp_listenfd);
        g_tcp_listenfd = -1;
    }
    return NULL;
}

int usbmuxd_embedded_start(const char *abstract_socket_name, const char *storage_dir)
{
    if (g_running) {
        LOGI("already running");
        return 0;
    }

    should_exit = 0;

    if (storage_dir) {
        strncpy(g_android_storage_dir, storage_dir, sizeof(g_android_storage_dir) - 1);
        g_android_storage_dir[sizeof(g_android_storage_dir) - 1] = '\0';
        LOGI("Configured storage dir: %s", g_android_storage_dir);
        config_set_config_dir(g_android_storage_dir);
    }

    g_listenfd = create_abstract_socket(abstract_socket_name);
    if (g_listenfd < 0) {
        return -1;
    }

    g_tcp_listenfd = create_tcp_socket(27015);
    if (g_tcp_listenfd >= 0) {
        setenv("USBMUXD_SOCKET_ADDRESS", "127.0.0.1:27015", 1);
    }

    g_running = 1;
    if (pthread_create(&g_daemon_thread, NULL, daemon_thread_main, NULL) != 0) {
        LOGE("pthread_create failed: %s", strerror(errno));
        g_running = 0;
        close(g_listenfd);
        g_listenfd = -1;
        if (g_tcp_listenfd >= 0) {
            close(g_tcp_listenfd);
            g_tcp_listenfd = -1;
        }
        return -1;
    }

    return 0;
}

void usbmuxd_embedded_stop(void)
{
    if (!g_running) return;
    should_exit = 1;
    g_running = 0;
    /* embedded_main_loop wakes up on its own poll timeout (usb_get_timeout()/
     * device_get_timeout(), usually well under a second) and will notice
     * g_running==0 and exit cleanly. If you need instant shutdown, write
     * a byte to a self-pipe added to the pollfds set instead of waiting. */
    pthread_join(g_daemon_thread, NULL);
}

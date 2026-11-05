/*
 * droidtop's media decode server for Wine games (Droidtop/tracker#601, the enginehost-ffmpeg handoff).
 *
 * Slice 1: the process shape and the wire, with a stub decoder. It listens on a Unix socket, forks one process per
 * stream (a crash or kill ends only that stream), and answers protocol.h. The stub "decodes" any input into the same
 * blank as the media converter's placeholder (docs/SPEC.md 9, "Proton's media converter"): 4 s of black I420 at
 * 64x48, 5 fps, and 8 kHz mono S16 silence, then END. Slice 2 replaces stub_* with the enginehost-ffmpeg library;
 * the child then locks down with Fibrewall's SBX_LOCK_COMPUTE before reading any media (#906).
 *
 * Usage: libdroidtopmediad.so --socket <path>
 */
#define _GNU_SOURCE
#include "protocol.h"

#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#define W 64
#define H 48
#define FPS 5
#define SECONDS 4
#define RATE 8000
#define AUDIO_CHUNK 1600 /* 0.2 s */

static int send_with_fd(int sock, const void *msg, size_t len, int fd) {
    struct iovec iov = { (void *)msg, len };
    char control[CMSG_SPACE(sizeof(int))];
    struct msghdr mh = { 0 };
    mh.msg_iov = &iov;
    mh.msg_iovlen = 1;
    if (fd >= 0) {
        memset(control, 0, sizeof(control));
        mh.msg_control = control;
        mh.msg_controllen = sizeof(control);
        struct cmsghdr *c = CMSG_FIRSTHDR(&mh);
        c->cmsg_level = SOL_SOCKET;
        c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int));
        memcpy(CMSG_DATA(c), &fd, sizeof(int));
    }
    return sendmsg(sock, &mh, MSG_NOSIGNAL) == (ssize_t)len ? 0 : -1;
}

/* One packet; *fd gets a passed descriptor (or -1). Returns its length, 0 on hang-up, -1 on error. */
static ssize_t recv_with_fd(int sock, void *buf, size_t len, int *fd) {
    struct iovec iov = { buf, len };
    char control[CMSG_SPACE(sizeof(int))];
    struct msghdr mh = { 0 };
    mh.msg_iov = &iov;
    mh.msg_iovlen = 1;
    mh.msg_control = control;
    mh.msg_controllen = sizeof(control);
    if (fd) *fd = -1;
    ssize_t n = recvmsg(sock, &mh, MSG_CMSG_CLOEXEC);
    if (n <= 0) return n;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&mh); c; c = CMSG_NXTHDR(&mh, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
            int got;
            memcpy(&got, CMSG_DATA(c), sizeof(int));
            if (fd) *fd = got; else close(got);
        }
    }
    return n;
}

static int make_shared(size_t size) {
    int fd = (int)syscall(SYS_memfd_create, "droidtop-mediad", 0);
    if (fd < 0) return -1;
    if (ftruncate(fd, (off_t)size) < 0) { close(fd); return -1; }
    return fd;
}

/* The stub decoder: every input is the blank. */
static const uint32_t stub_frames = FPS * SECONDS;
static const uint32_t stub_samples = RATE * SECONDS;

static void stub_frame(uint8_t *slot) {
    memset(slot, 16, W * H);                      /* Y: black */
    memset(slot + W * H, 128, W * H / 2);         /* U and V: neutral */
}

static void serve_stream(int sock) {
    struct md_open open_msg;
    int input = -1;
    if (recv_with_fd(sock, &open_msg, sizeof(open_msg), &input) != (ssize_t)sizeof(open_msg) ||
        open_msg.type != MD_OPEN || open_msg.version != MD_VERSION) {
        struct md_simple err = { MD_ERROR };
        send(sock, &err, sizeof(err), MSG_NOSIGNAL);
        return;
    }
    /* Slice 1 reads nothing from the input; slice 2 hands it to the decoder's AVIOContext. */
    if (input >= 0) close(input);

    const uint32_t frame_bytes = W * H * 3 / 2;
    const uint32_t audio_bytes = AUDIO_CHUNK * 2;
    int shared = make_shared(frame_bytes + audio_bytes);
    uint8_t *region = shared >= 0
        ? mmap(NULL, frame_bytes + audio_bytes, PROT_READ | PROT_WRITE, MAP_SHARED, shared, 0) : MAP_FAILED;
    if (region == MAP_FAILED) {
        struct md_simple err = { MD_ERROR };
        send(sock, &err, sizeof(err), MSG_NOSIGNAL);
        return;
    }
    struct md_info info = {
        .type = MD_INFO, .has_video = 1, .width = W, .height = H, .fps_n = FPS, .fps_d = 1,
        .has_audio = 1, .rate = RATE, .channels = 1,
        .frame_bytes = frame_bytes, .audio_bytes = audio_bytes,
        .duration_ns = (uint64_t)SECONDS * 1000000000ull,
    };
    if (send_with_fd(sock, &info, sizeof(info), shared) < 0) return;
    close(shared);

    uint32_t next_frame = 0, next_sample = 0;
    for (;;) {
        union { struct md_simple s; struct md_decode_audio a; } req;
        ssize_t n = recv_with_fd(sock, &req, sizeof(req), NULL);
        if (n <= 0 || req.s.type == MD_CLOSE) return;
        if (req.s.type == MD_DECODE_VIDEO) {
            if (next_frame >= stub_frames) {
                struct md_simple end = { MD_END };
                send(sock, &end, sizeof(end), MSG_NOSIGNAL);
                continue;
            }
            stub_frame(region);
            struct md_frame f = {
                .type = MD_FRAME, .bytes = frame_bytes,
                .pts_ns = (uint64_t)next_frame * 1000000000ull / FPS, .duration_ns = 1000000000ull / FPS,
            };
            next_frame++;
            send(sock, &f, sizeof(f), MSG_NOSIGNAL);
        } else if (req.s.type == MD_DECODE_AUDIO && n == (ssize_t)sizeof(struct md_decode_audio)) {
            uint32_t want = req.a.max_frames;
            if (want > AUDIO_CHUNK) want = AUDIO_CHUNK;
            if (want > stub_samples - next_sample) want = stub_samples - next_sample;
            if (want == 0) {
                struct md_simple end = { MD_END };
                send(sock, &end, sizeof(end), MSG_NOSIGNAL);
                continue;
            }
            memset(region + frame_bytes, 0, (size_t)want * 2);
            struct md_audio a = {
                .type = MD_AUDIO, .frames = want, .pts_ns = (uint64_t)next_sample * 1000000000ull / RATE,
            };
            next_sample += want;
            send(sock, &a, sizeof(a), MSG_NOSIGNAL);
        } else {
            struct md_simple err = { MD_ERROR };
            send(sock, &err, sizeof(err), MSG_NOSIGNAL);
        }
    }
}

int main(int argc, char **argv) {
    const char *path = NULL;
    for (int i = 1; i + 1 < argc; i++)
        if (!strcmp(argv[i], "--socket")) path = argv[i + 1];
    if (!path || strlen(path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        fprintf(stderr, "usage: %s --socket <path>\n", argv[0]);
        return 2;
    }
    signal(SIGCHLD, SIG_IGN); /* no zombies: a stream's process is reaped as it ends */
    signal(SIGPIPE, SIG_IGN);

    int listener = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    strcpy(addr.sun_path, path);
    unlink(path);
    if (listener < 0 || bind(listener, (struct sockaddr *)&addr, sizeof(addr)) < 0 || listen(listener, 16) < 0) {
        fprintf(stderr, "mediad: cannot listen on %s: %s\n", path, strerror(errno));
        return 1;
    }
    fprintf(stderr, "mediad: listening on %s\n", path);
    for (;;) {
        int conn = accept4(listener, NULL, NULL, SOCK_CLOEXEC);
        if (conn < 0) {
            if (errno == EINTR) continue;
            return 1;
        }
        pid_t child = fork();
        if (child == 0) {
            close(listener);
            serve_stream(conn);
            _exit(0);
        }
        close(conn);
    }
}

/*
 * droidtopffdemux: Wine's GStreamer hands a game's media container to droidtop's sandboxed decode server and gets raw
 * video and audio back (Droidtop/tracker#601, the enginehost-ffmpeg handoff; design on #601).
 *
 * winegstreamer builds decodebin pipelines, and the base image has no decoders for the formats games ship (WMV/WMA,
 * AV1, H.264, Opus: #897). This element claims the container caps at a rank above every demuxer, streams nothing of
 * the decoding itself, and exposes one x-raw pad per stream, which winegstreamer maps with no decoder after it. The
 * decoding happens in mediad (mediad.c), one process per stream, reached on $DROIDTOP_MEDIA_SOCKET (protocol.h).
 * When the server ends a stream, or dies, every pad gets EOS, so Media Foundation sees the end of the stream.
 *
 * Slice 1 claims ASF only (the Clover Days intro), and the server's decoder is a stub that returns the media
 * converter's blank. Slice 2 brings the enginehost-ffmpeg decoder and the other containers.
 *
 * Built against vendor/gstreamer and vendor/glib's headers, it is loaded by the image's own GStreamer
 * (usr/lib/gstreamer-1.0 is GameNative's; this plugin sits in its own directory on GST_PLUGIN_PATH).
 */
#define _GNU_SOURCE
#define PACKAGE "droidtop"
#include <gst/gst.h>

#include "protocol.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>

typedef struct {
    GstElement parent;
    GstPad *sinkpad, *vpad, *apad;
    int sock;
    guint8 *region;
    gsize region_size;
    struct md_info info;
    gboolean started, video_done, audio_done;
    guint64 vpts, apts;
} DroidtopFf;

typedef struct {
    GstElementClass parent_class;
} DroidtopFfClass;

G_DEFINE_TYPE(DroidtopFf, droidtop_ff, GST_TYPE_ELEMENT)

#define DROIDTOP_FF(obj) ((DroidtopFf *)(obj))

static GstStaticPadTemplate sink_template = GST_STATIC_PAD_TEMPLATE(
    "sink", GST_PAD_SINK, GST_PAD_ALWAYS, GST_STATIC_CAPS("video/x-ms-asf"));
static GstStaticPadTemplate video_template = GST_STATIC_PAD_TEMPLATE(
    "video", GST_PAD_SRC, GST_PAD_SOMETIMES, GST_STATIC_CAPS("video/x-raw, format=(string)I420"));
static GstStaticPadTemplate audio_template = GST_STATIC_PAD_TEMPLATE(
    "audio", GST_PAD_SRC, GST_PAD_SOMETIMES,
    GST_STATIC_CAPS("audio/x-raw, format=(string)S16LE, layout=(string)interleaved"));

static void post_error(DroidtopFf *self, const char *text) {
    GError *error = g_error_new_literal(gst_stream_error_quark(), GST_STREAM_ERROR_DECODE, text);
    gst_element_post_message(GST_ELEMENT(self), gst_message_new_error(GST_OBJECT(self), error, text));
    g_error_free(error);
}

static int send_with_fd(int sock, const void *msg, size_t len, int fd) {
    struct iovec iov = { (void *)msg, len };
    char control[CMSG_SPACE(sizeof(int))];
    struct msghdr mh;
    memset(&mh, 0, sizeof(mh));
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

static ssize_t recv_with_fd(int sock, void *buf, size_t len, int *fd) {
    struct iovec iov = { buf, len };
    char control[CMSG_SPACE(sizeof(int))];
    struct msghdr mh;
    memset(&mh, 0, sizeof(mh));
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

static GstPad *add_src_pad(DroidtopFf *self, GstStaticPadTemplate *templ, const char *name, GstCaps *caps) {
    GstPad *pad = gst_pad_new_from_static_template(templ, name);
    gst_pad_use_fixed_caps(pad);
    gst_pad_set_active(pad, TRUE);
    gchar *stream_id = gst_pad_create_stream_id(pad, GST_ELEMENT(self), name);
    gst_pad_push_event(pad, gst_event_new_stream_start(stream_id));
    g_free(stream_id);
    gst_pad_push_event(pad, gst_event_new_caps(caps));
    gst_caps_unref(caps);
    GstSegment segment;
    gst_segment_init(&segment, GST_FORMAT_TIME);
    gst_pad_push_event(pad, gst_event_new_segment(&segment));
    gst_element_add_pad(GST_ELEMENT(self), pad);
    return pad;
}

static void finish(DroidtopFf *self) {
    if (self->vpad && !self->video_done) gst_pad_push_event(self->vpad, gst_event_new_eos());
    if (self->apad && !self->audio_done) gst_pad_push_event(self->apad, gst_event_new_eos());
    self->video_done = self->audio_done = TRUE;
}

/* The streaming task: asks the server for whichever stream is behind, and pushes it. */
static void loop(gpointer data) {
    DroidtopFf *self = DROIDTOP_FF(data);
    GstPad *task_pad = self->vpad ? self->vpad : self->apad;
    if (self->video_done && self->audio_done) {
        gst_pad_pause_task(task_pad);
        return;
    }
    gboolean video = !self->video_done && (self->audio_done || self->vpts <= self->apts);
    union { struct md_simple s; struct md_frame f; struct md_audio a; } reply;
    if (video) {
        struct md_simple req = { MD_DECODE_VIDEO };
        if (send(self->sock, &req, sizeof(req), MSG_NOSIGNAL) != sizeof(req)) goto ended;
    } else {
        struct md_decode_audio req = { MD_DECODE_AUDIO, 1600 };
        if (send(self->sock, &req, sizeof(req), MSG_NOSIGNAL) != sizeof(req)) goto ended;
    }
    ssize_t n = recv_with_fd(self->sock, &reply, sizeof(reply), NULL);
    if (n < (ssize_t)sizeof(struct md_simple)) goto ended;

    GstBuffer *buffer = NULL;
    GstPad *pad;
    if (reply.s.type == MD_FRAME && video && n == sizeof(struct md_frame) && reply.f.bytes <= self->info.frame_bytes) {
        buffer = gst_buffer_new_allocate(NULL, reply.f.bytes, NULL);
        gst_buffer_fill(buffer, 0, self->region, reply.f.bytes);
        GST_BUFFER_PTS(buffer) = reply.f.pts_ns;
        GST_BUFFER_DURATION(buffer) = reply.f.duration_ns;
        self->vpts = reply.f.pts_ns;
        pad = self->vpad;
    } else if (reply.s.type == MD_AUDIO && !video && n == sizeof(struct md_audio) &&
               (gsize)reply.a.frames * 2 * self->info.channels <= self->info.audio_bytes) {
        gsize bytes = (gsize)reply.a.frames * 2 * self->info.channels;
        buffer = gst_buffer_new_allocate(NULL, bytes, NULL);
        gst_buffer_fill(buffer, 0, self->region + self->info.frame_bytes, bytes);
        GST_BUFFER_PTS(buffer) = reply.a.pts_ns;
        GST_BUFFER_DURATION(buffer) = (guint64)reply.a.frames * GST_SECOND / self->info.rate;
        self->apts = reply.a.pts_ns;
        pad = self->apad;
    } else if (reply.s.type == MD_END) {
        if (video) {
            gst_pad_push_event(self->vpad, gst_event_new_eos());
            self->video_done = TRUE;
        } else {
            gst_pad_push_event(self->apad, gst_event_new_eos());
            self->audio_done = TRUE;
        }
        return;
    } else {
        goto ended;
    }
    GstFlowReturn flow = gst_pad_push(pad, buffer);
    if (flow == GST_FLOW_NOT_LINKED) {
        /* Nobody wants this stream (a video-only reader): stop decoding it, keep the other. */
        if (video) self->video_done = TRUE; else self->audio_done = TRUE;
        gst_pad_push_event(pad, gst_event_new_eos());
    } else if (flow != GST_FLOW_OK) {
        gst_pad_pause_task(task_pad);
    }
    return;

ended:
    /* The server ended the stream, died or was killed by its watchdog: the stream ends, nothing else does. */
    finish(self);
    gst_pad_pause_task(task_pad);
}

static gboolean start(DroidtopFf *self) {
    const char *path = getenv("DROIDTOP_MEDIA_SOCKET");
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (!path || strlen(path) >= sizeof(addr.sun_path)) return FALSE;
    strcpy(addr.sun_path, path);
    self->sock = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (self->sock < 0 || connect(self->sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) return FALSE;

    /* The input descriptor. Slice 1's stub reads nothing from it; slice 2 fills it with the container's bytes. */
    int input = (int)syscall(SYS_memfd_create, "droidtop-media-input", 0);
    struct md_open open_msg = { MD_OPEN, MD_VERSION };
    int sent = send_with_fd(self->sock, &open_msg, sizeof(open_msg), input);
    if (input >= 0) close(input);
    if (sent < 0) return FALSE;

    int shared = -1;
    ssize_t n = recv_with_fd(self->sock, &self->info, sizeof(self->info), &shared);
    if (n != (ssize_t)sizeof(self->info) || self->info.type != MD_INFO || shared < 0) {
        if (shared >= 0) close(shared);
        return FALSE;
    }
    self->region_size = (gsize)self->info.frame_bytes + self->info.audio_bytes;
    self->region = mmap(NULL, self->region_size, PROT_READ, MAP_SHARED, shared, 0);
    close(shared);
    if (self->region == MAP_FAILED) {
        self->region = NULL;
        return FALSE;
    }

    self->video_done = !self->info.has_video;
    self->audio_done = !self->info.has_audio;
    if (self->info.has_video) {
        self->vpad = add_src_pad(self, &video_template, "video_0", gst_caps_new_simple("video/x-raw",
            "format", G_TYPE_STRING, "I420",
            "width", G_TYPE_INT, (gint)self->info.width,
            "height", G_TYPE_INT, (gint)self->info.height,
            "framerate", GST_TYPE_FRACTION, (gint)self->info.fps_n, (gint)self->info.fps_d,
            "pixel-aspect-ratio", GST_TYPE_FRACTION, 1, 1,
            NULL));
    }
    if (self->info.has_audio) {
        self->apad = add_src_pad(self, &audio_template, "audio_0", gst_caps_new_simple("audio/x-raw",
            "format", G_TYPE_STRING, "S16LE",
            "layout", G_TYPE_STRING, "interleaved",
            "rate", G_TYPE_INT, (gint)self->info.rate,
            "channels", G_TYPE_INT, (gint)self->info.channels,
            NULL));
    }
    gst_element_no_more_pads(GST_ELEMENT(self));
    GstPad *task_pad = self->vpad ? self->vpad : self->apad;
    if (!task_pad) return FALSE;
    return gst_pad_start_task(task_pad, loop, self, NULL);
}

static void stop(DroidtopFf *self) {
    if (self->vpad) gst_pad_stop_task(self->vpad);
    else if (self->apad) gst_pad_stop_task(self->apad);
    if (self->sock >= 0) {
        struct md_simple req = { MD_CLOSE };
        send(self->sock, &req, sizeof(req), MSG_NOSIGNAL);
        close(self->sock);
        self->sock = -1;
    }
    if (self->region) munmap(self->region, self->region_size);
    self->region = NULL;
    if (self->vpad) gst_element_remove_pad(GST_ELEMENT(self), self->vpad);
    if (self->apad) gst_element_remove_pad(GST_ELEMENT(self), self->apad);
    self->vpad = self->apad = NULL;
    self->started = FALSE;
}

static GstFlowReturn chain(GstPad *pad, GstObject *parent, GstBuffer *buffer) {
    DroidtopFf *self = DROIDTOP_FF(parent);
    (void)pad;
    gst_buffer_unref(buffer);
    if (!self->started) {
        self->started = TRUE;
        if (!start(self)) {
            post_error(self, "droidtop's media decode server is not available");
            return GST_FLOW_ERROR;
        }
    }
    return GST_FLOW_OK;
}

static gboolean sink_event(GstPad *pad, GstObject *parent, GstEvent *event) {
    (void)pad;
    (void)parent;
    /* The container's own segment, caps and EOS stay here: the server's output carries its own. */
    gst_event_unref(event);
    return TRUE;
}

static GstStateChangeReturn change_state(GstElement *element, GstStateChange transition) {
    GstStateChangeReturn ret = GST_ELEMENT_CLASS(droidtop_ff_parent_class)->change_state(element, transition);
    if (transition == GST_STATE_CHANGE_PAUSED_TO_READY) stop(DROIDTOP_FF(element));
    return ret;
}

static void droidtop_ff_init(DroidtopFf *self) {
    self->sock = -1;
    self->sinkpad = gst_pad_new_from_static_template(&sink_template, "sink");
    gst_pad_set_chain_function(self->sinkpad, chain);
    gst_pad_set_event_function(self->sinkpad, sink_event);
    gst_element_add_pad(GST_ELEMENT(self), self->sinkpad);
}

static void droidtop_ff_class_init(DroidtopFfClass *klass) {
    GstElementClass *element_class = GST_ELEMENT_CLASS(klass);
    gst_element_class_add_static_pad_template(element_class, &sink_template);
    gst_element_class_add_static_pad_template(element_class, &video_template);
    gst_element_class_add_static_pad_template(element_class, &audio_template);
    gst_element_class_set_static_metadata(element_class, "droidtop media handoff", "Codec/Demuxer",
        "Decodes a container in droidtop's sandboxed decode server (Droidtop/tracker#601)", "droidtop");
    element_class->change_state = change_state;
}

static gboolean plugin_init(GstPlugin *plugin) {
    /* Above every demuxer for these caps, Proton's converter (marginal) included. */
    return gst_element_register(plugin, "droidtopffdemux", GST_RANK_PRIMARY + 10, droidtop_ff_get_type());
}

GST_PLUGIN_DEFINE(GST_VERSION_MAJOR, GST_VERSION_MINOR, droidtopff,
    "droidtop's media handoff to its sandboxed decode server", plugin_init, "1.0", "GPL", "droidtop",
    "https://github.com/Droidtop/droidtop")

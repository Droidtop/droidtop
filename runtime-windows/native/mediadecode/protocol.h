/*
 * The wire between Wine's GStreamer element (gstdroidtopff.c) and droidtop's media decode server (mediad.c):
 * Droidtop/tracker#601, the enginehost-ffmpeg handoff for Wine games.
 *
 * One SOCK_SEQPACKET connection per stream; each message is one packet, little-endian, starting with a uint32 type.
 * The server forks one process per connection, so a crash or kill ends only that stream.
 *
 *   client -> OPEN {version}            + SCM_RIGHTS: the stream's input (a memfd or pipe the client fills)
 *   server -> INFO {...}                + SCM_RIGHTS: the shared output region (frame slot, then audio slot)
 *   client -> DECODE_VIDEO              server -> FRAME {pts, duration, bytes} written into the frame slot, or END
 *   client -> DECODE_AUDIO {max_frames} server -> AUDIO {pts, frames} written into the audio slot, or END
 *   client -> CLOSE                     (or just closes the socket)
 *
 * The calls mirror Enginehost's IMediaStream (#906: info, decodeVideo, decodeAudio, seek, close), so the decode side
 * can be the same enginehost-ffmpeg code in both builds. Seek is slice 3.
 */
#ifndef DROIDTOP_MEDIADECODE_PROTOCOL_H
#define DROIDTOP_MEDIADECODE_PROTOCOL_H

#include <stdint.h>

#define MD_VERSION 1

enum md_type {
    MD_OPEN = 1,
    MD_INFO = 2,
    MD_DECODE_VIDEO = 3,
    MD_FRAME = 4,
    MD_DECODE_AUDIO = 5,
    MD_AUDIO = 6,
    MD_END = 7,
    MD_CLOSE = 8,
    MD_ERROR = 9,
};

/* Video is I420, audio interleaved S16LE: both are x-raw, which winegstreamer maps with no decoder after it. */
struct md_open { uint32_t type, version; };
struct md_info {
    uint32_t type;
    uint32_t has_video, width, height, fps_n, fps_d;
    uint32_t has_audio, rate, channels;
    uint32_t frame_bytes;       /* size of the frame slot at offset 0 of the shared region */
    uint32_t audio_bytes;       /* size of the audio slot, right after the frame slot */
    uint64_t duration_ns;       /* 0 when unknown */
};
struct md_decode_audio { uint32_t type, max_frames; };
struct md_frame { uint32_t type, bytes; uint64_t pts_ns, duration_ns; };
struct md_audio { uint32_t type, frames; uint64_t pts_ns; };
struct md_simple { uint32_t type; };

#endif

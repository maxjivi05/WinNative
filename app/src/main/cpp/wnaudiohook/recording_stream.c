#include <stddef.h>
#include <stdlib.h>
#include <time.h>
#include <pthread.h>
#include "recording_tap.h"

void *wn_resolve_aaudio(const char *name);

typedef int32_t (*data_callback_t)(void *, void *, void *, int32_t);
struct recording_stream {
    void *builder;
    void *stream;
    data_callback_t callback;
    void *user;
    int socket_fd;
    int rate, channels, encoding, frame_bytes;
    uint64_t retry_ns;
    struct recording_stream *next;
};
static pthread_mutex_t recording_lock = PTHREAD_MUTEX_INITIALIZER;
static struct recording_stream *recording_streams;

static int32_t recording_callback(void *stream, void *user, void *data, int32_t frames) {
    struct recording_stream *tap = user;
    int32_t result = tap->callback(stream, tap->user, data, frames);
    if (tap->socket_fd < 0 || tap->frame_bytes == 0 || frames <= 0) return result;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    uint64_t time_ns = (uint64_t)now.tv_sec * 1000000000ULL + now.tv_nsec;
    if (time_ns < tap->retry_ns) return result;
    struct sockaddr_un address;
    socklen_t length = wn_record_address(&address);
    int maximum = WN_RECORD_PCM_BYTES / tap->frame_bytes;
    for (int offset = 0; offset < frames; offset += maximum) {
        int count = frames - offset;
        if (count > maximum) count = maximum;
        struct wn_record_packet packet = {
            .stream = ((uint64_t)(uint32_t)getpid() << 32) ^ (uintptr_t)stream,
            .time_ns = time_ns + (uint64_t)(offset + count) * 1000000000ULL / tap->rate,
            .rate = tap->rate, .channels = tap->channels, .encoding = tap->encoding,
            .bytes = count * tap->frame_bytes
        };
        struct iovec iov[2] = {
            {.iov_base = &packet, .iov_len = sizeof(packet)},
            {.iov_base = (char *)data + offset * tap->frame_bytes, .iov_len = (size_t)packet.bytes}
        };
        struct msghdr message = {.msg_name = &address, .msg_namelen = length, .msg_iov = iov, .msg_iovlen = 2};
        if (sendmsg(tap->socket_fd, &message, MSG_DONTWAIT | MSG_NOSIGNAL) < 0) {
            tap->retry_ns = time_ns + 100000000ULL;
            break;
        }
    }
    return result;
}

__attribute__((visibility("default")))
void AAudioStreamBuilder_setDataCallback(void *builder, data_callback_t callback, void *user) {
    void (*set)(void *, data_callback_t, void *) = wn_resolve_aaudio("AAudioStreamBuilder_setDataCallback");
    if (!set) return;
    pthread_mutex_lock(&recording_lock);
    struct recording_stream *tap = recording_streams;
    while (tap && tap->builder != builder) tap = tap->next;
    if (!tap) {
        tap = calloc(1, sizeof(*tap));
        if (tap) { tap->builder = builder; tap->socket_fd = -1; tap->next = recording_streams; recording_streams = tap; }
    }
    if (tap) { tap->callback = callback; tap->user = user; }
    set(builder, tap && callback ? recording_callback : callback, tap && callback ? tap : user);
    pthread_mutex_unlock(&recording_lock);
}

__attribute__((visibility("default")))
int32_t AAudioStreamBuilder_openStream(void *builder, void **stream) {
    int32_t (*open_stream)(void *, void **) = wn_resolve_aaudio("AAudioStreamBuilder_openStream");
    void (*set)(void *, data_callback_t, void *) = wn_resolve_aaudio("AAudioStreamBuilder_setDataCallback");
    if (!open_stream || !set) return -1;
    pthread_mutex_lock(&recording_lock);
    struct recording_stream *template = recording_streams;
    while (template && template->builder != builder) template = template->next;
    struct recording_stream *tap = template && template->callback ? calloc(1, sizeof(*tap)) : NULL;
    if (tap) {
        tap->callback = template->callback; tap->user = template->user; tap->socket_fd = -1;
        set(builder, recording_callback, tap);
    } else if (template) {
        set(builder, template->callback, template->user);
    }
    int32_t result = open_stream(builder, stream);
    if (template) set(builder, template->callback, template->user);
    if (result == 0 && tap) {
        tap->stream = *stream;
        int32_t (*direction)(void *) = wn_resolve_aaudio("AAudioStream_getDirection");
        int32_t (*rate)(void *) = wn_resolve_aaudio("AAudioStream_getSampleRate");
        int32_t (*channels)(void *) = wn_resolve_aaudio("AAudioStream_getChannelCount");
        int32_t (*format)(void *) = wn_resolve_aaudio("AAudioStream_getFormat");
        if (direction && rate && channels && format && direction(*stream) == 0) {
            tap->rate = rate(*stream); tap->channels = channels(*stream);
            int f = format(*stream);
            tap->encoding = f == 1 ? 2 : f == 2 ? 4 : 0;
            if (tap->rate > 0 && tap->channels > 0 && tap->channels <= 8 && tap->encoding) {
                tap->frame_bytes = tap->channels * (f == 1 ? 2 : 4);
                tap->socket_fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
            }
        }
        tap->next = recording_streams; recording_streams = tap;
    } else free(tap);
    pthread_mutex_unlock(&recording_lock);
    return result;
}

__attribute__((visibility("default")))
int32_t AAudioStreamBuilder_delete(void *builder) {
    int32_t (*delete_builder)(void *) = wn_resolve_aaudio("AAudioStreamBuilder_delete");
    int32_t result = delete_builder ? delete_builder(builder) : -1;
    pthread_mutex_lock(&recording_lock);
    struct recording_stream **entry = &recording_streams;
    while (*entry) {
        struct recording_stream *tap = *entry;
        if (tap->builder == builder) {
            tap->builder = NULL;
            if (!tap->stream) { *entry = tap->next; free(tap); }
            break;
        }
        entry = &tap->next;
    }
    pthread_mutex_unlock(&recording_lock);
    return result;
}

__attribute__((visibility("default")))
int32_t AAudioStream_close(void *stream) {
    int32_t (*close_stream)(void *) = wn_resolve_aaudio("AAudioStream_close");
    int32_t result = close_stream ? close_stream(stream) : -1;
    if (result != 0) return result;
    pthread_mutex_lock(&recording_lock);
    struct recording_stream **entry = &recording_streams;
    while (*entry) {
        struct recording_stream *tap = *entry;
        if (tap->stream == stream) {
            if (tap->socket_fd >= 0) close(tap->socket_fd);
            tap->socket_fd = -1; tap->stream = NULL;
            if (!tap->builder) { *entry = tap->next; free(tap); }
            break;
        }
        entry = &tap->next;
    }
    pthread_mutex_unlock(&recording_lock);
    return result;
}

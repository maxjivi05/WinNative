#include <assert.h>
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include "../../main/cpp/wnaudiohook/recording_tap.h"

typedef int32_t (*callback_t)(void *, void *, void *, int32_t);
struct builder { callback_t callback; void *user; int direction; };
struct stream { callback_t callback; void *user; int direction; };
void AAudioStreamBuilder_setDataCallback(void *, callback_t, void *);
int32_t AAudioStreamBuilder_openStream(void *, void **);
int32_t AAudioStreamBuilder_delete(void *);
int32_t AAudioStream_close(void *);

static void set_callback(void *pointer, callback_t callback, void *user) {
    struct builder *builder = pointer; builder->callback = callback; builder->user = user;
}
static int32_t open_stream(void *pointer, void **result) {
    struct builder *builder = pointer;
    struct stream *stream = calloc(1, sizeof(*stream));
    stream->callback = builder->callback; stream->user = builder->user; stream->direction = builder->direction;
    *result = stream; return 0;
}
static int32_t direction(void *pointer) { return ((struct stream *)pointer)->direction; }
static int32_t rate(void *pointer) { (void)pointer; return 48000; }
static int32_t channels(void *pointer) { (void)pointer; return 2; }
static int32_t format(void *pointer) { (void)pointer; return 1; }
static int32_t delete_builder(void *pointer) { (void)pointer; return 0; }
static int32_t close_stream(void *pointer) { free(pointer); return 0; }
void *wn_resolve_aaudio(const char *name) {
    if (!strcmp(name, "AAudioStreamBuilder_setDataCallback")) return set_callback;
    if (!strcmp(name, "AAudioStreamBuilder_openStream")) return open_stream;
    if (!strcmp(name, "AAudioStream_getDirection")) return direction;
    if (!strcmp(name, "AAudioStream_getSampleRate")) return rate;
    if (!strcmp(name, "AAudioStream_getChannelCount")) return channels;
    if (!strcmp(name, "AAudioStream_getFormat")) return format;
    if (!strcmp(name, "AAudioStreamBuilder_delete")) return delete_builder;
    if (!strcmp(name, "AAudioStream_close")) return close_stream;
    assert(0); return NULL;
}
static int32_t fill(void *stream, void *user, void *pcm, int32_t frames) {
    (void)stream;
    int16_t value = *(int16_t *)user;
    for (int i = 0; i < frames * 2; i++) ((int16_t *)pcm)[i] = value;
    return 0;
}
static void verify_packet(int socket, int sample, int frames) {
    char data[sizeof(struct wn_record_packet) + WN_RECORD_PCM_BYTES];
    int got = recv(socket, data, sizeof(data), 0);
    assert(got == (int)sizeof(struct wn_record_packet) + frames * 4);
    struct wn_record_packet header;
    memcpy(&header, data, sizeof(header));
    assert(header.rate == 48000 && header.channels == 2 && header.encoding == 2);
    assert(header.bytes == frames * 4 && header.time_ns > 0);
    for (int i = 0; i < frames * 2; i++) {
        int16_t value; memcpy(&value, data + sizeof(header) + i * 2, 2); assert(value == sample);
    }
}
int main(void) {
    int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK, 0);
    struct sockaddr_un address; socklen_t length = wn_record_address(&address);
    assert(bind(fd, (struct sockaddr *)&address, length) == 0);
    struct builder builder = {0};
    int16_t first = 123, second = -456;
    AAudioStreamBuilder_setDataCallback(&builder, fill, &first);
    struct stream *one, *two;
    assert(AAudioStreamBuilder_openStream(&builder, (void **)&one) == 0);
    AAudioStreamBuilder_setDataCallback(&builder, fill, &second);
    assert(AAudioStreamBuilder_openStream(&builder, (void **)&two) == 0);
    AAudioStreamBuilder_delete(&builder);
    int16_t pcm[4096];
    one->callback(one, one->user, pcm, 2048);
    verify_packet(fd, 123, 1024); verify_packet(fd, 123, 1024);
    two->callback(two, two->user, pcm, 480); verify_packet(fd, -456, 480);
    for (int i = 0; i < 10000; i++) one->callback(one, one->user, pcm, 480);
    AAudioStream_close(one); AAudioStream_close(two);
    while (recv(fd, pcm, sizeof(pcm), 0) > 0) {}
    builder.direction = 1;
    AAudioStreamBuilder_setDataCallback(&builder, fill, &first);
    AAudioStreamBuilder_openStream(&builder, (void **)&one);
    one->callback(one, one->user, pcm, 480);
    assert(pcm[0] == first);
    assert(recv(fd, pcm, sizeof(pcm), 0) < 0 && errno == EAGAIN);
    AAudioStream_close(one); AAudioStreamBuilder_delete(&builder);
    close(fd);
    return 0;
}

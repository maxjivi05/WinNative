#ifndef WN_RECORDING_TAP_H
#define WN_RECORDING_TAP_H
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define WN_RECORD_PCM_BYTES 4096
struct wn_record_packet {
    uint64_t stream;
    uint64_t time_ns;
    int32_t rate;
    int32_t channels;
    int32_t encoding;
    int32_t bytes;
};
static inline socklen_t wn_record_address(struct sockaddr_un *address) {
    memset(address, 0, sizeof(*address));
    address->sun_family = AF_UNIX;
    int length = snprintf(address->sun_path + 1, sizeof(address->sun_path) - 1,
                          "winnative-record-%u", (unsigned)getuid());
    return (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + length);
}
#endif

#include <stddef.h>
#include <jni.h>
#include <errno.h>
#include "recording_tap.h"

#define JNI_FN(name) Java_com_winlator_cmod_runtime_display_recording_NativeRecordingAudio_##name
JNIEXPORT jint JNICALL JNI_FN(open)(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    int enabled = 1;
    setsockopt(fd, SOL_SOCKET, SO_PASSCRED, &enabled, sizeof(enabled));
    int size = 256 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &size, sizeof(size));
    struct sockaddr_un address;
    socklen_t length = wn_record_address(&address);
    if (bind(fd, (struct sockaddr *)&address, length) < 0) { close(fd); return -1; }
    return fd;
}
JNIEXPORT jint JNICALL JNI_FN(read)(JNIEnv *env, jclass type, jint fd, jobject buffer) {
    (void)type;
    void *data = (*env)->GetDirectBufferAddress(env, buffer);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    if (fd < 0 || !data || capacity < sizeof(struct wn_record_packet) + WN_RECORD_PCM_BYTES) return -1;
    struct iovec iov = {.iov_base = data, .iov_len = (size_t)capacity};
    union { struct cmsghdr alignment; char bytes[CMSG_SPACE(sizeof(struct ucred))]; } control;
    struct msghdr message = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control.bytes, .msg_controllen = sizeof(control)};
    ssize_t count = recvmsg(fd, &message, MSG_DONTWAIT);
    if (count < 0) return errno == EAGAIN || errno == EWOULDBLOCK ? 0 : -1;
    if (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) return 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&message); c; c = CMSG_NXTHDR(&message, c)) {
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_CREDENTIALS) {
            struct ucred *credential = (struct ucred *)CMSG_DATA(c);
            return credential->uid == getuid() ? (jint)count : 0;
        }
    }
    return 0;
}
JNIEXPORT void JNICALL JNI_FN(close)(JNIEnv *env, jclass type, jint fd) {
    (void)env; (void)type;
    if (fd >= 0) close(fd);
}

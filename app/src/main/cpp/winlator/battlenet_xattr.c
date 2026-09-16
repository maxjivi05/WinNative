#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <sys/xattr.h>
#include <unistd.h>

typedef int (*setxattr_fn)(const char *, const char *, const void *, size_t, int);
typedef int (*fsetxattr_fn)(int, const char *, const void *, size_t, int);
typedef ssize_t (*getxattr_fn)(const char *, const char *, void *, size_t);
typedef ssize_t (*fgetxattr_fn)(int, const char *, void *, size_t);
typedef int (*fremovexattr_fn)(int, const char *);
typedef int (*chmod_fn)(const char *, mode_t);
typedef int (*fchmod_fn)(int, mode_t);
typedef int (*stat_fn)(const char *, struct stat *);
typedef int (*fstat_fn)(int, struct stat *);
typedef int (*fstatat_fn)(int, const char *, struct stat *, int);
typedef int (*access_fn)(const char *, int);

static pthread_once_t symbols_once = PTHREAD_ONCE_INIT;
static setxattr_fn real_setxattr;
static setxattr_fn real_lsetxattr;
static fsetxattr_fn real_fsetxattr;
static getxattr_fn real_getxattr;
static getxattr_fn real_lgetxattr;
static fgetxattr_fn real_fgetxattr;
static fremovexattr_fn real_fremovexattr;
static chmod_fn real_chmod;
static fchmod_fn real_fchmod;
static stat_fn real_stat;
static stat_fn real_lstat;
static fstat_fn real_fstat;
static fstatat_fn real_fstatat;
static access_fn real_access;

static void load_symbols(void) {
    real_setxattr = (setxattr_fn)dlsym(RTLD_NEXT, "setxattr");
    real_lsetxattr = (setxattr_fn)dlsym(RTLD_NEXT, "lsetxattr");
    real_fsetxattr = (fsetxattr_fn)dlsym(RTLD_NEXT, "fsetxattr");
    real_getxattr = (getxattr_fn)dlsym(RTLD_NEXT, "getxattr");
    real_lgetxattr = (getxattr_fn)dlsym(RTLD_NEXT, "lgetxattr");
    real_fgetxattr = (fgetxattr_fn)dlsym(RTLD_NEXT, "fgetxattr");
    real_fremovexattr = (fremovexattr_fn)dlsym(RTLD_NEXT, "fremovexattr");
    real_chmod = (chmod_fn)dlsym(RTLD_NEXT, "chmod");
    real_fchmod = (fchmod_fn)dlsym(RTLD_NEXT, "fchmod");
    real_stat = (stat_fn)dlsym(RTLD_NEXT, "stat");
    real_lstat = (stat_fn)dlsym(RTLD_NEXT, "lstat");
    real_fstat = (fstat_fn)dlsym(RTLD_NEXT, "fstat");
    real_fstatat = (fstatat_fn)dlsym(RTLD_NEXT, "fstatat");
    real_access = (access_fn)dlsym(RTLD_NEXT, "access");
}

static int compat_enabled(void) {
    const char *value = getenv("WN_BATTLENET_XATTR_COMPAT");
    return value != NULL && strcmp(value, "1") == 0;
}

static int wine_descriptor(const char *name) {
    return name != NULL && strncmp(name, "user.wine.", 10) == 0;
}

static int unsupported(void) {
    return errno == EOPNOTSUPP || errno == ENOTSUP || errno == EPERM || errno == EACCES;
}

static int shared_path(const char *path) {
    return path != NULL &&
           (strncmp(path, "/storage/emulated/", 18) == 0 || strncmp(path, "/sdcard/", 8) == 0);
}

static void normalize_stat(struct stat *value) {
    value->st_uid = getuid();
    value->st_gid = getgid();
    value->st_mode |= S_IRUSR | S_IWUSR;
    if (S_ISDIR(value->st_mode)) value->st_mode |= S_IXUSR;
}

static int shared_fd(int fd) {
    struct statfs value;
    return fstatfs(fd, &value) == 0 && (unsigned long)value.f_type == 0x65735546UL;
}

static int shared_at(int dirfd, const char *path) {
    if (shared_path(path)) return 1;
    if (path == NULL || path[0] == '/') return 0;
    if (dirfd != AT_FDCWD) return shared_fd(dirfd);
    char cwd[PATH_MAX];
    return getcwd(cwd, sizeof(cwd)) != NULL && shared_path(cwd);
}

__attribute__((visibility("default")))
int setxattr(const char *path, const char *name, const void *value, size_t size, int flags) {
    pthread_once(&symbols_once, load_symbols);
    if (real_setxattr == NULL) { errno = ENOSYS; return -1; }
    int result = real_setxattr(path, name, value, size, flags);
    if (result < 0 && compat_enabled() && shared_path(path) && wine_descriptor(name) && unsupported()) return 0;
    return result;
}

__attribute__((visibility("default")))
int lsetxattr(const char *path, const char *name, const void *value, size_t size, int flags) {
    pthread_once(&symbols_once, load_symbols);
    if (real_lsetxattr == NULL) { errno = ENOSYS; return -1; }
    int result = real_lsetxattr(path, name, value, size, flags);
    if (result < 0 && compat_enabled() && shared_path(path) && wine_descriptor(name) && unsupported()) return 0;
    return result;
}

__attribute__((visibility("default")))
int fsetxattr(int fd, const char *name, const void *value, size_t size, int flags) {
    pthread_once(&symbols_once, load_symbols);
    if (real_fsetxattr == NULL) { errno = ENOSYS; return -1; }
    int result = real_fsetxattr(fd, name, value, size, flags);
    if (result < 0 && compat_enabled() && shared_fd(fd) && wine_descriptor(name) && unsupported()) return 0;
    return result;
}

__attribute__((visibility("default")))
ssize_t getxattr(const char *path, const char *name, void *value, size_t size) {
    pthread_once(&symbols_once, load_symbols);
    if (real_getxattr == NULL) { errno = ENOSYS; return -1; }
    ssize_t result = real_getxattr(path, name, value, size);
    if (result < 0 && compat_enabled() && shared_path(path) && wine_descriptor(name) && unsupported()) errno = ENODATA;
    return result;
}

__attribute__((visibility("default")))
ssize_t lgetxattr(const char *path, const char *name, void *value, size_t size) {
    pthread_once(&symbols_once, load_symbols);
    if (real_lgetxattr == NULL) { errno = ENOSYS; return -1; }
    ssize_t result = real_lgetxattr(path, name, value, size);
    if (result < 0 && compat_enabled() && shared_path(path) && wine_descriptor(name) && unsupported()) errno = ENODATA;
    return result;
}

__attribute__((visibility("default")))
ssize_t fgetxattr(int fd, const char *name, void *value, size_t size) {
    pthread_once(&symbols_once, load_symbols);
    if (real_fgetxattr == NULL) { errno = ENOSYS; return -1; }
    ssize_t result = real_fgetxattr(fd, name, value, size);
    if (result < 0 && compat_enabled() && shared_fd(fd) && wine_descriptor(name) && unsupported()) errno = ENODATA;
    return result;
}

__attribute__((visibility("default")))
int fremovexattr(int fd, const char *name) {
    pthread_once(&symbols_once, load_symbols);
    if (real_fremovexattr == NULL) { errno = ENOSYS; return -1; }
    int result = real_fremovexattr(fd, name);
    if (result < 0 && compat_enabled() && shared_fd(fd) && wine_descriptor(name) && unsupported()) return 0;
    return result;
}

__attribute__((visibility("default")))
int chmod(const char *path, mode_t mode) {
    pthread_once(&symbols_once, load_symbols);
    if (real_chmod == NULL) { errno = ENOSYS; return -1; }
    int result = real_chmod(path, mode);
    if (result < 0 && compat_enabled() && shared_path(path) && unsupported()) return 0;
    return result;
}

__attribute__((visibility("default")))
int fchmod(int fd, mode_t mode) {
    pthread_once(&symbols_once, load_symbols);
    if (real_fchmod == NULL) { errno = ENOSYS; return -1; }
    int result = real_fchmod(fd, mode);
    if (result < 0 && compat_enabled() && shared_fd(fd) && unsupported()) return 0;
    return result;
}

__attribute__((visibility("default")))
int stat(const char *path, struct stat *value) {
    pthread_once(&symbols_once, load_symbols);
    if (real_stat == NULL) { errno = ENOSYS; return -1; }
    int result = real_stat(path, value);
    if (result == 0 && compat_enabled() && shared_path(path)) normalize_stat(value);
    return result;
}

__attribute__((visibility("default")))
int lstat(const char *path, struct stat *value) {
    pthread_once(&symbols_once, load_symbols);
    if (real_lstat == NULL) { errno = ENOSYS; return -1; }
    int result = real_lstat(path, value);
    if (result == 0 && compat_enabled() && shared_path(path)) normalize_stat(value);
    return result;
}

__attribute__((visibility("default")))
int fstat(int fd, struct stat *value) {
    pthread_once(&symbols_once, load_symbols);
    if (real_fstat == NULL) { errno = ENOSYS; return -1; }
    int result = real_fstat(fd, value);
    if (result == 0 && compat_enabled() && shared_fd(fd)) normalize_stat(value);
    return result;
}

__attribute__((visibility("default")))
int fstatat(int dirfd, const char *path, struct stat *value, int flags) {
    pthread_once(&symbols_once, load_symbols);
    if (real_fstatat == NULL) { errno = ENOSYS; return -1; }
    int result = real_fstatat(dirfd, path, value, flags);
    if (result == 0 && compat_enabled() && shared_at(dirfd, path)) normalize_stat(value);
    return result;
}

__attribute__((visibility("default")))
int access(const char *path, int mode) {
    pthread_once(&symbols_once, load_symbols);
    if (real_access == NULL) { errno = ENOSYS; return -1; }
    int result = real_access(path, mode);
    if (result < 0 && compat_enabled() && shared_at(AT_FDCWD, path) &&
        (errno == EACCES || errno == EPERM)) return 0;
    return result;
}

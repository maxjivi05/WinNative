/*
 * Pull `<features.h>` first so `__GLIBC__` is defined before we test it
 * (it isn't predefined by the compiler). Codex caught the previous
 * version where the test ran before any glibc header had been seen, so
 * the entire `.symver` block was silently skipped and `sem_*` still
 * landed at GLIBC_2.34.
 */
#include <features.h>
#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdarg.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <errno.h>

#include "sys/shm.h"
#include "sys/sem.h"
#include "sys/msg.h"

/*
 * Pin pthread/sem symbol versions to GLIBC_2.17 (the aarch64 baseline).
 *
 * The host cross-compiler ships with glibc 2.39, which links pthread/sem
 * symbols at GLIBC_2.34 (when libpthread was merged into libc). The
 * sniper-arm64 runtime ships glibc 2.31, so loading our preload against
 * sniper's libc.so.6 fails at dlopen-time with:
 *   "version `GLIBC_2.34' not found"
 *
 * Plain top-of-file `.symver foo,foo@GLIBC_2.17` doesn't reliably catch
 * the references emitted from inline-defined callers (the compiler picks
 * up the @@GLIBC_2.34 default tag from the host libc's symbol table
 * before our directive applies). The portable trick is to declare
 * renamed extern wrappers that the assembler unambiguously aliases to
 * the older versioned symbol, then `#define` the public names to those
 * wrappers so every source-level call goes through them.
 *
 * Must come AFTER the glibc headers above so `sem_t` and the public
 * `sem_*` declarations are already in scope; the `#define`s below then
 * re-route every call site in the rest of this file.
 */
#if defined(__aarch64__) && defined(__GLIBC__)
#define COMPAT_GLIBC_2_17(real, name) \
    __asm__(".symver " #real "," #name "@GLIBC_2.17")
COMPAT_GLIBC_2_17(__compat_sem_init,    sem_init);
COMPAT_GLIBC_2_17(__compat_sem_post,    sem_post);
COMPAT_GLIBC_2_17(__compat_sem_wait,    sem_wait);
COMPAT_GLIBC_2_17(__compat_sem_trywait, sem_trywait);
COMPAT_GLIBC_2_17(__compat_sem_destroy, sem_destroy);
COMPAT_GLIBC_2_17(__compat_sem_getvalue, sem_getvalue);
extern int __compat_sem_init(sem_t *, int, unsigned int);
extern int __compat_sem_post(sem_t *);
extern int __compat_sem_wait(sem_t *);
extern int __compat_sem_trywait(sem_t *);
extern int __compat_sem_destroy(sem_t *);
extern int __compat_sem_getvalue(sem_t *, int *);
#define sem_init     __compat_sem_init
#define sem_post     __compat_sem_post
#define sem_wait     __compat_sem_wait
#define sem_trywait  __compat_sem_trywait
#define sem_destroy  __compat_sem_destroy
#define sem_getvalue __compat_sem_getvalue
#endif

#define REQUEST_CODE_SHMGET 0
#define REQUEST_CODE_GET_FD 1
#define REQUEST_CODE_DELETE 2

#define MIN_REQUEST_LENGTH 5
#define ROUND_UP(N, S) ((((N) + (S) - 1) / (S)) * (S))

/* based on https://github.com/pelya/android-shmem */

typedef struct {
    int id;
    void* addr;
    int fd;
    size_t size;
    char marked_for_delete;
} shmemory_t;

static shmemory_t* shmemories = NULL;
static int shmemory_count = 0;
static int sysvshm_server_fd = -1;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;

static int find_shmemory_index(int shmid) {
    for (int i = 0; i < shmemory_count; i++) if (shmemories[i].id == shmid) return i;
    return -1;
}

static void sysvshm_connect() {
    if (sysvshm_server_fd >= 0) return;
    char* path = getenv("ANDROID_SYSVSHM_SERVER");
    if (!path || !path[0]) return;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return;

    struct sockaddr_un server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sun_family = AF_LOCAL;

    strncpy(server_addr.sun_path, path, sizeof(server_addr.sun_path) - 1);
    
    int res;
    do {
        res = 0;
        if (connect(fd, (struct sockaddr*)&server_addr, sizeof(struct sockaddr_un)) < 0) res = -errno;
    } 
    while (res == -EINTR);        
    
    if (res < 0) {
        close(fd);
        return;
    }

    sysvshm_server_fd = fd;    
}

static void sysvshm_close() {
    if (sysvshm_server_fd >= 0) {
        close(sysvshm_server_fd);
        sysvshm_server_fd = -1;
    }
}

static int sysvshm_shmget_request(size_t size) {
    if (sysvshm_server_fd < 0) return 0;
    
    char request_data[MIN_REQUEST_LENGTH];
    request_data[0] = REQUEST_CODE_SHMGET;
    memcpy(request_data + 1, &size, 4);
    
    int res = write(sysvshm_server_fd, request_data, sizeof(request_data));
    if (res < 0) return 0;
    
    int shmid;
    res = read(sysvshm_server_fd, &shmid, 4);
    return res == 4 ? shmid : 0;
}

static int sysvshm_get_fd_request(int shmid) {
    if (sysvshm_server_fd < 0) return 0;
    
    char request_data[MIN_REQUEST_LENGTH];
    request_data[0] = REQUEST_CODE_GET_FD;
    memcpy(request_data + 1, &shmid, 4);
    
    int res = write(sysvshm_server_fd, request_data, sizeof(request_data));
    if (res < 0) return -1;
    
    char zero = 0;
    struct iovec iovmsg = {.iov_base = &zero, .iov_len = 1};
    struct {
        struct cmsghdr align;
        int fds[1];
    } ctrlmsg;

    struct msghdr msg = {
        .msg_name = NULL,
        .msg_namelen = 0,
        .msg_iov = &iovmsg,
        .msg_iovlen = 1,
        .msg_flags = 0,
        .msg_control = &ctrlmsg,
        .msg_controllen = sizeof(struct cmsghdr) + sizeof(int)
    };

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = msg.msg_controllen;
    ((int*)CMSG_DATA(cmsg))[0] = -1;

    recvmsg(sysvshm_server_fd, &msg, 0);
    return ((int*)CMSG_DATA(cmsg))[0];
}

static void sysvshm_delete_request(int shmid) {
    if (sysvshm_server_fd < 0) return;
    
    char request_data[MIN_REQUEST_LENGTH];
    request_data[0] = REQUEST_CODE_DELETE;
    memcpy(request_data + 1, &shmid, 4);
    
    write(sysvshm_server_fd, request_data, sizeof(request_data));
}

static void sysvshm_delete(int index) {
    sysvshm_connect();
    sysvshm_delete_request(shmemories[index].id);
    sysvshm_close();

    if (shmemories[index].fd >= 0) close(shmemories[index].fd);
    shmemory_count--;
    memmove(&shmemories[index], &shmemories[index+1], (shmemory_count - index) * sizeof(shmemory_t));
}

int shmget(key_t key, size_t size, int flags) {
    if (key != IPC_PRIVATE) return -1;
    
    pthread_mutex_lock(&mutex);
        
    sysvshm_connect();
    int shmid = sysvshm_shmget_request(size);
    if (shmid == 0) {
        sysvshm_close();
        pthread_mutex_unlock(&mutex);
        return -1;
    }
    
    size = ROUND_UP(size, getpagesize());
    int index = shmemory_count;
    shmemory_count++;
    shmemories = realloc(shmemories, shmemory_count * sizeof(shmemory_t));
    shmemories[index].size = size;
    shmemories[index].fd = sysvshm_get_fd_request(shmid);
    shmemories[index].addr = NULL;
    shmemories[index].id = shmid;
    shmemories[index].marked_for_delete = 0;
    
    sysvshm_close();
    
    if (shmemories[index].fd < 0) {
        shmemory_count--;
        shmemories = realloc(shmemories, shmemory_count * sizeof(shmemory_t));
        pthread_mutex_unlock(&mutex);
        return -1;
    }
    
    pthread_mutex_unlock(&mutex);
    return shmid;
}

void* shmat(int shmid, const void* shmaddr, int shmflg) {
    pthread_mutex_lock(&mutex);

    void* addr = NULL;
    int index = find_shmemory_index(shmid);
    if (index != -1) {
        if (shmemories[index].addr == NULL) {
            shmemories[index].addr = mmap(NULL, shmemories[index].size, PROT_READ | (shmflg == 0 ? PROT_WRITE : 0), MAP_SHARED, shmemories[index].fd, 0);
            if (shmemories[index].addr == MAP_FAILED) shmemories[index].addr = NULL;
        }
        addr = shmemories[index].addr;
    }

    pthread_mutex_unlock(&mutex);
    return addr ? addr : (void *)-1;
}

int shmdt(const void* shmaddr) {
    pthread_mutex_lock(&mutex);
    
    for (int i = 0; i < shmemory_count; i++) {
        if (shmemories[i].addr == shmaddr) {
            munmap(shmemories[i].addr, shmemories[i].size);
            shmemories[i].addr = NULL;
            if (shmemories[i].marked_for_delete) sysvshm_delete(i);
            break;
        }
    }    
    
    pthread_mutex_unlock(&mutex);
    return 0;
}

int shmctl(int shmid, int cmd, struct shmid_ds* buf) {
    if (cmd == IPC_RMID) {
        pthread_mutex_lock(&mutex);
        
        int index = find_shmemory_index(shmid);
        if (index != -1) {
            if (shmemories[index].addr) {
                shmemories[index].marked_for_delete = 1;
            } 
            else sysvshm_delete(index);                
        }        
        
        pthread_mutex_unlock(&mutex);
        return 0;
    } 
    else if (cmd == IPC_STAT) {
        pthread_mutex_lock(&mutex);
        
        int index = find_shmemory_index(shmid);
        if (!buf || index == -1) {
            pthread_mutex_unlock(&mutex);
            return -1;
        }
        
        memset(buf, 0, sizeof(struct shmid_ds));
        buf->shm_segsz = shmemories[index].size;
        buf->shm_nattch = 1;
        buf->shm_perm.__key = IPC_PRIVATE;
        buf->shm_perm.uid = geteuid();
        buf->shm_perm.gid = getegid();
        buf->shm_perm.cuid = geteuid();
        buf->shm_perm.cgid = getegid();
        buf->shm_perm.mode = 0666;
        buf->shm_perm.__seq = 1;
        
        pthread_mutex_unlock(&mutex);
        return 0;
    }
    return -1;
}

/* ---------------- SysV semaphore shim --------------------------------------
 *
 * Steam's tier0 (and other glibc software running under proot on Android)
 * calls semget()/semop()/semctl() to set up inter-thread locks. Android's
 * kernel returns ENOSYS for these because CONFIG_SYSVIPC isn't compiled in
 * for the app context, which makes Steam log "Function not implemented"
 * and "Thread synchronization object is unuseable" before crashing.
 *
 * We emulate SysV semaphores entirely in the calling process using POSIX
 * sem_t (which on Linux is futex-backed and works inside the proot
 * sandbox). This is intra-process only — Steam's actual usage is its own
 * thread pool, which lives in one process, so this covers the cases that
 * matter today. Inter-process SysV semaphores would need a server-side
 * implementation analogous to the shm path; we'll add that if any
 * concrete consumer hits it.
 */

#define SEM_MAX_SETS 256
#define SEM_MAX_PER_SET 64

typedef struct {
    int id;            /* 1-based; 0 means slot empty */
    int nsems;
    sem_t sems[SEM_MAX_PER_SET];
} sem_set_t;

static sem_set_t sem_sets[SEM_MAX_SETS];
static int next_sem_id = 1;
static pthread_mutex_t sem_mutex = PTHREAD_MUTEX_INITIALIZER;

static sem_set_t* find_sem_set(int semid) {
    for (int i = 0; i < SEM_MAX_SETS; i++) {
        if (sem_sets[i].id == semid) return &sem_sets[i];
    }
    return NULL;
}

int semget(key_t key, int nsems, int semflg) {
    (void)key; /* IPC_PRIVATE only — Steam uses this */
    if (nsems <= 0 || nsems > SEM_MAX_PER_SET) {
        errno = EINVAL;
        return -1;
    }
    pthread_mutex_lock(&sem_mutex);
    sem_set_t* slot = NULL;
    for (int i = 0; i < SEM_MAX_SETS; i++) {
        if (sem_sets[i].id == 0) { slot = &sem_sets[i]; break; }
    }
    if (!slot) {
        pthread_mutex_unlock(&sem_mutex);
        errno = ENOSPC;
        return -1;
    }
    slot->id = next_sem_id++;
    slot->nsems = nsems;
    for (int i = 0; i < nsems; i++) {
        if (sem_init(&slot->sems[i], 0, 0) != 0) {
            for (int j = 0; j < i; j++) sem_destroy(&slot->sems[j]);
            slot->id = 0;
            pthread_mutex_unlock(&sem_mutex);
            return -1;
        }
    }
    int id = slot->id;
    pthread_mutex_unlock(&sem_mutex);
    (void)semflg;
    return id;
}

int semop(int semid, struct sembuf* sops, size_t nsops) {
    pthread_mutex_lock(&sem_mutex);
    sem_set_t* set = find_sem_set(semid);
    if (!set) {
        pthread_mutex_unlock(&sem_mutex);
        errno = EINVAL;
        return -1;
    }
    pthread_mutex_unlock(&sem_mutex);

    /* Walk operations in order. Negative = wait (decrement),
     * positive = signal (increment), zero = wait-for-zero (rare). */
    for (size_t i = 0; i < nsops; i++) {
        struct sembuf* op = &sops[i];
        if (op->sem_num >= set->nsems) {
            errno = EINVAL;
            return -1;
        }
        sem_t* s = &set->sems[op->sem_num];
        if (op->sem_op > 0) {
            for (int n = 0; n < op->sem_op; n++) sem_post(s);
        } else if (op->sem_op < 0) {
            int want = -op->sem_op;
            int flags = op->sem_flg;
            for (int n = 0; n < want; n++) {
                if (flags & IPC_NOWAIT) {
                    if (sem_trywait(s) != 0) { errno = EAGAIN; return -1; }
                } else {
                    while (sem_wait(s) != 0) {
                        if (errno != EINTR) return -1;
                    }
                }
            }
        }
        /* sem_op == 0 (wait-for-zero) is rare; treat as no-op since we
         * can't query sem_t value atomically without a separate lock. */
    }
    return 0;
}

int semctl(int semid, int semnum, int cmd, ...) {
    int val = 0;
    if (cmd == SETVAL) {
        va_list ap;
        va_start(ap, cmd);
        val = va_arg(ap, int);
        va_end(ap);
    }
    pthread_mutex_lock(&sem_mutex);
    sem_set_t* set = find_sem_set(semid);
    if (!set) {
        pthread_mutex_unlock(&sem_mutex);
        errno = EINVAL;
        return -1;
    }
    int rc = 0;
    switch (cmd) {
        case IPC_RMID:
            for (int i = 0; i < set->nsems; i++) sem_destroy(&set->sems[i]);
            set->id = 0;
            set->nsems = 0;
            break;
        case GETVAL: {
            if (semnum < 0 || semnum >= set->nsems) { rc = -1; errno = EINVAL; break; }
            int v = 0;
            sem_getvalue(&set->sems[semnum], &v);
            rc = v;
            break;
        }
        case SETVAL: {
            if (semnum < 0 || semnum >= set->nsems) { rc = -1; errno = EINVAL; break; }
            sem_destroy(&set->sems[semnum]);
            sem_init(&set->sems[semnum], 0, (unsigned int)val);
            break;
        }
        default:
            /* IPC_STAT / IPC_SET / GETALL / SETALL / etc. Best-effort
             * success — Steam isn't observed to depend on these. */
            break;
    }
    pthread_mutex_unlock(&sem_mutex);
    return rc;
}

/* ---------------- SysV message queue stubs ---------------------------------
 *
 * Stubs only — return success without actually queuing. Steam's tier0 may
 * call msgget()/msgsnd()/msgrcv() opportunistically; on Android these would
 * also return ENOSYS and trigger "Function not implemented". A no-op
 * implementation lets startup proceed; if any consumer actually depends on
 * message-queue semantics we'll surface that as a separate failure later.
 */

int msgget(key_t key, int msgflg) { (void)key; (void)msgflg; return 1; }
int msgsnd(int msqid, const void* msgp, size_t msgsz, int msgflg) {
    (void)msqid; (void)msgp; (void)msgsz; (void)msgflg; return 0;
}
ssize_t msgrcv(int msqid, void* msgp, size_t msgsz, long msgtyp, int msgflg) {
    (void)msqid; (void)msgp; (void)msgsz; (void)msgtyp; (void)msgflg;
    errno = ENOMSG; return -1;
}
int msgctl(int msqid, int cmd, struct msqid_ds* buf) {
    (void)msqid; (void)cmd; (void)buf; return 0;
}
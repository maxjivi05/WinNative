#define _GNU_SOURCE

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef MAP_ANONYMOUS
#define MAP_ANONYMOUS MAP_ANON
#endif

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

#define LOG_TAG "WinNativeExecMemFix"
#define MIN_SIGNAL_STACK_SIZE (64 * 1024)
#define MIN_PTHREAD_STACK_SIZE (8 * 1024 * 1024)

static __thread void *replacement_altstack;
static __thread size_t replacement_altstack_size;

struct thread_start_args {
  void *(*start_routine)(void *);
  void *arg;
};

static uintptr_t page_align_down(uintptr_t value, size_t page_size) {
  return value & ~((uintptr_t)page_size - 1);
}

static uintptr_t page_align_up(uintptr_t value, size_t page_size) {
  return (value + page_size - 1) & ~((uintptr_t)page_size - 1);
}

static void *sys_mmap(
    void *addr, size_t length, int prot, int flags, int fd, off_t offset) {
  return (void *)syscall(SYS_mmap, addr, length, prot, flags, fd, offset);
}

static int sys_mprotect(void *addr, size_t length, int prot) {
  return (int)syscall(SYS_mprotect, addr, length, prot);
}

static int sys_munmap(void *addr, size_t length) {
  return (int)syscall(SYS_munmap, addr, length);
}

static int create_memfd(const char *name) {
#ifdef SYS_memfd_create
  return (int)syscall(SYS_memfd_create, name, MFD_CLOEXEC);
#else
  (void)name;
  errno = ENOSYS;
  return -1;
#endif
}

static int signal_should_use_altstack(int signum) {
  return signum == SIGSEGV || signum == SIGBUS || signum == SIGILL ||
         signum == SIGFPE || signum == SIGTRAP;
}

static int ensure_signal_altstack(void) {
  stack_t current;
  if (syscall(SYS_sigaltstack, NULL, &current) != 0) {
    return -1;
  }

  if (!(current.ss_flags & SS_DISABLE) && current.ss_size >= MIN_SIGNAL_STACK_SIZE) {
    return 0;
  }

  void *stack =
      sys_mmap(NULL, MIN_SIGNAL_STACK_SIZE, PROT_READ | PROT_WRITE,
               MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (stack == MAP_FAILED) {
    return -1;
  }

  stack_t enlarged;
  enlarged.ss_sp = stack;
  enlarged.ss_size = MIN_SIGNAL_STACK_SIZE;
  enlarged.ss_flags = 0;

  if (syscall(SYS_sigaltstack, &enlarged, NULL) != 0) {
    int saved_errno = errno;
    sys_munmap(stack, MIN_SIGNAL_STACK_SIZE);
    errno = saved_errno;
    return -1;
  }

  if (replacement_altstack) {
    sys_munmap(replacement_altstack, replacement_altstack_size);
  }
  replacement_altstack = stack;
  replacement_altstack_size = MIN_SIGNAL_STACK_SIZE;

  __android_log_print(
      ANDROID_LOG_WARN, LOG_TAG,
      "installed %u byte signal altstack for thread", MIN_SIGNAL_STACK_SIZE);
  return 0;
}

static void ensure_process_stack_limit(void) {
  struct rlimit limit;
  if (getrlimit(RLIMIT_STACK, &limit) != 0) {
    return;
  }

  if (limit.rlim_cur >= MIN_PTHREAD_STACK_SIZE) {
    return;
  }

  rlim_t wanted = MIN_PTHREAD_STACK_SIZE;
  if (limit.rlim_max != RLIM_INFINITY && wanted > limit.rlim_max) {
    wanted = limit.rlim_max;
  }

  if (wanted > limit.rlim_cur) {
    limit.rlim_cur = wanted;
    if (setrlimit(RLIMIT_STACK, &limit) == 0) {
      __android_log_print(
          ANDROID_LOG_WARN, LOG_TAG,
          "raised process RLIMIT_STACK to %llu bytes",
          (unsigned long long)wanted);
    }
  }
}

static int copy_to_fixed_mapping(
    void *target, size_t length, int final_prot, const void *snapshot) {
  int fd = create_memfd("winnative-exec");
  if (fd >= 0) {
    if (ftruncate(fd, (off_t)length) == 0) {
      void *rw =
          sys_mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
      if (rw != MAP_FAILED) {
        memcpy(rw, snapshot, length);
        void *rx = sys_mmap(target, length, final_prot, MAP_SHARED | MAP_FIXED, fd, 0);
        int saved_errno = errno;
        sys_munmap(rw, length);
        close(fd);
        if (rx == target) {
          return 0;
        }
        errno = saved_errno;
      } else {
        close(fd);
      }
    } else {
      close(fd);
    }
  }

  void *mapped =
      sys_mmap(target, length, PROT_READ | PROT_WRITE,
               MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
  if (mapped != target) {
    return -1;
  }

  memcpy(mapped, snapshot, length);
  if (sys_mprotect(mapped, length, final_prot) == 0) {
    return 0;
  }

  __android_log_print(
      ANDROID_LOG_WARN, LOG_TAG,
      "anonymous fixed exec restore failed at %p size=%zu errno=%d",
      target, length, errno);
  return -1;
}

static int restore_exec_at_same_va(void *addr, size_t len, int prot) {
  long page_size_long = sysconf(_SC_PAGESIZE);
  size_t page_size = page_size_long > 0 ? (size_t)page_size_long : 4096;
  uintptr_t start = page_align_down((uintptr_t)addr, page_size);
  uintptr_t end = page_align_up((uintptr_t)addr + len, page_size);
  size_t length = end - start;
  void *target = (void *)start;

  if (!length) {
    errno = EINVAL;
    return -1;
  }

  void *snapshot =
      sys_mmap(NULL, length, PROT_READ | PROT_WRITE,
               MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (snapshot == MAP_FAILED) {
    return -1;
  }

  memcpy(snapshot, target, length);
  int ret = copy_to_fixed_mapping(target, length, prot, snapshot);
  int saved_errno = errno;
  sys_munmap(snapshot, length);

  if (ret == 0) {
    __android_log_print(
        ANDROID_LOG_WARN, LOG_TAG,
        "recovered executable mapping at original VA %p size=%zu prot=0x%x",
        target, length, prot);
    errno = 0;
    return 0;
  }

  errno = saved_errno;
  return -1;
}

__attribute__((visibility("default"))) int mprotect(void *addr, size_t len, int prot) {
  int ret = sys_mprotect(addr, len, prot);
  int saved_errno = errno;

  if (ret == 0) {
    return 0;
  }

  if (!(prot & PROT_EXEC) || (saved_errno != EACCES && saved_errno != EPERM)) {
    errno = saved_errno;
    return ret;
  }

  if (restore_exec_at_same_va(addr, len, prot) == 0) {
    return 0;
  }

  errno = saved_errno;
  return -1;
}

static void *recover_exec_mmap(
    void *addr, size_t length, int prot, int flags, int fd, off_t offset) {
  int non_exec_prot = prot & ~PROT_EXEC;
  if (!(non_exec_prot & PROT_READ)) non_exec_prot |= PROT_READ;

  void *mapped = sys_mmap(addr, length, non_exec_prot, flags, fd, offset);
  if (mapped == MAP_FAILED) {
    return MAP_FAILED;
  }

  if (restore_exec_at_same_va(mapped, length, prot) == 0) {
    return mapped;
  }

  int saved_errno = errno;
  sys_munmap(mapped, length);
  errno = saved_errno;
  return MAP_FAILED;
}

__attribute__((visibility("default"))) void *mmap(
    void *addr, size_t length, int prot, int flags, int fd, off_t offset) {
  void *ret = sys_mmap(addr, length, prot, flags, fd, offset);
  int saved_errno = errno;

  if (ret != MAP_FAILED) {
    return ret;
  }

  if (!(prot & PROT_EXEC) || fd < 0 ||
      (saved_errno != EACCES && saved_errno != EPERM)) {
    errno = saved_errno;
    return ret;
  }

  ret = recover_exec_mmap(addr, length, prot, flags, fd, offset);
  if (ret != MAP_FAILED) {
    return ret;
  }

  errno = saved_errno;
  return MAP_FAILED;
}

__attribute__((visibility("default"))) void *mmap64(
    void *addr, size_t length, int prot, int flags, int fd, off64_t offset) {
  return mmap(addr, length, prot, flags, fd, (off_t)offset);
}

__attribute__((visibility("default"))) int sigaltstack(const stack_t *ss, stack_t *old_ss) {
  if (ss && !(ss->ss_flags & SS_DISABLE) && ss->ss_size < MIN_SIGNAL_STACK_SIZE) {
    void *stack =
        sys_mmap(NULL, MIN_SIGNAL_STACK_SIZE, PROT_READ | PROT_WRITE,
                 MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (stack != MAP_FAILED) {
      if (replacement_altstack) {
        sys_munmap(replacement_altstack, replacement_altstack_size);
      }
      replacement_altstack = stack;
      replacement_altstack_size = MIN_SIGNAL_STACK_SIZE;

      stack_t enlarged = *ss;
      enlarged.ss_sp = stack;
      enlarged.ss_size = MIN_SIGNAL_STACK_SIZE;

      __android_log_print(
          ANDROID_LOG_WARN, LOG_TAG,
          "enlarged signal altstack from %zu to %u bytes",
          ss->ss_size, MIN_SIGNAL_STACK_SIZE);
      return (int)syscall(SYS_sigaltstack, &enlarged, old_ss);
    }
  }

  return (int)syscall(SYS_sigaltstack, ss, old_ss);
}

__attribute__((visibility("default"))) int sigaction(
    int signum, const struct sigaction *act, struct sigaction *oldact) {
  static int (*real_sigaction)(int, const struct sigaction *, struct sigaction *);
  if (!real_sigaction) {
    real_sigaction = dlsym(RTLD_NEXT, "sigaction");
  }
  if (!real_sigaction) {
    errno = ENOSYS;
    return -1;
  }

  if (act && signal_should_use_altstack(signum)) {
    struct sigaction patched = *act;
    patched.sa_flags |= SA_ONSTACK;
    ensure_signal_altstack();
    __android_log_print(
        ANDROID_LOG_WARN, LOG_TAG,
        "forcing SA_ONSTACK for signal %d handler", signum);
    return real_sigaction(signum, &patched, oldact);
  }

  return real_sigaction(signum, act, oldact);
}

__attribute__((visibility("default"))) int pthread_attr_setstacksize(
    pthread_attr_t *attr, size_t stacksize) {
  static int (*real_pthread_attr_setstacksize)(pthread_attr_t *, size_t);
  if (!real_pthread_attr_setstacksize) {
    real_pthread_attr_setstacksize =
        dlsym(RTLD_NEXT, "pthread_attr_setstacksize");
  }
  if (!real_pthread_attr_setstacksize) {
    errno = ENOSYS;
    return -1;
  }
  if (stacksize < MIN_PTHREAD_STACK_SIZE) {
    stacksize = MIN_PTHREAD_STACK_SIZE;
  }
  return real_pthread_attr_setstacksize(attr, stacksize);
}

__attribute__((visibility("default"))) int pthread_attr_setstack(
    pthread_attr_t *attr, void *stackaddr, size_t stacksize) {
  static int (*real_pthread_attr_setstack)(pthread_attr_t *, void *, size_t);
  if (!real_pthread_attr_setstack) {
    real_pthread_attr_setstack = dlsym(RTLD_NEXT, "pthread_attr_setstack");
  }
  if (!real_pthread_attr_setstack) {
    errno = ENOSYS;
    return -1;
  }
  if (stacksize && stacksize < MIN_PTHREAD_STACK_SIZE) {
    __android_log_print(
        ANDROID_LOG_WARN, LOG_TAG,
        "accepting caller-provided pthread stack below ARM64 floor: %zu bytes",
        stacksize);
  }
  return real_pthread_attr_setstack(attr, stackaddr, stacksize);
}

static void *thread_start_wrapper(void *arg) {
  struct thread_start_args start = *(struct thread_start_args *)arg;
  free(arg);
  ensure_signal_altstack();
  return start.start_routine(start.arg);
}

__attribute__((visibility("default"))) int pthread_create(
    pthread_t *thread, const pthread_attr_t *attr,
    void *(*start_routine)(void *), void *arg) {
  static int (*real_pthread_create)(
      pthread_t *, const pthread_attr_t *, void *(*)(void *), void *);
  if (!real_pthread_create) {
    real_pthread_create = dlsym(RTLD_NEXT, "pthread_create");
  }
  if (!real_pthread_create) {
    errno = ENOSYS;
    return -1;
  }

  pthread_attr_t enlarged_attr;
  const pthread_attr_t *effective_attr = attr;
  if (attr) {
    size_t stack_size = 0;
    if (pthread_attr_getstacksize(attr, &stack_size) == 0 &&
        stack_size > 0 && stack_size < MIN_PTHREAD_STACK_SIZE) {
      enlarged_attr = *attr;
      pthread_attr_setstacksize(&enlarged_attr, MIN_PTHREAD_STACK_SIZE);
      effective_attr = &enlarged_attr;
    }
  }

  struct thread_start_args *wrapped =
      malloc(sizeof(struct thread_start_args));
  if (!wrapped) {
    return real_pthread_create(thread, effective_attr, start_routine, arg);
  }
  wrapped->start_routine = start_routine;
  wrapped->arg = arg;

  int ret = real_pthread_create(thread, effective_attr, thread_start_wrapper, wrapped);
  if (ret != 0) {
    free(wrapped);
  }
  return ret;
}

__attribute__((constructor)) static void winnative_execmemfix_init(void) {
  ensure_process_stack_limit();
  ensure_signal_altstack();
}

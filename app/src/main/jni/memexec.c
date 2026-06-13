/*
 * memexec.c - Execute a binary from a memfd (anonymous memory).
 *
 * Bypasses Android's noexec mount flag on app data directories by
 * copying the binary into anonymous memory (memfd) and executing
 * from there. The kernel treats memfd as RAM-backed, not subject
 * to filesystem noexec.
 *
 * Compile for arm64 Android:
 *   clang -shared -fPIC -o libmemexec.so memexec.c -I$PREFIX/include -llog
 *
 * Requires: Linux 3.17+ (memfd_create), Android API 28+
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <errno.h>
#include <android/log.h>

#define TAG "Memexec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifndef __NR_memfd_create
#if defined(__aarch64__)
#define __NR_memfd_create 279
#else
#error "Unsupported architecture"
#endif
#endif

/*
 * Class:     com_kaonixx_zeroclaw_NativeExecutor
 * Method:    execBinary
 * Signature: (Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)I
 *
 * 1. Reads the binary from disk
 * 2. Creates a memfd (anonymous RAM file)
 * 3. Writes the binary into the memfd
 * 4. fchmod(memfd, 0755)
 * 5. Forks
 * 6. Child: execve(/proc/self/fd/<memfd>, ...) -- bypasses noexec
 * 7. Parent: closes memfd, returns child PID
 *
 * Returns: child PID (>0) on success, negative on error
 */
JNIEXPORT jint JNICALL
Java_com_kaonixx_zeroclaw_NativeExecutor_execBinary(
    JNIEnv* env, jclass clazz,
    jstring binPath, jobjectArray args, jobjectArray envArr) {

    const char* path = (*env)->GetStringUTFChars(env, binPath, NULL);
    if (!path) return -1;
    LOGI("Reading: %s", path);

    int bfd = open(path, O_RDONLY);
    if (bfd < 0) { LOGE("open: %s", strerror(errno)); (*env)->ReleaseStringUTFChars(env, binPath, path); return -2; }

    struct stat st;
    if (fstat(bfd, &st) < 0) { close(bfd); (*env)->ReleaseStringUTFChars(env, binPath, path); return -3; }

    size_t sz = st.st_size;
    unsigned char* dat = malloc(sz);
    if (!dat) { close(bfd); (*env)->ReleaseStringUTFChars(env, binPath, path); return -4; }

    ssize_t rd = 0;
    while (rd < (ssize_t)sz) {
        ssize_t n = read(bfd, dat + rd, sz - rd);
        if (n <= 0) { free(dat); close(bfd); (*env)->ReleaseStringUTFChars(env, binPath, path); return -5; }
        rd += n;
    }
    close(bfd); (*env)->ReleaseStringUTFChars(env, binPath, path);

    int mfd = syscall(__NR_memfd_create, "zeroclaw", 0);
    if (mfd < 0) { free(dat); return -6; }

    rd = 0;
    while (rd < (ssize_t)sz) {
        ssize_t n = write(mfd, dat + rd, sz - rd);
        if (n <= 0) { close(mfd); free(dat); return -7; }
        rd += n;
    }
    free(dat); fchmod(mfd, 0755); lseek(mfd, 0, SEEK_SET);
    LOGI("memfd: %zu bytes. Forking...", sz);

    // Build argv
    jsize ac = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = malloc((ac + 2) * sizeof(char*));
    if (!argv) { close(mfd); return -9; }
    argv[0] = strdup("zeroclaw");
    for (int i = 0; i < ac; i++) {
        jstring js = (*env)->GetObjectArrayElement(env, args, i);
        const char* s = js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL;
        argv[i + 1] = s ? strdup(s) : strdup("");
        if (js) { (*env)->ReleaseStringUTFChars(env, js, s); (*env)->DeleteLocalRef(env, js); }
    }
    argv[ac + 1] = NULL;

    // Build envp
    jsize ec = envArr ? (*env)->GetArrayLength(env, envArr) : 0;
    char** envp = malloc((ec + 1) * sizeof(char*));
    if (!envp) { for (int i = 0; i <= ac; i++) free(argv[i]); free(argv); close(mfd); return -10; }
    for (int i = 0; i < ec; i++) {
        jstring js = (*env)->GetObjectArrayElement(env, envArr, i);
        const char* s = js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL;
        envp[i] = s ? strdup(s) : strdup("");
        if (js) { (*env)->ReleaseStringUTFChars(env, js, s); (*env)->DeleteLocalRef(env, js); }
    }
    envp[ec] = NULL;

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        for (int i = 0; i <= ac; i++) free(argv[i]); free(argv);
        for (int i = 0; i < ec; i++) free(envp[i]); free(envp);
        close(mfd); return -11;
    }

    if (pid == 0) {
        char fp[64];
        snprintf(fp, sizeof(fp), "/proc/self/fd/%d", mfd);
        LOGI("Child PID %d executing: %s", getpid(), fp);
        execve(fp, argv, envp);
        LOGE("execve: %s", strerror(errno));
        _exit(127);
    }

    LOGI("Child PID: %d", pid);
    close(mfd);
    for (int i = 0; i <= ac; i++) free(argv[i]); free(argv);
    for (int i = 0; i < ec; i++) free(envp[i]); free(envp);
    return (jint)pid;
}

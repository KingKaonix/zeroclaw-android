/*
 * elfloader.c - Load and execute an ELF binary from a regular file
 * by mapping its segments into anonymous executable memory.
 *
 * This bypasses Android's noexec mount flag because we use
 * mmap(MAP_ANONYMOUS|MAP_PRIVATE, PROT_READ|PROT_WRITE|PROT_EXEC)
 * which allocates from the kernel's virtual memory, not from
 * a filesystem mount.
 *
 * Compile: clang -shared -fPIC -o libelfloader.so elfloader.c -I$PREFIX/include -llog
 */

#define _GNU_SOURCE
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <errno.h>
#include <android/log.h>

#include <elf.h>

#define TAG "ElfLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Userspace ELF loader for PIE executables.
 *
 * Strategy:
 *   1. Open the ELF binary and read headers
 *   2. Parse program headers to find PT_LOAD segments
 *   3. For each PT_LOAD, mmap anonymous executable memory at the right address
 *   4. Copy segment data from file into the mapping
 *   5. Zero-fill any difference between memsz and filesz (BSS)
 *   6. Fork
 *   7. In child: set up stack with argv/envp/auxv, jump to entry point
 *   8. In parent: return child PID
 */

/* aarch64 register layout for _start */
struct pt_regs {
    unsigned long regs[31];
    unsigned long sp;
    unsigned long pc;
    unsigned long pstate;
};

static int load_elf(const char *path, unsigned char **out_base, size_t *out_size,
                    Elf64_Addr *out_entry) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) { LOGE("open: %s", strerror(errno)); return -1; }

    /* Read ELF header */
    Elf64_Ehdr ehdr;
    if (read(fd, &ehdr, sizeof(ehdr)) != sizeof(ehdr)) {
        LOGE("read ehdr: %s", strerror(errno)); close(fd); return -2;
    }

    /* Verify ELF magic */
    if (memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) {
        LOGE("Not an ELF file"); close(fd); return -3;
    }

    /* Must be PIE executable (ET_DYN) for modern Android */
    if (ehdr.e_type != ET_DYN) {
        LOGE("Not a PIE executable (e_type=%d)", ehdr.e_type); close(fd); return -4;
    }

    /* Must be 64-bit */
    if (ehdr.e_ident[EI_CLASS] != ELFCLASS64) {
        LOGE("Not 64-bit ELF"); close(fd); return -5;
    }

    /* Must be aarch64 */
    if (ehdr.e_machine != EM_AARCH64) {
        LOGE("Not aarch64 (machine=%d)", ehdr.e_machine); close(fd); return -6;
    }

    *out_entry = ehdr.e_entry;
    LOGI("Entry point: 0x%lx", ehdr.e_entry);

    /* Read program headers */
    size_t phdr_size = ehdr.e_phnum * ehdr.e_phentsize;
    Elf64_Phdr *phdrs = malloc(phdr_size);
    if (!phdrs) { close(fd); return -7; }

    if (lseek(fd, ehdr.e_phoff, SEEK_SET) < 0) {
        LOGE("lseek phdr: %s", strerror(errno)); free(phdrs); close(fd); return -8;
    }
    if (read(fd, phdrs, phdr_size) != (ssize_t)phdr_size) {
        LOGE("read phdrs: %s", strerror(errno)); free(phdrs); close(fd); return -9;
    }

    /* First pass: find base address range */
    Elf64_Addr min_va = ~0ULL, max_va = 0;
    Elf64_Addr min_offset = ~0ULL;
    int found_load = 0;

    for (int i = 0; i < ehdr.e_phnum; i++) {
        if (phdrs[i].p_type == PT_LOAD) {
            Elf64_Addr align = phdrs[i].p_align > 0 ? phdrs[i].p_align : 1;
            Elf64_Addr start = phdrs[i].p_vaddr & ~(align - 1);
            Elf64_Addr end = (phdrs[i].p_vaddr + phdrs[i].p_memsz + align - 1) & ~(align - 1);
            if (start < min_va) { min_va = start; min_offset = phdrs[i].p_offset & ~(align - 1); }
            if (end > max_va) max_va = end;
            found_load = 1;
        }
    }

    if (!found_load) { free(phdrs); close(fd); return -10; }

    /* Map the entire address range (anonymous) */
    size_t total_size = max_va - min_va;
    LOGI("ELF range: 0x%lx - 0x%lx (size=%zu)", min_va, max_va, total_size);

    void *base = mmap((void*)min_va, total_size,
                      PROT_READ | PROT_WRITE | PROT_EXEC,
                      MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED,
                      -1, 0);

    if (base == MAP_FAILED) {
        /* Try without MAP_FIXED (let kernel choose) */
        LOGE("mmap fixed failed (0x%lx): %s - trying without fixed", min_va, strerror(errno));
        base = mmap((void*)min_va, total_size,
                    PROT_READ | PROT_WRITE | PROT_EXEC,
                    MAP_PRIVATE | MAP_ANONYMOUS,
                    -1, 0);
        if (base == MAP_FAILED) {
            LOGE("mmap failed: %s", strerror(errno));
            free(phdrs); close(fd); return -11;
        }
        /* Adjust entry for the actual mapping address */
        *out_entry = ehdr.e_entry + (unsigned long)base - min_va;
    }

    LOGI("Mapped at: %p (entry: 0x%lx)", base, *out_entry);

    /* Second pass: copy segment data */
    for (int i = 0; i < ehdr.e_phnum; i++) {
        if (phdrs[i].p_type == PT_LOAD) {
            Elf64_Addr offset = phdrs[i].p_offset;
            Elf64_Addr vaddr = phdrs[i].p_vaddr;
            Elf64_Xword filesz = phdrs[i].p_filesz;
            Elf64_Xword memsz = phdrs[i].p_memsz;

            void *dest = (void*)((unsigned long)base + (vaddr - min_va));
            LOGI("  LOAD: offset=0x%lx vaddr=0x%lx filesz=%lu memsz=%lu -> %p",
                 offset, vaddr, filesz, memsz, dest);

            if (filesz > 0) {
                if (lseek(fd, offset, SEEK_SET) < 0) {
                    LOGE("lseek segment: %s", strerror(errno));
                    continue;
                }
                if (read(fd, dest, filesz) != (ssize_t)filesz) {
                    LOGE("read segment: %s", strerror(errno));
                    continue;
                }
            }

            /* Zero-fill BSS (memsz - filesz) */
            if (memsz > filesz) {
                memset(dest + filesz, 0, memsz - filesz);
            }
        }
    }

    *out_base = base;
    *out_size = total_size;

    free(phdrs);
    close(fd);
    return 0;
}

/*
 * Class:     com_kaonixx_zeroclaw_ElfLoader
 * Method:    loadAndExecBinary
 * Signature: (Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)I
 *
 * Loads a PIE ELF binary into executable memory and runs it in a
 * child process via fork + direct entry point jump.
 */
JNIEXPORT jint JNICALL
Java_com_kaonixx_zeroclaw_ElfLoader_loadAndExecBinary(
    JNIEnv* env, jclass clazz,
    jstring binPath, jobjectArray args, jobjectArray envArr) {

    const char* path = (*env)->GetStringUTFChars(env, binPath, NULL);
    if (!path) return -1;
    LOGI("Loading: %s", path);

    unsigned char *base = NULL;
    size_t size = 0;
    Elf64_Addr entry = 0;

    int ret = load_elf(path, &base, &size, &entry);
    (*env)->ReleaseStringUTFChars(env, binPath, path);
    if (ret < 0) {
        LOGE("load_elf failed: %d", ret);
        return -100 - ret;
    }

    LOGI("ELF loaded at %p, size %zu, entry 0x%lx", base, size, entry);

    /* Build argv (JNI -> C) */
    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = malloc((argc + 2) * sizeof(char*));
    if (!argv) { munmap(base, size); return -20; }
    argv[0] = strdup("zeroclaw");
    for (int i = 0; i < argc; i++) {
        jstring js = (*env)->GetObjectArrayElement(env, args, i);
        const char *s = js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL;
        argv[i + 1] = s ? strdup(s) : strdup("");
        if (js) { (*env)->ReleaseStringUTFChars(env, js, s); (*env)->DeleteLocalRef(env, js); }
    }
    argv[argc + 1] = NULL;

    /* Build envp */
    jsize envc = envArr ? (*env)->GetArrayLength(env, envArr) : 0;
    char **envp = malloc((envc + 1) * sizeof(char*));
    if (!envp) {
        for (int i = 0; i <= argc; i++) free(argv[i]); free(argv);
        munmap(base, size); return -21;
    }
    for (int i = 0; i < envc; i++) {
        jstring js = (*env)->GetObjectArrayElement(env, envArr, i);
        const char *s = js ? (*env)->GetStringUTFChars(env, js, NULL) : NULL;
        envp[i] = s ? strdup(s) : strdup("");
        if (js) { (*env)->ReleaseStringUTFChars(env, js, s); (*env)->DeleteLocalRef(env, js); }
    }
    envp[envc] = NULL;

    /* Build auxiliary vector (minimal: just AT_NULL) */
    unsigned long auxv[] = { AT_NULL, 0 };

    /* Fork */
    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        for (int i = 0; i <= argc; i++) free(argv[i]); free(argv);
        for (int i = 0; i < envc; i++) free(envp[i]); free(envp);
        munmap(base, size); return -22;
    }

    if (pid == 0) {
        /* CHILD process - jump to the ELF entry point */

        /* Count argv and envp */
        int ac = 0;
        while (argv[ac]) ac++;
        int ec = 0;
        while (envp[ec]) ec++;

        /* Set up stack:
         *   [sp+0] = argc (8 bytes)
         *   [sp+8] = argv[0] ... argv[argc] (NULL terminated)
         *   [sp+8*(ac+2)] = envp[0] ... envp[ec] (NULL terminated)
         *   [sp+8*(ac+2+ec+1)] = auxv pairs (AT_NULL terminated)
         *
         * We use a standard Linux stack layout.
         */
        size_t stack_size = 4096;
        void *stack = mmap(NULL, stack_size,
                          PROT_READ | PROT_WRITE,
                          MAP_PRIVATE | MAP_ANONYMOUS,
                          -1, 0);
        if (stack == MAP_FAILED) {
            LOGE("child mmap stack: %s", strerror(errno));
            _exit(127);
        }

        unsigned long *sp = (unsigned long*)((unsigned char*)stack + stack_size);

        /* Push auxv */
        *(--sp) = 0; *(--sp) = 0;  /* AT_NULL */

        /* Push envp */
        for (int i = ec - 1; i >= 0; i--) {
            size_t slen = strlen(envp[i]) + 1;
            sp -= (slen + 7) / 8;
            memcpy(sp, envp[i], slen);
        }
        unsigned long *envp_stack = sp;
        sp--;  /* make room for NULL terminator */

        /* Push argv */
        for (int i = ac - 1; i >= 0; i--) {
            size_t slen = strlen(argv[i]) + 1;
            sp -= (slen + 7) / 8;
            memcpy(sp, argv[i], slen);
        }
        unsigned long *argv_stack = sp;
        sp--;  /* make room for NULL terminator */
        sp--; *sp = (unsigned long)argv_stack;  /* argv pointer */
        sp--; *sp = ac;  /* argc */

        /* Set sp register and jump to entry point */
        LOGI("Child jumping to entry 0x%lx", entry);

#if defined(__aarch64__)
        __asm__ volatile (
            "mov sp, %0\n\t"
            "mov x0, %1\n\t"    /* argc in x0 */
            "mov x1, %2\n\t"    /* argv in x1 */
            "mov x2, %3\n\t"    /* envp in x2 */
            "mov x3, %4\n\t"    /* auxv in x3 */
            "br %5\n\t"         /* branch to entry */
            :
            : "r"(sp), "r"((unsigned long)ac),
              "r"((unsigned long)argv_stack),
              "r"((unsigned long)envp_stack),
              "r"((unsigned long)auxv),
              "r"(entry)
            : "sp", "x0", "x1", "x2", "x3"
        );
#else
        LOGE("Unsupported architecture");
        _exit(127);
#endif
        /* Should never reach here */
        _exit(127);
    }

    /* PARENT process */
    LOGI("Child PID: %d", (int)pid);

    /* Clean up */
    for (int i = 0; i <= argc; i++) free(argv[i]); free(argv);
    for (int i = 0; i < envc; i++) free(envp[i]); free(envp);

    /* Don't unmap the ELF - the child needs it */
    /* Actually, the child is a COPY of this process (fork), so unmap is fine */
    munmap(base, size);

    return (jint)pid;
}

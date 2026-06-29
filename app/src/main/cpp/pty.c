#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <pty.h>
#include <signal.h>

/**
 * Minimal PTY wrapper for Android.
 * Provides forkpty() + read/write/close via JNI.
 */

/* Store child PID so we can wait on close */
static pid_t g_child_pid = -1;

/**
 * Create a PTY and fork a child process.
 * Returns the master fd on success, -1 on failure.
 *
 * @param cmd   The command to execute in the child (e.g. "/path/to/proot")
 * @param args  Argument array as a single string, separated by '\n'
 * @param env   Environment variables, separated by '\n' (KEY=VALUE), or NULL
 * @param cwd   Working directory for the child, or NULL
 */
JNIEXPORT jint JNICALL
Java_com_corecoder_app_core_exec_Pty_nativeCreatePty(
    JNIEnv *env, jclass cls,
    jstring jCmd, jstring jArgs, jstring jEnv, jstring jCwd)
{
    const char *cmd = (*env)->GetStringUTFChars(env, jCmd, NULL);
    const char *args_str = (*env)->GetStringUTFChars(env, jArgs, NULL);
    const char *env_str = jEnv ? (*env)->GetStringUTFChars(env, jEnv, NULL) : NULL;
    const char *cwd = jCwd ? (*env)->GetStringUTFChars(env, jCwd, NULL) : NULL;

    /* Build argv from newline-separated args string */
    /* Count args */
    int argc = 1; /* cmd itself */
    for (const char *p = args_str; *p; p++) {
        if (*p == '\n') argc++;
    }
    argc++; /* NULL terminator */

    char **argv = (char **)calloc(argc, sizeof(char *));
    argv[0] = strdup(cmd);
    int i = 1;
    char *args_copy = strdup(args_str);
    char *tok = strtok(args_copy, "\n");
    while (tok && i < argc - 1) {
        argv[i++] = strdup(tok);
        tok = strtok(NULL, "\n");
    }
    argv[i] = NULL;
    free(args_copy);

    /* Build envp if provided */
    char **envp = NULL;
    if (env_str && strlen(env_str) > 0) {
        int envc = 1;
        for (const char *p = env_str; *p; p++) {
            if (*p == '\n') envc++;
        }
        envp = (char **)calloc(envc + 1, sizeof(char *));
        char *env_copy = strdup(env_str);
        char *etok = strtok(env_copy, "\n");
        int ei = 0;
        while (etok && ei < envc) {
            envp[ei++] = strdup(etok);
            etok = strtok(NULL, "\n");
        }
        envp[ei] = NULL;
        free(env_copy);
    }

    struct winsize ws = { .ws_row = 24, .ws_col = 80 };
    int master_fd = -1;

    pid_t pid = forkpty(&master_fd, NULL, NULL, &ws);

    if (pid < 0) {
        /* Fork failed */
        (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
        (*env)->ReleaseStringUTFChars(env, jArgs, args_str);
        if (env_str) (*env)->ReleaseStringUTFChars(env, jEnv, env_str);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        for (int k = 0; argv[k]; k++) free(argv[k]);
        free(argv);
        if (envp) { for (int k = 0; envp[k]; k++) free(envp[k]); free(envp); }
        return -1;
    }

    if (pid == 0) {
        /* Child process */
        if (cwd) chdir(cwd);

        /* Set TERM */
        setenv("TERM", "xterm-256color", 1);

        if (envp) {
            execve(cmd, argv, envp);
        } else {
            execv(cmd, argv);
        }
        /* If exec fails, exit child */
        _exit(127);
    }

    /* Parent process */
    g_child_pid = pid;

    /* Set non-blocking on master fd */
    int flags = fcntl(master_fd, F_GETFL, 0);
    /* Keep blocking — we use timeouts in Kotlin */

    (*env)->ReleaseStringUTFChars(env, jCmd, cmd);
    (*env)->ReleaseStringUTFChars(env, jArgs, args_str);
    if (env_str) (*env)->ReleaseStringUTFChars(env, jEnv, env_str);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    for (int k = 0; argv[k]; k++) free(argv[k]);
    free(argv);
    if (envp) { for (int k = 0; envp[k]; k++) free(envp[k]); free(envp); }

    return master_fd;
}

/**
 * Write data to the PTY master fd.
 * Returns number of bytes written, or -1 on error.
 */
JNIEXPORT jint JNICALL
Java_com_corecoder_app_core_exec_Pty_nativeWrite(
    JNIEnv *env, jclass cls, jint fd, jbyteArray data)
{
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);

    ssize_t written = write(fd, buf, len);

    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return (jint)written;
}

/**
 * Read data from the PTY master fd.
 * Blocks until data is available or fd is closed.
 * Returns number of bytes read, 0 on EOF, -1 on error.
 */
JNIEXPORT jint JNICALL
Java_com_corecoder_app_core_exec_Pty_nativeRead(
    JNIEnv *env, jclass cls, jint fd, jbyteArray buffer)
{
    jsize len = (*env)->GetArrayLength(env, buffer);
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);

    ssize_t n = read(fd, buf, len);

    if (n > 0) {
        (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);
    } else {
        (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);
    }

    if (n < 0 && errno == EIO) {
        /* EIO on PTY read means child has exited — treat as EOF */
        return 0;
    }
    return (jint)n;
}

/**
 * Close the PTY master fd and wait for child to exit.
 */
JNIEXPORT void JNICALL
Java_com_corecoder_app_core_exec_Pty_nativeClose(
    JNIEnv *env, jclass cls, jint fd)
{
    close(fd);
    if (g_child_pid > 0) {
        int status;
        waitpid(g_child_pid, &status, WNOHANG);
        g_child_pid = -1;
    }
}

/**
 * Send SIGTERM to the child process.
 */
JNIEXPORT void JNICALL
Java_com_corecoder_app_core_exec_Pty_nativeKill(
    JNIEnv *env, jclass cls)
{
    if (g_child_pid > 0) {
        kill(g_child_pid, SIGTERM);
    }
}

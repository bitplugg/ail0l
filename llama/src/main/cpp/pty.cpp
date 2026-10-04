// Pseudo-terminal bridge for the in-app terminal.
//
// A pipe gives the shell no tty, so programs disable colours, refuse to run interactively and
// line editing does not work at all. forkpty() gives the child a real terminal; the master side
// is what Java reads and writes.

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

#include <mutex>
#include <string>
#include <vector>

namespace {

struct PtySession {
    int master = -1;
    pid_t pid = -1;
    bool reaped = false;
    int exitCode = -1;
};

std::mutex g_lock;
std::vector<PtySession *> g_sessions;

PtySession *session_for(jlong handle) {
    if (handle < 0 || handle >= static_cast<jlong>(g_sessions.size())) {
        return nullptr;
    }
    return g_sessions[static_cast<size_t>(handle)];
}

std::string jstring_to_utf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_arm_aichat_internal_PtyBridge_nativeStart(JNIEnv *env, jobject, jobjectArray argv,
                                                   jint cols, jint rows) {
    if (argv == nullptr || env->GetArrayLength(argv) == 0) return -1;

    std::vector<std::string> args;
    const jsize count = env->GetArrayLength(argv);
    for (jsize i = 0; i < count; i++) {
        auto item = reinterpret_cast<jstring>(env->GetObjectArrayElement(argv, i));
        args.push_back(jstring_to_utf8(env, item));
        env->DeleteLocalRef(item);
    }

    struct winsize size {};
    size.ws_col = cols > 0 ? static_cast<unsigned short>(cols) : 80;
    size.ws_row = rows > 0 ? static_cast<unsigned short>(rows) : 24;

    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &size);
    if (pid < 0) return -1;

    if (pid == 0) {
        // Child: become a session leader with the slave pty as controlling terminal.
        setsid();
        setenv("TERM", "xterm-256color", 1);
        std::vector<char *> raw;
        raw.reserve(args.size() + 1);
        for (auto &arg : args) raw.push_back(const_cast<char *>(arg.c_str()));
        raw.push_back(nullptr);
        execv(raw[0], raw.data());
        _exit(127);
    }

    int flags = fcntl(master, F_GETFL, 0);
    fcntl(master, F_SETFL, flags | O_NONBLOCK);

    std::lock_guard<std::mutex> guard(g_lock);
    g_sessions.push_back(new PtySession{master, pid, false, -1});
    return static_cast<jlong>(g_sessions.size() - 1);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_PtyBridge_nativeWrite(JNIEnv *env, jobject, jlong handle, jbyteArray data) {
    PtySession *session = session_for(handle);
    if (session == nullptr || session->master < 0 || data == nullptr) return -1;

    const jsize length = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    const ssize_t written = write(session->master, bytes, static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_arm_aichat_internal_PtyBridge_nativeRead(JNIEnv *env, jobject, jlong handle, jint timeoutMs) {
    PtySession *session = session_for(handle);
    if (session == nullptr || session->master < 0) return env->NewByteArray(0);

    struct pollfd waiting {};
    waiting.fd = session->master;
    waiting.events = POLLIN;
    const int ready = poll(&waiting, 1, timeoutMs);
    if (ready <= 0) return env->NewByteArray(0);

    char buffer[8192];
    const ssize_t got = read(session->master, buffer, sizeof(buffer));
    if (got <= 0) return env->NewByteArray(0);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(got));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(got), reinterpret_cast<const jbyte *>(buffer));
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_PtyBridge_nativeResize(JNIEnv *, jobject, jlong handle, jint cols, jint rows) {
    PtySession *session = session_for(handle);
    if (session == nullptr || session->master < 0) return -1;

    struct winsize size {};
    size.ws_col = static_cast<unsigned short>(cols);
    size.ws_row = static_cast<unsigned short>(rows);
    return ioctl(session->master, TIOCSWINSZ, &size);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_PtyBridge_nativeWait(JNIEnv *, jobject, jlong handle) {
    PtySession *session = session_for(handle);
    if (session == nullptr) return -1;
    if (session->reaped) return session->exitCode;

    int status = 0;
    if (waitpid(session->pid, &status, WNOHANG) == session->pid) {
        session->reaped = true;
        session->exitCode = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    }
    return session->reaped ? session->exitCode : -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_PtyBridge_nativeAlive(JNIEnv *, jobject, jlong handle) {
    PtySession *session = session_for(handle);
    if (session == nullptr || session->master < 0) return 0;
    return session->reaped ? 0 : 1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_PtyBridge_nativeClose(JNIEnv *, jobject, jlong handle) {
    PtySession *session = session_for(handle);
    if (session == nullptr) return;

    std::lock_guard<std::mutex> guard(g_lock);
    if (session->master >= 0) {
        close(session->master);
        session->master = -1;
    }
    if (session->pid > 0 && !session->reaped) {
        kill(session->pid, SIGHUP);
        kill(session->pid, SIGKILL);
        waitpid(session->pid, nullptr, 0);
    }
    delete session;
    g_sessions[static_cast<size_t>(handle)] = nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_PtyBridge_nativeAvailable(JNIEnv *, jobject) {
    return 1;
}

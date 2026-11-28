#pragma once

#include <android/log.h>
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <string>
#include <sys/stat.h>

namespace kleetrace {

inline std::mutex &trace_mutex() {
    static std::mutex m;
    return m;
}

inline std::string &trace_path() {
    static std::string p;
    return p;
}

inline void init(const std::string &pkgDataDir) {
    std::lock_guard<std::mutex> lock(trace_mutex());
    if (pkgDataDir.empty()) {
        trace_path().clear();
        return;
    }
    std::string dir = pkgDataDir + "/files/klee_adrenotools";
    mkdir((pkgDataDir + "/files").c_str(), 0755);
    mkdir(dir.c_str(), 0755);
    trace_path() = dir + "/boot_trace.txt";
}

inline void logf(const char *tag, int prio, const char *fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    __android_log_print(prio, tag, "%s", buf);

    std::lock_guard<std::mutex> lock(trace_mutex());
    if (!trace_path().empty()) {
        FILE *fp = fopen(trace_path().c_str(), "a");
        if (fp) {
            fprintf(fp, "[%s] %s\n", tag, buf);
            fclose(fp);
        }
    }
}

} // namespace kleetrace

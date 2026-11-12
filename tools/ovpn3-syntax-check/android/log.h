#pragma once
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_INFO 4
#include <cstdarg>
inline int __android_log_print(int, const char *, const char *, ...) { return 0; }

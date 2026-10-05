#pragma once
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
extern "C" int __android_log_write(int,const char*,const char*);

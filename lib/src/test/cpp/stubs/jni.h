#pragma once
#include <cstdint>
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
using jboolean=unsigned char;
using jint=int;
using jfloat=float;
using jchar=uint16_t;
using jobject=void*;
using jstring=void*;
struct JNIEnv {
void *FindClass(const char*);
int ThrowNew(void*,const char*);
const jchar *GetStringChars(jstring,void*);
int GetStringLength(jstring);
void ReleaseStringChars(jstring,const jchar*);
jstring NewString(const jchar*,int);
};

#include <jni.h>
#include <android/log.h>
#include "model_loader.h"
#include <cstring>
#include <cstdlib>
#include <unistd.h>
#include <errno.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "JNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "JNI", __VA_ARGS__)

struct ProgressData { JNIEnv* env; jobject cb; };
static void progressFn(void* ud, int pct) {
    auto* d=(ProgressData*)ud;
    if(!d->cb) return;
    jclass cls=d->env->GetObjectClass(d->cb);
    jmethodID inv=d->env->GetMethodID(cls,"invoke","(Ljava/lang/Object;)Ljava/lang/Object;");
    if(!inv) return;
    jclass ic=d->env->FindClass("java/lang/Integer");
    jmethodID vof=d->env->GetStaticMethodID(ic,"valueOf","(I)Ljava/lang/Integer;");
    jobject box=d->env->CallStaticObjectMethod(ic,vof,pct);
    d->env->CallObjectMethod(d->cb,inv,box);
    d->env->DeleteLocalRef(box); d->env->DeleteLocalRef(ic);
}

// Read file descriptor into native buffer
static uint8_t* readFd(int fd, long long offset, long long length, size_t* outLen) {
    if(lseek(fd,(off_t)offset,SEEK_SET)<0) return nullptr;
    uint8_t* buf=(uint8_t*)malloc((size_t)length);
    if(!buf) return nullptr;
    size_t total=0;
    while(total<(size_t)length){
        ssize_t r=read(fd,buf+total,(size_t)length-total);
        if(r<=0) break;
        total+=r;
    }
    *outLen=total;
    return buf;
}

static jlong doLoad(ModelData* model) {
    LOGI("Loaded: %d tris display / %d original", model->displayTriangleCount, model->originalTriangleCount);
    return (jlong)(intptr_t)model;
}

// ── Load from file descriptor ─────────────────────────────────────────────────
extern "C" JNIEXPORT jlong JNICALL
Java_com_dmitrybrant_modelviewer_NativeModelLoader_loadFromFd(
    JNIEnv* env, jclass, jint fd, jlong offset, jlong length,
    jstring formatHint, jobject progressCb)
{
    size_t dataLen=0;
    uint8_t* data=readFd(fd,(long long)offset,(long long)length,&dataLen);
    if(!data||dataLen<10) { free(data); return 0; }

    const char* fmt=env->GetStringUTFChars(formatHint,nullptr);
    auto* model=new ModelData(); memset(model,0,sizeof(ModelData));
    ProgressData pd={env,progressCb};

    bool ok=false;
    if(strstr(fmt,".obj")||strstr(fmt,"obj"))
        ok=objLoad(data,dataLen,model,progressCb?progressFn:nullptr,&pd);
    else if(strstr(fmt,".ply")||strstr(fmt,"ply"))
        ok=plyLoad(data,dataLen,model,progressCb?progressFn:nullptr,&pd);
    else
        ok=stlLoad(data,dataLen,false,model,progressCb?progressFn:nullptr,&pd);

    env->ReleaseStringUTFChars(formatHint,fmt);
    free(data);
    if(!ok){delete model; return 0;}
    return doLoad(model);
}

// ── Load from byte array (assets/HTTP) ───────────────────────────────────────
extern "C" JNIEXPORT jlong JNICALL
Java_com_dmitrybrant_modelviewer_NativeModelLoader_loadFromBytes(
    JNIEnv* env, jclass, jbyteArray data, jstring formatHint, jobject progressCb)
{
    jsize len=env->GetArrayLength(data);
    jbyte* bytes=env->GetByteArrayElements(data,nullptr);
    if(!bytes) return 0;

    const char* fmt=env->GetStringUTFChars(formatHint,nullptr);
    auto* model=new ModelData(); memset(model,0,sizeof(ModelData));
    ProgressData pd={env,progressCb};

    bool ok=false;
    if(strstr(fmt,".obj")||strstr(fmt,"obj"))
        ok=objLoad((const uint8_t*)bytes,(size_t)len,model,progressCb?progressFn:nullptr,&pd);
    else if(strstr(fmt,".ply")||strstr(fmt,"ply"))
        ok=plyLoad((const uint8_t*)bytes,(size_t)len,model,progressCb?progressFn:nullptr,&pd);
    else
        ok=stlLoad((const uint8_t*)bytes,(size_t)len,false,model,progressCb?progressFn:nullptr,&pd);

    env->ReleaseStringUTFChars(formatHint,fmt);
    env->ReleaseByteArrayElements(data,bytes,JNI_ABORT);
    if(!ok){delete model; return 0;}
    return doLoad(model);
}

// ── Export ────────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_dmitrybrant_modelviewer_NativeModelLoader_exportModel(
    JNIEnv* env, jclass, jlong handle,
    jstring formatStr, jfloat sx, jfloat sy, jfloat sz)
{
    auto* m=(ModelData*)(intptr_t)handle;
    if(!m) return nullptr;

    const char* fmt=env->GetStringUTFChars(formatStr,nullptr);

    // First call: get size
    int needed=0;
    if(strstr(fmt,"obj"))      needed=exportOBJ(m,sx,sy,sz,nullptr,0);
    else if(strstr(fmt,"ply")) needed=exportPLY(m,sx,sy,sz,nullptr,0);
    else                        needed=exportSTL(m,sx,sy,sz,nullptr,0);

    env->ReleaseStringUTFChars(formatStr,fmt);
    if(needed<=0) return nullptr;

    uint8_t* buf=(uint8_t*)malloc(needed);
    if(!buf) return nullptr;

    fmt=env->GetStringUTFChars(formatStr,nullptr);
    int written=0;
    if(strstr(fmt,"obj"))      written=exportOBJ(m,sx,sy,sz,buf,needed);
    else if(strstr(fmt,"ply")) written=exportPLY(m,sx,sy,sz,buf,needed);
    else                        written=exportSTL(m,sx,sy,sz,buf,needed);
    env->ReleaseStringUTFChars(formatStr,fmt);

    if(written<=0){free(buf);return nullptr;}
    jbyteArray arr=env->NewByteArray(written);
    env->SetByteArrayRegion(arr,0,written,(jbyte*)buf);
    free(buf);
    return arr;
}

// ── Getters ───────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jobject JNICALL
Java_com_dmitrybrant_modelviewer_NativeModelLoader_getVertexBuffer(
    JNIEnv* env,jclass,jlong h){
    auto* m=(ModelData*)(intptr_t)h;
    if(!m||!m->vertices) return nullptr;
    return env->NewDirectByteBuffer(m->vertices,m->vertexFloats*4);
}
extern "C" JNIEXPORT jobject JNICALL
Java_com_dmitrybrant_modelviewer_NativeModelLoader_getNormalBuffer(
    JNIEnv* env,jclass,jlong h){
    auto* m=(ModelData*)(intptr_t)h;
    if(!m||!m->normals) return nullptr;
    return env->NewDirectByteBuffer(m->normals,m->normalFloats*4);
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_dmitrybrant_modelviewer_NativeModelLoader_getModelInfo(
    JNIEnv* env,jclass,jlong h){
    auto* m=(ModelData*)(intptr_t)h;
    if(!m) return nullptr;
    jfloatArray a=env->NewFloatArray(13);
    float v[13]={m->minX,m->minY,m->minZ,m->maxX,m->maxY,m->maxZ,
                 m->centerX,m->centerY,m->centerZ,
                 (float)m->vertexCount,(float)m->originalTriangleCount,
                 (float)m->displayTriangleCount,m->isDecimated?1.f:0.f};
    env->SetFloatArrayRegion(a,0,13,v);
    return a;
}
extern "C" JNIEXPORT void JNICALL
Java_com_dmitrybrant_modelviewer_NativeModelLoader_freeModel(
    JNIEnv*,jclass,jlong h){
    auto* m=(ModelData*)(intptr_t)h;
    if(!m) return;
    modelFree(m); delete m;
}

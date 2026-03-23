#include "stl_loader.h"
#include <cstring>
#include <cmath>
#include <cstdlib>
#include <algorithm>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "STLLoader", __VA_ARGS__)

static inline float readF(const uint8_t* p) { float f; memcpy(&f,p,4); return f; }
static inline uint32_t readU32(const uint8_t* p) {
    return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);
}

// ── Face normal (always from geometry for correct lighting) ───────────────────
static inline void faceNormal(const float* v0, const float* v1, const float* v2, float* n) {
    float ax=v1[0]-v0[0], ay=v1[1]-v0[1], az=v1[2]-v0[2];
    float bx=v2[0]-v0[0], by=v2[1]-v0[1], bz=v2[2]-v0[2];
    n[0]=ay*bz-az*by; n[1]=az*bx-ax*bz; n[2]=ax*by-ay*bx;
    float len=sqrtf(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);
    if(len>1e-10f){n[0]/=len;n[1]/=len;n[2]/=len;}
    else{n[0]=0;n[1]=1;n[2]=0;}
}

// ── Smooth normals: sort-based vertex welding ─────────────────────────────────
// Groups vertices at the same position and averages their face normals.
// Result: smooth shading on curved surfaces, sharp edges preserved.
struct SortVertex {
    int32_t qx, qy, qz;  // quantized position
    float nx, ny, nz;     // face normal
    int   origIdx;        // original vertex index
};

static void smoothNormals(float* verts, float* norms, int vc) {
    if (vc <= 3) return;
    const float Q = 1000.0f;

    SortVertex* sv = (SortVertex*)malloc(vc * sizeof(SortVertex));
    if (!sv) return;

    for (int i=0; i<vc; i++) {
        sv[i].qx = (int32_t)(verts[i*3]   * Q);
        sv[i].qy = (int32_t)(verts[i*3+1] * Q);
        sv[i].qz = (int32_t)(verts[i*3+2] * Q);
        sv[i].nx = norms[i*3];
        sv[i].ny = norms[i*3+1];
        sv[i].nz = norms[i*3+2];
        sv[i].origIdx = i;
    }

    // Sort by 3D position
    std::sort(sv, sv+vc, [](const SortVertex& a, const SortVertex& b){
        if(a.qx!=b.qx) return a.qx<b.qx;
        if(a.qy!=b.qy) return a.qy<b.qy;
        return a.qz<b.qz;
    });

    // Allocate smooth normal output
    float* out = (float*)calloc(vc * 3, sizeof(float));
    if (!out) { free(sv); return; }

    // For each group of same-position vertices, average their normals
    int i = 0;
    while (i < vc) {
        // Find group end
        int j = i+1;
        while (j < vc && sv[j].qx==sv[i].qx && sv[j].qy==sv[i].qy && sv[j].qz==sv[i].qz) j++;

        // Sum normals
        float sx=0,sy=0,sz=0;
        for (int k=i;k<j;k++) { sx+=sv[k].nx; sy+=sv[k].ny; sz+=sv[k].nz; }
        float len = sqrtf(sx*sx+sy*sy+sz*sz);
        if (len > 1e-10f) { sx/=len; sy/=len; sz/=len; }

        // Write averaged normal back using original index
        for (int k=i;k<j;k++) {
            int idx = sv[k].origIdx;
            out[idx*3]=sx; out[idx*3+1]=sy; out[idx*3+2]=sz;
        }
        i = j;
    }

    memcpy(norms, out, vc*3*sizeof(float));
    free(out);
    free(sv);
}

// ── Binary STL ────────────────────────────────────────────────────────────────
static bool loadBinary(const uint8_t* data, size_t len,
                       ModelData* out, ProgressCallback cb, void* ud)
{
    if(len<84) return false;
    uint32_t total = readU32(data+80);
    if(total==0||total>50000000) return false;
    if(84+(size_t)total*50>len) total=(uint32_t)((len-84)/50);

    out->originalTriangleCount=(int)total;

    // KEY FIX: Load first N triangles in order — NO skipping, NO random sampling
    // This preserves mesh connectivity → smooth normals work → NO DOTS
    const int MAX_TRI = 1500000;
    int keep = (int)std::min((uint32_t)MAX_TRI, total);
    out->isDecimated = keep < (int)total;
    out->displayTriangleCount = keep;

    out->vertices = (float*)malloc(keep*9*sizeof(float));
    out->normals  = (float*)malloc(keep*9*sizeof(float));
    if(!out->vertices||!out->normals) return false;

    float minX=1e30f,minY=1e30f,minZ=1e30f;
    float maxX=-1e30f,maxY=-1e30f,maxZ=-1e30f;
    double sx=0,sy=0,sz=0;
    int vPtr=0, nPtr=0, lastProg=-1;

    for(int i=0; i<keep; i++) {
        const uint8_t* tri = data+84+i*50;
        int prog = (int)(i*75LL/keep);
        if(prog!=lastProg&&cb){cb(ud,prog);lastProg=prog;}

        float v[9];
        for(int vi=0;vi<3;vi++){
            v[vi*3]   = readF(tri+12+vi*12);
            v[vi*3+1] = readF(tri+12+vi*12+4);
            v[vi*3+2] = readF(tri+12+vi*12+8);
        }
        float n[3]; faceNormal(v,v+3,v+6,n);

        for(int vi=0;vi<3;vi++){
            float vx=v[vi*3],vy=v[vi*3+1],vz=v[vi*3+2];
            if(vx<minX)minX=vx; if(vx>maxX)maxX=vx;
            if(vy<minY)minY=vy; if(vy>maxY)maxY=vy;
            if(vz<minZ)minZ=vz; if(vz>maxZ)maxZ=vz;
            sx+=vx; sy+=vy; sz+=vz;
            out->vertices[vPtr++]=vx; out->vertices[vPtr++]=vy; out->vertices[vPtr++]=vz;
            out->normals[nPtr++]=n[0]; out->normals[nPtr++]=n[1]; out->normals[nPtr++]=n[2];
        }
    }

    if(vPtr==0) return false;
    if(cb) cb(ud,80);

    int vc=vPtr/3;
    smoothNormals(out->vertices, out->normals, vc);
    if(cb) cb(ud,97);

    out->vertexCount=vc; out->vertexFloats=vPtr; out->normalFloats=nPtr;
    out->minX=minX;out->minY=minY;out->minZ=minZ;
    out->maxX=maxX;out->maxY=maxY;out->maxZ=maxZ;
    out->centerX=(float)(sx/vc); out->centerY=(float)(sy/vc); out->centerZ=(float)(sz/vc);
    LOGI("Binary STL: %d/%d triangles, decimated=%d", keep, (int)total, out->isDecimated);
    return true;
}

// ── Text STL ──────────────────────────────────────────────────────────────────
static bool loadText(const uint8_t* data, size_t len,
                     ModelData* out, ProgressCallback cb, void* ud)
{
    const int MAX_TRI = 1500000;
    out->vertices=(float*)malloc(MAX_TRI*9*sizeof(float));
    out->normals =(float*)malloc(MAX_TRI*9*sizeof(float));
    if(!out->vertices||!out->normals) return false;

    float minX=1e30f,minY=1e30f,minZ=1e30f;
    float maxX=-1e30f,maxY=-1e30f,maxZ=-1e30f;
    double sx=0,sy=0,sz=0;
    int vPtr=0,nPtr=0,total=0;
    float cv[9]={}; int vi=0;

    const char* p=(const char*)data;
    const char* end=p+len;

    while(p<end){
        while(p<end&&(*p==' '||*p=='\t'||*p=='\r'||*p=='\n'))p++;
        if(p>=end) break;
        if(strncmp(p,"vertex",6)==0){
            p+=6; float a,b,c;
            if(vi<3&&sscanf(p," %f %f %f",&a,&b,&c)==3){
                cv[vi*3]=a;cv[vi*3+1]=b;cv[vi*3+2]=c;vi++;
            }
        } else if(strncmp(p,"endfacet",8)==0){
            // KEY FIX: Take triangles in order, stop at limit
            if(vPtr/9 < MAX_TRI){
                float n[3]; faceNormal(cv,cv+3,cv+6,n);
                for(int v=0;v<3;v++){
                    float vx=cv[v*3],vy=cv[v*3+1],vz=cv[v*3+2];
                    if(vx<minX)minX=vx;if(vx>maxX)maxX=vx;
                    if(vy<minY)minY=vy;if(vy>maxY)maxY=vy;
                    if(vz<minZ)minZ=vz;if(vz>maxZ)maxZ=vz;
                    sx+=vx;sy+=vy;sz+=vz;
                    out->vertices[vPtr++]=vx;out->vertices[vPtr++]=vy;out->vertices[vPtr++]=vz;
                    out->normals[nPtr++]=n[0];out->normals[nPtr++]=n[1];out->normals[nPtr++]=n[2];
                }
            }
            vi=0; total++;
        } else if(strncmp(p,"facet",5)==0) vi=0;
        while(p<end&&*p!='\n')p++;
    }

    if(vPtr==0) return false;
    if(cb) cb(ud,80);

    int vc=vPtr/3;
    smoothNormals(out->vertices,out->normals,vc);
    if(cb) cb(ud,97);

    out->originalTriangleCount=total;
    out->displayTriangleCount=vPtr/9;
    out->isDecimated=(vPtr/9)<total;
    out->vertexCount=vc; out->vertexFloats=vPtr; out->normalFloats=nPtr;
    out->minX=minX;out->minY=minY;out->minZ=minZ;
    out->maxX=maxX;out->maxY=maxY;out->maxZ=maxZ;
    out->centerX=(float)(sx/vc);out->centerY=(float)(sy/vc);out->centerZ=(float)(sz/vc);
    return true;
}

// ── Public API ────────────────────────────────────────────────────────────────
bool stlLoad(const uint8_t* data, size_t len, bool /*decimate*/,
             ModelData* out, ProgressCallback cb, void* ud)
{
    if(!data||len<84||!out) return false;
    memset(out,0,sizeof(ModelData));
    const char* s=(const char*)data;
    // Simple text detection
    bool isText = len>256 && strstr(s,"solid") && strstr(s,"facet") && strstr(s,"vertex");
    bool ok = isText ? loadText(data,len,out,cb,ud)
                     : loadBinary(data,len,out,cb,ud);
    if(!ok) modelFree(out);
    return ok;
}




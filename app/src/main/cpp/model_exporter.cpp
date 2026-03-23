#include "model_loader.h"
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,"Exporter",__VA_ARGS__)

static inline void writeF_le(uint8_t* p, float f){memcpy(p,&f,4);}
static inline void writeU32_le(uint8_t* p, uint32_t v){
    p[0]=v&0xff;p[1]=(v>>8)&0xff;p[2]=(v>>16)&0xff;p[3]=(v>>24)&0xff;
}

// ── STL binary export ─────────────────────────────────────────────────────────
int exportSTL(const ModelData* m, float sx, float sy, float sz,
              uint8_t* outBuf, size_t bufLen)
{
    if(!m||!m->vertices||!m->normals) return -1;
    int triCount = m->vertexFloats/9;
    size_t needed = 84 + (size_t)triCount*50;
    if(outBuf&&bufLen<needed) return (int)needed;
    if(!outBuf) return (int)needed;

    // 80-byte header
    memset(outBuf, 0, 80);
    strcpy((char*)outBuf, "Binary STL - 3D Model Viewer");
    writeU32_le(outBuf+80, (uint32_t)triCount);

    uint8_t* p = outBuf+84;
    for(int t=0;t<triCount;t++){
        int b=t*9;
        // Normal
        writeF_le(p,    m->normals[b]);
        writeF_le(p+4,  m->normals[b+1]);
        writeF_le(p+8,  m->normals[b+2]);
        // 3 vertices with scale
        for(int v=0;v<3;v++){
            int vb=b+v*3;
            writeF_le(p+12+v*12,   m->vertices[vb]*sx);
            writeF_le(p+12+v*12+4, m->vertices[vb+1]*sy);
            writeF_le(p+12+v*12+8, m->vertices[vb+2]*sz);
        }
        p[48]=0; p[49]=0; // attribute
        p+=50;
    }
    return (int)needed;
}

// ── OBJ text export ───────────────────────────────────────────────────────────
int exportOBJ(const ModelData* m, float sx, float sy, float sz,
              uint8_t* outBuf, size_t bufLen)
{
    if(!m||!m->vertices) return -1;
    int vc = m->vertexFloats/3;
    int triCount = vc/3;

    // Estimate size: each vertex line ~30 chars, each face line ~15 chars
    size_t estSize = (size_t)vc*30 + (size_t)triCount*20 + 200;
    if(!outBuf||bufLen<estSize) return (int)estSize;

    char* p=(char*)outBuf;
    char* pend=(char*)outBuf+bufLen-64;

    p+=sprintf(p,"# 3D Model Viewer export\n");
    for(int i=0;i<vc&&p<pend;i++){
        int b=i*3;
        p+=sprintf(p,"v %.6f %.6f %.6f\n",
            m->vertices[b]*sx, m->vertices[b+1]*sy, m->vertices[b+2]*sz);
    }
    if(m->normals&&m->normalFloats>=vc*3){
        for(int i=0;i<vc&&p<pend;i++){
            int b=i*3;
            p+=sprintf(p,"vn %.6f %.6f %.6f\n",
                m->normals[b],m->normals[b+1],m->normals[b+2]);
        }
        for(int t=0;t<triCount&&p<pend;t++){
            int b=t*3+1;
            p+=sprintf(p,"f %d//%d %d//%d %d//%d\n",b,b,b+1,b+1,b+2,b+2);
        }
    } else {
        for(int t=0;t<triCount&&p<pend;t++){
            int b=t*3+1;
            p+=sprintf(p,"f %d %d %d\n",b,b+1,b+2);
        }
    }
    return (int)(p-(char*)outBuf);
}

// ── PLY binary export ─────────────────────────────────────────────────────────
int exportPLY(const ModelData* m, float sx, float sy, float sz,
              uint8_t* outBuf, size_t bufLen)
{
    if(!m||!m->vertices) return -1;
    int vc=m->vertexFloats/3;
    int triCount=vc/3;
    bool hasNormals=m->normals&&m->normalFloats>=vc*3;
    int floatsPerVert=hasNormals?6:3;
    size_t vertBytes=(size_t)vc*(floatsPerVert*4);
    size_t faceBytes=(size_t)triCount*13; // 1 + 3*4
    size_t estSize=vertBytes+faceBytes+300;

    if(!outBuf||bufLen<estSize) return (int)estSize;

    char* hp=(char*)outBuf;
    hp+=sprintf(hp,"ply\nformat binary_little_endian 1.0\ncomment 3D Model Viewer\n");
    hp+=sprintf(hp,"element vertex %d\nproperty float x\nproperty float y\nproperty float z\n",vc);
    if(hasNormals) hp+=sprintf(hp,"property float nx\nproperty float ny\nproperty float nz\n");
    hp+=sprintf(hp,"element face %d\nproperty list uchar int vertex_indices\nend_header\n",triCount);

    uint8_t* bp=(uint8_t*)hp;
    for(int i=0;i<vc;i++){
        int b=i*3;
        writeF_le(bp,   m->vertices[b]*sx);
        writeF_le(bp+4, m->vertices[b+1]*sy);
        writeF_le(bp+8, m->vertices[b+2]*sz);
        bp+=12;
        if(hasNormals){
            writeF_le(bp,   m->normals[b]);
            writeF_le(bp+4, m->normals[b+1]);
            writeF_le(bp+8, m->normals[b+2]);
            bp+=12;
        }
    }
    for(int t=0;t<triCount;t++){
        *bp++=3;
        writeU32_le(bp,(uint32_t)(t*3));   bp+=4;
        writeU32_le(bp,(uint32_t)(t*3+1)); bp+=4;
        writeU32_le(bp,(uint32_t)(t*3+2)); bp+=4;
    }
    return (int)(bp-(uint8_t*)outBuf);
}

void modelFree(ModelData* out){
    if(!out) return;
    free(out->vertices); out->vertices=nullptr;
    free(out->normals);  out->normals=nullptr;
}

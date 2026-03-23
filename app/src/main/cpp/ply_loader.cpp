#include "model_loader.h"
#include <cstring>
#include <cmath>
#include <cstdlib>
#include <algorithm>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PLYLoader", __VA_ARGS__)

static inline float readF_le(const uint8_t* p){float f;memcpy(&f,p,4);return f;}
static inline uint32_t readU32_le(const uint8_t* p){
    return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);
}
static inline void faceNormal(const float* v0,const float* v1,const float* v2,float* n){
    float ax=v1[0]-v0[0],ay=v1[1]-v0[1],az=v1[2]-v0[2];
    float bx=v2[0]-v0[0],by=v2[1]-v0[1],bz=v2[2]-v0[2];
    n[0]=ay*bz-az*by;n[1]=az*bx-ax*bz;n[2]=ax*by-ay*bx;
    float len=sqrtf(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);
    if(len>1e-10f){n[0]/=len;n[1]/=len;n[2]/=len;}else{n[0]=0;n[1]=1;n[2]=0;}
}

bool plyLoad(const uint8_t* data, size_t len, ModelData* out,
             ProgressCallback cb, void* ud)
{
    if(!data||!out||len<10) return false;
    memset(out,0,sizeof(ModelData));
    if(strncmp((const char*)data,"ply",3)!=0) return false;

    // Parse header
    const char* p=(const char*)data;
    const char* end=p+len;
    int numVerts=0, numFaces=0;
    bool isBinaryLE=false, isAscii=false;
    bool hasNx=false,hasNy=false,hasNz=false;
    int propCount=0; // number of vertex properties before we hit faces
    int nxIdx=-1,nyIdx=-1,nzIdx=-1;

    while(p<end){
        // Skip whitespace
        while(p<end&&(*p==' '||*p=='\t'))p++;
        if(p>=end) break;

        if(strncmp(p,"end_header",10)==0){p+=10;while(p<end&&*p!='\n')p++;if(p<end)p++;break;}
        else if(strncmp(p,"format binary_little_endian",27)==0) isBinaryLE=true;
        else if(strncmp(p,"format ascii",12)==0) isAscii=true;
        else if(strncmp(p,"element vertex",14)==0){p+=14;numVerts=(int)strtol(p,nullptr,10);}
        else if(strncmp(p,"element face",12)==0){p+=12;numFaces=(int)strtol(p,nullptr,10);}
        else if(strncmp(p,"property float nx",17)==0||strncmp(p,"property float32 nx",19)==0){hasNx=true;nxIdx=propCount++;continue;}
        else if(strncmp(p,"property float ny",17)==0||strncmp(p,"property float32 ny",19)==0){hasNy=true;nyIdx=propCount++;continue;}
        else if(strncmp(p,"property float nz",17)==0||strncmp(p,"property float32 nz",19)==0){hasNz=true;nzIdx=propCount++;continue;}
        else if(strncmp(p,"property float",14)==0||strncmp(p,"property float32",16)==0||
                strncmp(p,"property double",15)==0||strncmp(p,"property uchar",14)==0||
                strncmp(p,"property int",12)==0||strncmp(p,"property uint",13)==0){propCount++;}

        while(p<end&&*p!='\n')p++;
        if(p<end)p++;
    }

    if(numVerts<=0) return false;
    const int MAX_TRI=1500000;

    float* vpos=(float*)malloc(numVerts*3*sizeof(float));
    float* vnrm=(hasNx&&hasNy&&hasNz)?(float*)malloc(numVerts*3*sizeof(float)):nullptr;
    if(!vpos){return false;}

    int keep=numFaces>0?std::min(numFaces,MAX_TRI):MAX_TRI;
    out->vertices=(float*)malloc(keep*9*sizeof(float));
    out->normals =(float*)malloc(keep*9*sizeof(float));
    if(!out->vertices||!out->normals){free(vpos);free(vnrm);return false;}

    float minX=1e30f,minY=1e30f,minZ=1e30f,maxX=-1e30f,maxY=-1e30f,maxZ=-1e30f;
    double sx=0,sy=0,sz=0;
    int vPtr=0,nPtr=0,triTotal=0;

    if(isBinaryLE){
        const uint8_t* bp=(const uint8_t*)p;
        const uint8_t* bend=(const uint8_t*)end;
        // Each vertex: propCount floats (4 bytes each)
        int propBytes=propCount*4;
        for(int i=0;i<numVerts&&bp+propBytes<=bend;i++){
            vpos[i*3]  =readF_le(bp);
            vpos[i*3+1]=readF_le(bp+4);
            vpos[i*3+2]=readF_le(bp+8);
            if(vnrm&&nxIdx>=0&&nyIdx>=0&&nzIdx>=0){
                vnrm[i*3]  =readF_le(bp+nxIdx*4);
                vnrm[i*3+1]=readF_le(bp+nyIdx*4);
                vnrm[i*3+2]=readF_le(bp+nzIdx*4);
            }
            bp+=propBytes;
        }
        // Faces
        for(int f=0;f<numFaces&&bp<bend&&vPtr/9<MAX_TRI;f++){
            uint8_t fc=*bp++;
            if(fc<3||bp+(size_t)fc*4>bend) break;
            uint32_t fi[4]={0,0,0,0};
            for(int k=0;k<fc&&k<4;k++){fi[k]=readU32_le(bp);bp+=4;}
            for(int t=0;t<fc-2;t++){
                int i0=fi[0],i1=fi[t+1],i2=fi[t+2];
                if(i0>=numVerts||i1>=numVerts||i2>=numVerts) continue;
                float* p0=vpos+i0*3,*p1=vpos+i1*3,*p2=vpos+i2*3;
                float n[3];
                if(vnrm){n[0]=vnrm[i0*3];n[1]=vnrm[i0*3+1];n[2]=vnrm[i0*3+2];}
                else faceNormal(p0,p1,p2,n);
                for(int v=0;v<3;v++){
                    float* vp=v==0?p0:v==1?p1:p2;
                    float vx=vp[0],vy=vp[1],vz=vp[2];
                    if(vx<minX)minX=vx;if(vx>maxX)maxX=vx;
                    if(vy<minY)minY=vy;if(vy>maxY)maxY=vy;
                    if(vz<minZ)minZ=vz;if(vz>maxZ)maxZ=vz;
                    sx+=vx;sy+=vy;sz+=vz;
                    out->vertices[vPtr++]=vx;out->vertices[vPtr++]=vy;out->vertices[vPtr++]=vz;
                    out->normals[nPtr++]=n[0];out->normals[nPtr++]=n[1];out->normals[nPtr++]=n[2];
                }
                triTotal++;
            }
        }
    } else {
        // ASCII PLY
        for(int i=0;i<numVerts&&p<end;i++){
            while(p<end&&(*p=='\n'||*p=='\r'||*p==' '||*p=='\t'))p++;
            char* ep;
            vpos[i*3]  =(float)strtod(p,&ep);p=ep;
            vpos[i*3+1]=(float)strtod(p,&ep);p=ep;
            vpos[i*3+2]=(float)strtod(p,&ep);p=ep;
            while(p<end&&*p!='\n')p++;if(p<end)p++;
        }
        for(int f=0;f<numFaces&&p<end&&vPtr/9<MAX_TRI;f++){
            while(p<end&&(*p=='\n'||*p=='\r'||*p==' '||*p=='\t'))p++;
            char* ep; int fc=(int)strtol(p,&ep,10);p=ep;
            int fi[4]={0,0,0,0};
            for(int k=0;k<fc&&k<4;k++){fi[k]=(int)strtol(p,&ep,10)-1;p=ep;}
            for(int t=0;t<fc-2;t++){
                int i0=fi[0],i1=fi[t+1],i2=fi[t+2];
                if(i0<0||i0>=numVerts||i1<0||i1>=numVerts||i2<0||i2>=numVerts) continue;
                float* p0=vpos+i0*3,*p1=vpos+i1*3,*p2=vpos+i2*3;
                float n[3]; faceNormal(p0,p1,p2,n);
                for(int v=0;v<3;v++){
                    float* vp=v==0?p0:v==1?p1:p2;
                    float vx=vp[0],vy=vp[1],vz=vp[2];
                    if(vx<minX)minX=vx;if(vx>maxX)maxX=vx;
                    if(vy<minY)minY=vy;if(vy>maxY)maxY=vy;
                    if(vz<minZ)minZ=vz;if(vz>maxZ)maxZ=vz;
                    sx+=vx;sy+=vy;sz+=vz;
                    out->vertices[vPtr++]=vx;out->vertices[vPtr++]=vy;out->vertices[vPtr++]=vz;
                    out->normals[nPtr++]=n[0];out->normals[nPtr++]=n[1];out->normals[nPtr++]=n[2];
                }
                triTotal++;
            }
            while(p<end&&*p!='\n')p++;if(p<end)p++;
        }
    }

    free(vpos); free(vnrm);
    if(vPtr==0) return false;
    int vc=vPtr/3;
    out->vertexCount=vc; out->vertexFloats=vPtr; out->normalFloats=nPtr;
    out->originalTriangleCount=triTotal;
    out->displayTriangleCount=vPtr/9;
    out->isDecimated=out->displayTriangleCount<triTotal;
    out->minX=minX;out->minY=minY;out->minZ=minZ;
    out->maxX=maxX;out->maxY=maxY;out->maxZ=maxZ;
    out->centerX=(float)(sx/vc);out->centerY=(float)(sy/vc);out->centerZ=(float)(sz/vc);
    LOGI("PLY: %d triangles", triTotal);
    return true;
}

#include "model_loader.h"
#include <cstring>
#include <cmath>
#include <cstdlib>
#include <vector>
#include <algorithm>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OBJLoader", __VA_ARGS__)

static inline void faceNormal(const float* v0, const float* v1, const float* v2, float* n) {
    float ax=v1[0]-v0[0], ay=v1[1]-v0[1], az=v1[2]-v0[2];
    float bx=v2[0]-v0[0], by=v2[1]-v0[1], bz=v2[2]-v0[2];
    n[0]=ay*bz-az*by; n[1]=az*bx-ax*bz; n[2]=ax*by-ay*bx;
    float len=sqrtf(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);
    if(len>1e-10f){n[0]/=len;n[1]/=len;n[2]/=len;}
    else{n[0]=0;n[1]=1;n[2]=0;}
}

static const char* skipWs(const char* p, const char* end) {
    while (p<end && (*p==' '||*p=='\t')) p++;
    return p;
}
static const char* nextLine(const char* p, const char* end) {
    while (p<end && *p!='\n') p++;
    if (p<end) p++;
    return p;
}
static float parseFloat(const char*& p, const char* end) {
    while(p<end&&(*p==' '||*p=='\t'))p++;
    char* ep; float v=(float)strtod(p,&ep);
    p=(ep>p)?ep:p+1; return v;
}
static int parseInt(const char*& p, const char* end) {
    while(p<end&&(*p==' '||*p=='\t'))p++;
    char* ep; long v=strtol(p,&ep,10);
    p=(ep>p)?ep:p+1; return (int)v;
}

bool objLoad(const uint8_t* data, size_t len, ModelData* out,
             ProgressCallback cb, void* ud)
{
    if(!data||!out||len<4) return false;
    memset(out,0,sizeof(ModelData));

    // First pass: count vertices and faces
    const char* p=(const char*)data;
    const char* end=p+len;
    int nv=0, nn=0, nf=0;
    while(p<end){
        p=skipWs(p,end);
        if(p<end){
            if(p[0]=='v'&&p[1]==' ') nv++;
            else if(p[0]=='v'&&p[1]=='n') nn++;
            else if(p[0]=='f'&&p[1]==' ') nf++;
        }
        p=nextLine(p,end);
    }

    if(nv==0||nf==0) return false;

    // Allocate position and normal arrays
    float* vpos = (float*)malloc(nv*3*sizeof(float));
    float* vnrm = nn>0 ? (float*)malloc(nn*3*sizeof(float)) : nullptr;
    if(!vpos){return false;}

    // Estimate output size
    const int MAX_TRI = 1500000;
    int keep = std::min(nf, MAX_TRI);
    out->vertices = (float*)malloc(keep*9*sizeof(float));
    out->normals  = (float*)malloc(keep*9*sizeof(float));
    if(!out->vertices||!out->normals){free(vpos);free(vnrm);return false;}

    float minX=1e30f,minY=1e30f,minZ=1e30f;
    float maxX=-1e30f,maxY=-1e30f,maxZ=-1e30f;
    double sx=0,sy=0,sz=0;
    int vi=0,ni=0,vPtr=0,nPtr=0,triTotal=0;

    p=(const char*)data;
    while(p<end){
        p=skipWs(p,end);
        if(p>=end) break;

        if(p[0]=='v'&&p[1]==' '){
            p+=2;
            if(vi<nv){
                vpos[vi*3]  =parseFloat(p,end);
                vpos[vi*3+1]=parseFloat(p,end);
                vpos[vi*3+2]=parseFloat(p,end);
                vi++;
            }
        } else if(p[0]=='v'&&p[1]=='n'&&vnrm){
            p+=2;
            if(ni<nn){
                vnrm[ni*3]  =parseFloat(p,end);
                vnrm[ni*3+1]=parseFloat(p,end);
                vnrm[ni*3+2]=parseFloat(p,end);
                ni++;
            }
        } else if(p[0]=='f'&&p[1]==' '){
            p+=2;
            // Parse face indices (supports v, v/vt, v/vt/vn, v//vn)
            int fv[4]={0,0,0,0}, fn[4]={0,0,0,0};
            int fc=0;
            while(p<end&&*p!='\n'&&fc<4){
                p=skipWs(p,end);
                if(p>=end||*p=='\n') break;
                fv[fc]=parseInt(p,end)-1; // vertex index
                if(p<end&&*p=='/'){
                    p++; // skip /
                    if(p<end&&*p!='/') parseInt(p,end); // skip texcoord
                    if(p<end&&*p=='/'){
                        p++;
                        fn[fc]=parseInt(p,end)-1; // normal index
                    }
                }
                fc++;
            }
            // Triangulate (fan triangulation for quads)
            for(int t=0;t<fc-2&&vPtr/9<MAX_TRI;t++){
                int i0=fv[0],i1=fv[t+1],i2=fv[t+2];
                if(i0<0||i0>=nv||i1<0||i1>=nv||i2<0||i2>=nv) continue;
                float* p0=vpos+i0*3, *p1=vpos+i1*3, *p2=vpos+i2*3;
                float n[3];
                if(vnrm&&fn[0]>=0&&fn[0]<nn) {
                    n[0]=vnrm[fn[0]*3]; n[1]=vnrm[fn[0]*3+1]; n[2]=vnrm[fn[0]*3+2];
                } else faceNormal(p0,p1,p2,n);

                for(int v=0;v<3;v++){
                    float* vp = v==0?p0:v==1?p1:p2;
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
        p=nextLine(p,end);
    }

    free(vpos); free(vnrm);
    if(vPtr==0){return false;}

    int vc=vPtr/3;
    out->vertexCount=vc; out->vertexFloats=vPtr; out->normalFloats=nPtr;
    out->originalTriangleCount=triTotal;
    out->displayTriangleCount=vPtr/9;
    out->isDecimated=out->displayTriangleCount<triTotal;
    out->minX=minX;out->minY=minY;out->minZ=minZ;
    out->maxX=maxX;out->maxY=maxY;out->maxZ=maxZ;
    out->centerX=(float)(sx/vc);out->centerY=(float)(sy/vc);out->centerZ=(float)(sz/vc);
    LOGI("OBJ: %d triangles", triTotal);
    return true;
}

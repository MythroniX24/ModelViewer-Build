#pragma once
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

typedef void (*ProgressCallback)(void* userData, int percent);

struct ModelData {
    float* vertices;
    float* normals;
    int    vertexCount;
    int    vertexFloats;
    int    normalFloats;
    float  minX,minY,minZ;
    float  maxX,maxY,maxZ;
    float  centerX,centerY,centerZ;
    int    originalTriangleCount;
    int    displayTriangleCount;
    bool   isDecimated;
};

// Loaders
bool stlLoad(const uint8_t* data, size_t len, bool decimate,
             ModelData* out, ProgressCallback cb, void* ud);
bool objLoad(const uint8_t* data, size_t len,
             ModelData* out, ProgressCallback cb, void* ud);
bool plyLoad(const uint8_t* data, size_t len,
             ModelData* out, ProgressCallback cb, void* ud);

// Exporters — returns bytes written (or needed size if outBuf==nullptr)
int exportSTL(const ModelData* m, float sx, float sy, float sz,
              uint8_t* outBuf, size_t bufLen);
int exportOBJ(const ModelData* m, float sx, float sy, float sz,
              uint8_t* outBuf, size_t bufLen);
int exportPLY(const ModelData* m, float sx, float sy, float sz,
              uint8_t* outBuf, size_t bufLen);

void modelFree(ModelData* m);

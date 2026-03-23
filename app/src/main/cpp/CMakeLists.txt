cmake_minimum_required(VERSION 3.22.1)
project("modelviewer")

add_library(modelviewer SHARED
    stl_loader.cpp
    obj_loader.cpp
    ply_loader.cpp
    model_exporter.cpp
    jni_bridge.cpp
)

find_library(log-lib log)
target_link_libraries(modelviewer ${log-lib} android)

target_compile_options(modelviewer PRIVATE
    -O3 -ffast-math -ffunction-sections -fdata-sections -fvisibility=hidden -DNDEBUG
)
target_link_options(modelviewer PRIVATE -Wl,--gc-sections -Wl,--strip-all)

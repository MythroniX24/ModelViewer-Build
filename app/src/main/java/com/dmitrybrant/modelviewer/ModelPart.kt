package com.dmitrybrant.modelviewer

/**
 * Represents one connected component (part) of a multi-part model.
 * Each part has its own vertex range in the parent model's vertex buffer
 * and its own independent scale.
 */
data class ModelPart(
    val index: Int,
    val vertexOffset: Int,   // start index in vertex buffer (in floats)
    val vertexCount: Int,    // number of vertices in this part
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val sizeX: Float,        // bounding box size in original model units
    val sizeY: Float,
    val sizeZ: Float,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var scaleZ: Float = 1f,
    var isSelected: Boolean = false
) {
    val currentSizeX: Float get() = sizeX * scaleX
    val currentSizeY: Float get() = sizeY * scaleY
    val currentSizeZ: Float get() = sizeZ * scaleZ
}

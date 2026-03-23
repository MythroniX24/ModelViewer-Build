package com.dmitrybrant.modelviewer

import kotlin.math.sqrt

object MeshSimplifier {

    /**
     * Simplify using vertex clustering grid.
     * Memory-efficient: uses flat arrays instead of HashMaps.
     * Safe for large models.
     */
    fun simplify(
        vertices: FloatArray,
        normals: FloatArray,
        targetRatio: Float = 0.5f
    ): Pair<FloatArray, FloatArray> {
        val triCount = vertices.size / 9
        if (triCount < 100) return Pair(vertices, normals)

        // Find bounding box
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < vertices.size) {
            if (vertices[i] < minX) minX = vertices[i]
            if (vertices[i] > maxX) maxX = vertices[i]
            if (vertices[i+1] < minY) minY = vertices[i+1]
            if (vertices[i+1] > maxY) maxY = vertices[i+1]
            if (vertices[i+2] < minZ) minZ = vertices[i+2]
            if (vertices[i+2] > maxZ) maxZ = vertices[i+2]
            i += 3
        }

        val rangeX = maxX - minX + 0.0001f
        val rangeY = maxY - minY + 0.0001f
        val rangeZ = maxZ - minZ + 0.0001f

        // Grid resolution — keep it modest to avoid OOM
        val gridRes = when {
            triCount > 500_000 -> 30
            triCount > 100_000 -> 50
            triCount > 50_000  -> 80
            else               -> 100
        }
        val gridSize = gridRes * gridRes * gridRes

        // Only allocate if grid is manageable
        if (gridSize > 2_000_000) {
            // Fall back to simple triangle skip for huge models
            return simplifyBySkip(vertices, normals)
        }

        // Per-cell: sumX, sumY, sumZ, sumNX, sumNY, sumNZ, count
        val cellData = FloatArray(gridSize * 7)
        val vertexToCell = IntArray(vertices.size / 3)

        for (v in 0 until vertices.size / 3) {
            val base = v * 3
            val cx = ((vertices[base]   - minX) / rangeX * gridRes).toInt().coerceIn(0, gridRes - 1)
            val cy = ((vertices[base+1] - minY) / rangeY * gridRes).toInt().coerceIn(0, gridRes - 1)
            val cz = ((vertices[base+2] - minZ) / rangeZ * gridRes).toInt().coerceIn(0, gridRes - 1)
            val cellIdx = (cx * gridRes * gridRes + cy * gridRes + cz).coerceIn(0, gridSize - 1)
            val cellBase = cellIdx * 7
            cellData[cellBase]   += vertices[base]
            cellData[cellBase+1] += vertices[base+1]
            cellData[cellBase+2] += vertices[base+2]
            if (base + 2 < normals.size) {
                cellData[cellBase+3] += normals[base]
                cellData[cellBase+4] += normals[base+1]
                cellData[cellBase+5] += normals[base+2]
            }
            cellData[cellBase+6] += 1f
            vertexToCell[v] = cellIdx
        }

        // Compute representative per cell
        val cellRep = FloatArray(gridSize * 3) // x,y,z per cell
        for (c in 0 until gridSize) {
            val base = c * 7
            val count = cellData[base + 6]
            if (count > 0f) {
                val repBase = c * 3
                cellRep[repBase]   = cellData[base] / count
                cellRep[repBase+1] = cellData[base+1] / count
                cellRep[repBase+2] = cellData[base+2] / count
            }
        }

        // Rebuild triangles
        val newVerts = ArrayList<Float>(vertices.size / 2)
        val newNorms = ArrayList<Float>(normals.size / 2)

        for (t in 0 until triCount) {
            val vi0 = t * 3; val vi1 = t * 3 + 1; val vi2 = t * 3 + 2
            if (vi2 >= vertexToCell.size) continue
            val c0 = vertexToCell[vi0]; val c1 = vertexToCell[vi1]; val c2 = vertexToCell[vi2]
            if (c0 == c1 || c1 == c2 || c0 == c2) continue // degenerate

            for (vi in intArrayOf(vi0, vi1, vi2)) {
                val c = vertexToCell[vi]
                val rb = c * 3
                newVerts.add(cellRep[rb]); newVerts.add(cellRep[rb+1]); newVerts.add(cellRep[rb+2])
                // Normal: use original
                val nb = vi * 3
                if (nb + 2 < normals.size) {
                    newNorms.add(normals[nb]); newNorms.add(normals[nb+1]); newNorms.add(normals[nb+2])
                } else {
                    newNorms.add(0f); newNorms.add(1f); newNorms.add(0f)
                }
            }
        }

        if (newVerts.size < 9) return Pair(vertices, normals)
        return Pair(newVerts.toFloatArray(), newNorms.toFloatArray())
    }

    /** Fallback: skip every 2nd triangle (memory safe) */
    private fun simplifyBySkip(vertices: FloatArray, normals: FloatArray): Pair<FloatArray, FloatArray> {
        val triCount = vertices.size / 9
        val keepCount = (triCount + 1) / 2
        val newVerts = FloatArray(keepCount * 9)
        val newNorms = FloatArray(keepCount * 9)
        var ptr = 0
        for (t in 0 until triCount step 2) {
            val base = t * 9
            for (j in 0..8) {
                newVerts[ptr] = if (base + j < vertices.size) vertices[base + j] else 0f
                newNorms[ptr] = if (base + j < normals.size) normals[base + j] else 0f
                ptr++
            }
        }
        return Pair(newVerts.copyOf(ptr), newNorms.copyOf(ptr))
    }

    /**
     * Find connected components using Union-Find.
     * Memory-safe implementation.
     */
    fun findComponents(
        vertices: FloatArray,
        normals: FloatArray
    ): Triple<FloatArray, FloatArray, List<IntArray>> {
        val triCount = vertices.size / 9
        if (triCount < 3) return Triple(vertices, normals, listOf(intArrayOf(0, vertices.size / 3)))

        // Quantize vertices to find shared ones
        val QUANT = 100f
        val vertCount = vertices.size / 3

        // Build triangle adjacency via shared vertices
        // Key: quantized vertex → list of triangle indices
        val vertexToTris = HashMap<Long, MutableList<Int>>(vertCount)
        for (v in 0 until vertCount) {
            val base = v * 3
            if (base + 2 >= vertices.size) continue
            val key = quantize(vertices[base], vertices[base+1], vertices[base+2], QUANT)
            vertexToTris.getOrPut(key) { mutableListOf() }.add(v / 3)
        }

        // Union-Find for triangles
        val parent = IntArray(triCount) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        for ((_, tris) in vertexToTris) {
            if (tris.size > 1) for (k in 1 until tris.size) union(tris[0], tris[k])
        }

        // Group by component
        val components = HashMap<Int, MutableList<Int>>()
        for (t in 0 until triCount) components.getOrPut(find(t)) { mutableListOf() }.add(t)

        // Sort by size
        val sorted = components.values.sortedByDescending { it.size }

        // Reorder
        val newV = FloatArray(vertices.size)
        val newN = FloatArray(normals.size)
        val ranges = mutableListOf<IntArray>()
        var ptr = 0

        for (triList in sorted) {
            val start = ptr / 3
            for (t in triList) {
                val src = t * 9
                for (j in 0..8) {
                    newV[ptr] = if (src + j < vertices.size) vertices[src + j] else 0f
                    newN[ptr] = if (src + j < normals.size) normals[src + j] else 0f
                    ptr++
                }
            }
            ranges.add(intArrayOf(start, ptr / 3 - start))
        }

        return Triple(newV, newN, ranges)
    }

    private fun quantize(x: Float, y: Float, z: Float, q: Float): Long {
        val qx = (x * q).toLong().coerceIn(-999_999L, 999_999L)
        val qy = (y * q).toLong().coerceIn(-999_999L, 999_999L)
        val qz = (z * q).toLong().coerceIn(-999_999L, 999_999L)
        return (qx + 999_999L) * 4_000_000L * 4_000_000L +
               (qy + 999_999L) * 4_000_000L +
               (qz + 999_999L)
    }
}

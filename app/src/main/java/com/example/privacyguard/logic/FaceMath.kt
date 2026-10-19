package com.example.privacyguard.logic

import kotlin.math.sqrt

/** Small maths helpers for face "number vectors" (embeddings). */
object FaceMath {

    /** Scales a vector so its length is 1. Returns a copy. */
    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val length = sqrt(sum).toFloat()
        if (length == 0f) return v.copyOf()
        return FloatArray(v.size) { v[it] / length }
    }

    /** Cosine similarity: 1 = same direction (same person), around 0 = unrelated, -1 = opposite. */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must have the same size" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** Averages several embeddings into one profile vector (normalised to length 1). */
    fun averageProfile(embeddings: List<FloatArray>): FloatArray {
        require(embeddings.isNotEmpty()) { "Need at least one embedding" }
        val size = embeddings[0].size
        val sum = FloatArray(size)
        for (e in embeddings) {
            require(e.size == size) { "All embeddings must have the same size" }
            val n = l2Normalize(e) // every face counts the same, whatever its raw length
            for (i in 0 until size) sum[i] += n[i]
        }
        return l2Normalize(sum)
    }
}

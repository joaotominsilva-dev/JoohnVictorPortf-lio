package com.charis.shoot.culling

import android.graphics.Bitmap
import android.graphics.Rect
import com.charis.shoot.drive.Photo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Culling no aparelho:
 *  - nitidez: variância do Laplaciano (no rosto principal, ou no trecho mais nítido da foto);
 *  - olhos fechados: ML Kit (modelo embutido, roda offline);
 *  - duplicadas: hash perceptual (dHash 64 bits) comparado com as fotos vizinhas no tempo.
 * O resultado vai para appProperties do arquivo no Drive.
 */
class Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.06f)
            .build()
    )

    suspend fun analyze(bmp: Bitmap): Map<String, String> {
        val faces = runCatching { detector.process(InputImage.fromBitmap(bmp, 0)).await() }.getOrDefault(emptyList())
        return withContext(Dispatchers.Default) {
            val minFace = bmp.width * 0.04f
            val usable = faces.filter { it.boundingBox.width() >= minFace }
            val closed = usable.any { f ->
                val l = f.leftEyeOpenProbability
                val r = f.rightEyeOpenProbability
                l != null && r != null && l < 0.35f && r < 0.35f
            }
            val eyes = when {
                usable.isEmpty() -> "none"
                closed -> "closed"
                else -> "open"
            }
            val main = usable.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            val sharp = sharpness(bmp, main?.boundingBox?.takeIf { it.width() >= bmp.width * 0.08f })
            mapOf(
                Photo.K_VERSION to "1",
                Photo.K_SHARP to sharp.toInt().toString(),
                Photo.K_EYES to eyes,
                Photo.K_FACES to usable.size.toString(),
                Photo.K_HASH to dHash(bmp).toString(16),
            )
        }
    }

    fun close() = detector.close()

    companion object {
        private const val WORK = 640

        private fun gray(bmp: Bitmap): Triple<IntArray, Int, Int> {
            val s = WORK.toFloat() / max(bmp.width, bmp.height)
            val w = max(8, (bmp.width * min(1f, s)).toInt())
            val h = max(8, (bmp.height * min(1f, s)).toInt())
            val small = if (w == bmp.width && h == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== bmp) small.recycle()
            for (i in px.indices) {
                val c = px[i]
                px[i] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
            return Triple(px, w, h)
        }

        private fun lapVar(g: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var sum = 0.0
            var sq = 0.0
            var n = 0
            for (y in max(1, y0) until min(y1, g.size / w - 1)) {
                val row = y * w
                for (x in max(1, x0) until min(x1, w - 1)) {
                    val i = row + x
                    val v = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
                    sum += v; sq += v * v; n++
                }
            }
            if (n == 0) return 0.0
            val m = sum / n
            return sq / n - m * m
        }

        /** Nitidez: no rosto principal, se houver; senão o maior valor entre 16 blocos (sujeito em foco + fundo desfocado). */
        fun sharpness(bmp: Bitmap, face: Rect?): Double {
            val (g, w, h) = gray(bmp)
            if (face != null) {
                val sx = w.toFloat() / bmp.width
                val sy = h.toFloat() / bmp.height
                return lapVar(g, w, (face.left * sx).toInt(), (face.top * sy).toInt(),
                    (face.right * sx).toInt(), (face.bottom * sy).toInt())
            }
            var best = 0.0
            val tw = w / 4
            val th = h / 4
            for (ty in 0 until 4) for (tx in 0 until 4) {
                best = max(best, lapVar(g, w, tx * tw, ty * th, (tx + 1) * tw, (ty + 1) * th))
            }
            return best
        }

        fun dHash(bmp: Bitmap): ULong {
            val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
            val px = IntArray(72)
            small.getPixels(px, 0, 9, 0, 0, 9, 8)
            if (small !== bmp) small.recycle()
            fun l(c: Int) = ((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114
            var hash = 0UL
            var bit = 0
            for (y in 0 until 8) for (x in 0 until 8) {
                if (l(px[y * 9 + x]) < l(px[y * 9 + x + 1])) hash = hash or (1UL shl bit)
                bit++
            }
            return hash
        }
    }
}

/** Sinais calculados a partir do que está salvo no Drive (sem baixar de novo). */
data class Insights(
    val blurry: Set<String> = emptySet(),
    val groupOf: Map<String, Int> = emptyMap(),
    val groupSize: Map<Int, Int> = emptyMap(),
    val best: Set<String> = emptySet(),
    val blurThreshold: Int = 0,
) {
    fun isDuplicate(id: String) = groupOf.containsKey(id) && id !in best
    fun suggestReject(p: Photo) = p.id in blurry || p.eyes == "closed" || isDuplicate(p.id)
}

enum class Sensitivity(val label: String, val absolute: Double, val relative: Double) {
    LOW("Baixa", 25.0, 0.22),
    MEDIUM("Média", 45.0, 0.35),
    HIGH("Alta", 70.0, 0.5),
}

fun computeInsights(photos: List<Photo>, sens: Sensitivity): Insights {
    val analyzed = photos.filter { it.analyzed && it.sharpness != null }
    if (analyzed.isEmpty()) return Insights()

    val scores = analyzed.map { it.sharpness!! }.sorted()
    val median = scores[scores.size / 2].toDouble()
    val thr = max(sens.absolute, sens.relative * median)
    val blurry = analyzed.filter { it.sharpness!! < thr }.map { it.id }.toSet()

    // Duplicadas: fotos vizinhas (ordem de captura) parecidas e tiradas perto no tempo.
    val groupOf = HashMap<String, Int>()
    var group = -1
    for (i in 1 until analyzed.size) {
        val a = analyzed[i - 1]
        val b = analyzed[i]
        val ha = a.hash ?: continue
        val hb = b.hash ?: continue
        val dist = java.lang.Long.bitCount((ha xor hb).toLong())
        val close = a.time == null || b.time == null || kotlin.math.abs(b.time - a.time) <= 20_000
        if (dist <= 10 && close) {
            val g = groupOf[a.id] ?: run { group++; groupOf[a.id] = group; group }
            groupOf[b.id] = g
        }
    }
    val members = groupOf.entries.groupBy({ it.value }, { it.key })
    val byId = analyzed.associateBy { it.id }
    val best = members.values.mapNotNull { ids ->
        ids.maxByOrNull { id ->
            val p = byId.getValue(id)
            val s = p.sharpness!!.toDouble()
            (if (p.eyes == "closed") s * 0.3 else s) * (if (p.isReject) 0.1 else 1.0) + p.rating * 1e6 + (if (p.isPick) 1e7 else 0.0)
        }
    }.toSet()
    return Insights(blurry, groupOf, members.mapValues { it.value.size }, best, thr.toInt())
}

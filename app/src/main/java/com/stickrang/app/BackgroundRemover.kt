package com.stickrang.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions

/**
 * Removes a photo's background on the device with ML Kit subject segmentation
 * (people, pets, objects). Nothing is uploaded; Play services downloads the model once.
 */
object BackgroundRemover {
    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
    }

    /** Calls back on the main thread with the subject on a transparent background, or null on failure. */
    fun remove(photo: Bitmap, onDone: (Bitmap?) -> Unit) {
        segmenter.process(InputImage.fromBitmap(photo, 0))
            .addOnSuccessListener { onDone(it.foregroundBitmap) }
            .addOnFailureListener { onDone(null) }
    }

    /** Removes the background from every frame in order; calls back with null if any frame fails. */
    fun removeAll(frames: List<Bitmap>, onProgress: (Int) -> Unit, onDone: (List<Bitmap>?) -> Unit) {
        val out = ArrayList<Bitmap>(frames.size)
        fun next(i: Int) {
            if (i == frames.size) { onDone(out); return }
            onProgress(i)
            remove(frames[i]) { fg ->
                if (fg == null) onDone(null) else { out += fg; next(i + 1) }
            }
        }
        next(0)
    }
}

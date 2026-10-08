package com.x1colegal.materialyt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import coil.size.Size
import coil.transform.Transformation

/** Coil 2 compatible blur fallback for Android versions without RenderEffect. */
@Suppress("DEPRECATION")
class LegacyBlurTransformation(
    context: Context,
    private val radius: Float = 24f,
    private val sampling: Float = 3f,
) : Transformation {
    private val appContext = context.applicationContext

    init {
        require(radius in 0f..25f)
        require(sampling > 0f)
    }

    override val cacheKey = "${LegacyBlurTransformation::class.java.name}-$radius-$sampling"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val output = createBitmap(
            (input.width / sampling).toInt().coerceAtLeast(1),
            (input.height / sampling).toInt().coerceAtLeast(1),
            input.config ?: Bitmap.Config.ARGB_8888,
        )
        output.applyCanvas {
            scale(1f / sampling, 1f / sampling)
            drawBitmap(input, 0f, 0f, paint)
        }

        val renderScript = RenderScript.create(appContext)
        val inputAllocation = Allocation.createFromBitmap(
            renderScript,
            output,
            Allocation.MipmapControl.MIPMAP_NONE,
            Allocation.USAGE_SCRIPT,
        )
        val outputAllocation = Allocation.createTyped(renderScript, inputAllocation.type)
        val blur = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
        try {
            blur.setRadius(radius)
            blur.setInput(inputAllocation)
            blur.forEach(outputAllocation)
            outputAllocation.copyTo(output)
        } finally {
            blur.destroy()
            outputAllocation.destroy()
            inputAllocation.destroy()
            renderScript.destroy()
        }
        return output
    }
}

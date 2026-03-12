package com.PrepPro.mobile.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CropEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class EditorState(
        val scaleX: Float,
        val scaleY: Float,
        val transX: Float,
        val transY: Float,
        val refViewWidth: Int,
        val refViewHeight: Int,
        val cropLeftRatio: Float,
        val cropTopRatio: Float,
        val cropRightRatio: Float,
        val cropBottomRatio: Float,
        val cropImageLeftPx: Float? = null,
        val cropImageTopPx: Float? = null,
        val cropImageRightPx: Float? = null,
        val cropImageBottomPx: Float? = null
    )

    private var bitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()

    private val cropRect = RectF()
    private var initialized = false
    private var pendingState: EditorState? = null
    private var cropAspectRatio = 1f
    private var cropEditingEnabled = true

    private var lastX = 0f
    private var lastY = 0f
    private var dragMode = DragMode.NONE

    private val minCropSize = 120f
    private val handleRadius = 32f

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88000000")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // Preview mode (small window) rendering resources
    private val previewFitMatrix = Matrix()
    private val previewDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66000000")
    }
    private val previewBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val previewCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                constrainImagePosition()
                invalidate()
                return true
            }
        })

    fun setBitmap(newBitmap: Bitmap) {
        bitmap = newBitmap
        cropAspectRatio = if (newBitmap.height > 0) {
            newBitmap.width.toFloat() / newBitmap.height.toFloat()
        } else {
            1f
        }
        initialized = false
        requestLayout()
        invalidate()
    }

    // Used by realtime preview: replace frame without resetting editor state.
    fun updateBitmapKeepingState(newBitmap: Bitmap) {
        val current = bitmap
        if (initialized && current != null &&
            current.width == newBitmap.width && current.height == newBitmap.height
        ) {
            bitmap = newBitmap
            invalidate()
            return
        }

        // If geometry changed, fall back to full reset path.
        setBitmap(newBitmap)
    }

    fun applyEditorState(state: EditorState) {
        pendingState = state
        if (initialized) {
            applyPendingStateIfAny()
            invalidate()
        }
    }

    fun setCropEditingEnabled(enabled: Boolean) {
        cropEditingEnabled = enabled
        invalidate()
    }

    fun exportEditorState(): EditorState? {
        if (!initialized || width <= 0 || height <= 0) return null
        val src = bitmap ?: return null
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        val cropInBitmap = mapCropRectToBitmap(cropRect, src) ?: return null
        return EditorState(
            scaleX = values[Matrix.MSCALE_X],
            scaleY = values[Matrix.MSCALE_Y],
            transX = values[Matrix.MTRANS_X],
            transY = values[Matrix.MTRANS_Y],
            refViewWidth = width,
            refViewHeight = height,
            cropLeftRatio = (cropRect.left / width).coerceIn(0f, 1f),
            cropTopRatio = (cropRect.top / height).coerceIn(0f, 1f),
            cropRightRatio = (cropRect.right / width).coerceIn(0f, 1f),
            cropBottomRatio = (cropRect.bottom / height).coerceIn(0f, 1f),
            cropImageLeftPx = cropInBitmap.left,
            cropImageTopPx = cropInBitmap.top,
            cropImageRightPx = cropInBitmap.right,
            cropImageBottomPx = cropInBitmap.bottom,
        )
    }

    fun getCroppedBitmap(): Bitmap? {
        val src = bitmap ?: return null
        if (!imageMatrix.invert(inverseMatrix)) return null

        val pts = floatArrayOf(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
        inverseMatrix.mapPoints(pts)

        val left = pts[0].coerceIn(0f, src.width.toFloat())
        val top = pts[1].coerceIn(0f, src.height.toFloat())
        val right = pts[2].coerceIn(0f, src.width.toFloat())
        val bottom = pts[3].coerceIn(0f, src.height.toFloat())

        val w = (right - left).toInt()
        val h = (bottom - top).toInt()
        if (w <= 1 || h <= 1) return null

        return Bitmap.createBitmap(src, left.toInt(), top.toInt(), w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initializeStateIfNeeded()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = bitmap ?: return
        initializeStateIfNeeded()

        if (!cropEditingEnabled) {
            // Preview mode: always render full desktop frame without inheriting fullscreen letterbox.
            val scale = min(width.toFloat() / src.width, height.toFloat() / src.height)
            val dx = (width - src.width * scale) / 2f
            val dy = (height - src.height * scale) / 2f
            previewFitMatrix.reset()
            previewFitMatrix.postScale(scale, scale)
            previewFitMatrix.postTranslate(dx, dy)
            canvas.drawBitmap(src, previewFitMatrix, null)

            val cropInBitmap = mapCropRectToBitmap(cropRect, src)
            if (cropInBitmap != null) {
                val pts = floatArrayOf(
                    cropInBitmap.left,
                    cropInBitmap.top,
                    cropInBitmap.right,
                    cropInBitmap.bottom,
                )
                previewFitMatrix.mapPoints(pts)
                val cl = pts[0]
                val ct = pts[1]
                val cr = pts[2]
                val cb = pts[3]
                if (cr - cl > 2f && cb - ct > 2f) {
                    canvas.drawRect(0f, 0f, width.toFloat(), ct, previewDimPaint)
                    canvas.drawRect(0f, cb, width.toFloat(), height.toFloat(), previewDimPaint)
                    canvas.drawRect(0f, ct, cl, cb, previewDimPaint)
                    canvas.drawRect(cr, ct, width.toFloat(), cb, previewDimPaint)
                    canvas.drawRect(cl, ct, cr, cb, previewBorderPaint)
                    val cornerLen = min(cr - cl, cb - ct) * 0.22f
                    canvas.drawLine(cl, ct, cl + cornerLen, ct, previewCornerPaint)
                    canvas.drawLine(cl, ct, cl, ct + cornerLen, previewCornerPaint)
                    canvas.drawLine(cr - cornerLen, ct, cr, ct, previewCornerPaint)
                    canvas.drawLine(cr, ct, cr, ct + cornerLen, previewCornerPaint)
                    canvas.drawLine(cl, cb - cornerLen, cl, cb, previewCornerPaint)
                    canvas.drawLine(cl, cb, cl + cornerLen, cb, previewCornerPaint)
                    canvas.drawLine(cr - cornerLen, cb, cr, cb, previewCornerPaint)
                    canvas.drawLine(cr, cb - cornerLen, cr, cb, previewCornerPaint)
                }
            }
            return
        }

        // Editing mode: draw image at current pan/zoom, dim outside crop, show handles.
        canvas.drawBitmap(src, imageMatrix, null)

        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)

        canvas.drawRect(cropRect, borderPaint)
        canvas.drawCircle(cropRect.left, cropRect.top, handleRadius / 2f, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, handleRadius / 2f, handlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, handleRadius / 2f, handlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleRadius / 2f, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false

        if (!cropEditingEnabled) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    performClick()
                    return true
                }

                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_CANCEL -> return true
            }
        }

        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            parent?.requestDisallowInterceptTouchEvent(true)
            dragMode = DragMode.NONE
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragMode = detectDragMode(lastX, lastY)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = event.x - lastX
                val dy = event.y - lastY
                when (dragMode) {
                    DragMode.PAN_IMAGE -> panImage(dx, dy)
                    DragMode.MOVE_CROP -> moveCrop(dx, dy)
                    DragMode.RESIZE_LT -> resizeCrop(dx, dy, true, true)
                    DragMode.RESIZE_RT -> resizeCrop(dx, dy, false, true)
                    DragMode.RESIZE_LB -> resizeCrop(dx, dy, true, false)
                    DragMode.RESIZE_RB -> resizeCrop(dx, dy, false, false)
                    DragMode.NONE -> Unit
                }
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragMode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun initializeStateIfNeeded() {
        val src = bitmap ?: return
        if (width <= 0 || height <= 0 || initialized) return

        imageMatrix.reset()
        val scale = min(width.toFloat() / src.width, height.toFloat() / src.height)
        val dx = (width - src.width * scale) / 2f
        val dy = (height - src.height * scale) / 2f
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)

        val maxW = width * 0.82f
        val maxH = height * 0.82f
        val fitByWidth = maxW
        val fitByHeight = maxH * cropAspectRatio
        val cw = min(fitByWidth, fitByHeight)
        val ch = if (cropAspectRatio <= 0f) maxH else cw / cropAspectRatio
        cropRect.set(
            (width - cw) / 2f,
            (height - ch) / 2f,
            (width + cw) / 2f,
            (height + ch) / 2f
        )
        initialized = true
        applyPendingStateIfAny()
    }

    private fun applyPendingStateIfAny() {
        val state = pendingState ?: return
        pendingState = null

        val src = bitmap
        if (!cropEditingEnabled && src != null) {
            // Small preview uses its own full-image fit matrix, not fullscreen pan/zoom matrix.
            resetImageMatrixToFit(src)
        } else {
            val widthRatio = if (state.refViewWidth > 0) width.toFloat() / state.refViewWidth.toFloat() else 1f
            val heightRatio = if (state.refViewHeight > 0) height.toFloat() / state.refViewHeight.toFloat() else 1f
            // Keep image scaling isotropic when restoring across different view sizes,
            // otherwise width/height independent scaling can stretch the frame.
            val uniformRatio = min(widthRatio, heightRatio)
            val uniformBaseScale = min(state.scaleX, state.scaleY).coerceAtLeast(0.0001f)
            val matrixValues = floatArrayOf(
                uniformBaseScale * uniformRatio, 0f, state.transX * widthRatio,
                0f, uniformBaseScale * uniformRatio, state.transY * heightRatio,
                0f, 0f, 1f
            )
            imageMatrix.setValues(matrixValues)
            constrainImagePosition()
        }

        val hasBitmapCrop = src != null &&
            state.cropImageLeftPx != null &&
            state.cropImageTopPx != null &&
            state.cropImageRightPx != null &&
            state.cropImageBottomPx != null

        if (hasBitmapCrop) {
            val imageCrop = RectF(
                state.cropImageLeftPx!!.coerceIn(0f, src!!.width.toFloat()),
                state.cropImageTopPx!!.coerceIn(0f, src.height.toFloat()),
                state.cropImageRightPx!!.coerceIn(0f, src.width.toFloat()),
                state.cropImageBottomPx!!.coerceIn(0f, src.height.toFloat()),
            )
            val mapped = mapBitmapRectToView(imageCrop)
            if (mapped != null) {
                cropRect.set(mapped)
            } else {
                cropRect.set(
                    (state.cropLeftRatio * width).coerceIn(0f, width.toFloat()),
                    (state.cropTopRatio * height).coerceIn(0f, height.toFloat()),
                    (state.cropRightRatio * width).coerceIn(0f, width.toFloat()),
                    (state.cropBottomRatio * height).coerceIn(0f, height.toFloat())
                )
            }
        } else {
            cropRect.set(
                (state.cropLeftRatio * width).coerceIn(0f, width.toFloat()),
                (state.cropTopRatio * height).coerceIn(0f, height.toFloat()),
                (state.cropRightRatio * width).coerceIn(0f, width.toFloat()),
                (state.cropBottomRatio * height).coerceIn(0f, height.toFloat())
            )
        }

        if (!cropEditingEnabled) {
            constrainCropRectToImageBounds()
            return
        }

        if (cropRect.width() < minCropSize) {
            cropRect.right = (cropRect.left + minCropSize).coerceAtMost(width.toFloat())
            cropRect.left = (cropRect.right - minCropSize).coerceAtLeast(0f)
        }
        if (cropRect.height() < minCropSize) {
            cropRect.bottom = (cropRect.top + minCropSize).coerceAtMost(height.toFloat())
            cropRect.top = (cropRect.bottom - minCropSize).coerceAtLeast(0f)
        }

        enforceAspectAndBounds()
    }

    private fun detectDragMode(x: Float, y: Float): DragMode {
        if (near(x, y, cropRect.left, cropRect.top)) return DragMode.RESIZE_LT
        if (near(x, y, cropRect.right, cropRect.top)) return DragMode.RESIZE_RT
        if (near(x, y, cropRect.left, cropRect.bottom)) return DragMode.RESIZE_LB
        if (near(x, y, cropRect.right, cropRect.bottom)) return DragMode.RESIZE_RB
        if (cropRect.contains(x, y)) return DragMode.MOVE_CROP
        return DragMode.PAN_IMAGE
    }

    private fun near(x: Float, y: Float, px: Float, py: Float): Boolean {
        return abs(x - px) <= handleRadius && abs(y - py) <= handleRadius
    }

    private fun panImage(dx: Float, dy: Float) {
        imageMatrix.postTranslate(dx, dy)
        constrainImagePosition()
    }

    private fun moveCrop(dx: Float, dy: Float) {
        val nx = when {
            cropRect.left + dx < 0f -> -cropRect.left
            cropRect.right + dx > width -> width - cropRect.right
            else -> dx
        }
        val ny = when {
            cropRect.top + dy < 0f -> -cropRect.top
            cropRect.bottom + dy > height -> height - cropRect.bottom
            else -> dy
        }
        cropRect.offset(nx, ny)
    }

    private fun resizeCrop(dx: Float, dy: Float, leftSide: Boolean, topSide: Boolean) {
        val anchorX = if (leftSide) cropRect.right else cropRect.left
        val anchorY = if (topSide) cropRect.bottom else cropRect.top
        val proposedWidth = if (leftSide) {
            (anchorX - (cropRect.left + dx)).coerceAtLeast(minCropSize)
        } else {
            ((cropRect.right + dx) - anchorX).coerceAtLeast(minCropSize)
        }
        val proposedHeight = if (topSide) {
            (anchorY - (cropRect.top + dy)).coerceAtLeast(minCropSize)
        } else {
            ((cropRect.bottom + dy) - anchorY).coerceAtLeast(minCropSize)
        }

        var nextWidth = proposedWidth
        var nextHeight = proposedHeight

        if (leftSide) {
            cropRect.left = anchorX - nextWidth
            cropRect.right = anchorX
        } else {
            cropRect.left = anchorX
            cropRect.right = anchorX + nextWidth
        }

        if (topSide) {
            cropRect.top = anchorY - nextHeight
            cropRect.bottom = anchorY
        } else {
            cropRect.top = anchorY
            cropRect.bottom = anchorY + nextHeight
        }

        enforceAspectAndBounds()
    }

    private fun enforceAspectAndBounds() {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        cropRect.left = cropRect.left.coerceIn(0f, viewW)
        cropRect.top = cropRect.top.coerceIn(0f, viewH)
        cropRect.right = cropRect.right.coerceIn(0f, viewW)
        cropRect.bottom = cropRect.bottom.coerceIn(0f, viewH)

        if (cropRect.width() < minCropSize) {
            cropRect.right = (cropRect.left + minCropSize).coerceAtMost(viewW)
            cropRect.left = (cropRect.right - minCropSize).coerceAtLeast(0f)
        }
        if (cropRect.height() < minCropSize) {
            cropRect.bottom = (cropRect.top + minCropSize).coerceAtMost(viewH)
            cropRect.top = (cropRect.bottom - minCropSize).coerceAtLeast(0f)
        }
    }

    private fun constrainImagePosition() {
        val src = bitmap ?: return
        val pts = floatArrayOf(
            0f, 0f,
            src.width.toFloat(), 0f,
            src.width.toFloat(), src.height.toFloat(),
            0f, src.height.toFloat()
        )
        imageMatrix.mapPoints(pts)

        var minX = pts[0]
        var maxX = pts[0]
        var minY = pts[1]
        var maxY = pts[1]
        for (i in 2 until pts.size step 2) {
            minX = min(minX, pts[i])
            maxX = max(maxX, pts[i])
            minY = min(minY, pts[i + 1])
            maxY = max(maxY, pts[i + 1])
        }

        var tx = 0f
        var ty = 0f
        if (maxX - minX < width) {
            tx = width / 2f - (minX + maxX) / 2f
        } else {
            if (minX > 0f) tx = -minX
            if (maxX < width) tx = width - maxX
        }

        if (maxY - minY < height) {
            ty = height / 2f - (minY + maxY) / 2f
        } else {
            if (minY > 0f) ty = -minY
            if (maxY < height) ty = height - maxY
        }

        imageMatrix.postTranslate(tx, ty)
    }

    private fun resetImageMatrixToFit(src: Bitmap) {
        imageMatrix.reset()
        val scale = min(width.toFloat() / src.width, height.toFloat() / src.height)
        val dx = (width - src.width * scale) / 2f
        val dy = (height - src.height * scale) / 2f
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
    }

    private fun constrainCropRectToImageBounds() {
        val src = bitmap ?: return
        val pts = floatArrayOf(
            0f, 0f,
            src.width.toFloat(), src.height.toFloat(),
        )
        imageMatrix.mapPoints(pts)
        val imageLeft = min(pts[0], pts[2])
        val imageTop = min(pts[1], pts[3])
        val imageRight = max(pts[0], pts[2])
        val imageBottom = max(pts[1], pts[3])
        cropRect.left = cropRect.left.coerceIn(imageLeft, imageRight)
        cropRect.top = cropRect.top.coerceIn(imageTop, imageBottom)
        cropRect.right = cropRect.right.coerceIn(imageLeft, imageRight)
        cropRect.bottom = cropRect.bottom.coerceIn(imageTop, imageBottom)
        if (cropRect.right < cropRect.left) {
            val t = cropRect.left
            cropRect.left = cropRect.right
            cropRect.right = t
        }
        if (cropRect.bottom < cropRect.top) {
            val t = cropRect.top
            cropRect.top = cropRect.bottom
            cropRect.bottom = t
        }
    }

    private fun mapCropRectToBitmap(viewRect: RectF, src: Bitmap): RectF? {
        if (!imageMatrix.invert(inverseMatrix)) return null
        val pts = floatArrayOf(viewRect.left, viewRect.top, viewRect.right, viewRect.bottom)
        inverseMatrix.mapPoints(pts)
        return RectF(
            pts[0].coerceIn(0f, src.width.toFloat()),
            pts[1].coerceIn(0f, src.height.toFloat()),
            pts[2].coerceIn(0f, src.width.toFloat()),
            pts[3].coerceIn(0f, src.height.toFloat()),
        )
    }

    private fun mapBitmapRectToView(bitmapRect: RectF): RectF? {
        val pts = floatArrayOf(bitmapRect.left, bitmapRect.top, bitmapRect.right, bitmapRect.bottom)
        imageMatrix.mapPoints(pts)
        return RectF(
            pts[0].coerceIn(0f, width.toFloat()),
            pts[1].coerceIn(0f, height.toFloat()),
            pts[2].coerceIn(0f, width.toFloat()),
            pts[3].coerceIn(0f, height.toFloat()),
        )
    }

    private enum class DragMode {
        NONE,
        PAN_IMAGE,
        MOVE_CROP,
        RESIZE_LT,
        RESIZE_RT,
        RESIZE_LB,
        RESIZE_RB
    }
}

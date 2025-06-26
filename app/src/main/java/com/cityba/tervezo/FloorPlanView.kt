package com.cityba.tervezo

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

class FloorPlanView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { WALL, WINDOW, DOOR }
    enum class Mode { NONE, DRAW_WALL, PLACE_WINDOW, PLACE_DOOR, EDIT }

    data class Wall(var x1: Float, var y1: Float, var x2: Float, var y2: Float, var thickness: Int)
    data class WindowOrDoor(
        var x1: Float, var y1: Float,
        var x2: Float, var y2: Float,
        val type: Tool,
        var parentWallIndex: Int
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("floorplan_prefs", Context.MODE_PRIVATE)

    val walls = mutableListOf<Wall>()
    val elements = mutableListOf<WindowOrDoor>()

    var currentTool: Tool = Tool.WALL
    private var currentMode: Mode = Mode.NONE
    private var currentThickness: Int = 30 // cm

    // Drawing coordinates
    private var startWX = 0f
    private var startWY = 0f
    private var lastWX = 0f
    private var lastWY = 0f
    private var isDrawing = false

    // Edit mode
    private var movingWall: Wall? = null
    private var editingWallEnd: Int = -1
    private var movingElement: WindowOrDoor? = null
    private var editingElementEnd: Int = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var hoveredElement: WindowOrDoor? = null
    private var hoveredWall: Wall? = null

    // Drag icon
    private var dragIcon: String? = null
    private var dragIconX = 0f
    private var dragIconY = 0f
    private var isDragging = false

    // Snap distance
    private val snapDistanceCm = 50f // Csökkentett tolerancia
    private var editTolerance = 30f
    private var elementSnapDistance = 5f // 5cm for windows/doors

    // Pinch and pan
    private var prevPinchDist = 0f
    private var prevMidX = 0f
    private var prevMidY = 0f
    private var isTwoFinger = false
    private var hossz = 0f
    private val trashRect = RectF()
    private val trashPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    // Corner points handling
    private data class CornerPoint(var x: Float, var y: Float) {
        val connectedWalls = mutableListOf<Wall>()
    }

    private val cornerPoints = mutableListOf<CornerPoint>()
    private var isClosedShape = false
    private var movingCorner: CornerPoint? = null

    // Paint objects
    private val paintWall = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val paintWindow = Paint().apply {
        style = Paint.Style.FILL // Váltás STROKE -> FILL
        color = Color.parseColor("#e8e3e3")
    }

    private val paintDoor = Paint().apply {
        style = Paint.Style.FILL // Váltás STROKE -> FILL
        color = Color.parseColor("#e8e3e3")
    }
    private val paintText = Paint().apply {
        color = Color.BLACK
        textSize = 48f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val paintGrid = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val paintWallFill: Paint by lazy {
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.DKGRAY
        }
    }
    private val overlayBg = Paint().apply {
        color = Color.BLACK
        alpha = 100
        style = Paint.Style.FILL
    }
    private val overlayText = Paint().apply {
        color = Color.WHITE
        textSize = 70f
        isAntiAlias = true
    }
    private val overlayPadding = 40f
    private val paintEdit = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 8f
    }
    private val paintHandle = Paint().apply {
        style = Paint.Style.FILL
        color = Color.RED
    }
    private val paintHighlight = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.RED
        strokeWidth = 4f
    }
    private val paintCorner = Paint().apply {
        style = Paint.Style.FILL
        color = Color.GREEN
    }
    private val dragIconPaint = Paint().apply {
        color = Color.WHITE
        textSize = 120f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        setShadowLayer(15f, 0f, 0f, Color.BLACK)
    }

    private val matrix = Matrix()
    private val inverseMatrix = Matrix()

    // Add constant for minimum window/door size
    private val MIN_ELEMENT_SIZE_CM = 40f


    init {
        editTolerance = cmToPx(10f) // 10 cm tolerancia
        elementSnapDistance = cmToPx(5f) // 5 cm

        loadFromPrefs()
        analyzeWalls()
    }

    // ---------------------------------------
    // CORNER POINT HANDLING AND CLOSED SHAPE DETECTION
    // ---------------------------------------
    private fun distanceToWallCenter(wall: Wall, x: Float, y: Float): Float {
        val midX = (wall.x1 + wall.x2) / 2
        val midY = (wall.y1 + wall.y2) / 2
        return distance(x, y, midX, midY)
    }

    private fun analyzeWalls() {
        cornerPoints.clear()
        isClosedShape = false

        // Create corner points for all wall endpoints
        walls.forEach { wall ->
            val startPoint = findOrCreateCorner(wall.x1, wall.y1)
            val endPoint = findOrCreateCorner(wall.x2, wall.y2)

            // Connect wall to corners
            if (!startPoint.connectedWalls.contains(wall)) {
                startPoint.connectedWalls.add(wall)
            }
            if (!endPoint.connectedWalls.contains(wall)) {
                endPoint.connectedWalls.add(wall)
            }
        }

        // Detect closed shape (at least 3 walls and first/last corners are close)
        if (cornerPoints.size >= 3) {
            val firstCorner = cornerPoints.firstOrNull()
            val lastCorner = cornerPoints.lastOrNull()

            if (firstCorner != null && lastCorner != null) {
                val distance = distance(firstCorner.x, firstCorner.y, lastCorner.x, lastCorner.y)

                // Check if all corners have at least 2 connected walls (closed loop)
                val allCornersConnected = cornerPoints.all { it.connectedWalls.size >= 2 }

                // Tolerance: 20 cm
                if (distance < cmToPx(20f) && allCornersConnected) {
                    isClosedShape = true
                    Log.d("FloorPlan", "Closed shape detected")
                }
            }
        }
    }

    private fun findOrCreateCorner(x: Float, y: Float): CornerPoint {
        val tolerance = cmToPx(10f) // 10 cm tolerance
        cornerPoints.forEach { corner ->
            if (distance(corner.x, corner.y, x, y) < tolerance) {
                return corner
            }
        }
        return CornerPoint(x, y).also { cornerPoints.add(it) }
    }

    private fun findCornerNearPoint(x: Float, y: Float): CornerPoint? {
        val tolerance = cmToPx(15f) // 15 cm tolerance
        return cornerPoints.minByOrNull { distance(it.x, it.y, x, y) }?.takeIf {
            distance(it.x, it.y, x, y) < tolerance
        }
    }

    // ---------------------------------------
    // HELPER FUNCTIONS
    // ---------------------------------------

    fun setWallThickness(cm: Int) {
        if (cm == 30 || cm == 10) {
            currentThickness = cm
            paintWindow.strokeWidth = cm.toFloat() / 2
            paintDoor.strokeWidth = cm.toFloat() / 2
            invalidate()
        }
    }

    private fun viewToWorld(vx: Float, vy: Float): Pair<Float, Float> {
        inverseMatrix.reset()
        matrix.invert(inverseMatrix)
        val pts = floatArrayOf(vx, vy)
        inverseMatrix.mapPoints(pts)
        return Pair(pts[0], pts[1])
    }

    private fun normalVector(wall: Wall): Pair<Float, Float> {
        val dx = wall.x2 - wall.x1
        val dy = wall.y2 - wall.y1
        val length = max(1f, distance(wall.x1, wall.y1, wall.x2, wall.y2))
        return Pair(-dy / length, dx / length)
    }

    private fun drawGrid(canvas: Canvas) {
        val step = 100
        canvas.save()
        canvas.concat(matrix)
        for (x in 0 until width step step) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paintGrid)
        }
        for (y in 0 until height step step) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paintGrid)
        }
        canvas.restore()
    }

    private fun drawWallPattern(canvas: Canvas, wall: Wall) {
        val dx = wall.x2 - wall.x1
        val dy = wall.y2 - wall.y1
        val length = distance(wall.x1, wall.y1, wall.x2, wall.y2)
        val angle = atan2(dy, dx)

        // Pattern generation
        val patternSize = 40
        val pattern = Bitmap.createBitmap(patternSize, patternSize, Bitmap.Config.ARGB_8888)
        val patternCanvas = Canvas(pattern)
        val patternPaint = Paint().apply {
            color = Color.DKGRAY
            strokeWidth = 4f
            isAntiAlias = true
        }

        for (i in -patternSize until patternSize * 2 step 20) {
            patternCanvas.drawLine(i.toFloat(), 0f, (i + patternSize).toFloat(), patternSize.toFloat(), patternPaint)
        }

        val shader = BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paintWallFill.shader = shader

        canvas.save()
        canvas.concat(matrix)
        canvas.translate(wall.x1, wall.y1)
        canvas.rotate(angle.toDegrees())

        val top = -wall.thickness / 1f
        val bottom = wall.thickness / 1f

        canvas.drawRect(0f, top, length, bottom, paintWallFill)
        paintWall.strokeWidth = 4f
        paintWall.style = Paint.Style.STROKE
        canvas.drawRect(0f, top, length, bottom, paintWall)
        canvas.restore()
    }

    private fun Float.toDegrees(): Float = Math.toDegrees(this.toDouble()).toFloat()

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return sqrt((x2 - x1).pow(2) + (y2 - y1).pow(2))
    }

    private fun distanceToLineSegment(
        px: Float, py: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Float {
        val lineLen = distance(x1, y1, x2, y2)
        if (lineLen == 0f) return distance(px, py, x1, y1)
        val t = ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / (lineLen * lineLen)
        return when {
            t < 0 -> distance(px, py, x1, y1)
            t > 1 -> distance(px, py, x2, y2)
            else -> {
                val projX = x1 + t * (x2 - x1)
                val projY = y1 + t * (y2 - y1)
                distance(px, py, projX, projY)
            }
        }
    }

    private fun findWallNearPoint(x: Float, y: Float): Wall? {
        walls.forEach { wall ->
            if (distance(x, y, wall.x1, wall.y1) < editTolerance ||
                distance(x, y, wall.x2, wall.y2) < editTolerance ||
                distanceToLineSegment(x, y, wall.x1, wall.y1, wall.x2, wall.y2) < editTolerance) {
                return wall
            }
        }
        return null
    }

    private fun findElementNearPoint(x: Float, y: Float): WindowOrDoor? {
        elements.forEach { elem ->
            if (distance(x, y, elem.x1, elem.y1) < editTolerance ||
                distance(x, y, elem.x2, elem.y2) < editTolerance ||
                distanceToLineSegment(x, y, elem.x1, elem.y1, elem.x2, elem.y2) < editTolerance) {
                return elem
            }
        }
        return null
    }

    private fun findWallForElement(
        start: Pair<Float, Float>,
        end: Pair<Float, Float>
    ): Int {
        var bestIndex = -1
        var minAvgDist = Float.MAX_VALUE
        walls.forEachIndexed { i, w ->
            val d1 = distanceToLineSegment(start.first, start.second, w.x1, w.y1, w.x2, w.y2)
            val d2 = distanceToLineSegment(end.first, end.second, w.x1, w.y1, w.x2, w.y2)
            if (d1 < cmToPx(snapDistanceCm) && d2 < cmToPx(snapDistanceCm)) {
                val avg = (d1 + d2) / 2
                if (avg < minAvgDist) {
                    minAvgDist = avg
                    bestIndex = i
                }
            }
        }
        return bestIndex
    }

    private fun projectPointOnLine(
        px: Float, py: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Pair<Float, Float> {
        val dx = x2 - x1
        val dy = y2 - y1
        val lenSq = dx * dx + dy * dy

        if (lenSq == 0f) return Pair(x1, y1)

        val t = ((px - x1) * dx + (py - y1) * dy) / lenSq
        val clampedT = t.coerceIn(0f, 1f)

        return Pair(
            x1 + clampedT * dx,
            y1 + clampedT * dy
        )
    }

    fun pxToCm(px: Float): Float {
        val dpi = resources.displayMetrics.xdpi
        val inches = px / dpi
        return inches * 2.54f * 100f
    }

    fun cmToPx(cm: Float): Float {
        val dpi = resources.displayMetrics.xdpi
        val inches = cm / 2.54f / 100f
        return inches * dpi
    }

    // Snap to 5cm grid for windows/doors
    private fun snapTo5cm(valueCm: Float): Float {
        return ((valueCm / 5).roundToInt() * 5).toFloat()
    }



    private fun snapPxTo5cmGrid(px: Float): Float {
        val cm = pxToCm(px)
        val snappedCm = ((cm / 5).roundToInt() * 5).toFloat()
        return cmToPx(snappedCm)
    }

    private fun saveToPrefs() {
        val wallsArray = JSONArray()
        walls.forEach { w ->
            val obj = JSONObject().apply {
                put("x1", w.x1); put("y1", w.y1)
                put("x2", w.x2); put("y2", w.y2)
                put("thickness", w.thickness)
            }
            wallsArray.put(obj)
        }
        val elemsArray = JSONArray()
        elements.forEach { e ->
            val obj = JSONObject().apply {
                put("x1", e.x1); put("y1", e.y1)
                put("x2", e.x2); put("y2", e.y2)
                put("type", e.type.name)
                put("parentWallIndex", e.parentWallIndex)
            }
            elemsArray.put(obj)
        }
        prefs.edit()
            .putString("walls", wallsArray.toString())
            .putString("elements", elemsArray.toString())
            .apply()
    }

    private fun loadFromPrefs() {
        walls.clear()
        elements.clear()
        prefs.getString("walls", null)?.let { str ->
            val arr = JSONArray(str)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                walls.add(
                    Wall(
                        obj.getDouble("x1").toFloat(),
                        obj.getDouble("y1").toFloat(),
                        obj.getDouble("x2").toFloat(),
                        obj.getDouble("y2").toFloat(),
                        obj.getInt("thickness")
                    )
                )
            }
        }
        prefs.getString("elements", null)?.let { str ->
            val arr = JSONArray(str)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val type = Tool.valueOf(obj.getString("type"))
                elements.add(
                    WindowOrDoor(
                        obj.getDouble("x1").toFloat(),
                        obj.getDouble("y1").toFloat(),
                        obj.getDouble("x2").toFloat(),
                        obj.getDouble("y2").toFloat(),
                        type,
                        obj.getInt("parentWallIndex")
                    )
                )
            }
        }
        analyzeWalls()
    }

    private fun snapAngle(radians: Float): Float {
        val degrees = Math.toDegrees(radians.toDouble())
        val snappedDegrees = (degrees / 5.0).roundToInt() * 5.0
        return Math.toRadians(snappedDegrees).toFloat()
    }

    private fun createWallAlongGrid(x1: Float, y1: Float, x2: Float, y2: Float): Wall {
        val angle = atan2(y2 - y1, x2 - x1)
        val snappedAngle = snapAngle(angle)
        val length = distance(x1, y1, x2, y2)
        val snappedEndX = x1 + cos(snappedAngle) * length
        val snappedEndY = y1 + sin(snappedAngle) * length
        return Wall(x1, y1, snappedEndX, snappedEndY, currentThickness)
    }

    private fun spacing(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return distance(dx, dy, 0f, 0f)
    }

    private fun snapToGrid(value: Float): Float {
        val gridSize = cmToPx(30f)
        return (value / gridSize).roundToInt() * gridSize
    }

    // Snap point to nearest corner
    private fun snapToCorner(x: Float, y: Float): Pair<Float, Float> {
        val tolerance = cmToPx(snapDistanceCm)
        var minDist = Float.MAX_VALUE
        var snappedPoint = Pair(x, y)

        cornerPoints.forEach { corner ->
            val dist = distance(x, y, corner.x, corner.y)
            if (dist < tolerance && dist < minDist) {
                minDist = dist
                snappedPoint = Pair(corner.x, corner.y)
            }
        }

        return snappedPoint
    }

    // JAVÍTOTT: Falra illesztés prioritásos rendszerrel
    private fun snapToWall(x: Float, y: Float): Pair<Float, Float> {
        val tolerance = cmToPx(snapDistanceCm) // 50 cm tolerancia
        var minDist = Float.MAX_VALUE
        var snappedPoint = Pair(x, y)

        // 1. Prioritás: falvégek
        walls.forEach { wall ->
            // Kezdőpont
            var dist = distance(x, y, wall.x1, wall.y1)
            if (dist < tolerance && dist < minDist) {
                minDist = dist
                snappedPoint = Pair(wall.x1, wall.y1)
            }

            // Végpont
            dist = distance(x, y, wall.x2, wall.y2)
            if (dist < tolerance && dist < minDist) {
                minDist = dist
                snappedPoint = Pair(wall.x2, wall.y2)
            }
        }

        // 2. Prioritás: falak síkja
        if (minDist == Float.MAX_VALUE) {
            walls.forEach { wall ->
                // Pontosabb vetítés a fal síkjára
                val (projX, projY) = projectPointOnLine(x, y, wall.x1, wall.y1, wall.x2, wall.y2)
                val dist = distance(x, y, projX, projY)

                // Ellenőrizzük, hogy a vetített pont a fal szakaszon belül van-e
                val segLength = distance(wall.x1, wall.y1, wall.x2, wall.y2)
                val distToStart = distance(projX, projY, wall.x1, wall.y1)
                val distToEnd = distance(projX, projY, wall.x2, wall.y2)

                if (dist < tolerance && dist < minDist &&
                    distToStart <= segLength && distToEnd <= segLength) {
                    minDist = dist
                    snappedPoint = Pair(projX, projY)
                }
            }
        }

        return snappedPoint
    }

    // ---------------------------------------
    // DRAWING
    // ---------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        if (currentMode == Mode.EDIT && hoveredWall != null) {
            canvas.save()
            canvas.concat(matrix)

            // Fal középpontjának kiszámítása
            val midX = (hoveredWall!!.x1 + hoveredWall!!.x2) / 2
            val midY = (hoveredWall!!.y1 + hoveredWall!!.y2) / 2

            // Sárga kör a középpontban (csak ha nincs más elem kiválasztva)
            if (movingWall == hoveredWall && editingWallEnd == -1) {
                paintHandle.color = Color.YELLOW
                canvas.drawCircle(midX, midY, 35f, paintHandle)
            }

            canvas.restore()
        }
        // Draw walls
        walls.forEachIndexed { _, wall ->
            paintWall.strokeWidth = wall.thickness.toFloat()
            drawWallPattern(canvas, wall)

            // Fal hosszának kiszámítása és megjelenítése
            val lengthPx = distance(wall.x1, wall.y1, wall.x2, wall.y2)
            val lengthCm = pxToCm(lengthPx)
            val label = if (lengthCm >= 100) {
                "%.2f m".format(lengthCm / 100).replace('.', ',')
            } else {
                "%.0f cm".format(lengthCm)
            }

            val midX = (wall.x1 + wall.x2) / 2
            val midY = (wall.y1 + wall.y2) / 2
            val angle = atan2(wall.y2 - wall.y1, wall.x2 - wall.x1)

            // Normálvektor kiszámítása a segédfüggvénnyel
            val (nx, ny) = normalVector(wall)

            // Címke eltolása a falra merőlegesen
            val offsetDist = wall.thickness / 2f + 80f
            val labelX = midX + nx * offsetDist
            val labelY = midY + ny * offsetDist

            canvas.save()
            canvas.concat(matrix)
            canvas.translate(labelX, labelY)
            canvas.rotate(angle.toDegrees())
            paintText.color = Color.BLACK
            paintText.textAlign = Paint.Align.CENTER
            val fm = paintText.fontMetrics
            val textY = - (fm.ascent + fm.descent) / 2
            canvas.drawText(label, 0f, textY, paintText)
            canvas.restore()
        }

        // Windows and doors
        elements.forEach { elem ->
            val wall = walls.getOrNull(elem.parentWallIndex) ?: return@forEach
            val paint = if (elem.type == Tool.WINDOW) paintWindow else paintDoor

            val (psx, psy) = projectPointOnLine(elem.x1, elem.y1, wall.x1, wall.y1, wall.x2, wall.y2)
            val (pex, pey) = projectPointOnLine(elem.x2, elem.y2, wall.x1, wall.y1, wall.x2, wall.y2)
            val (nx, ny) = normalVector(wall)

            // FAL TELJES VASTAGSÁGA
            val fullThickness = wall.thickness.toFloat()
            val offsetX = nx * fullThickness
            val offsetY = ny * fullThickness

            // KÜLSŐ TÉGLALAP (teljes vastagság)
            val outerRect = Path().apply {
                moveTo(psx - offsetX, psy - offsetY)
                lineTo(psx + offsetX, psy + offsetY)
                lineTo(pex + offsetX, pey + offsetY)
                lineTo(pex - offsetX, pey - offsetY)
                close()
            }

            // BELSŐ TÉGLALAP (10 cm széles)
            val innerThickness = cmToPx(5f) // 10 cm átváltása pixelre
            val innerOffsetX = nx * innerThickness
            val innerOffsetY = ny * innerThickness

            val innerRect = Path().apply {
                moveTo(psx - innerOffsetX, psy - innerOffsetY)
                lineTo(psx + innerOffsetX, psy + innerOffsetY)
                lineTo(pex + innerOffsetX, pey + innerOffsetY)
                lineTo(pex - innerOffsetX, pey - innerOffsetY)
                close()
            }

            canvas.save()
            canvas.concat(matrix)

            // 1. Külső téglalap rajzolása (kék/szürke)
            paint.style = Paint.Style.FILL
            canvas.drawPath(outerRect, paint)

            // 2. Belső téglalap kerete (fekete sima vonal)
            val borderPaint = Paint().apply {
                style = Paint.Style.STROKE  // CSAK KERETET RAJZOLUNK
                color = Color.BLACK
                strokeWidth = 4f  // Keret vastagsága
                isAntiAlias = true  // Sima vonalhoz
            }
            canvas.drawPath(innerRect, borderPaint)  // Csak a keret

            canvas.restore()

            val emoji = if (elem.type == Tool.WINDOW) "\uD83E\uDE9F" else "\uD83D\uDEAA"
            val lengthCm = String.format("%.0f", pxToCm(distance(pex, pey, psx, psy)))

            val midX = (psx + pex) / 2f
            val midY = (psy + pey) / 2f
            val anglew = atan2(wall.y2 - wall.y1, wall.x2 - wall.x1)
            canvas.save()
            canvas.concat(matrix)
            canvas.translate(midX, midY)
            canvas.rotate(anglew.toDegrees())
            paintText.textAlign = Paint.Align.CENTER
            paintText.textSize = 64f
            paintText.color = if (elem.type == Tool.WINDOW) Color.BLUE else Color.DKGRAY
            val fm = paintText.fontMetrics
            canvas.drawText(emoji, 0f, fm.descent, paintText)
            paintText.textSize = 48f
            val lineH = fm.ascent
            canvas.drawText(lengthCm, 0f, lineH, paintText)
            canvas.restore()
        }

        // Drawing preview
        if (isDrawing) {
            val paint = when (currentTool) {
                Tool.WALL -> paintWall
                Tool.WINDOW -> paintWindow
                Tool.DOOR -> paintDoor
            }
            paint.strokeWidth = currentThickness.toFloat()
            canvas.save()
            canvas.concat(matrix)
            canvas.drawLine(startWX, startWY, lastWX, lastWY, paint)
            val lengthPx = distance(lastWX, lastWY, startWX, startWY)
            val lengthCm = pxToCm(lengthPx)

            // For windows/doors, show snapped size in preview
            val displayLength = when (currentTool) {
                Tool.WINDOW, Tool.DOOR -> {
                    if (lengthCm >= MIN_ELEMENT_SIZE_CM) snapTo5cm(lengthCm) else lengthCm
                }
                else -> lengthCm
            }
            val label = if (displayLength >= 100) {
                "%.2f m".format(displayLength / 100).replace('.', ',')
            } else {
                "${displayLength.toInt()} cm"
            }
            canvas.restore()

            val textW = overlayText.measureText(label)
            val paddingW = overlayPadding
            val rectLeft = (width - textW) / 2 - paddingW
            val rectTop = paddingW + 30f
            val rectRight = (width + textW) / 2 + paddingW
            val rectBottom = rectTop + overlayText.textSize + paddingW

            canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, overlayBg)
            val textX = rectLeft + overlayText.textSize
            val textY = rectTop + overlayText.textSize
            canvas.drawText(label, textX, textY, overlayText)
        }

        // Edit markers
        if (currentMode == Mode.EDIT) {
            canvas.save()
            canvas.concat(matrix)

            hoveredElement?.let { elem ->
                paintHighlight.color = Color.RED
                canvas.drawCircle(elem.x1, elem.y1, 20f, paintHandle)
                canvas.drawCircle(elem.x2, elem.y2, 20f, paintHandle)
                canvas.drawLine(elem.x1, elem.y1, elem.x2, elem.y2, paintHighlight)
            }

            hoveredWall?.let { wall ->
                paintHighlight.color = Color.BLUE
                canvas.drawCircle(wall.x1, wall.y1, 20f, paintHandle)
                canvas.drawCircle(wall.x2, wall.y2, 20f, paintHandle)
                canvas.drawLine(wall.x1, wall.y1, wall.x2, wall.y2, paintHighlight)
            }

            // Draw corner points
            cornerPoints.forEach { corner ->
                paintCorner.color = if (corner == movingCorner) Color.YELLOW else Color.GREEN
                canvas.drawCircle(corner.x, corner.y, 25f, paintCorner)
            }

            canvas.restore()

            // Trash icon
            val iconSize = 140f
            val margin = 50f
            val left = width - iconSize - margin
            val top = margin
            val right = width - margin
            val bottom = top + iconSize
            trashRect.set(left, top, right, bottom)
            canvas.drawRoundRect(trashRect, 10f, 10f, trashPaint)
            paintText.color = Color.WHITE
            paintText.textAlign = Paint.Align.CENTER
            canvas.drawText("🗑", trashRect.centerX(), trashRect.centerY() + 10f, paintText)

            if (hossz > 0) {
                val label = if (hossz >= 100) {
                    "%.2f m".format(hossz / 100).replace('.', ',')
                } else {
                    "%.0f cm".format(hossz)
                }
                val textW = overlayText.measureText(label)
                val paddingW = overlayPadding
                val rectLeft = (width - textW) / 2 - paddingW
                val rectTop = paddingW + 30f
                val rectRight = (width + textW) / 2 + paddingW
                val rectBottom = rectTop + overlayText.textSize + paddingW

                canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, overlayBg)

                // Középre igazított szöveg
                val textX = rectLeft + overlayText.textSize
                val textY = rectTop + overlayText.textSize
                canvas.drawText(label, textX, textY, overlayText)
            }
        }

        // Drag icon
        dragIcon?.let { icon ->
            canvas.drawText(icon, dragIconX, dragIconY, dragIconPaint)
        }
    }

    // ---------------------------------------
    // TOUCH HANDLING WITH IMPROVED WINDOW/DOOR MOVEMENT
    // ---------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount

        if (pointerCount > 1) {
            handleTwoFinger(event)
            return true
        }

        val (vx, vy) = Pair(event.x, event.y)
        val (wx, wy) = viewToWorld(vx, vy)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                when (currentMode) {
                    Mode.DRAW_WALL -> {
                        var sx = wx
                        var sy = wy
                        var snapped = false

                        // 1. Snap to existing walls (ELŐSZÖR a falakra illesztünk!)
                        val (snappedToWallX, snappedToWallY) = snapToWall(wx, wy)
                        if (distance(wx, wy, snappedToWallX, snappedToWallY) < cmToPx(50f)) {
                            sx = snappedToWallX
                            sy = snappedToWallY
                            snapped = true
                        }

                        // 2. Snap to existing corners (másodikként a sarkokra)
                        if (!snapped) {
                            val (snappedToCornerX, snappedToCornerY) = snapToCorner(wx, wy)
                            if (distance(wx, wy, snappedToCornerX, snappedToCornerY) < cmToPx(50f)) {
                                sx = snappedToCornerX
                                sy = snappedToCornerY
                                snapped = true
                            }
                        }

                        // 3. Grid snap (végül a rácsra)
                        if (!snapped) {
                            sx = snapToGrid(wx)
                            sy = snapToGrid(wy)
                        }

                        startWX = sx
                        startWY = sy
                        lastWX = startWX
                        lastWY = startWY
                        isDrawing = true
                    }


                    Mode.PLACE_WINDOW, Mode.PLACE_DOOR -> {
                        // Snap to 5cm grid
                        val sx = snapPxTo5cmGrid(wx)
                        val sy = snapPxTo5cmGrid(wy)
                        startWX = sx
                        startWY = sy
                        lastWX = startWX
                        lastWY = startWY
                        isDrawing = true
                    }

                    Mode.EDIT -> {
                        movingElement = null
                        editingElementEnd = -1
                        movingWall = null
                        editingWallEnd = -1
                        movingCorner = null
                        dragIcon = null
                        isDragging = false

                        // 1. Check if corner point was touched
                        movingCorner = findCornerNearPoint(wx, wy)
                        if (movingCorner != null) {
                            lastTouchX = wx
                            lastTouchY = wy
                            invalidate()
                            return true
                        }
                        // 2. Check if element was touched
                        else {
                            val elemFound = findElementNearPoint(wx, wy)
                            if (elemFound != null) {
                                movingElement = elemFound
                                lastTouchX = wx
                                lastTouchY = wy
                                hoveredElement = elemFound
                                hoveredWall = null

                                // Calculate distance to center
                                val centerX = (elemFound.x1 + elemFound.x2) / 2
                                val centerY = (elemFound.y1 + elemFound.y2) / 2
                                val distToCenter = distance(wx, wy, centerX, centerY)

                                if (distToCenter < editTolerance) {
                                    // Moving entire element
                                    editingElementEnd = -1
                                    dragIcon = if (elemFound.type == Tool.WINDOW) "\uD83E\uDE9F" else "\uD83D\uDEAA"
                                    dragIconX = event.x
                                    dragIconY = event.y
                                    isDragging = true
                                } else {
                                    // Moving endpoint
                                    val distToStart = distance(wx, wy, elemFound.x1, elemFound.y1)
                                    val distToEnd = distance(wx, wy, elemFound.x2, elemFound.y2)
                                    editingElementEnd = if (distToStart < distToEnd) 1 else 2
                                }
                            }
                            // 3. Check if wall was touched
                            val wallFound = findWallNearPoint(wx, wy)
                            if (wallFound != null) {
                                // Középpont detektálása elsőbbséggel
                                val centerDist = distanceToWallCenter(wallFound, wx, wy)
                                val startDist = distance(wx, wy, wallFound.x1, wallFound.y1)
                                val endDist = distance(wx, wy, wallFound.x2, wallFound.y2)

                                if (centerDist < editTolerance && centerDist < startDist && centerDist < endDist) {
                                    // Középpont mozgatása
                                    movingWall = wallFound
                                    hoveredWall = wallFound
                                    editingWallEnd = -1
                                }
                                else if (startDist < editTolerance && startDist < endDist) {
                                    // Kezdőpont mozgatása
                                    movingWall = wallFound
                                    hoveredWall = wallFound
                                    editingWallEnd = 1
                                }
                                else if (endDist < editTolerance) {
                                    // Végpont mozgatása
                                    movingWall = wallFound
                                    hoveredWall = wallFound
                                    editingWallEnd = 2
                                }

                                lastTouchX = wx
                                lastTouchY = wy
                                invalidate()
                                return true
                            }

                            // Ha nem találtunk semmit, töröljük a hover állapotot
                            hoveredElement = null
                            hoveredWall = null
                            invalidate()
                        }
                    }
                    else -> {}
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (currentMode) {
                    Mode.DRAW_WALL -> if (isDrawing) {
                        var ex = wx
                        var ey = wy
                        var snapped = false

                        // 1. Snap to existing walls (ELŐSZÖR a falakra illesztünk!)
                        val (snappedToWallX, snappedToWallY) = snapToWall(wx, wy)
                        if (distance(wx, wy, snappedToWallX, snappedToWallY) < cmToPx(50f)) {
                            ex = snappedToWallX
                            ey = snappedToWallY
                            snapped = true
                        }

                        // 2. Snap to existing corners (másodikként a sarkokra)
                        if (!snapped) {
                            val (snappedToCornerX, snappedToCornerY) = snapToCorner(wx, wy)
                            if (distance(wx, wy, snappedToCornerX, snappedToCornerY) < cmToPx(50f)) {
                                ex = snappedToCornerX
                                ey = snappedToCornerY
                                snapped = true
                            }
                        }

                        // 3. Grid snap (végül a rácsra)
                        if (!snapped) {
                            ex = snapToGrid(wx)
                            ey = snapToGrid(wy)
                        }

                        lastWX = ex
                        lastWY = ey
                        invalidate()
                    }

                    Mode.PLACE_WINDOW, Mode.PLACE_DOOR -> if (isDrawing) {
                        // Snap to 5cm grid
                        lastWX = snapPxTo5cmGrid(wx)
                        lastWY = snapPxTo5cmGrid(wy)
                        invalidate()
                    }

                    Mode.EDIT -> {
                        val dx = wx - lastTouchX
                        val dy = wy - lastTouchY

                        // Egész fal mozgatása (ha a közepénél fogtuk meg)
                        if (movingWall != null && editingWallEnd == -1) {
                            movingWall!!.x1 += dx
                            movingWall!!.y1 += dy
                            movingWall!!.x2 += dx
                            movingWall!!.y2 += dy
                            hossz = pxToCm(distance(movingWall!!.x1, movingWall!!.y1, movingWall!!.x2, movingWall!!.y2))
                        }
                        // Sarokpont mozgatása
                        else if (movingCorner != null) {
                            movingCorner!!.x += dx
                            movingCorner!!.y += dy

                            // Update all connected walls
                            movingCorner!!.connectedWalls.forEach { wall ->
                                if (distance(wall.x1, wall.y1, movingCorner!!.x - dx, movingCorner!!.y - dy) < editTolerance) {
                                    wall.x1 = movingCorner!!.x
                                    wall.y1 = movingCorner!!.y
                                }
                                if (distance(wall.x2, wall.y2, movingCorner!!.x - dx, movingCorner!!.y - dy) < editTolerance) {
                                    wall.x2 = movingCorner!!.x
                                    wall.y2 = movingCorner!!.y
                                }
                            }

                            // Frissítsük a hosszt az első kapcsolt falra
                            movingCorner!!.connectedWalls.firstOrNull()?.let { wall ->
                                hossz = pxToCm(distance(wall.x1, wall.y1, wall.x2, wall.y2))
                            }
                        }
                        // Fal mozgatása
                        else if (movingWall != null) {
                            when {
                                editingWallEnd == 1 -> {
                                    movingWall!!.x1 += dx
                                    movingWall!!.y1 += dy
                                    hossz = pxToCm(distance(movingWall!!.x1, movingWall!!.y1, movingWall!!.x2, movingWall!!.y2))
                                }
                                editingWallEnd == 2 -> {
                                    movingWall!!.x2 += dx
                                    movingWall!!.y2 += dy
                                    hossz = pxToCm(distance(movingWall!!.x1, movingWall!!.y1, movingWall!!.x2, movingWall!!.y2))
                                }
                                else -> {
                                    movingWall!!.x1 += dx
                                    movingWall!!.y1 += dy
                                    movingWall!!.x2 += dx
                                    movingWall!!.y2 += dy
                                    hossz = pxToCm(distance(movingWall!!.x1, movingWall!!.y1, movingWall!!.x2, movingWall!!.y2))
                                }

                            }
                        }
                        // Elem mozgatása
                        else if (movingElement != null) {
                            movingElement!!.let { elem ->
                                val wall = walls.getOrNull(elem.parentWallIndex)
                                if (wall != null) {
                                    when (editingElementEnd) {
                                        -1 -> {
                                            // Move entire element
                                            val centerX = (elem.x1 + elem.x2) / 2
                                            val centerY = (elem.y1 + elem.y2) / 2

                                            // Calculate new center position
                                            val newCenterX = centerX + dx
                                            val newCenterY = centerY + dy

                                            // Project new center to wall
                                            val (projCenterX, projCenterY) = projectPointOnLine(
                                                newCenterX, newCenterY,
                                                wall.x1, wall.y1, wall.x2, wall.y2
                                            )

                                            // SNAP TO 5CM GRID
                                            val snappedCenterX = snapPxTo5cmGrid(projCenterX)
                                            val snappedCenterY = snapPxTo5cmGrid(projCenterY)

                                            // Calculate element vector
                                            val elemDx = elem.x2 - elem.x1
                                            val elemDy = elem.y2 - elem.y1
                                            val elemLength = distance(elem.x1, elem.y1, elem.x2, elem.y2)
                                            val elemAngle = atan2(elemDy, elemDx)

                                            // Calculate new positions from projected center
                                            elem.x1 = snappedCenterX - cos(elemAngle) * elemLength / 2
                                            elem.y1 = snappedCenterY - sin(elemAngle) * elemLength / 2
                                            elem.x2 = snappedCenterX + cos(elemAngle) * elemLength / 2
                                            elem.y2 = snappedCenterY + sin(elemAngle) * elemLength / 2
                                        }
                                        1 -> {
                                            // Move start point
                                            elem.x1 += dx; elem.y1 += dy
                                            val (projX, projY) = projectPointOnLine(elem.x1, elem.y1, wall.x1, wall.y1, wall.x2, wall.y2)

                                            // SNAP TO 5CM GRID
                                            elem.x1 = snapPxTo5cmGrid(projX)
                                            elem.y1 = snapPxTo5cmGrid(projY)
                                        }
                                        2 -> {
                                            // Move end point
                                            elem.x2 += dx; elem.y2 += dy
                                            val (projX, projY) = projectPointOnLine(elem.x2, elem.y2, wall.x1, wall.y1, wall.x2, wall.y2)

                                            // SNAP TO 5CM GRID
                                            elem.x2 = snapPxTo5cmGrid(projX)
                                            elem.y2 = snapPxTo5cmGrid(projY)
                                        }
                                    }
                                    // Update length label
                                    hossz = pxToCm(distance(elem.x1, elem.y1, elem.x2, elem.y2))
                                }
                            }

                        }

                        lastTouchX = wx
                        lastTouchY = wy
                        saveToPrefs()
                        invalidate()

                        if (isDragging) {
                            dragIconX = event.x
                            dragIconY = event.y
                            invalidate()
                        }
                    }
                    else -> {}
                }
            }

            MotionEvent.ACTION_UP -> {
                when (currentMode) {
                    Mode.DRAW_WALL -> if (isDrawing) {
                        var ex = lastWX
                        var ey = lastWY

                        if (distance(startWX, startWY, ex, ey) > 40f) {
                            val newWall = createWallAlongGrid(startWX, startWY, ex, ey)
                            walls.add(newWall)
                            analyzeWalls()
                            saveToPrefs()
                        }

                        isDrawing = false
                        invalidate()
                    }

                    Mode.PLACE_WINDOW, Mode.PLACE_DOOR -> if (isDrawing) {
                        val wallIndex = findWallForElement(startWX to startWY, lastWX to lastWY)
                        if (wallIndex >= 0) {
                            val wall = walls[wallIndex]
                            val (psx, psy) = projectPointOnLine(startWX, startWY, wall.x1, wall.y1, wall.x2, wall.y2)
                            val (pex, pey) = projectPointOnLine(lastWX, lastWY, wall.x1, wall.y1, wall.x2, wall.y2)

                            // SNAP TO 5CM GRID
                            val snappedPsx = snapPxTo5cmGrid(psx)
                            val snappedPsy = snapPxTo5cmGrid(psy)
                            val snappedPex = snapPxTo5cmGrid(pex)
                            val snappedPey = snapPxTo5cmGrid(pey)

                            val lengthPx = distance(snappedPex, snappedPey, snappedPsx, snappedPsy)
                            val lengthCm = pxToCm(lengthPx)

                            if (lengthCm >= MIN_ELEMENT_SIZE_CM) {
                                elements.add(
                                    WindowOrDoor(snappedPsx, snappedPsy, snappedPex, snappedPey, currentTool, wallIndex)
                                )
                                saveToPrefs()
                            }
                        }
                        isDrawing = false
                        invalidate()
                    }

                    Mode.EDIT -> {
                        if (trashRect.contains(event.x, event.y)) {
                            if (movingElement != null) {
                                elements.remove(movingElement)
                                saveToPrefs()
                                movingElement = null
                                invalidate()
                            } else if (movingWall != null) {
                                val wallIndex = walls.indexOf(movingWall)
                                if (wallIndex >= 0) {
                                    // Töröljük a falhoz tartozó ablakokat/ajtókat
                                    elements.removeAll { it.parentWallIndex == wallIndex }

                                    // Frissítjük a többi elem referenciáit
                                    elements.forEach {
                                        if (it.parentWallIndex > wallIndex) {
                                            it.parentWallIndex -= 1
                                        }
                                    }

                                    walls.removeAt(wallIndex)
                                    saveToPrefs()
                                    movingWall = null
                                    invalidate()
                                }
                            } else if (movingCorner != null) {
                                // Delete corner and connected walls
                                val connectedWalls = movingCorner!!.connectedWalls.toList()
                                connectedWalls.forEach { wall ->
                                    walls.remove(wall)
                                }
                                cornerPoints.remove(movingCorner)
                                movingCorner = null
                                saveToPrefs()
                                invalidate()
                            }
                        }

                        // Re-analyze walls after movement
                        if (movingCorner != null || movingWall != null) {
                            analyzeWalls()
                        }

                        hoveredElement = null
                        hoveredWall = null
                        movingWall = null
                        movingElement = null
                        movingCorner = null
                        hossz = 0f
                        dragIcon = null
                        isDragging = false
                        invalidate()
                    }

                    else -> {}
                }
            }
        }
        return true
    }

    private fun handleTwoFinger(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    isDrawing = false
                    isTwoFinger = true
                    prevMidX = (event.getX(0) + event.getX(1)) / 2
                    prevMidY = (event.getY(0) + event.getY(1)) / 2
                    prevPinchDist = spacing(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 2 && isTwoFinger) {
                    val newMidX = (event.getX(0) + event.getX(1)) / 2
                    val newMidY = (event.getY(0) + event.getY(1)) / 2
                    val dx = newMidX - prevMidX
                    val dy = newMidY - prevMidY
                    matrix.postTranslate(dx, dy)
                    prevMidX = newMidX
                    prevMidY = newMidY

                    val newDist = spacing(event)
                    if (prevPinchDist > 10f) {
                        val scale = newDist / prevPinchDist
                        matrix.postScale(scale, scale, newMidX, newMidY)
                        prevPinchDist = newDist
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTwoFinger = false
            }
        }
    }

    // ---------------------------------------
    // PUBLIC METHODS
    // ---------------------------------------

    fun setTool(tool: Tool) {
        currentTool = tool
        currentMode = when (tool) {
            Tool.WALL -> Mode.DRAW_WALL
            Tool.WINDOW -> Mode.PLACE_WINDOW
            Tool.DOOR -> Mode.PLACE_DOOR
        }
        movingWall = null
        editingWallEnd = -1
        hoveredElement = null
        hoveredWall = null
        dragIcon = null
        isDragging = false
        movingCorner = null
        invalidate()
    }

    fun toggleEditMode() {
        if (currentMode == Mode.EDIT) {
            currentMode = Mode.NONE
            movingWall = null
            editingWallEnd = -1
            hoveredElement = null
            hoveredWall = null
            dragIcon = null
            isDragging = false
            movingCorner = null
        } else {
            currentMode = Mode.EDIT
        }
        invalidate()
    }

    fun clearAll() {
        walls.clear()
        elements.clear()
        cornerPoints.clear()
        isClosedShape = false
        currentMode = Mode.NONE
        movingWall = null
        editingWallEnd = -1
        hoveredElement = null
        hoveredWall = null
        dragIcon = null
        isDragging = false
        movingCorner = null
        saveToPrefs()
        invalidate()
    }
}
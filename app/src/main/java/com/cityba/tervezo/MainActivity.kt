package com.cityba.tervezo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
class MainActivity : AppCompatActivity() {

    private lateinit var toolButtons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val view = findViewById<FloorPlanView>(R.id.floorPlanView)

        // Gombok kilistázása
        val btnMainWall = findViewById<Button>(R.id.btnMainWall)
        val btnPartition = findViewById<Button>(R.id.btnPartition)
        val btnWindow = findViewById<Button>(R.id.btnWindow)
        val btnDoor = findViewById<Button>(R.id.btnDoor)
        val btnEdit = findViewById<Button>(R.id.btnEdit)
        val btnClear = findViewById<Button>(R.id.btnClear)
        val btnExport = findViewById<Button>(R.id.btnExport)
        val bottomBar = findViewById<View>(R.id.bottomBar)
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            v.setPadding(
                v.paddingLeft,
                v.paddingTop,
                v.paddingRight,
                insets.systemWindowInsetBottom
            )
            insets
        }
        // Segédlista
        toolButtons = listOf(btnMainWall, btnPartition, btnWindow, btnDoor, btnEdit, btnClear, btnExport)

        // Események
        btnMainWall.setOnClickListener {
            view.setWallThickness(30)
            view.setTool(FloorPlanView.Tool.WALL)
            selectTool(btnMainWall)
        }

        btnPartition.setOnClickListener {
            view.setWallThickness(10)
            view.setTool(FloorPlanView.Tool.WALL)
            selectTool(btnPartition)
        }

        btnWindow.setOnClickListener {
            view.setTool(FloorPlanView.Tool.WINDOW)
            selectTool(btnWindow)
        }

        btnDoor.setOnClickListener {
            view.setTool(FloorPlanView.Tool.DOOR)
            selectTool(btnDoor)
        }

        btnExport.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Export formátum")
                .setItems(arrayOf("FML (Floorplanner)", "JPEG (Kép)")) { _, which ->
                    when (which) {
                        0 -> { // FML
                            promptUserForFilenamePrefix { prefix ->
                                exportFmlToDocuments(this, view, view.walls, view.elements, prefix)
                            }
                        }
                        1 -> { // JPEG
                            promptUserForFilenamePrefix { prefix ->
                                exportJpeg(this, view, prefix)
                            }
                        }
                    }
                }
                .show()
            selectTool(btnExport)
        }

        btnClear.setOnClickListener {
            view.clearAll()
            selectTool(btnClear)
        }

        btnEdit.setOnClickListener {
            view.toggleEditMode()
            selectTool(btnEdit)
        }
    }

    private fun selectTool(button: Button) {
        toolButtons.forEach { it.isSelected = false }
        button.isSelected = true
    }


    private fun promptUserForFilenamePrefix(onResult: (String) -> Unit) {
        val editText = EditText(this).apply {
            hint = "Írd be a fájl nevét"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Mentés fájlba")
            .setMessage("Fájl neve:")
            .setView(editText)
            .setPositiveButton("OK") { dialog, _ ->
                val userInput = editText.text.toString().trim()
                onResult(userInput)
                dialog.dismiss()
            }
            .setNegativeButton("Mégse") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
    private fun exportJpeg(context: Context, view: FloorPlanView, prefix: String) {
        try {
            // 1) Készítsünk bitképet a nézetből
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            view.draw(canvas)

            // 2) Mentsük a Dokumentumok mappába
            val documentsDir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                .apply { if (!exists()) mkdirs() }

            val filename = if (prefix.isNotEmpty()) "${prefix}_plan.jpg" else "plan.jpg"
            val outFile = File(documentsDir, filename)

            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }

            Toast.makeText(context, "JPEG exportálva: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Hiba JPEG exportálásakor: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    /**
     * Most a FloorPlanView pxToCm-jét használjuk, mert az
     * pontosan azt a konverziót alkalmazza, amivel a View-ban dolgozunk.
     */
    private fun exportFmlToDocuments(
        context: Context,
        view: FloorPlanView,
        walls: List<FloorPlanView.Wall>,
        elements: List<FloorPlanView.WindowOrDoor>,
        prefix: String
    ) {
        try {
            val projectJson = JSONObject().apply {
                put("name", "Projekt")
                put("public", false)

                val floorsArray = JSONArray()
                val floorObj = JSONObject().apply {
                    put("name", "Ground Floor")
                    put("level", 0)
                    put("height", 300) // mintapélda: fal magassága 250 cm

                    val designsArray = JSONArray()
                    val designObj = JSONObject().apply {
                        put("name", "Design 1")

                        val wallsArray = JSONArray()
                        for (i in walls.indices) {
                            val wall = walls[i]

                            // Falvégek cm-ben a View pxToCm-metódusával
                            val aXcm = view.pxToCm(wall.x1).toInt()
                            val aYcm = view.pxToCm(wall.y1).toInt()
                            val bXcm = view.pxToCm(wall.x2).toInt()
                            val bYcm = view.pxToCm(wall.y2).toInt()

                            // Fal hossz cm-ben
                            val dxPx = wall.x2 - wall.x1
                            val dyPx = wall.y2 - wall.y1
                            val wallLengthCm = view.pxToCm(hypot(dxPx, dyPx))

                            // Nyílások (openings) a falhoz
                            val openingsArray = JSONArray()
                            elements.filter { it.parentWallIndex == i }
                                .forEach { item ->
                                    val isDoor = (item.type == FloorPlanView.Tool.DOOR)

                                    // Nyílás középpontja px-ben
                                    val centerXpx = (item.x1 + item.x2) / 2f
                                    val centerYpx = (item.y1 + item.y2) / 2f

                                    // Vetítés a falra → t paraméter (0..1)
                                    val dxWall = wall.x2 - wall.x1
                                    val dyWall = wall.y2 - wall.y1
                                    val denom = dxWall * dxWall + dyWall * dyWall
                                    val tRaw = if (denom == 0f) 0f
                                    else ((centerXpx - wall.x1) * dxWall + (centerYpx - wall.y1) * dyWall) / denom
                                    val tClamped = tRaw.coerceIn(0f, 1f)

                                    // Nyílás szélessége cm-ben
                                    val rawWidthPx = hypot(item.x2 - item.x1, item.y2 - item.y1)
                                    val widthCm = view.pxToCm(rawWidthPx).toInt()

                                    val openingObj = JSONObject().apply {
                                        put("type", if (isDoor) "door" else "window")
                                        put("refid", if (isDoor) "e1826" else "b291deba1c7627783ff4b8cbb41cb11040687507")  //e919
                                        put("width", widthCm)
                                        put("t", tClamped)
                                        put("mirrored", JSONArray(listOf(0, 0))) // kötelező

                                        if (isDoor) {
                                            put("z", 0)                 // ajtó alja padlóról
                                            put("z_height", 220)       // mintapélda: ajtó magasság 210 cm
                                        } else {
                                            put("z", 70)              // mintapélda: ablak párkány 100 cm
                                            put("z_height", 150)       // ablak magasság 150 cm
                                        }
                                    }
                                    openingsArray.put(openingObj)
                                }

                            val wallObj = JSONObject().apply {
                                put("a", JSONObject().apply {
                                    put("x", aXcm)
                                    put("y", aYcm)
                                })
                                put("b", JSONObject().apply {
                                    put("x", bXcm)
                                    put("y", bYcm)
                                })
                                put("c", JSONObject.NULL)

                                put("az", JSONObject().apply {
                                    put("z", 0)
                                    put("h", 300)
                                })
                                put("bz", JSONObject().apply {
                                    put("z", 0)
                                    put("h", 300)
                                })

                                put("thickness", wall.thickness)
                                put("balance", 0.5)
                                put("openings", openingsArray)
                                put("decor", JSONObject().apply {
                                    put("left", JSONObject().apply {
                                        put("color", "#e3ddd1")
                                    })
                                    put("right", JSONObject().apply {
                                        put("color", "#ebdfc7")
                                    })
                                })

                            }
                            wallsArray.put(wallObj)
                        }

                        put("walls", wallsArray)
                        put("areas", JSONArray())
                        put("surfaces", JSONArray())
                        put("dimensions", JSONArray())
                        put("items", JSONArray())
                        put("labels", JSONArray())
                        put("lines", JSONArray())
                    }

                    designsArray.put(designObj)
                    put("designs", designsArray)
                    put("cameras", JSONArray())
                }

                floorsArray.put(floorObj)
                put("floors", floorsArray)
            }

            val documentsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            if (!documentsDir.exists()) {
                documentsDir.mkdirs()
            }
            val filename = if (prefix.isNotEmpty()) {
                "${prefix}_terv.fml"
            } else {
                "terv.fml"
            }
            val outFile = File(documentsDir, filename)
            outFile.writeText(projectJson.toString(2), Charsets.UTF_8)

            Toast.makeText(
                context,
                "FML exportálva: ${outFile.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Hiba FML exportálásakor: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            e.printStackTrace()
        }
    }
}

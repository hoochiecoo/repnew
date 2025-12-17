package com.example.camera_starter

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.Executors
import java.nio.ByteBuffer

class MainActivity: FlutterActivity() {
    private val METHOD_CHANNEL = "com.example.camera/methods"
    private val EVENT_CHANNEL = "com.example.camera/events"
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var eventSink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "startCamera") {
                if (checkPermissions()) {
                    val tid = startCamera(flutterEngine)
                    result.success(tid)
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
                    result.error("PERM", "Permissions needed", null)
                }
            } else {
                result.notImplemented()
            }
        }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(args: Any?, events: EventChannel.EventSink?) { eventSink = events }
                override fun onCancel(args: Any?) { eventSink = null }
            }
        )
    }

    private fun startCamera(flutterEngine: FlutterEngine): Long {
        val textureEntry = flutterEngine.renderer.createSurfaceTexture()
        val surfaceTexture = textureEntry.surfaceTexture()
        surfaceTexture.setDefaultBufferSize(640, 480) 
        val textureId = textureEntry.id()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            
            preview.setSurfaceProvider { request ->
                val surface = Surface(surfaceTexture)
                request.provideSurface(surface, cameraExecutor) {}
            }

            // --- АНАЛИЗАТОР ИЗОБРАЖЕНИЯ (БЕЗОПАСНЫЙ ДЕТЕКТОР) ---
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            
            imageAnalyzer.setAnalyzer(cameraExecutor) { image ->
                try {
                    // 1. Получаем Y-плоскость (яркость/чб)
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val width = image.width
                    val height = image.height
                    
                    // 2. Сканируем только среднюю строку для производительности
                    // Это безопасно и не нагружает CPU
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val midY = height / 2
                    
                    // Позиция начала средней строки в буфере
                    val startOffset = midY * rowStride
                    
                    var currentRun = 0
                    var maxRun = 0
                    
                    // Проход по пикселям средней строки
                    if (buffer.capacity() > startOffset + width * pixelStride) {
                        for (x in 0 until width step 2) { // step 2 для ускорения
                            val index = startOffset + (x * pixelStride)
                            // Конвертируем byte в int (0..255)
                            val pixelVal = buffer.get(index).toInt() and 0xFF
                            
                            // Если пиксель яркий (> 150), считаем это частью линии
                            if (pixelVal > 150) {
                                currentRun++
                            } else {
                                if (currentRun > maxRun) maxRun = currentRun
                                currentRun = 0
                            }
                        }
                    }
                    if (currentRun > maxRun) maxRun = currentRun

                    // 3. Отправляем результат во Flutter
                    // Если линия длиннее 30% ширины, отправляем флаг detected = true
                    val threshold = (width / 2) * 0.3 
                    val detected = maxRun > threshold
                    
                    runOnUiThread { 
                        eventSink?.success(mapOf(
                            "detected" to detected,
                            "length" to maxRun,
                            "width" to width
                        )) 
                    }
                } catch (e: Exception) {
                    // Игнорируем ошибки кадра, чтобы не крашить приложение
                } finally {
                    image.close() // Обязательно закрываем!
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch(e: Exception) {}

        }, ContextCompat.getMainExecutor(this))

        return textureId
    }

    private fun checkPermissions() = ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}


package com.example.camera_starter

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.concurrent.Executors

class MainActivity: FlutterActivity() {

    private val METHOD = "com.example.camera/methods"
    private val EVENTS = "com.example.camera/events"
    private val executor = Executors.newSingleThreadExecutor()
    private var sink: EventChannel.EventSink? = null

    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)

        OpenCVLoader.initDebug()

        MethodChannel(engine.dartExecutor.binaryMessenger, METHOD)
            .setMethodCallHandler { call, res ->
                if(call.method=="startCamera"){
                    if(checkPerm()){
                        res.success(startCamera(engine))
                    } else {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA),100)
                        res.error("PERM","no camera",null)
                    }
                }
            }

        EventChannel(engine.dartExecutor.binaryMessenger, EVENTS)
            .setStreamHandler(object:EventChannel.StreamHandler{
                override fun onListen(a:Any?,e:EventChannel.EventSink?){sink=e}
                override fun onCancel(a:Any?){sink=null}
            })
    }

    private fun startCamera(engine:FlutterEngine):Long{
        val tex=engine.renderer.createSurfaceTexture()
        val st=tex.surfaceTexture()
        st.setDefaultBufferSize(640,480)
        val id=tex.id()

        val providerFuture=ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider=providerFuture.get()

            val preview=Preview.Builder().build()
            preview.setSurfaceProvider{
                it.provideSurface(Surface(st),executor){}
            }

            val analysis=ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(executor){img->
                try{
                    val y=img.planes[0]
                    val w=img.width
                    val h=img.height

                    val gray=Mat(h,w,CvType.CV_8UC1,y.buffer)
                    val row=Mat()
                    Core.reduce(gray,row,0,Core.REDUCE_AVG)

                    Imgproc.threshold(row,row,160.0,255.0,Imgproc.THRESH_BINARY)
                    val count=Core.countNonZero(row)

                    val detected = count > w*0.3

                    runOnUiThread{
                        sink?.success(mapOf(
                            "detected" to detected,
                            "length" to count,
                            "width" to w
                        ))
                    }
                } catch(e:Exception){}
                finally{img.close()}
            }

            provider.unbindAll()
            provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,preview,analysis)
        },ContextCompat.getMainExecutor(this))

        return id
    }

    private fun checkPerm()=
        ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
}

package com.laquysoft.polyarcore

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.design.widget.BaseTransientBottomBar
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.laquysoft.polyarcore.api.PolyService
import com.laquysoft.polyarcore.model.AssetModel
import com.laquysoft.polyarcore.model.FormatModel
import com.laquysoft.polyarcore.rendering.BackgroundRenderer
import com.laquysoft.polyarcore.rendering.ObjectRenderer
import com.laquysoft.polyarcore.rendering.PlaneRenderer
import com.laquysoft.polyarcore.rendering.PointCloudRenderer
import com.laquysoft.polyarcore.utils.AsyncFileDownloader
import com.laquysoft.polyarcore.utils.CameraPermissionHelper
import com.laquysoft.polyarcore.utils.DisplayRotationHelper
import dagger.android.AndroidInjection
import kotlinx.android.synthetic.main.activity_main.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity() {

    @Inject lateinit var api: PolyService

    lateinit var call: Call<AssetModel>

    // The AsyncFileDownloader responsible for downloading a set of data files from Poly.
    lateinit var fileDownloader: AsyncFileDownloader
    // Our background thread, which does all of the heavy lifting so we don't block the main thread.
    lateinit var backgroundThread: HandlerThread
    // Handler for the background thread, to which we post background thread tasks.
    lateinit var backgroundThreadHandler: Handler

    lateinit var session: Session

    private var virtualObject: ObjectRenderer? = null

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val anchorMatrix = FloatArray(16)

    // Tap handling and UI.
    private val queuedSingleTaps = ArrayBlockingQueue<MotionEvent>(16)
    private val anchors = ArrayList<Anchor>()

    lateinit var gestureDetector: GestureDetector

    @Volatile
    private var readyToImport: Boolean = false

    lateinit var messageSnackbar: Snackbar
    lateinit var displayRotationHelper: DisplayRotationHelper


    // Scale factor to apply to asset when displaying.
    private val ASSET_SCALE = 0.2f

    private val ASSET_ID = "6b7Ul6MeLrJ"
    private val API_KEY = "AIzaSyDIOvdvJ9uMXW22jp1LN3MihWYCZ8iAt4o"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AndroidInjection.inject(this)


        // Set up tap listener.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onSingleTap(e)
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })

        surfaceview.setOnTouchListener(View.OnTouchListener { v, event -> gestureDetector.onTouchEvent(event) })

          var exception: Exception? = null
        var message: String? = null
        try {
            session = Session(this)
        } catch (e: UnavailableArcoreNotInstalledException) {
            message = "Please install ARCore"
            exception = e
        } catch (e: UnavailableApkTooOldException) {
            message = "Please update ARCore"
            exception = e
        } catch (e: UnavailableSdkTooOldException) {
            message = "Please update this app"
            exception = e
        } catch (e: Exception) {
            message = "This device does not support AR"
            exception = e
        }

        if (message != null) {
            showSnackbarMessage(message, true)
            Log.e(TAG, "Exception creating session", exception)
            return
        }

        displayRotationHelper = DisplayRotationHelper(this)
        // Create default config and check if supported.
        val config = Config(session)
        if (!session.isSupported(config)) {
            showSnackbarMessage("This device does not support AR", true)
        }
        session.configure(config)

        fileDownloader = AsyncFileDownloader()

        // Set up renderer.
        surfaceview.setPreserveEGLContextOnPause(true)
        surfaceview.setEGLContextClientVersion(2)
        surfaceview.setEGLConfigChooser(8, 8, 8, 8, 16, 0) // Alpha used for plane blending.

        surfaceview.setRenderer(ARRenderer(this, BackgroundRenderer(), PlaneRenderer(),
                PointCloudRenderer(), session, displayRotationHelper, fileDownloader))
        surfaceview.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY)



        backgroundThread = HandlerThread("Worker")
        backgroundThread.start()
        backgroundThreadHandler = Handler(backgroundThread.getLooper())

        call = api.getAsset(ASSET_ID, API_KEY)

        call.enqueue(object : Callback<AssetModel> {
            override fun onFailure(call: Call<AssetModel>?, t: Throwable?) {
                Log.e("MainActivity", "Error getting data " + t.toString())
            }

            override fun onResponse(call: Call<AssetModel>?, response: Response<AssetModel>?) {

                if (response != null && response.isSuccessful) {
                    Log.d("MainActivity", "Success " + response.body()!!.displayName)
                    response.body()!!.formats.forEach { format -> requestDataFiles(format) }
                } else {
                    Log.d("MainActivity", "Error getting data ")
                }

            }
        })
    }


    override fun onResume() {
        super.onResume()

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (CameraPermissionHelper.hasCameraPermission(this)) {
            showLoadingMessage()
            // Note that order matters - see the note in onPause(), the reverse applies here.
            session.resume()
            surfaceview.onResume()
            displayRotationHelper.onResume()
        } else {
            CameraPermissionHelper.requestCameraPermission(this)
        }
    }

    override fun onPause() {
        super.onPause()
        // Note that the order matters - GLSurfaceView is paused first so that it does not try
        // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
        // still call mSession.update() and get a SessionPausedException.
        displayRotationHelper.onPause()
        surfaceview.onPause()
        session.pause()

    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Standard Android full-screen functionality.
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Requests the data files for the OBJ format.
    // NOTE: this runs on the background thread.
    private fun requestDataFiles(objFormat: FormatModel) {
        // objFormat has the list of data files for the OBJ format (OBJ file, MTL file, textures).
        // We will use a AsyncFileDownloader to download all those files.

        // The "root file" is the OBJ.
        val rootFile = objFormat.root
        if (rootFile.relativePath.toLowerCase().endsWith(".obj"))
        {
            fileDownloader.add(rootFile.relativePath, rootFile.url)

            // The "resource files" are the MTL file and textures.
            val resources = objFormat.resources
            resources.forEach { fileModel ->
                run {
                    val path = fileModel.relativePath
                    val url = fileModel.url
                    // For this example, we only care about OBJ and PNG files.
                    if (path.toLowerCase().endsWith(".obj") || path.toLowerCase().endsWith(".png")) {
                        fileDownloader.add(path, url)
                    }
                }
            }

            // Now start downloading the data files. When this is done, the callback will call
            // processDataFiles().
            Log.d(TAG, "Starting to download data files, # files: " + fileDownloader.entryCount)
            fileDownloader.start(backgroundThreadHandler, object : AsyncFileDownloader.CompletionListener {
                override fun onPolyDownloadFinished(downloader: AsyncFileDownloader) {
                    if (downloader.isError) {
                        Log.e(TAG, "Failed to download data files for asset.")
                        return
                    }
                    // Signal to the GL thread that download is complete, so it can go ahead and
                    // import the model.
                    Log.d(TAG, "Download complete, ready to import model. " + downloader.entryCount)
                    readyToImport = true
                }
            })
        }

    }

    companion object {
        private val TAG = "MainActivity"
    }


    inner class ARRenderer(val context: Context,
                           val backgroundRenderer: BackgroundRenderer,
                           val planeRenderer: PlaneRenderer,
                           var pointCloudRenderer: PointCloudRenderer,
                           var session: Session,
                           var displayRotationHelper: DisplayRotationHelper,
                           var downloader: AsyncFileDownloader) : GLSurfaceView.Renderer {

        override fun onDrawFrame(gl: GL10) {
            // Clear screen to notify driver it should not load any pixels from previous frame.
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            // If we are ready to import the object and haven't done so yet, do it now.
            if (readyToImport && virtualObject == null) {
                importDownloadedObject(downloader)
            } else {
                Log.d(TAG, "Count  " + readyToImport )

            }
                // Notify ARCore session that the view size changed so that the perspective matrix and
            // the video background can be properly adjusted.
            displayRotationHelper.updateSessionIfNeeded(session)

            try {
                // Obtain the current frame from ARSession. When the configuration is set to
                // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
                // camera framerate.
                val frame = session.update()
                val camera = frame.getCamera()

                // Handle taps. Handling only one tap per frame, as taps are usually low frequency
                // compared to frame rate.
                val tap = queuedSingleTaps.poll()
                if (tap != null && camera.getTrackingState() == Trackable.TrackingState.TRACKING) {
                    for (hit in frame.hitTest(tap!!)) {
                        // Check if any plane was hit, and if it was hit inside the plane polygon
                        val trackable = hit.getTrackable()
                        if (trackable is Plane && (trackable as Plane).isPoseInPolygon(hit.getHitPose())) {
                            // Cap the number of objects created. This avoids overloading both the
                            // rendering system and ARCore.
                            if (anchors.size >= 20) {
                                anchors.get(0).detach()
                                anchors.removeAt(0)
                            }
                            // Adding an Anchor tells ARCore that it should track this position in
                            // space. This anchor is created on the Plane to place the 3d model
                            // in the correct position relative both to the world and to the plane.
                            anchors.add(hit.createAnchor())

                            // Hits are sorted by depth. Consider only closest hit on a plane.
                            break
                        }
                    }
                }

                // Draw background.
               backgroundRenderer.draw(frame)

                // If not tracking, don't draw 3d objects.
                if (camera.getTrackingState() == Trackable.TrackingState.PAUSED) {
                    return
                }

                // Get projection matrix.
                val projmtx = FloatArray(16)
                camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

                // Get camera matrix and draw.
                val viewmtx = FloatArray(16)
                camera.getViewMatrix(viewmtx, 0)

                // Compute lighting from average intensity of the image.
                val lightIntensity = frame.getLightEstimate().getPixelIntensity()

                // Visualize tracked points.
                val pointCloud = frame.acquirePointCloud()
                pointCloudRenderer.update(pointCloud)
                pointCloudRenderer.draw(viewmtx, projmtx)

                // Application is responsible for releasing the point cloud resources after
                // using it.
                pointCloud.release()

                // Check if we detected at least one plane. If so, hide the loading message.
                for (plane in session.getAllTrackables(Plane::class.java)) {
                    if (plane.getType() == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING && plane.getTrackingState() == Trackable.TrackingState.TRACKING) {
                        hideLoadingMessage()
                        break
                    }
                }


                // Visualize planes.
                planeRenderer.drawPlanes(
                        session.getAllTrackables(Plane::class.java), camera.getDisplayOrientedPose(), projmtx)

                // Visualize anchors created by touch.
                val scaleFactor = 1.0f
                for (anchor in anchors) {
                    if (anchor.getTrackingState() != Trackable.TrackingState.TRACKING) {
                        continue
                    }
                    // Get the current pose of an Anchor in world space. The Anchor pose is updated
                    // during calls to session.update() as ARCore refines its estimate of the world.
                    anchor.getPose().toMatrix(anchorMatrix, 0)

                    if ( virtualObject != null ) {
                        // Update and draw the model and its shadow.
                        virtualObject!!.updateModelMatrix(anchorMatrix, ASSET_SCALE * scaleFactor)
                        virtualObject!!.draw(viewmtx, projmtx, lightIntensity)
                    }

                }

            } catch (t: Throwable) {
                // Avoid crashing the application due to unhandled exceptions.
                Log.e(TAG, "Exception on the OpenGL thread", t)
            }


        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)

            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(/*context=*/context)
            session.setCameraTextureName(backgroundRenderer.textureId)


            try {
                planeRenderer.createOnGlThread(/*context=*/context, "trigrid.png")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read plane texture")
            }

            pointCloudRenderer.createOnGlThread(/*context=*/context)

        }

        override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
            displayRotationHelper.onSurfaceChanged(width, height)
            GLES20.glViewport(0, 0, width, height)
        }
    }

    private fun showSnackbarMessage(message: String, finishOnDismiss: Boolean) {
        messageSnackbar = Snackbar.make(
                this@MainActivity.findViewById(android.R.id.content),
                message, Snackbar.LENGTH_INDEFINITE)
        messageSnackbar.getView().setBackgroundColor(-0x40cdcdce)
        if (finishOnDismiss) {
            messageSnackbar.setAction(
                    "Dismiss",
                    View.OnClickListener { messageSnackbar.dismiss() })
            messageSnackbar.addCallback(
                    object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                        override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                            super.onDismissed(transientBottomBar, event)
                            finish()
                        }
                    })
        }
        messageSnackbar.show()
    }

    private fun showLoadingMessage() {
        runOnUiThread { showSnackbarMessage("Searching for surfaces...", false) }
    }

    private fun hideLoadingMessage() {
        runOnUiThread {
            messageSnackbar.dismiss()
        }
    }

    private fun importDownloadedObject(downloader: AsyncFileDownloader) {
        Log.d(TAG, "importDownloadedObject" + downloader.entryCount)
        try {
            virtualObject = ObjectRenderer()

            var objBytes: ByteArray? = null
            var textureBytes: ByteArray? = null


            for (i in 0..downloader.entryCount-1) {
                val thisEntry = downloader.getEntry(i)
                Log.d(TAG, "entry " + thisEntry.fileName)
                if (thisEntry.fileName.toLowerCase().endsWith(".obj")) {
                    objBytes = thisEntry.contents
                } else if (thisEntry.fileName.toLowerCase().endsWith(".png")) {
                    textureBytes = thisEntry.contents
                }
            }

            if (objBytes == null || textureBytes == null) {
                Log.e(TAG, "Downloaded asset doesn't have OBJ data and a PNG texture.")
                return
            }
            Log.d(TAG, "Importing OBJ.")

            virtualObject!!.createOnGlThread(/*context=*/this, objBytes, textureBytes)
            virtualObject!!.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read or parse obj file")
        }

    }

    private fun onSingleTap(e: MotionEvent) {
        // Queue tap if there is space. Tap is lost if queue is full.
        queuedSingleTaps.offer(e)
    }


}

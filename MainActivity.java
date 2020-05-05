package org.onursert.cameraapi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    // Initialize ----------------------------------------------------------------------------------
    private Context context;

    // Capture button
    private Button captureButton;
    // Toggle between recording and preview
    private boolean isRecording = false;
    // Switch button
    private Button switchButton;
    // In case of device has 2 camera
    private int whichCam = 0;
    // Layout for both preview and capturing
    private FrameLayout preview;

    // Actual physical camera connection
    private Camera mCamera;
    // Camera preview before capturing process
    private CameraPreview mPreview;

    // Actual physical camera options
    private Camera.Parameters parameters;
    private List<Camera.Size> mSupportedPreviewSizes;
    private List<Camera.Size> mSupportedVideoSizes;
    private Camera.Size optimalSize;

    // Media recorder that records video
    private MediaRecorder mediaRecorder;

    // Output file
    private File mOutputFile;

    // Permissions
    private static final int MEDIA_RECORDER_REQUEST = 0;
    private final String[] requiredPermissions = {
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
    };

    // On Create Init On Resume starting life cycle ------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        setContentView(R.layout.activity_main);
        context = getApplicationContext();

        if (areCameraPermissionGranted()) {
            captureButton = (Button) findViewById(R.id.button_capture);
            captureButton.setOnClickListener(this);

            switchButton = (Button) findViewById(R.id.button_switch);
            if (Camera.getNumberOfCameras() == 2) {
                switchButton.setOnClickListener(this);
            } else {
                switchButton.setEnabled(false);
            }

            preview = (FrameLayout) findViewById(R.id.camera_preview);

            initPrev();
        } else {
            requestCameraPermissions();
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }
    private void initPrev() {
        // Create our Preview view and set it as the content of our activity.
        mPreview = new CameraPreview(this);
        try {
            preview.removeAllViews();
            preview.addView(mPreview);
        } catch (Exception e) {
            System.out.println("Surface texture is unavailable or unsuitable" + e.getMessage());
        }
    }

    // On Pause ------------------------------------------------------------------------------------
    @Override
    protected void onPause() {
        super.onPause();
        releaseMediaRecorder(); // if you are using MediaRecorder, release it first
        releaseCamera(); // Then release camera
        lastOnPause(); // Finally make everything default
    }
    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset(); // clear recorder configuration
            mediaRecorder.release(); // release the recorder object
            mediaRecorder = null;
            mCamera.lock(); // lock camera for later use
        }
    }
    private void releaseCamera() {
        if (mCamera != null) {
            // release the camera for other applications
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }
    private void lastOnPause() {
        captureButton.setText("Capture");
        isRecording = false;
    }

    // Preview -------------------------------------------------------------------------------------
    // A camera preview class
    public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {
        private SurfaceHolder mHolder;

        public CameraPreview(Context context) {
            super(context);

            // Install a SurfaceHolder.Callback so we get notified when the
            // underlying surface is created and destroyed.
            mHolder = getHolder();
            mHolder.addCallback(this);
            // deprecated setting, but required on Android versions prior to 3.0
            mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        public void surfaceCreated(SurfaceHolder holder) {
            initCamera(whichCam);
            // The Surface has been created, now tell the camera where to draw the preview.
            try {
                mCamera.setPreviewDisplay(holder);
                mCamera.startPreview();
            } catch (Exception e) {
                System.out.println("Error setting camera preview: " + e.getMessage());
            }
        }

        public void surfaceDestroyed(SurfaceHolder holder) {
            releaseMediaRecorder();
            releaseCamera();
            lastOnPause();
        }

        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            // If your preview can change or rotate, take care of those events here.
            // Make sure to stop the preview before resizing or reformatting it.

            if (mHolder.getSurface() == null) {
                // preview surface does not exist
                return;
            }

            // stop preview before making changes
            try {
                mCamera.stopPreview();
            } catch (Exception e) {
                // ignore: tried to stop a non-existent preview
            }

            setCameraOptions();

            // start preview with new settings
            try {
                mCamera.setPreviewDisplay(mHolder);
                mCamera.startPreview();
            } catch (Exception e) {
                System.out.println("Error starting camera preview: " + e.getMessage());
            }
        }
    }
    private void initCamera(int whichCam) {
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            // Create an instance of Camera
            mCamera = null;
            try {
                mCamera = Camera.open(whichCam); // open camera
            } catch (Exception e) {
                Toast.makeText(context, "Camera is not available", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(context, "This device has no camera", Toast.LENGTH_SHORT).show();
        }
    }
    private void setCameraOptions() {
        // We need to make sure that our preview and recording video size are supported by the
        // camera. Query camera to find all the sizes and choose the optimal size given the
        // dimensions of our preview surface.
        try {
            parameters = mCamera.getParameters();
            mSupportedPreviewSizes = parameters.getSupportedPreviewSizes();
            mSupportedVideoSizes = parameters.getSupportedVideoSizes();
            optimalSize = getOptimalVideoSize(mSupportedVideoSizes, mSupportedPreviewSizes, mPreview.getWidth(), mPreview.getHeight());
            parameters.setPreviewSize(optimalSize.width, optimalSize.height);

            if (parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);

            mCamera.setDisplayOrientation(getRotation());

            mCamera.setParameters(parameters);
        } catch (Exception e) {
            // try catch is good but toast doesn't neccessary
            // Toast.makeText(context, "Camera is still busy", Toast.LENGTH_SHORT).show();
        }
    }
    public int getRotation() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(whichCam, info);
        int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }

    // Video Capture -------------------------------------------------------------------------------
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.button_capture:
                startCapture();
                break;
            case R.id.button_switch:
                releaseMediaRecorder();
                releaseCamera();
                lastOnPause();
                whichCam = whichCam == 0 ? 1 : 0;
                initPrev();
                break;
        }
    }
    @SuppressLint("SourceLockedOrientationActivity")
    private void startCapture() {
        if (isRecording) {
            // unlock orientation
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            // stop recording and release camera
            try {
                mediaRecorder.stop();  // stop the recording
            } catch (RuntimeException e) {
                // RuntimeException is thrown when stop() is called immediately after start().
                // In this case the output file is not properly constructed ans should be deleted.
                System.out.println("RuntimeException: stop() is called immediately after start()");
                // noinspection ResultOfMethodCallIgnored
                mOutputFile.delete();
            }
            releaseMediaRecorder(); // release the MediaRecorder object

            // inform the user that recording has stopped
            lastOnPause();
        } else {
            // lock orientation
            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
            }
            // start recording
            new MediaPrepareTask().execute(null, null, null);
        }
    }
    // This consuming task is processed in background
    class MediaPrepareTask extends AsyncTask<Void, Void, Boolean> {
        @Override
        protected Boolean doInBackground(Void... voids) {
            // initialize video camera
            if (prepareVideoRecorder()) {
                // Camera is available and unlocked, MediaRecorder is prepared,
                // now you can start recording
                try {
                    mediaRecorder.start();  // stop the recording
                } catch (RuntimeException e) {
                    // RuntimeException is thrown when start() is called immediately after stop().
                    // In this case the output file is not properly constructed ans should be deleted.
                    System.out.println("RuntimeException: start() is called immediately after stop()");
                    //noinspection ResultOfMethodCallIgnored
                    mOutputFile.delete();
                }
                isRecording = true;
            } else {
                // prepare didn't work, release the camera
                releaseMediaRecorder();
                return false;
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (!result) {
                MainActivity.this.finish();
            }
            // inform the user that recording has started
            captureButton.setText("Stop");
        }
    }
    private boolean prepareVideoRecorder() {
        // Media Recorder initializes
        mediaRecorder = new MediaRecorder();

        // Step 1: Unlock and set camera to MediaRecorder
        mCamera.unlock();
        mediaRecorder.setCamera(mCamera);

        // Step 2: Set sources
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        if (whichCam == 0) {
            mediaRecorder.setOrientationHint(getRotation());
        } else {
            mediaRecorder.setOrientationHint((360 - getRotation()) % 360);
        }

        // Step 3: Set output format and encoding (for versions prior to API Level 8)
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.DEFAULT);

        try {
            mediaRecorder.setVideoEncodingBitRate(10000000);
            mediaRecorder.setVideoSize(optimalSize.width, optimalSize.height);
            mediaRecorder.setVideoFrameRate(30);

            mediaRecorder.setAudioEncodingBitRate(192000);
            mediaRecorder.setAudioChannels(1);
            mediaRecorder.setAudioSamplingRate(44100);
        } catch (Exception e) {
            System.out.println("Video or Audio options can not set");
        }

        // Step 4: Set output file
        mOutputFile = getOutputMediaFile(2);
        mediaRecorder.setOutputFile(mOutputFile.getPath());

        // Step 5: Set the preview output
        mediaRecorder.setPreviewDisplay(mPreview.getHolder().getSurface());

        // Step 6: Prepare configured MediaRecorder
        try {
            mediaRecorder.prepare();
        } catch (IllegalStateException e) {
            System.out.println("IllegalStateException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        } catch (IOException e) {
            System.out.println("IOException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        }
        return true;
    }
    // Find optimal size preview and capturing
    // Some device's screen has more resolution than its camera. In that case preview can shown but record gives an error
    public static Camera.Size getOptimalVideoSize(List<Camera.Size> supportedVideoSizes, List<Camera.Size> previewSizes, int w, int h) {
        // Use a very small tolerance because we want an exact match.
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) w / h;

        // Supported video sizes list might be null, it means that we are allowed to use the preview
        // sizes
        List<Camera.Size> videoSizes;
        if (supportedVideoSizes != null) {
            videoSizes = supportedVideoSizes;
        } else {
            videoSizes = previewSizes;
        }
        Camera.Size optimalSize = null;

        // Start with max value and refine as we iterate over available video sizes. This is the
        // minimum difference between view and camera height.
        double minDiff = Double.MAX_VALUE;

        // Target view height
        int targetHeight = h;

        // Try to find a video size that matches aspect ratio and the target view size.
        // Iterate over all available sizes and pick the largest size that can fit in the view and
        // still maintain the aspect ratio.
        for (Camera.Size size : videoSizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE)
                continue;
            if (Math.abs(size.height - targetHeight) < minDiff && previewSizes.contains(size)) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find video size that matches the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : videoSizes) {
                if (Math.abs(size.height - targetHeight) < minDiff && previewSizes.contains(size)) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        return optimalSize;
    }

    // Saving --------------------------------------------------------------------------------------
    // Create a File for saving an image or video
    private static File getOutputMediaFile(int type) {
        // To be safe, you should check that the SDCard is mounted
        // using Environment.getExternalStorageState() before doing this.
        if (!Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED)) {
            return null;
        }

        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MyCameraApp");
        // This location works best if you want the created images to be shared
        // between applications and persist after your app has been uninstalled.

        // Create the storage directory if it does not exist
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                System.out.println("failed to create directory");
                return null;
            }
        }

        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File mediaFile;
        if (type == 2) { // Type of video = 2, Type of image = 1
            mediaFile = new File(mediaStorageDir.getPath() + File.separator + "VID_" + timeStamp + ".mp4");
        } else {
            return null;
        }

        return mediaFile;
    }

    // Permissions ---------------------------------------------------------------------------------
    private boolean areCameraPermissionGranted() {
        for (String permission : requiredPermissions) {
            if (!(ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)) {
                return false;
            }
        }
        return true;
    }
    private void requestCameraPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, MEDIA_RECORDER_REQUEST);
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (MEDIA_RECORDER_REQUEST != requestCode) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        boolean areAllPermissionsGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                areAllPermissionsGranted = false;
                break;
            }
        }

        if (areAllPermissionsGranted) {
            initCamera(whichCam);
        } else {
            // User denied one or more of the permissions, without these we cannot record
            // Show a toast to inform the user.
            Toast.makeText(getApplicationContext(), "This app needs the camera permission", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}

package com.example.Activity;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.example.Handler.DetectHandler;
import com.example.Message.ProcessMessage;
import com.example.R;
import com.example.Utils.CVUtil;
import com.example.Utils.MediaScanner;
import com.example.View.DrawView;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvException;
import org.opencv.core.Mat;
import org.opencv.core.Point;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;

public class CaptureActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener
{
    static
    {
        System.loadLibrary("opencv_java4");
    }

    static final int PERMISSIONS_REQUEST_CODE = 1000;
    String[] PERMISSIONS = {"android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE"};

    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_PRECAPTURE = 2;
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    private static final int STATE_PICTURE_TAKEN = 4;

    private int state = STATE_PREVIEW;
    private TextureView cameraView;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder previewCaptureBuilder;
    private CaptureRequest.Builder pictureCaptureBuilder;
    private CameraCaptureSession captureSession;
    private ImageReader previewReader;
    private ImageReader pictureReader;
    private Surface textureSurface;
    private HandlerThread previewThread;
    private Handler previewHandler;
    private CaptureRequest previewRequest;

    private Size previewSize;
    private Size pictureSize;

    private RelativeLayout drawSurface;
    private DrawView drawView;

    private HandlerThread detectThread;
    private DetectHandler detectHandler;

    private Button saveButton;

    private MediaScanner mediaScanner;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            if(!hasPermissions(PERMISSIONS))
            {
                requestPermissions(PERMISSIONS, PERMISSIONS_REQUEST_CODE);
            }
        }

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_capture);

        cameraView = findViewById(R.id.cameraView);

        drawSurface = findViewById(R.id.drawSurface);
        drawView = new DrawView(this);
        drawSurface.addView(drawView);

        saveButton = findViewById(R.id.saveButton);

        mediaScanner = new MediaScanner(getApplicationContext());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults)
    {
        switch (requestCode)
        {
            case PERMISSIONS_REQUEST_CODE:
            {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED)
                {
                    // granted
                } else
                {
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle("Error");
                    builder.setMessage("You must grant permissions to use this app.");
                    builder.setPositiveButton("예", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            finish();
                        }
                    });
                    builder.create().show();
                }
            }
        }
    }

    @Override
    public void onResume()
    {
        super.onResume();

        startPreviewThread();

        if(!OpenCVLoader.initDebug())
        {
            Log.d("opencv", "onResume :: Internal OpenCV library not found.");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, loaderCallback);
        }
        else
        {
            Log.d("opencv", "onResume :: Internal OpenCV library found inside package.");
            loaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }

        startDetectThread();
    }

    @Override
    public void onPause()
    {
        closePreviewThread();
        closeDetectThread();
        closeCamera();

        super.onPause();
    }

    @Override
    public void onDestroy()
    {
        closePreviewThread();
        closeDetectThread();
        closeCamera();

        super.onDestroy();
    }

    private boolean hasPermissions(String[] permissions)
    {
        int result;

        for (String perms : permissions)
        {
            result = ContextCompat.checkSelfPermission(this, perms);

            if (result == PackageManager.PERMISSION_DENIED)
            {
                return false;
            }
        }
        return true;
    }

    private BaseLoaderCallback loaderCallback = new BaseLoaderCallback(this)
    {
        @Override
        public void onManagerConnected(int status)
        {
            switch (status)
            {
                case LoaderCallbackInterface.SUCCESS:
                    enableCameraView();
                    break;
                default:
                    super.onManagerConnected(status);
                    break;
            }
        }
    };

    private CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback()
    {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice)
        {
            CaptureActivity.this.cameraDevice = cameraDevice;
            try
            {
                startPreview();
            }
            catch (CameraAccessException e)
            {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice)
        {
            closePreviewThread();
            closeDetectThread();
            cameraDevice.close();
            CaptureActivity.this.cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error)
        {
            closePreviewThread();
            closeDetectThread();
            cameraDevice.close();
            CaptureActivity.this.cameraDevice = null;

        }
    };

    private CameraCaptureSession.StateCallback sessionCallback = new CameraCaptureSession.StateCallback()
    {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession)
        {
            captureSession = cameraCaptureSession;

            try
            {
                previewRequest = previewCaptureBuilder.build();
                captureSession.setRepeatingRequest(previewRequest, captureCallback, previewHandler);
            }
            catch(CameraAccessException e)
            {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
        }
    };

    private CameraCaptureSession.CaptureCallback captureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (state) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            state = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult)
        {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result)
        {
            process(result);
        }
    };

    private ImageReader.OnImageAvailableListener previewReaderListener = new ImageReader.OnImageAvailableListener()
    {
        @Override
        public void onImageAvailable(ImageReader imageReader)
        {
            Image image = imageReader.acquireNextImage();

            if(detectHandler != null && !detectHandler.isImageDetectRunning())
            {
                Mat mat = CVUtil.yuvImageToRgbMat(image);
                Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE);

                final Message msg = detectHandler.obtainMessage();
                msg.obj = new ProcessMessage("detectRectangle", mat);
                detectHandler.sendMessage(msg);
            }

            image.close();
        }
    };

    private ImageReader.OnImageAvailableListener pictureReaderListener = new ImageReader.OnImageAvailableListener()
    {
        @Override
        public void onImageAvailable(ImageReader imageReader)
        {
            Image image = imageReader.acquireNextImage();

            Mat mat = CVUtil.yuvImageToRgbMat(image);
            Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE);

            saveImage(mat);

            mat.release();
            image.close();
        }
    };

    private void runPrecaptureSequence()
    {
        try
        {
            previewCaptureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            state = STATE_WAITING_PRECAPTURE;
            captureSession.capture(previewCaptureBuilder.build(), captureCallback, previewHandler);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    private void captureStillPicture() {
        try {
            final Activity activity = this;
            if (null == activity || null == cameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(pictureReader.getSurface());

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    unlockFocus();
                }
            };

            captureSession.stopRepeating();
            captureSession.abortCaptures();
            captureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void lockFocus()
    {
        try {
            previewCaptureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            state = STATE_WAITING_LOCK;
            captureSession.capture(previewCaptureBuilder.build(), captureCallback, previewHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void takePicture(View view) {
        lockFocus();
    }

    private void unlockFocus()
    {
        try
        {
            previewCaptureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(previewCaptureBuilder.build(), captureCallback, previewHandler);
            state = STATE_PREVIEW;
            captureSession.setRepeatingRequest(previewRequest, captureCallback, previewHandler);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
    }

    private void enableCameraView()
    {
        if(cameraView.isAvailable())
        {
            openCamera();
        }
        else
        {
            cameraView.setSurfaceTextureListener(this);
        }
    }

    private void openCamera()
    {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        //TODO: 각 익셉션 처리할 것
        try
        {
            String cameraId = getBackCameraId();
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            previewSize = getPreviewSize(map.getOutputSizes(ImageFormat.YUV_420_888), 16, 9);
            pictureSize = getPictureSize(map.getOutputSizes(ImageFormat.YUV_420_888), 16, 9);

            previewReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(previewReaderListener, previewHandler);

            pictureReader = ImageReader.newInstance(pictureSize.getWidth(), pictureSize.getHeight(), ImageFormat.YUV_420_888, 2);
            pictureReader.setOnImageAvailableListener(pictureReaderListener, previewHandler);

            manager.openCamera(cameraId, stateCallback, null);
        }
        catch(CameraAccessException e)
        {
            //카메라에 접근할 수 없음
            e.printStackTrace();
        }
        catch(SecurityException e)
        {
            //카메라 권한이 없음
            e.printStackTrace();
        }
        catch(RuntimeException e)
        {
            //찾을 수 없음
            e.printStackTrace();
        }
    }

    private void startPreview() throws CameraAccessException
    {
        SurfaceTexture texture = cameraView.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

        textureSurface = new Surface(texture);

        try
        {
            previewCaptureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            pictureCaptureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        }
        catch(CameraAccessException e)
        {
            throw e;
        }

        previewCaptureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        previewCaptureBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90);
        previewCaptureBuilder.addTarget(textureSurface);
        previewCaptureBuilder.addTarget(previewReader.getSurface());

        try
        {
            cameraDevice.createCaptureSession(Arrays.asList(textureSurface, previewReader.getSurface(), pictureReader.getSurface()), sessionCallback, null);
        }
        catch(CameraAccessException e)
        {
            throw e;
        }
    }

    private void startPreviewThread()
    {
        previewThread = new HandlerThread("CameraPreview");
        previewThread.start();
        previewHandler = new Handler(previewThread.getLooper());
    }

    private void closePreviewThread()
    {
        if(previewThread != null )
        {
            previewThread.quitSafely();

            try
            {
                previewThread.join();
                previewThread = null;
                previewHandler = null;
            }
            catch(InterruptedException e)
            {
                e.printStackTrace();
            }
        }
    }

    private void closeCamera()
    {
        if(cameraDevice != null)
        {
            cameraDevice.close();
        }
    }

    private void startDetectThread()
    {
        if(detectThread == null)
        {
            detectThread = new HandlerThread("handlerThread");
            detectThread.start();
        }

        if(detectHandler == null)
        {
            detectHandler = new DetectHandler(detectThread.getLooper(), this);
        }
    }

    private void closeDetectThread()
    {
        if(detectThread != null)
        {
            detectThread.quitSafely();
            try
            {
                detectThread.join();
                detectThread = null;
                detectHandler = null;
            }
            catch(InterruptedException e)
            {
                e.printStackTrace();
            }
        }
    }

    private String getBackCameraId() throws CameraAccessException
    {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try
        {
            for(final String id : manager.getCameraIdList())
            {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                int orientation = characteristics.get(CameraCharacteristics.LENS_FACING);
                if(orientation == CameraCharacteristics.LENS_FACING_BACK)
                {
                    return id;
                }
            }
        }
        catch (CameraAccessException e)
        {
            throw e;
        }

        throw new RuntimeException("Camera Not Found");
    }

    public void redrawSurface()
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                drawView.invalidate();
            }
        });
    }

    public void setDrawPoint(List<Point[]> drawPoint, Mat currentMat)
    {
        drawView.setDrawPoint(drawPoint, currentMat);
    }

    public void saveImage(Mat mat)
    {
        if(mat == null)
        {
            Toast.makeText(this, "Can't find preview image", Toast.LENGTH_SHORT).show();
            return;
        }

        Point[] points = null;
        try
        {
            points = drawView.getPoint(0);
        }
        catch(IndexOutOfBoundsException e)
        {
            Toast.makeText(this, "Can't find document", Toast.LENGTH_SHORT).show();
            mat.release();
            return;
        }

        float widthRatio = (float)mat.size().width / (float) drawView.getCurrentMatSize().width;
        float heightRatio = (float)mat.size().height / (float) drawView.getCurrentMatSize().height;

        Point[] stretchedPoints = new Point[points.length];
        for(int i = 0; i < points.length; ++i)
        {
            stretchedPoints[i] = new Point(points[i].x * widthRatio, points[i].y * heightRatio);
        }

        Mat transformedImage = CVUtil.getPerspectiveTransformImageOfPoint(mat, stretchedPoints);

        saveMatrixAsImage(transformedImage);

        transformedImage.release();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height)
    {
        openCamera();
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture)
    {
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height)
    {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture)
    {
        return true;
    }

    private void saveMatrixAsImage(Mat mat)
    {
        final String TAG = "save";

        Bitmap bmp = null;
        try
        {
            bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(mat, bmp);
        }
        catch (CvException e)
        {
            Log.e(TAG, e.getMessage());
            Toast.makeText(this, "Unknown error in save image", Toast.LENGTH_SHORT).show();
        }

        File saveDirectory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + getResources().getString(R.string.app_name));

        boolean success = true;
        if(!saveDirectory.exists())
        {
            success = saveDirectory.mkdir();
        }

        if(success)
        {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss.SSS");
            String filename = format.format(new Date()) + ".jpg";
            File dest = new File(saveDirectory, filename);

            try(FileOutputStream out = new FileOutputStream(dest))
            {
                bmp.compress(Bitmap.CompressFormat.JPEG, 100, out);

                mediaScanner.scan(dest.getAbsolutePath(), "image/jpeg");

                Toast.makeText(this, "Save successfully: " + saveDirectory.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private Size getPictureSize(Size[] sizes, double widthRatio, double heightRatio)
    {
        double ratio = widthRatio / heightRatio;

        for(Size size : sizes)
        {
            if(((double)size.getWidth() / (double)size.getHeight()) == ratio)
            {
                return size;
            }
        }

        throw new NoSuchElementException();
    }

    private Size getPreviewSize(Size[] sizes, double widthRatio, double heightRatio)
    {
        double ratio = widthRatio / heightRatio;

        for(Size size : sizes)
        {
            if(size.getWidth() <= 1920 && size.getHeight() <= 1080 && ((double)size.getWidth() / (double)size.getHeight()) == ratio)
            {
                return size;
            }
        }

        throw new NoSuchElementException();
    }
}

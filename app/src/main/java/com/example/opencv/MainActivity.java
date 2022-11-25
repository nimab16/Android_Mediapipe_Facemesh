package com.example.opencv;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;


import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.exifinterface.media.ExifInterface;
// ContentResolver dependency
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.solutioncore.CameraInput;
import com.google.mediapipe.solutioncore.SolutionGlSurfaceView;
import com.google.mediapipe.solutioncore.VideoInput;
import com.google.mediapipe.solutions.facemesh.FaceMesh;
import com.google.mediapipe.solutions.facemesh.FaceMeshOptions;
import com.google.mediapipe.solutions.facemesh.FaceMeshResult;
import java.io.IOException;
import java.io.InputStream;

/** Main activity of MediaPipe Face Mesh app. */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private FaceMesh facemesh;
    // Run the pipeline and the model inference on GPU or CPU.
    private static final boolean RUN_ON_GPU = false;

    private enum InputSource {
        UNKNOWN,
        IMAGE,
        VIDEO,
        CAMERA,
    }
    private InputSource inputSource = InputSource.UNKNOWN;
    // Image demo UI and image loader components.
    private ActivityResultLauncher<Intent> imageGetter;
    private FaceMeshResultImageView imageView;
    // Video demo UI and video loader components.
    private VideoInput videoInput;
    private ActivityResultLauncher<Intent> videoGetter;
    // Live camera demo UI and camera components.
    private CameraInput cameraInput;

    private SolutionGlSurfaceView<FaceMeshResult> glSurfaceView;
    private static final int MY_CAMERA_REQUEST_CODE = 100;
    private static final int READ_STORAGE_PERMISSION_REQUEST_CODE = 41;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // TODO: Add a toggle to switch between the original face mesh and attention mesh.
        setupStaticImageDemoUiComponents();
        setupVideoDemoUiComponents();


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, MY_CAMERA_REQUEST_CODE);
            }
        }
        try {
            requestPermissionForReadExtertalStorage();
        } catch (Exception e) {
            e.printStackTrace();
        }

        setupLiveDemoUiComponents();
    }

    public boolean checkPermissionForReadExtertalStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int result = this.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            return result == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }
    public void requestPermissionForReadExtertalStorage() throws Exception {
        try {
            ActivityCompat.requestPermissions((Activity) this, new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    READ_STORAGE_PERMISSION_REQUEST_CODE);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MY_CAMERA_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "camera permission granted", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "camera permission denied", Toast.LENGTH_LONG).show();
            }
        }
    }
    @Override
    protected void onResume() {
        super.onResume();
        if (inputSource == InputSource.CAMERA) {
            // Restarts the camera and the opengl surface rendering.
            cameraInput = new CameraInput(this);
            cameraInput.setNewFrameListener(textureFrame -> facemesh.send(textureFrame));
            glSurfaceView.post(this::startCamera);
            glSurfaceView.setVisibility(View.VISIBLE);
        } else if (inputSource == InputSource.VIDEO) {
            videoInput.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (inputSource == InputSource.CAMERA) {
            glSurfaceView.setVisibility(View.GONE);
            cameraInput.close();
        } else if (inputSource == InputSource.VIDEO) {
            videoInput.pause();
        }
    }

    private Bitmap downscaleBitmap(Bitmap originalBitmap) {
        double aspectRatio = (double) originalBitmap.getWidth() / originalBitmap.getHeight();
        int width = imageView.getWidth();
        int height = imageView.getHeight();
        if (((double) imageView.getWidth() / imageView.getHeight()) > aspectRatio) {
            width = (int) (height * aspectRatio);
        } else {
            height = (int) (width / aspectRatio);
        }
        return Bitmap.createScaledBitmap(originalBitmap, width, height, false);
    }

    private Bitmap rotateBitmap(Bitmap inputBitmap, InputStream imageData) throws IOException {
        int orientation =
                new ExifInterface(imageData)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        if (orientation == ExifInterface.ORIENTATION_NORMAL) {
            return inputBitmap;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            default:
                matrix.postRotate(0);
        }
        return Bitmap.createBitmap(
                inputBitmap, 0, 0, inputBitmap.getWidth(), inputBitmap.getHeight(), matrix, true);
    }

    /** Sets up the UI components for the static image demo. */
    private void setupStaticImageDemoUiComponents() {
        // The Intent to access gallery and read images as bitmap.
        imageGetter =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            Intent resultIntent = result.getData();
                            if (resultIntent != null) {
                                if (result.getResultCode() == RESULT_OK) {
                                    Bitmap bitmap = null;
                                    try {
                                        bitmap =
                                                downscaleBitmap(
                                                        MediaStore.Images.Media.getBitmap(
                                                                this.getContentResolver(), resultIntent.getData()));
                                    } catch (IOException e) {
                                        Log.e(TAG, "Bitmap reading error:" + e);
                                    }
                                    try {
                                        InputStream imageData =
                                                this.getContentResolver().openInputStream(resultIntent.getData());
                                        bitmap = rotateBitmap(bitmap, imageData);
                                    } catch (IOException e) {
                                        Log.e(TAG, "Bitmap rotation error:" + e);
                                    }
                                    if (bitmap != null) {
                                        facemesh.send(bitmap);
                                    }
                                }
                            }
                        });
        Button loadImageButton = findViewById(R.id.button_load_picture);
        loadImageButton.setOnClickListener(
                v -> {
                    if (inputSource != InputSource.IMAGE) {
                        stopCurrentPipeline();
                        setupStaticImageModePipeline();
                    }
                    // Reads images from gallery.
                    Intent pickImageIntent = new Intent(Intent.ACTION_PICK);
                    pickImageIntent.setDataAndType(MediaStore.Images.Media.INTERNAL_CONTENT_URI, "image/*");
                    imageGetter.launch(pickImageIntent);
                });
        imageView = new FaceMeshResultImageView(this);
    }

    /** Sets up core workflow for static image mode. */
    private void setupStaticImageModePipeline() {
        this.inputSource = InputSource.IMAGE;
        // Initializes a new MediaPipe Face Mesh solution instance in the static image mode.
        facemesh =
                new FaceMesh(
                        this,
                        FaceMeshOptions.builder()
                                .setStaticImageMode(true)
                                .setRefineLandmarks(true)
                                .setRunOnGpu(RUN_ON_GPU)
                                .build());

        // Connects MediaPipe Face Mesh solution to the user-defined FaceMeshResultImageView.
        facemesh.setResultListener(
                faceMeshResult -> {
                    logNoseLandmark(faceMeshResult, /*showPixelValues=*/ true);
                    imageView.setFaceMeshResult(faceMeshResult);
                    runOnUiThread(() -> imageView.update());
                });
        facemesh.setErrorListener((message, e) -> Log.e(TAG, "MediaPipe Face Mesh error:" + message));

        // Updates the preview layout.
        FrameLayout frameLayout = findViewById(R.id.preview_display_layout);
        frameLayout.removeAllViewsInLayout();
        imageView.setImageDrawable(null);
        frameLayout.addView(imageView);
        imageView.setVisibility(View.VISIBLE);
    }

    /** Sets up the UI components for the video demo. */
    private void setupVideoDemoUiComponents() {
        // The Intent to access gallery and read a video file.
        videoGetter =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            Intent resultIntent = result.getData();
                            if (resultIntent != null) {
                                if (result.getResultCode() == RESULT_OK) {
                                    glSurfaceView.post(
                                            () ->
                                                    videoInput.start(
                                                            this,
                                                            resultIntent.getData(),
                                                            facemesh.getGlContext(),
                                                            glSurfaceView.getWidth(),
                                                            glSurfaceView.getHeight()));
                                }
                            }
                        });
        Button loadVideoButton = findViewById(R.id.video_button);
        loadVideoButton.setOnClickListener(
                v -> {
                    stopCurrentPipeline();
                    setupStreamingModePipeline(InputSource.VIDEO);
                    // Reads video from gallery.
                    Intent pickVideoIntent = new Intent(Intent.ACTION_PICK);
                    pickVideoIntent.setDataAndType(MediaStore.Video.Media.INTERNAL_CONTENT_URI, "video/*");
                    videoGetter.launch(pickVideoIntent);
                });
    }

    /** Sets up the UI components for the live demo with camera input. */
    private void setupLiveDemoUiComponents() {
        Button startCameraButton = findViewById(R.id.button_start_camera);
        startCameraButton.setOnClickListener(
                v -> {

                    if (inputSource == InputSource.CAMERA) {
                        return;
                    }
                    stopCurrentPipeline();
                    setupStreamingModePipeline(InputSource.CAMERA);
                });
    }

    /** Sets up core workflow for streaming mode. */
    private void setupStreamingModePipeline(InputSource inputSource) {
        this.inputSource = inputSource;
        // Initializes a new MediaPipe Face Mesh solution instance in the streaming mode.
        facemesh =
                new FaceMesh(
                        this,
                        FaceMeshOptions.builder()
                                .setStaticImageMode(false)
                                .setRefineLandmarks(true)
                                .setRunOnGpu(RUN_ON_GPU)
                                .build());
        facemesh.setErrorListener((message, e) -> Log.e(TAG, "MediaPipe Face Mesh error:" + message));

        if (inputSource == InputSource.CAMERA) {
            cameraInput = new CameraInput(this);
            cameraInput.setNewFrameListener(textureFrame -> facemesh.send(textureFrame));
        } else if (inputSource == InputSource.VIDEO) {
            videoInput = new VideoInput(this);
            videoInput.setNewFrameListener(textureFrame -> facemesh.send(textureFrame));
        }

        // Initializes a new Gl surface view with a user-defined FaceMeshResultGlRenderer.
        glSurfaceView =
                new SolutionGlSurfaceView<>(this, facemesh.getGlContext(), facemesh.getGlMajorVersion());
        glSurfaceView.setSolutionResultRenderer(new FaceMeshResultGlRenderer());
        glSurfaceView.setRenderInputImage(true);
        facemesh.setResultListener(
                faceMeshResult -> {
                    logNoseLandmark(faceMeshResult, /*showPixelValues=*/ false);
                    glSurfaceView.setRenderData(faceMeshResult);
                    glSurfaceView.requestRender();
                });

        // The runnable to start camera after the gl surface view is attached.
        // For video input source, videoInput.start() will be called when the video uri is available.
        if (inputSource == InputSource.CAMERA) {
            glSurfaceView.post(this::startCamera);
        }

        // Updates the preview layout.
        FrameLayout frameLayout = findViewById(R.id.preview_display_layout);
        imageView.setVisibility(View.GONE);
        frameLayout.removeAllViewsInLayout();
        frameLayout.addView(glSurfaceView);
        glSurfaceView.setVisibility(View.VISIBLE);
        frameLayout.requestLayout();
    }

    private void startCamera() {
        cameraInput.start(
                this,
                facemesh.getGlContext(),
                CameraInput.CameraFacing.FRONT,
                glSurfaceView.getWidth(),
                glSurfaceView.getHeight());
    }

    private void stopCurrentPipeline() {
        if (cameraInput != null) {
            cameraInput.setNewFrameListener(null);
            cameraInput.close();
        }
        if (videoInput != null) {
            videoInput.setNewFrameListener(null);
            videoInput.close();
        }
        if (glSurfaceView != null) {
            glSurfaceView.setVisibility(View.GONE);
        }
        if (facemesh != null) {
            facemesh.close();
        }
    }

    private void logNoseLandmark(FaceMeshResult result, boolean showPixelValues) {
        if (result == null || result.multiFaceLandmarks().isEmpty()) {
            return;
        }
        NormalizedLandmark noseLandmark = result.multiFaceLandmarks().get(0).getLandmarkList().get(1);
        // For Bitmaps, show the pixel values. For texture inputs, show the normalized coordinates.
        if (showPixelValues) {
            int width = result.inputBitmap().getWidth();
            int height = result.inputBitmap().getHeight();
            Log.i(
                    TAG,
                    String.format(
                            "MediaPipe Face Mesh nose coordinates (pixel values): x=%f, y=%f",
                            noseLandmark.getX() * width, noseLandmark.getY() * height));
        } else {
            Log.i(
                    TAG,
                    String.format(
                            "MediaPipe Face Mesh nose normalized coordinates (value range: [0, 1]): x=%f, y=%f",
                            noseLandmark.getX(), noseLandmark.getY()));
        }
    }
}

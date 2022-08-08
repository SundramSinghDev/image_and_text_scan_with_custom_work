package com.example.customcamera;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.TorchState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.example.customcamera.databinding.ActivityMainBinding;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {
    ActivityMainBinding binding;
    private final String TAG = this.getClass().getSimpleName();
    final int REQUEST_CODE_PERMISSION = 101;
    String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"};
    ImageCapture imageCapture;
    ImageCapture.OutputFileOptions outputFileOptions;
    List<String> extractedData;
    CameraControl cameraControl;
    CameraInfo cameraInfo;
    CameraSelector lensFacing = CameraSelector.DEFAULT_BACK_CAMERA;
    ImageAnalysis imageAnalysis;
    Image fromImage_var;
    ImageProxy imageProxy_var;
    String from;
    Camera camera;
    ProcessCameraProvider cameraProvider;
    OrientationEventListener orientationEventListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSION);
        }
        binding.imageCaptureButton.setOnClickListener(view -> onTakePicture());
        binding.flashBtn.setOnClickListener(view -> {
            if (cameraInfo.hasFlashUnit()) {
                if ((cameraInfo.getTorchState().getValue() != null && cameraInfo.getTorchState().getValue() == TorchState.OFF)) {
                    enableTorch();
                } else {
                    disableTorch();
                }
            }
        });
        binding.turnCameraFace.setOnClickListener(view -> {
            flipCamera();
            binding.labelDataListview.setVisibility(View.GONE);
        });

        from = getIntent().getStringExtra("for");
        if (from.equalsIgnoreCase("image")) {
            binding.rotationTv.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                }

                @SuppressLint("RestrictedApi")
                @Override
                public void afterTextChanged(Editable editable) {
                    imageAnalysis.onAttached();
                }
            });
        } else if (from.equalsIgnoreCase("text")) {
            binding.imageCaptureButton.setVisibility(View.GONE);
            binding.rotationTv.setVisibility(View.GONE);
            binding.turnCameraFace.setVisibility(View.GONE);
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                onCreateAndManageImageCaptureObject();
                onCreateAndManageImageAnalyserObject();
                // Unbind use cases before rebinding
                cameraProvider.unbindAll();
                // Bind use cases to camera
                camera = cameraProvider.bindToLifecycle(MainActivity.this, lensFacing, preview, imageCapture, imageAnalysis);
                preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());
                cameraControl = camera.getCameraControl();
                cameraInfo = camera.getCameraInfo();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));

    }

    @SuppressLint({"UnsafeOptInUsageError", "RestrictedApi"})
    private void onCreateAndManageImageAnalyserObject() {
        imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_BLOCK_PRODUCER)
                .setOnePixelShiftEnabled(false)
                .setImageQueueDepth(1)
                .build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), imageProxy -> {
            Image image = imageProxy.getImage();
            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
            // after done, release the ImageProxy object
            if (!from.equalsIgnoreCase("text"))
                onManageImageDataAndShowText(getInputImageObjectFromMediaImage(image, rotationDegrees));
            else
                onCreateAndManageTextRecogniserObject(getInputImageObjectFromMediaImage(image, rotationDegrees));
            imageProxy_var = imageProxy;
            fromImage_var = image;
        });
    }

    private void onCreateAndManageImageCaptureObject() {
        try {
            // Set up the capture use case to allow users to take photos
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build();
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, "test_file");
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image");
            }

            outputFileOptions = new ImageCapture.OutputFileOptions.Builder(
                    getContentResolver(),
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues)
                    .build();

            orientationEventListener = new OrientationEventListener(this) {
                @Override
                public void onOrientationChanged(int orientation) {
                    int rotation;
                    // Monitors orientation values to determine the target rotation value
                    if (orientation >= 45 && orientation < 135) {
                        rotation = Surface.ROTATION_270;
                        binding.rotationTv.setText(String.valueOf("270"));
                    } else if (orientation >= 135 && orientation < 225) {
                        rotation = Surface.ROTATION_180;
                        binding.rotationTv.setText(String.valueOf("180"));
                    } else if (orientation >= 225 && orientation < 315) {
                        rotation = Surface.ROTATION_90;
                        binding.rotationTv.setText(String.valueOf("90"));
                    } else {
                        rotation = Surface.ROTATION_0;
                        binding.rotationTv.setText(String.valueOf("0"));
                    }
                    imageCapture.setTargetRotation(rotation);
                    Log.e(TAG, "onOrientationChanged: " + rotation);
                }
            };
            orientationEventListener.enable();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("RestrictedApi")
    private void onCreateAndManageTextRecogniserObject(InputImage image) {
        // When using Latin script library
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        // Task completed successfully
        recognizer.process(image)
                .addOnSuccessListener(this::processTextRecognitionResult)
                .addOnFailureListener(
                        e -> {
                            // Task failed with an exception
                            Log.e(TAG, "onFailureTextRecogniser: " + e);
                            fromImage_var.close();
                        }).addOnCompleteListener(task -> {
            imageProxy_var.close();
            imageAnalysis.onDetached();

        });
    }

    private void processTextRecognitionResult(Text texts) {
        List<Text.TextBlock> blocks = texts.getTextBlocks();
        if (blocks.size() == 0) {
            showToast("No text found");
            return;
        }
        extractedData = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            List<Text.Line> lines = blocks.get(i).getLines();
            for (int j = 0; j < lines.size(); j++) {
                List<Text.Element> elements = lines.get(j).getElements();
                for (int k = 0; k < elements.size(); k++) {
                    extractedData.add(elements.get(k).getText());
                }
            }
        }
        if (extractedData.size() > 0) {
            onManageArrayAdapter(extractedData);
        }
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void flipCamera() {
        if (lensFacing == CameraSelector.DEFAULT_FRONT_CAMERA)
            lensFacing = CameraSelector.DEFAULT_BACK_CAMERA;
        else if (lensFacing == CameraSelector.DEFAULT_BACK_CAMERA)
            lensFacing = CameraSelector.DEFAULT_FRONT_CAMERA;
        startCamera();
    }

    private void enableTorch() {
        cameraControl.enableTorch(true);
    }

    private void disableTorch() {
        cameraControl.enableTorch(false);
    }

    private void onTakePicture() {
        imageCapture.takePicture(outputFileOptions,
                ContextCompat.getMainExecutor(MainActivity.this),
                new ImageCapture.OnImageSavedCallback() {
                    @SuppressLint("RestrictedApi")
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        Uri uri = output.getSavedUri();
                        Log.e(TAG, "onImageSaved: " + uri);
                        try {
//                            onManageImageDataAndShowText(getInputImageObjectFromUri(MainActivity.this, uri));
                            onCreateAndManageTextRecogniserObject(getInputImageObjectFromUri(MainActivity.this, uri));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "onError: " + exception);
                    }
                });
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSION) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this,
                        "Permissions not granted by the user.",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }


    private InputImage getInputImageObjectFromUri(Context context, Uri uri) throws IOException {
        return InputImage.fromFilePath(context, uri);
    }

    private InputImage getInputImageObjectFromMediaImage(Image image, int rotationDegree) {
        return InputImage.fromMediaImage(image, rotationDegree);
    }

    @SuppressLint("RestrictedApi")
    private void onManageImageDataAndShowText(InputImage image) {
        try {
            ImageLabelerOptions options =
                    new ImageLabelerOptions.Builder()
                            .setConfidenceThreshold(0.5f)
                            .build();
            ImageLabeler labeler = ImageLabeling.getClient(options);
            labeler.process(image)
                    .addOnSuccessListener(labels -> {
                        // Task completed successfully
                        extractedData = new ArrayList<>();
                        for (ImageLabel label : labels) {
                            String text = label.getText();
                            float confidence = label.getConfidence();
                            int index = label.getIndex();
                            extractedData.add("Index: " + index + "\nConfidence: " + confidence + "\ntext: " + text);
                        }
                        Log.e(TAG, "onManageImageDataAndShowText: " + extractedData);
                        onManageArrayAdapter(extractedData);
                        fromImage_var.close();
                    })
                    .addOnFailureListener(e -> {
                        // Task failed with an exception
                        // ...
                        Log.e(TAG, "onManageImageDataAndShowTextERROR: " + e);
                    }).addOnCompleteListener(task -> {
                imageProxy_var.close();
                imageAnalysis.onDetached();
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onManageArrayAdapter(List<String> extractedData) {
        ArrayAdapter<String> labelDataArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, extractedData);
        binding.labelDataListview.setAdapter(labelDataArrayAdapter);
        binding.labelDataListview.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        imageAnalysis.clearAnalyzer();
        orientationEventListener.disable();
    }

    @SuppressLint("RestrictedApi")
    @Override
    protected void onDestroy() {
        super.onDestroy();
        imageAnalysis.clearAnalyzer();
        orientationEventListener.disable();
    }
}
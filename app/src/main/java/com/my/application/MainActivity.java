package com.my.application;

import android.content.Intent;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Size;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private TextView txtResult;
    private MaterialButton btnToggleScan, btnCopy, btnShare;
    private View focusAreaView;
    private FloatingActionButton btnFlash;


    private ExecutorService cameraExecutor;
    private boolean isScanning = true;

    // Variáveis para controlar a lanterna e o zoom
    private androidx.camera.core.Camera camera;
    private boolean isFlashEnabled = false;
    private ScaleGestureDetector scaleGestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hideSystemUI();

        previewView = findViewById(R.id.previewView);
        txtResult = findViewById(R.id.txtResult);
        btnToggleScan = findViewById(R.id.btnToggleScan);
        btnCopy = findViewById(R.id.btnCopy);
        btnShare = findViewById(R.id.btnShare);
        focusAreaView = findViewById(R.id.focusArea);
        btnFlash = findViewById(R.id.btnFlash);

        setupButtons();
        setupZoom(); // Agora o zoom é ativado quando o app abre!

        btnFlash.setOnClickListener(v -> toggleFlash());

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void hideSystemUI() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void setupButtons() {
        btnToggleScan.setOnClickListener(v -> {
            isScanning = !isScanning;
            if (isScanning) {
                btnToggleScan.setText("Pausar");
                btnToggleScan.setIconResource(android.R.drawable.ic_media_pause);
                txtResult.setText("Escaneando...");
            } else {
                btnToggleScan.setText("Retomar");
                btnToggleScan.setIconResource(android.R.drawable.ic_media_play);
            }
        });

        btnCopy.setOnClickListener(v -> {
            String texto = txtResult.getText().toString();
            if (texto.isEmpty() || texto.equals("Escaneando...")) {
                Toast.makeText(this, "Nada para copiar", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Texto OCR", texto);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copiado!", Toast.LENGTH_SHORT).show();
        });

        // Lógica do botão de Compartilhar
        btnShare.setOnClickListener(v -> {
            String texto = txtResult.getText().toString();

            // Verifica se tem algo para compartilhar
            if (texto.isEmpty() || texto.equals("Escaneando...")) {
                Toast.makeText(this, "Nenhum texto para enviar", Toast.LENGTH_SHORT).show();
                return;
            }

            // Cria a "Intenção" de enviar um texto simples
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, texto);

            // Abre a gaveta do Android para o usuário escolher o app (WhatsApp, Email, etc)
            startActivity(Intent.createChooser(shareIntent, "Enviar texto via..."));
        });
    }

    private void setupZoom() {
        ScaleGestureDetector.SimpleOnScaleGestureListener listener = new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera != null) {
                    float currentZoomRatio = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                    float delta = detector.getScaleFactor();
                    camera.getCameraControl().setZoomRatio(currentZoomRatio * delta);
                    return true;
                }
                return false;
            }
        };

        scaleGestureDetector = new ScaleGestureDetector(this, listener);

        previewView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void toggleFlash() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            isFlashEnabled = !isFlashEnabled;
            camera.getCameraControl().enableTorch(isFlashEnabled);

            if(isFlashEnabled){
                btnFlash.setAlpha(1.0f);
            } else {
                btnFlash.setAlpha(0.5f);
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(previewView.getDisplay().getRotation())
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (!isScanning) {
                        image.close();
                        return;
                    }
                    // O passo essencial: recortar a imagem antes de analisar
                    cropAndRecognizeText(image);
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();

                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e("CameraX", "Erro na câmera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void cropAndRecognizeText(ImageProxy imageProxy) {
        if (previewView.getWidth() == 0 || focusAreaView.getWidth() == 0) {
            imageProxy.close();
            return;
        }

        Image imageInput = imageProxy.getImage();
        if (imageInput == null) {
            imageProxy.close();
            return;
        }

        Bitmap bitmapOriginal = null;
        Bitmap bitmapRotated = null;
        Bitmap bitmapCropped = null;

        try {
            bitmapOriginal = yuvToBitmap(imageInput);
            if (bitmapOriginal == null) {
                imageProxy.close();
                return;
            }

            Matrix matrix = new Matrix();
            matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());
            bitmapRotated = Bitmap.createBitmap(bitmapOriginal, 0, 0, bitmapOriginal.getWidth(), bitmapOriginal.getHeight(), matrix, true);

            float scaleX = (float) previewView.getWidth() / bitmapRotated.getWidth();
            float scaleY = (float) previewView.getHeight() / bitmapRotated.getHeight();
            float scale = Math.max(scaleX, scaleY);

            float scaledWidth = bitmapRotated.getWidth() * scale;
            float scaledHeight = bitmapRotated.getHeight() * scale;
            float offsetX = (previewView.getWidth() - scaledWidth) / 2f;
            float offsetY = (previewView.getHeight() - scaledHeight) / 2f;

            int cropLeft = (int) ((focusAreaView.getLeft() - offsetX) / scale);
            int cropTop = (int) ((focusAreaView.getTop() - offsetY) / scale);
            int cropWidth = (int) (focusAreaView.getWidth() / scale);
            int cropHeight = (int) (focusAreaView.getHeight() / scale);

            if (cropLeft < 0) cropLeft = 0;
            if (cropTop < 0) cropTop = 0;
            if (cropLeft + cropWidth > bitmapRotated.getWidth()) cropWidth = bitmapRotated.getWidth() - cropLeft;
            if (cropTop + cropHeight > bitmapRotated.getHeight()) cropHeight = bitmapRotated.getHeight() - cropTop;

            if (cropWidth <= 0 || cropHeight <= 0) {
                imageProxy.close();
                return;
            }

            bitmapCropped = Bitmap.createBitmap(bitmapRotated, cropLeft, cropTop, cropWidth, cropHeight);
            InputImage croppedImage = InputImage.fromBitmap(bitmapCropped, 0);
            TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            final Bitmap finalOriginal = bitmapOriginal;
            final Bitmap finalRotated = bitmapRotated;
            final Bitmap finalCropped = bitmapCropped;

            recognizer.process(croppedImage)
                    .addOnSuccessListener(visionText -> {
                        String textoDetectado = visionText.getText().trim();
                        if (!textoDetectado.isEmpty()) {
                            txtResult.setText(textoDetectado);
                        }
                    })
                    .addOnFailureListener(e -> Log.e("MLKit", "Erro no OCR", e))
                    .addOnCompleteListener(task -> {
                        imageProxy.close();
                        if (finalOriginal != null) finalOriginal.recycle();
                        if (finalRotated != null) finalRotated.recycle();
                        if (finalCropped != null) finalCropped.recycle();
                    });

        } catch (Exception e) {
            Log.e("CameraX", "Erro ao processar imagem", e);
            imageProxy.close();
            if (bitmapOriginal != null) bitmapOriginal.recycle();
            if (bitmapRotated != null) bitmapRotated.recycle();
            if (bitmapCropped != null) bitmapCropped.recycle();
        }
    }

    private Bitmap yuvToBitmap(Image image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out);
        byte[] imageBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
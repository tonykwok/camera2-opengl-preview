package com.jxtras.android.sample.camera2opengl;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import android.opengl.GLES31;
import android.view.Surface;

public class CustomGLRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "CustomGLRenderer";

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoords;
    private int renderProgram;

    private SurfaceTexture mSTexture;
    private final float[] mTexMatrix = new float[16];
    private final float[] mTFX = new float[16];
    private final float[] mMVPMatrix = new float[16];

    private boolean mGLInit = false;
    private boolean mUpdateST = false;

    private final CustomGLSurfaceView mView;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private String mCameraID;
    private Size mPreviewSize;

    private ImageReader mImageReader;

    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    // {
    private static final int TEXTURE_TYPE_2D = 0;
    private static final int TEXTURE_TYPE_EXT = 1;
    // only available when using es3
    private static final int TEXTURE_TYPE_YUV_ITU_601 = 2; // itu_601
    private static final int TEXTURE_TYPE_YUV_ITU_601_FULL = 3; // itu_601_full_range
    private static final int TEXTURE_TYPE_YUV_ITU_709 = 4; // itu_709
    private static final int TEXTURE_TYPE_YUV_TRANSFORM_601 = 5; // custom 601
    private static final int TEXTURE_TYPE_YUV_TRANSFORM_601_FULL = 6; // custom 601 full
    private static final int TEXTURE_TYPE_YUV_TRANSFORM_709 = 7; // custom 709
    private static final int TEXTURE_TYPE_YUV_TRANSFORM_709_FULL = 7; // custom 709
    // }

    private static final boolean sUseFbo = true;
    private static final boolean sUseEs3 = true;
    private static final boolean sUseLut3D = true;
    private static final boolean sDumpTexture = false;

    private int mTextureExternalOes = -1;
    private int mFrameBufferTextureId = -1;
    private int mFrameBufferId = -1;
    private int mLut3DTextureId = -1;

    CustomGLRenderer (CustomGLSurfaceView view) {
        mView = view;
        float[] vtmp =  {
                -1.0f, -1.0f, // bottom-left
                 1.0f, -1.0f, // bottom-right
                -1.0f, 1.0f,  // up-left
                 1.0f, 1.0f   // up-right
        };
        vertexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(vtmp);
        vertexBuffer.position(0);

        float[] ttmp =  {
                0.0f, 0.0f, // bottom-left
                1.0f, 0.0f, // bottom-right
                0.0f, 1.0f, // up-left
                1.0f, 1.0f  // up-right
        };
        texCoords = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoords.put(ttmp);
        texCoords.position(0);
    }

    public void onResume() {
        startBackgroundThread();
    }

    public void onPause() {
        mGLInit = false;
        mUpdateST = false;
        closeCamera();
        stopBackgroundThread();
    }

    public void onSurfaceCreated ( GL10 unused, EGLConfig config ) {

        Point ss = new Point();
        mView.getDisplay().getRealSize(ss);

//        case Surface.ROTATION_0: 0
//        case Surface.ROTATION_90: 1
//        case Surface.ROTATION_180: 2
//        case Surface.ROTATION_270: 3
        Log.d(TAG, "RealSize: " + ss + ", rotation: " + mView.getDisplay().getRotation());

        ss.x = 1920;
        ss.y = 1080;

        mPreviewSize = new Size(ss.x, ss.y);
        mImageReader = ImageReader.newInstance(
                ss.x,
                ss.y,
                ImageFormat.YUV_420_888,
                5
        );
        HandlerThread imageReaderThread = new HandlerThread("imagereader");
        imageReaderThread.start();
        mImageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = reader.acquireNextImage();
                Log.d(TAG, "onImageAvailable: " +image.getPlanes().length);
                int w = image.getWidth();
                int h = image.getHeight();

                int i420size = w * h * 3 / 2;

                Image.Plane[] planes = image.getPlanes();
                int r0 = planes[0].getBuffer().remaining();
                int r1 = planes[1].getBuffer().remaining();
                int r2 = planes[2].getBuffer().remaining();

                int pixelStride = planes[2].getPixelStride();
                int rowOffset = planes[2].getRowStride();
                byte[] nv21 = new byte[i420size];
                byte[] yRawBytes = new byte[r0];
                planes[0].getBuffer().get(yRawBytes);
                byte[] uRawBytes = new byte[r1];
                planes[1].getBuffer().get(uRawBytes);
                byte[] vRawBytes = new byte[r2];
                planes[2].getBuffer().get(vRawBytes);

                if (pixelStride == w) {
                    System.arraycopy(yRawBytes, 0, nv21,0, rowOffset * h);
                    System.arraycopy(vRawBytes, 0, nv21, rowOffset * h, rowOffset * h / 2 - 1);
                } else {
                    byte[] y = new byte[w * h];
                    byte[] u = new byte[w * h / 2 - 1];
                    byte[] v = new byte[w * h / 2 - 1];
                    for (int row = 0; row < h; row++) {
                        System.arraycopy(yRawBytes, rowOffset* row, y, w * row, w);
                        if (row % 2 == 0) {
                            if (row == h - 2) {
                                System.arraycopy(vRawBytes, rowOffset * row / 2,v, w * row / 2, w - 1);
                            } else {
                                System.arraycopy(vRawBytes, rowOffset * row / 2, v, w * row / 2, w);
                            }
                        }
                    }
                    System.arraycopy(y, 0, nv21, 0, w * h);
                    System.arraycopy(v, 0, nv21, w*h, w*h/2-1);
                }
                YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
                try {
                    File filePath = new File(mView.getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "yuvdump.jpg");
                    yuvImage.compressToJpeg(new Rect(0, 0, w, h), 90, new FileOutputStream(filePath));
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
                image.close();
            }
        }, new Handler(imageReaderThread.getLooper()));

        int[] textures = new int[1];
        initTextureExternalOes(textures);
        mTextureExternalOes = textures[0];
        mSTexture = new SurfaceTexture (mTextureExternalOes);
        mSTexture.setOnFrameAvailableListener(this);

        initTexture2D(textures, ss.y, ss.x);
        mFrameBufferTextureId = textures[0];
        mFrameBufferId = createFrameBuffer(ss.y, ss.x, mFrameBufferTextureId);

        if (sUseEs3) {
            renderProgram = loadRenderShaders("vertexShader3.vert", "fragmentShader3.frag");
        } else {
            renderProgram = loadRenderShaders("vertexShader.vert", "fragmentShader.frag");
        }

        cacPreviewSize(ss.x, ss.y);
        openCamera();

        mGLInit = true;
    }

    public void onDrawFrame ( GL10 unused ) {
        if ( !mGLInit ) return;

        synchronized(this) {
            if (mUpdateST) {
                mSTexture.updateTexImage();
                mSTexture.getTransformMatrix(mTexMatrix);
                mUpdateST = false;
            }
        }

        Log.d(TAG, "TFX0: " + arrayString(mTexMatrix));

        Matrix.setIdentityM(mTFX, 0);
//        // back facing camera
//        Matrix.translateM(mTFX, 0, 1f, 1f, 0);
//        Matrix.scaleM(mTFX, 0, 1, -1, 1);
//        Matrix.rotateM(mTFX, 0, 90, 0, 0, 1);
        // front facing camera
        Matrix.translateM(mTFX, 0, 0f, 1f, 0);
        Matrix.rotateM(mTFX, 0, -90, 0, 0, 1);
        Log.d(TAG, "TFX1: " + arrayString(mTFX));

//        Matrix.setIdentityM(mTransformMatrix, 0);

        GLES31.glClearColor(0,0,0,0);
        GLES31.glClear(GLES31.GL_DEPTH_BUFFER_BIT | GLES31.GL_COLOR_BUFFER_BIT);

        GLES31.glUseProgram(renderProgram);

        if (sUseFbo) {
            GLES31.glViewport(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, mFrameBufferId);
        }

        int positionHandler = GLES31.glGetAttribLocation(renderProgram, "aPosition");
        int texCoordHandler = GLES31.glGetAttribLocation ( renderProgram, "aTextureCoord" );

        GLES31.glVertexAttribPointer(positionHandler, 2, GLES31.GL_FLOAT, false, 4 * 2, vertexBuffer);
        GLES31.glVertexAttribPointer(texCoordHandler, 2, GLES31.GL_FLOAT, false, 4 * 2, texCoords);

        GLES31.glEnableVertexAttribArray(positionHandler);
        GLES31.glEnableVertexAttribArray(texCoordHandler);

        GLES31.glActiveTexture(GLES31.GL_TEXTURE1);
        GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureExternalOes);

        // when in landscape mode, apply the following transformation
//        Matrix.translateM(mTransformMatrix, 0, 0.5f, 0.5f, 0);
//        Matrix.rotateM(mTransformMatrix, 0, (360 - mView.getDisplay().getRotation() * 90) % 360, 0, 0, 1);
//        Matrix.translateM(mTransformMatrix, 0, -0.5f, -0.5f, 0);
//        Log.d(TAG, "TFX2: " + arrayString(mTransformMatrix));

        GLES31.glUniformMatrix4fv(
                GLES31.glGetUniformLocation(renderProgram, "uTexMatrix"),
                1, false, mTexMatrix, 0);

        Matrix.setIdentityM(mMVPMatrix, 0);
        GLES31.glUniformMatrix4fv(
                GLES31.glGetUniformLocation(renderProgram, "uMVPMatrix"),
                1, false, mMVPMatrix, 0);

        if (sUseEs3) {
            if (mLut3DTextureId == -1) {
                mLut3DTextureId = loadLutTexture("Lucky_64.CUBE");
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4);
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, mLut3DTextureId);
            GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "sTexture3D"), 4);
            if (sUseLut3D) {
                GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "uEnableLut3D"), 1);
            } else {
                GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "uEnableLut3D"), 0);
            }
            GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "uTextureType"), TEXTURE_TYPE_YUV_TRANSFORM_601_FULL);
            GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "sTextureYUV"), 1);
        } else {
            GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "uTextureType"), TEXTURE_TYPE_EXT);
            GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "sTextureExt"), 1);
        }

        GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4);

        GLES31.glFlush();

        GLES31.glFinish();

        if (sDumpTexture) {
            dumpTexture(mView.getContext(), mTextureExternalOes, true,
                    0, 0, mPreviewSize.getWidth(), mPreviewSize.getHeight(),
                    "external.jpg");
            if (sUseFbo) {
                dumpTexture(mView.getContext(), mFrameBufferTextureId, false,
                        0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth(),
                        "internal.jpg");
            }
        }

        if (sUseFbo) {
            GLES31.glViewport(0, 0, mView.getWidth(), mView.getHeight());
            GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);

            GLES31.glActiveTexture(GLES31.GL_TEXTURE0);
            GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, mFrameBufferTextureId);

            Matrix.setIdentityM(mTexMatrix, 0);
            GLES31.glUniformMatrix4fv(
                    GLES31.glGetUniformLocation(renderProgram, "uTexMatrix"),
                    1, false, mTexMatrix, 0);

            Matrix.setIdentityM(mMVPMatrix, 0);
            GLES31.glUniformMatrix4fv(
                    GLES31.glGetUniformLocation(renderProgram, "uMVPMatrix"),
                    1, false, mMVPMatrix, 0);

            GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "uTextureType"), TEXTURE_TYPE_2D);
            GLES31.glUniform1i(GLES31.glGetUniformLocation(renderProgram, "sTexture2D"), 0);

            GLES31.glDrawArrays(GLES31.GL_TRIANGLE_STRIP, 0, 4);
        }

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);
        GLES31.glUseProgram(0);
    }

    /**
     * Checks to see if a GLES error has been raised.
     */
    public static void checkGlError(String op) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            String msg = op + ": glError 0x" + Integer.toHexString(error);
            Log.e(TAG, msg);
            throw new RuntimeException(msg);
        }
    }

    public void onSurfaceChanged ( GL10 unused, int width, int height) {
        GLES31.glViewport(0, 0, width, height);
    }

    private void initTexture2D(int[] textures, int width, int height) {
        GLES31.glGenTextures(1, textures, 0);
        GLES31.glBindTexture(GL10.GL_TEXTURE_2D, textures[0]);

        GLES31.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST);
        GLES31.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR);
        GLES31.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_REPEAT);
        GLES31.glTexParameteri(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_REPEAT);

        // GLES31.glTexImage2D(GL10.GL_TEXTURE_2D, 0, GL10.GL_RGBA, width, height, 0, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, null);
        GLES31.glTexStorage2D(GLES31.GL_TEXTURE_2D, 1, GLES31.GL_RGBA8, width, height);
        GLES31.glTexSubImage2D(GLES31.GL_TEXTURE_2D, 0,
                0, 0,
                width, height,
                GLES31.GL_RGBA, GLES31.GL_UNSIGNED_BYTE, null);
    }

    private int createFrameBuffer(int width, int height, int targetTextureId) {
        int framebuffer;
        int[] framebuffers = new int[1];
        GLES31.glGenFramebuffers(1, framebuffers, 0);
        framebuffer = framebuffers[0];
        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, framebuffer);

        int depthbuffer;
        int[] renderbuffers = new int[1];
        GLES31.glGenRenderbuffers(1, renderbuffers, 0);
        depthbuffer = renderbuffers[0];

        GLES31.glBindRenderbuffer(GLES31.GL_RENDERBUFFER, depthbuffer);
        GLES31.glRenderbufferStorage(GLES31.GL_RENDERBUFFER, GLES31.GL_DEPTH_COMPONENT16, width, height);
        GLES31.glFramebufferRenderbuffer(GLES31.GL_FRAMEBUFFER, GLES31.GL_DEPTH_ATTACHMENT, GLES31.GL_RENDERBUFFER, depthbuffer);

        GLES31.glFramebufferTexture2D(GLES31.GL_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, targetTextureId, 0);
        int status = GLES31.glCheckFramebufferStatus(GLES31.GL_FRAMEBUFFER);
        if (status != GLES31.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Framebuffer is not complete: " +
                    Integer.toHexString(status));
        }
        GLES20.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, 0);
        return framebuffer;
    }

    private void initTextureExternalOes(int[] textures) {
        GLES31.glGenTextures(1, textures, 0);
        GLES31.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_WRAP_S, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_WRAP_T, GLES31.GL_CLAMP_TO_EDGE);
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MIN_FILTER, GLES31.GL_NEAREST);
        GLES31.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES31.GL_TEXTURE_MAG_FILTER, GLES31.GL_NEAREST);
    }

    public synchronized void onFrameAvailable ( SurfaceTexture st ) {
        mUpdateST = true;
        mView.requestRender();
    }

    private int loadRenderShaders(String vert, String frag) {
        int vertexShader = GLES31.glCreateShader(GLES31.GL_VERTEX_SHADER);

        AssetManager assetManager = mView.getContext().getAssets();
        String vertexShaderSrc = "";
        try {
            BufferedReader vertexShaderReader = new BufferedReader(new InputStreamReader(assetManager.open(vert)));
            StringBuilder strBuilder = new StringBuilder();

            String line;
            while ((line = vertexShaderReader.readLine()) != null) {
                strBuilder.append(line);

                strBuilder.append("\n");
            }
            vertexShaderSrc = strBuilder.toString();

        } catch (IOException ex) {
            Log.e(TAG, "Error: " + ex.getMessage());
        }
        GLES31.glShaderSource(vertexShader, vertexShaderSrc);
        GLES31.glCompileShader(vertexShader);
        int[] compiled = new int[1];
        GLES31.glGetShaderiv(vertexShader, GLES31.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.v(TAG, "Could not compile vertexShader:" + GLES31.glGetShaderInfoLog(vertexShader));
            GLES31.glDeleteShader(vertexShader);
            vertexShader = 0;
        }

        int fragmentShader = GLES31.glCreateShader(GLES31.GL_FRAGMENT_SHADER);

        String fragmentShaderSrc = "";
        try {
            BufferedReader fragmentShaderBuffer = new BufferedReader(new InputStreamReader(assetManager.open(frag)));
            StringBuilder strBuilder = new StringBuilder();

            String line;
            while ((line = fragmentShaderBuffer.readLine()) != null) {
                strBuilder.append(line);

                strBuilder.append("\n");
            }
            fragmentShaderSrc = strBuilder.toString();

        } catch (IOException ex) {
            Log.e(TAG, "Error: " + ex.getMessage());
        }

        GLES31.glShaderSource(fragmentShader, fragmentShaderSrc);
        GLES31.glCompileShader(fragmentShader);
        GLES31.glGetShaderiv(fragmentShader, GLES31.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile fshader:" + GLES31.glGetShaderInfoLog(fragmentShader));
            GLES31.glDeleteShader(fragmentShader);
            fragmentShader = 0;
        }

        int program = GLES31.glCreateProgram();
        GLES31.glAttachShader(program, vertexShader);
        GLES31.glAttachShader(program, fragmentShader);
        GLES31.glLinkProgram(program);

        return program;
    }

    void cacPreviewSize( final int width, final int height ) {
        CameraManager manager = (CameraManager)mView.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraID : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                    continue;

                mCameraID = cameraID;
                int mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                for ( Size psize : map.getOutputSizes(SurfaceTexture.class)) {
                    Log.d(TAG, "getOutputSizes: " + psize + ", SensorOrientation: " + mSensorOrientation);
                    if ( width == psize.getWidth() && height == psize.getHeight() ) {
                        mPreviewSize = psize;
                        break;
                    }
                }
                break;
            }
        } catch ( CameraAccessException e ) {
            Log.e(TAG, "cacPreviewSize - Camera Access Exception");
        } catch ( IllegalArgumentException e ) {
            Log.e(TAG, "cacPreviewSize - Illegal Argument Exception");
        } catch ( SecurityException e ) {
            Log.e(TAG, "cacPreviewSize - Security Exception");
        }
    }

    void openCamera() {
        CameraManager manager = (CameraManager)mView.getContext().getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraID);
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraID,mStateCallback,mBackgroundHandler);
        } catch ( CameraAccessException e ) {
            Log.e(TAG, "OpenCamera - Camera Access Exception");
        } catch ( IllegalArgumentException e ) {
            Log.e(TAG, "OpenCamera - Illegal Argument Exception");
        } catch ( SecurityException e ) {
            Log.e(TAG, "OpenCamera - Security Exception");
        } catch ( InterruptedException e ) {
            Log.e(TAG, "OpenCamera - Interrupted Exception");
        }
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

    };

    private void createCameraPreviewSession() {
        try {
            mSTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            Surface surface0 = new Surface(mSTexture);
            Surface surface1 = mImageReader.getSurface();

            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface0);
            mPreviewRequestBuilder.addTarget(surface1);

            mCameraDevice.createCaptureSession(Arrays.asList(surface0, surface1),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice)
                                return;

                            mCaptureSession = cameraCaptureSession;
                            try {
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "createCaptureSession");
                            }
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCameraPreviewSession");
        }
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread == null) {
            return;
        }
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            Log.e(TAG, "stopBackgroundThread");
        }
    }

    private static void dumpTexture(Context context, int textureId, boolean isExternal, int x, int y, int w, int h, String fileName) {
        int[] old_fbo = new int[1];
        GLES31.glGetIntegerv(GLES31.GL_FRAMEBUFFER_BINDING, old_fbo, 0);
        Log.d(TAG, "old fbo: " + old_fbo[0]);

        int[] framebuffers = new int[1];
        GLES31.glGenFramebuffers(1, framebuffers, 0);
        Log.d(TAG, "new fbo: " + framebuffers[0]);

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, framebuffers[0]);

        if (isExternal) {
            GLES31.glFramebufferTexture2D(
                    GLES31.GL_FRAMEBUFFER,
                    GLES31.GL_COLOR_ATTACHMENT0,
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId, 0);
        } else {
            GLES31.glFramebufferTexture2D(
                    GLES31.GL_FRAMEBUFFER,
                    GLES31.GL_COLOR_ATTACHMENT0,
                    GLES31.GL_TEXTURE_2D, textureId, 0);
        }

        ByteBuffer buf = ByteBuffer.allocate(w * h * 4);
        GLES20.glReadPixels(x, y, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        File filePath = new File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName);
        saveBitmap(buf, w, h, Bitmap.Config.ARGB_8888, filePath);

        GLES31.glBindFramebuffer(GLES31.GL_FRAMEBUFFER, old_fbo[0]);
        GLES31.glDeleteFramebuffers(1, framebuffers, 0);
    }

    private static boolean saveBitmap(Buffer buf, int w, int h, Bitmap.Config config, File path) {
        boolean succeed = false;
        if (buf != null) {
            Bitmap bmp = Bitmap.createBitmap(w, h, config);
            bmp.copyPixelsFromBuffer(buf);
            try (FileOutputStream fos = new FileOutputStream(path)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                succeed = true;
            } catch (IOException e) {
                Log.e(TAG, "saveBitmap failed!", e);
            }
            bmp.recycle();
        }
        return succeed;
    }

    private int loadLutTexture(String lutName) {
        long startTime = System.currentTimeMillis();
        ByteBuffer lut3d = null;
        int lutBlockSize = 0;
        try (InputStream in = mView.getContext().getAssets().open(lutName);
             BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                line = line.trim();
                if(line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\s+");
                if(parts[0].equals("TITLE")) {
                    Log.d(TAG, "TITLE:" + parts[1]);
                } else if(parts[0].equals("DOMAIN_MIN")) {
                    Log.d(TAG, "DOMAIN_MIN:" + parts[1] + "," + parts[2] + "," + parts[3]);
                } else if(parts[0].equals("DOMAIN_MAX")) {
                    Log.d(TAG, "DOMAIN_MAX:" + parts[1] + "," + parts[2] + "," + parts[3]);
                } else if(parts[0].equals("LUT_1D_SIZE")) {
                    Log.d(TAG, "LUT_1D_SIZE:" + Integer.parseInt(parts[1]));
                } else if(parts[0].equals("LUT_3D_SIZE")) {
                    lutBlockSize = Integer.parseInt(parts[1]);
                    Log.d(TAG, "LUT_3D_SIZE:" + lutBlockSize);
                    lut3d = ByteBuffer.allocateDirect(lutBlockSize * lutBlockSize * lutBlockSize * 3);
                } else if (lut3d != null) {
                    int r = Math.min(Math.max(0, (int) (Float.parseFloat(parts[0]) * 255)), 255);
                    int g = Math.min(Math.max(0, (int) (Float.parseFloat(parts[1]) * 255)), 255);
                    int b = Math.min(Math.max(0, (int) (Float.parseFloat(parts[2]) * 255)), 255);
//                    Log.e(TAG, "loadLutTexture: " + r + "," + g + "," + b);
                    lut3d.put((byte) (r  & 0xFF));
                    lut3d.put((byte) (g  & 0xFF));
                    lut3d.put((byte) (b  & 0xFF));
                }
            }
            Log.d(TAG, "loadLutTexture success! cost:" + (System.currentTimeMillis() - startTime));
        } catch (IOException e) {
            Log.e(TAG, "loadLutTexture fail, error:" + e.getCause());
        }
        if (lut3d == null || lut3d.limit() != lutBlockSize * lutBlockSize * lutBlockSize * 3) {
            throw new IllegalStateException("failed to create lut3d");
        }
        lut3d.position(0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        int[] textures = new int[1];
        GLES31.glGenTextures(1, textures, 0);
        int texture =textures[0];
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, texture);
        GLES20.glTexParameterf(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8,
                lutBlockSize, lutBlockSize, lutBlockSize,
                0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, lut3d);
        checkGlError("glTexImage3D");
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, 0);
        return texture;
    }

    static String arrayString(float[] array) {
        StringBuffer sb = new StringBuffer();
        for (float v : array) {
            if (v < 0.001) {
                sb.append(", 0");
            } else {
                sb.append(", " + v);
            }
        }
        return sb.toString();
    }
}

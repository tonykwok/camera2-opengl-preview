package com.jxtras.android.sample.camera2opengl;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.SurfaceHolder;

public class CustomGLSurfaceView extends GLSurfaceView {

    CustomGLRenderer mRenderer;

    CustomGLSurfaceView ( Context context) {
        super ( context );
        mRenderer = new CustomGLRenderer(this);
        setEGLContextClientVersion ( 3 );
        setRenderer ( mRenderer );
        setRenderMode ( GLSurfaceView.RENDERMODE_WHEN_DIRTY );
    }

    public CustomGLSurfaceView(Context context, AttributeSet attrs) {
        super ( context, attrs );
        mRenderer = new CustomGLRenderer(this);
        setEGLContextClientVersion ( 3 );
        setRenderer ( mRenderer );
        setRenderMode ( GLSurfaceView.RENDERMODE_WHEN_DIRTY );
    }

    @Override
    public void surfaceCreated ( SurfaceHolder holder ) {
        super.surfaceCreated(holder);
    }

    @Override
    public void surfaceDestroyed ( SurfaceHolder holder ) {
        super.surfaceDestroyed(holder);
    }

    @Override
    public void surfaceChanged ( SurfaceHolder holder, int format, int w, int h ) {
        super.surfaceChanged(holder, format, w, h);
    }

    @Override
    public void onResume() {
        super.onResume();
        mRenderer.onResume();
    }

    @Override
    public void onPause() {
        mRenderer.onPause();
        super.onPause();
    }
}
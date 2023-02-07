#extension GL_OES_EGL_image_external : require

precision mediump float;

varying vec2 vTextureCoord;

uniform int uTextureType;
uniform sampler2D sTexture2D;
uniform samplerExternalOES sTextureExt;

void main() {
    if (uTextureType == 0) {
        gl_FragColor = texture2D(sTexture2D, vTextureCoord);
    } else {
        gl_FragColor = texture2D(sTextureExt, vTextureCoord);
    }
}
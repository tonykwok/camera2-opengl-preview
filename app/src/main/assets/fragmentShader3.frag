#version 300 es

#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_OES_EGL_image_external : require
#extension GL_EXT_YUV_target : require

precision mediump float;

in vec2 vTextureCoord;
out vec4 outColor;

uniform int uTextureType;
uniform sampler2D sTexture2D;

uniform int uEnableLut3D;
uniform sampler3D sTexture3D;

uniform __samplerExternal2DY2YEXT sTextureYUV;
// uniform samplerExternalOES sTextureExt;

// Kr = 0.2126, Kg = 0.7152, Kb = 0.0722
const mat3 MATRIX_COLOR_TRANSFORM_BT709_FULL = mat3(
             1,                   1,      1,
             0, -0.1873242729306487, 1.8556,
        1.5748, -0.4681242729306488,      0
);

const mat3 MATRIX_COLOR_TRANSFORM_BT709_LIMITED = mat3(
    1.164383561643835,  1.164383561643835,  1.164383561643835,
                    0, -0.2132486142737296, 2.112401785714286,
    1.792741071428571, -0.532909328559444,                  0
);

// Kr = 0.299, Kg = 0.587, Kb = 0.114
const mat3 MATRIX_COLOR_TRANSFORM_BT601_FULL = mat3(
            1,                   1,     1,
            0, -0.3441362862010221, 1.772,
        1.402, -0.7141362862010221,     0
);

const mat3 MATRIX_COLOR_TRANSFORM_BT601_LIMITED = mat3(
    1.164383561643835,   1.164383561643835,   1.164383561643835,
                    0,  -0.3917622900949136,  2.017232142857143,
    1.596026785714285,  -0.8129676472377707,                  0
);

// color.rgb = vec3(MATRIX_COLOR_TRANSFORM_BT601_FULL * inverse(MATRIX_COLOR_TRANSFORM_BT709_FULL) * color.rgb);

void main() {
    vec4 color;
    if (uTextureType == 0) {
        color = texture(sTexture2D, vTextureCoord);
    } else if (uTextureType == 1) {
        // color = texture(sTextureExt, vTextureCoord);
    } else if (uTextureType == 2) {
        vec3 srcYuv = texture(sTextureYUV, vTextureCoord).xyz;
        // yuvCscStandardEXT conv1 = itu_601;
        // yuvCscStandardEXT conv2 = itu_601_full_range;
        // yuvCscStandardEXT conv3 = itu_709;
        color = vec4(yuv_2_rgb(srcYuv, itu_601), 1.0);
    }  else if (uTextureType == 3) {
        vec3 srcYuv = texture(sTextureYUV, vTextureCoord).xyz;
        // yuvCscStandardEXT conv1 = itu_601;
        // yuvCscStandardEXT conv2 = itu_601_full_range;
        // yuvCscStandardEXT conv3 = itu_709;
        color = vec4(yuv_2_rgb(srcYuv, itu_601_full_range), 1.0);
    }  else if (uTextureType == 4) {
        vec3 srcYuv = texture(sTextureYUV, vTextureCoord).xyz;
        // yuvCscStandardEXT conv1 = itu_601;
        // yuvCscStandardEXT conv2 = itu_601_full_range;
        // yuvCscStandardEXT conv3 = itu_709;
        color = vec4(yuv_2_rgb(srcYuv, itu_709), 1.0);
    } else if (uTextureType == 5) {
        // 601 yuv full range
        vec3 srcYuv = texture(sTextureYUV, vTextureCoord).xyz;
        vec3 yuvOffset;
        yuvOffset.x = srcYuv.r - 0.0; // y
        yuvOffset.y = srcYuv.g - 0.5; // u
        yuvOffset.z = srcYuv.b - 0.5; // v
        color = vec4(MATRIX_COLOR_TRANSFORM_BT601_FULL * yuvOffset, 1.0);
    } else if (uTextureType == 6) {
        // 601 yuv limited range
        vec3 srcYuv = texture(sTextureYUV, vTextureCoord).xyz;
        vec3 yuvOffset;
        yuvOffset.x = srcYuv.r - 0.0625; // y
        yuvOffset.y = srcYuv.g - 0.5;    // u
        yuvOffset.z = srcYuv.b - 0.5;    // v
        color = vec4(MATRIX_COLOR_TRANSFORM_BT601_LIMITED * yuvOffset, 1.0);
    }  else if (uTextureType == 7) {
        // 709 yuv full range
        vec3 srcYuv = texture(sTextureYUV, vTextureCoord).xyz;
        vec3 yuvOffset;
        yuvOffset.x = srcYuv.r - 0.0; // y
        yuvOffset.y = srcYuv.g - 0.5; // u
        yuvOffset.z = srcYuv.b - 0.5; // v
        color = vec4(MATRIX_COLOR_TRANSFORM_BT709_FULL * yuvOffset, 1.0);
    } else if (uTextureType == 8) {
        // 709 yuv limited range
        vec3 srcYuv = texture(sTextureYUV, vTextureCoord).xyz;
        vec3 yuvOffset;
        yuvOffset.x = srcYuv.r - 0.0625; // y
        yuvOffset.y = srcYuv.g - 0.5;    // u
        yuvOffset.z = srcYuv.b - 0.5;    // v
        color = vec4(MATRIX_COLOR_TRANSFORM_BT709_LIMITED * yuvOffset, 1.0);
    } else {
        color = vec4(1.0, 0.0, 0.0, 0.0);
    }
    if (uEnableLut3D == 1) {
        color.rgb = texture(sTexture3D, color.rgb).rgb;
    }
    outColor = color;
}
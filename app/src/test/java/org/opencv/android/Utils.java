package org.opencv.android;

import android.graphics.Bitmap;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

/**
 * Test-classpath replacement for OpenCV's {@code org.opencv.android.Utils}.
 *
 * <p>The real class is the one piece of the app's OpenCV usage that cannot work
 * off-device: its {@code bitmapToMat} is a thin wrapper over the native
 * {@code nBitmapToMat2}, which is implemented only in the <em>Android</em> build
 * of {@code libopencv_java4} (it calls the NDK's {@code AndroidBitmap_lockPixels}
 * on a real {@code android.graphics.Bitmap}). No desktop OpenCV binary exports
 * that symbol at any version, so with the real class on the classpath a JVM test
 * clears {@code System.loadLibrary} and then dies at the first conversion.
 *
 * <p>Everything else the recognizer touches — {@code cvtColor}, {@code resize},
 * {@code matchTemplate}, {@code minMaxLoc}, {@code Mat}, {@code Rect},
 * {@code Size} — is ordinary desktop OpenCV, so replacing just this conversion
 * with a pure-Java equivalent lets the real template-matching code run locally.
 * It also makes the desktop binary's exact version irrelevant, since the only
 * Android-specific entry point is gone.
 *
 * <p>This lives in the test source set and shadows the AAR's class by classpath
 * order; nothing in {@code src/main} is affected. Only the two methods the app
 * actually calls are implemented — anything else would fail loudly rather than
 * silently return wrong pixels.
 */
public class Utils {

    /**
     * Produces the same CV_8UC4 RGBA matrix the Android implementation does.
     * Robolectric's Bitmap is backed by a real BufferedImage, so getPixels
     * returns genuine ARGB_8888 values rather than zeros.
     */
    public static void bitmapToMat(Bitmap bmp, Mat mat, boolean unPremultiplyAlpha) {
        if (bmp == null) throw new IllegalArgumentException("bmp == null");
        if (mat == null) throw new IllegalArgumentException("mat == null");
        int width = bmp.getWidth();
        int height = bmp.getHeight();
        int[] pixels = new int[width * height];
        bmp.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int o = i * 4;
            rgba[o] = (byte) ((p >> 16) & 0xFF);
            rgba[o + 1] = (byte) ((p >> 8) & 0xFF);
            rgba[o + 2] = (byte) (p & 0xFF);
            rgba[o + 3] = (byte) ((p >>> 24) & 0xFF);
        }
        mat.create(height, width, CvType.CV_8UC4);
        mat.put(0, 0, rgba);
    }

    public static void bitmapToMat(Bitmap bmp, Mat mat) {
        bitmapToMat(bmp, mat, false);
    }

    public static void matToBitmap(Mat mat, Bitmap bmp, boolean premultiplyAlpha) {
        if (mat == null) throw new IllegalArgumentException("mat == null");
        if (bmp == null) throw new IllegalArgumentException("bmp == null");
        int width = mat.cols();
        int height = mat.rows();
        int channels = mat.channels();
        byte[] data = new byte[width * height * channels];
        mat.get(0, 0, data);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int o = i * channels;
            int r = data[o] & 0xFF;
            int g = channels > 1 ? data[o + 1] & 0xFF : r;
            int b = channels > 2 ? data[o + 2] & 0xFF : r;
            int a = channels > 3 ? data[o + 3] & 0xFF : 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    public static void matToBitmap(Mat mat, Bitmap bmp) {
        matToBitmap(mat, bmp, false);
    }
}

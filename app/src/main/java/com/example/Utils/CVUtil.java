package com.example.Utils;

import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;
import android.widget.Toast;

import org.opencv.core.Point;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;

import static java.lang.Math.pow;
import static java.lang.Math.sqrt;
import static org.opencv.core.CvType.CV_8UC1;

public class CVUtil
{
    public static Mat yuvImageToRgbMat(Image image)
    {
        Mat mat = new Mat();

        Image.Plane[] planes = image.getPlanes();
        int w = image.getWidth();
        int h = image.getHeight();

        int chromaPixelStride = planes[1].getPixelStride();

        if (chromaPixelStride == 2) { // Chroma channels are interleaved
            assert (planes[0].getPixelStride() == 1);
            assert (planes[2].getPixelStride() == 2);
            ByteBuffer y_plane = planes[0].getBuffer();
            ByteBuffer uv_plane1 = planes[1].getBuffer();
            ByteBuffer uv_plane2 = planes[2].getBuffer();
            Mat y_mat = new Mat(h, w, CvType.CV_8UC1, y_plane);
            Mat uv_mat1 = new Mat(h / 2, w / 2, CvType.CV_8UC2, uv_plane1);
            Mat uv_mat2 = new Mat(h / 2, w / 2, CvType.CV_8UC2, uv_plane2);
            long addr_diff = uv_mat2.dataAddr() - uv_mat1.dataAddr();
            if (addr_diff > 0) {
                assert (addr_diff == 1);
                Imgproc.cvtColorTwoPlane(y_mat, uv_mat1, mat, Imgproc.COLOR_YUV2RGBA_NV12);
            } else {
                assert (addr_diff == -1);
                Imgproc.cvtColorTwoPlane(y_mat, uv_mat2, mat, Imgproc.COLOR_YUV2RGBA_NV21);
            }

            y_mat.release();
            uv_mat1.release();
            uv_mat2.release();

            return mat;
        } else { // Chroma channels are not interleaved
            byte[] yuv_bytes = new byte[w * (h + h / 2)];
            ByteBuffer y_plane = planes[0].getBuffer();
            ByteBuffer u_plane = planes[1].getBuffer();
            ByteBuffer v_plane = planes[2].getBuffer();

            y_plane.get(yuv_bytes, 0, w * h);

            int chromaRowStride = planes[1].getRowStride();
            int chromaRowPadding = chromaRowStride - w / 2;

            int offset = w * h;
            if (chromaRowPadding == 0) {
                // When the row stride of the chroma channels equals their width, we can copy
                // the entire channels in one go
                u_plane.get(yuv_bytes, offset, w * h / 4);
                offset += w * h / 4;
                v_plane.get(yuv_bytes, offset, w * h / 4);
            } else {
                // When not equal, we need to copy the channels row by row
                for (int i = 0; i < h / 2; i++) {
                    u_plane.get(yuv_bytes, offset, w / 2);
                    offset += w / 2;
                    if (i < h / 2 - 1) {
                        u_plane.position(u_plane.position() + chromaRowPadding);
                    }
                }
                for (int i = 0; i < h / 2; i++) {
                    v_plane.get(yuv_bytes, offset, w / 2);
                    offset += w / 2;
                    if (i < h / 2 - 1) {
                        v_plane.position(v_plane.position() + chromaRowPadding);
                    }
                }
            }

            Mat yuv_mat = new Mat(h + h / 2, w, CvType.CV_8UC1);
            yuv_mat.put(0, 0, yuv_bytes);
            Imgproc.cvtColor(yuv_mat, mat, Imgproc.COLOR_YUV2RGBA_I420, 4);

            yuv_mat.release();
            return mat;
        }
    }

    public static Mat getPerspectiveTransformImageOfPoint(Mat image, Point[] points)
    {
        double w1 = sqrt(pow(points[2].x - points[3].x, 2) + pow(points[2].x - points[3].x, 2));
        double w2 = sqrt(pow(points[1].x - points[0].x, 2) + pow(points[1].x - points[0].x, 2));

        double h1 = sqrt(pow(points[1].y - points[2].y, 2) + pow(points[1].y - points[2].y, 2));
        double h2 = sqrt(pow(points[0].y - points[3].y, 2) + pow(points[0].y - points[3].y, 2));

        double maxWidth = w1 < w2 ? w1 : w2;
        double maxHeight = h1 < h2 ? h1 : h2;

        MatOfPoint2f sourcePoint = new MatOfPoint2f(
            points[0],
            points[1],
            points[2],
            points[3]
        );

        MatOfPoint2f warpPoint = new MatOfPoint2f(
            new org.opencv.core.Point(0, 0),
            new org.opencv.core.Point(maxWidth - 1, 0),
            new org.opencv.core.Point(maxWidth - 1, maxHeight - 1),
            new org.opencv.core.Point(0, maxHeight - 1)
        );

        Mat trans = Imgproc.getPerspectiveTransform(sourcePoint, warpPoint);

        Mat resultImage = new Mat();
        Imgproc.warpPerspective(image, resultImage, trans, new org.opencv.core.Size(maxWidth, maxHeight));

        trans.release();
        warpPoint.release();
        sourcePoint.release();

        return resultImage;
    }
}

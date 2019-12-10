package com.example.Handler;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;

import com.example.Activity.MainActivity;
import com.example.Message.ProcessMessage;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DetectHandler extends Handler
{
    MainActivity mainActivity;
    Mat matTemp;

    boolean imageDetectRunning = false;

    public DetectHandler(Looper lopper, MainActivity mainActivity)
    {
        super(lopper);
        this.mainActivity = mainActivity;
    }

    final Comparator<Point> sumComparator = new Comparator<Point>() {
        @Override
        public int compare(Point lhs, Point rhs) {
            return Double.valueOf(lhs.y + lhs.x).compareTo(rhs.y + rhs.x);
        }
    };
    final Comparator<Point> diffComparator = new Comparator<Point>() {
        @Override
        public int compare(Point lhs, Point rhs) {
            return Double.valueOf(lhs.y - lhs.x).compareTo(rhs.y - rhs.x);
        }
    };

   final Comparator<MatOfPoint> equalComparator =  new Comparator<MatOfPoint>() {
        @Override
        public int compare(MatOfPoint lhs, MatOfPoint rhs) {
            return Double.valueOf(Imgproc.contourArea(rhs)).compareTo(Imgproc.contourArea(lhs));
        }
    };

    @Override
    public void handleMessage(@NonNull Message msg)
    {
        if(msg.obj.getClass() == ProcessMessage.class)
        {
            ProcessMessage processMessage = (ProcessMessage)msg.obj;
            String command = processMessage.getCommand();

            if(command.equals("detectRectangle"))
            {
                Mat source = processMessage.getFrame();
                if(source != null)
                {
                    imageDetectRunning = true;
                    detectRectangle(source);
                }
            }
        }
    }

    public void detectRectangle(Mat mat)
    {
        double realWidth = mat.size().width;
        double realHeight = mat.size().height;

        double ratio = (realHeight / realWidth) * 2;
        int width = Double.valueOf(realWidth / ratio).intValue();
        int height = Double.valueOf(realHeight / ratio).intValue();

        Size matSize = new Size(width,height);

        if(matTemp == null)
        {
            matTemp = new Mat(matSize, CvType.CV_8UC4);
        }

        Imgproc.resize(mat,matTemp,matSize);
        Imgproc.cvtColor(matTemp, matTemp, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.GaussianBlur(matTemp, matTemp, new Size(5, 5), 0);
        Imgproc.Canny(matTemp, matTemp, 100, 200, 3);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(matTemp, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        hierarchy.release();

        Collections.sort(contours, equalComparator);

        List<Point[]> drawPoint = new ArrayList<>();

        for(MatOfPoint contour : contours)
        {
            MatOfPoint2f src = new MatOfPoint2f(contour.toArray());

            MatOfPoint2f approx = new MatOfPoint2f();
            Imgproc.approxPolyDP(src, approx, Imgproc.arcLength(src, true) * 0.02, true);

            src.release();

            double area = Math.abs(Imgproc.contourArea(approx));

            if(area > 10000)
            {
                int size = (int)approx.total();
                Point[] points = approx.toArray();

                if(size == 4)
                {
                    Point[] rescaledPoints = new Point[4];

                    for( int j = 0; j < 4 ; ++j)
                    {
                        rescaledPoints[j] = new Point((int)(points[j].x*ratio), (int)(points[j].y*ratio));
                    }

                    drawPoint.add(sortPoints(rescaledPoints));
                }

                points = null;
            }
            approx.release();
        }

        mainActivity.setDrawPoint(drawPoint, mat.clone());
        mainActivity.redrawSurface();

        //release memory
        for(MatOfPoint p : contours)
        {
            p.release();
        }
        matTemp.release();
        mat.release();
        drawPoint = null;

        imageDetectRunning = false;
    }

    private Point[] sortPoints( Point[] src )
    {
        ArrayList<Point> srcPoints = new ArrayList<>(Arrays.asList(src));
        Point[] result = { null , null , null , null };

        result[0] = Collections.min(srcPoints, sumComparator);
        result[2] = Collections.max(srcPoints, sumComparator);
        result[1] = Collections.min(srcPoints, diffComparator);
        result[3] = Collections.max(srcPoints, diffComparator);

        srcPoints = null;

        return result;
    }

    public void setDetectRunning(boolean imageDetectRunning)
    {
        this.imageDetectRunning = imageDetectRunning;
    }

    public boolean isImageDetectRunning()
    {
        return imageDetectRunning;
    }
}

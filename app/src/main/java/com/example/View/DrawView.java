package com.example.View;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.opencv.core.Mat;
import org.opencv.core.Point;

import java.util.ArrayList;
import java.util.List;

public class DrawView extends View
{
    private Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Point[]> drawPoint = new ArrayList<>(10);
    private Mat currentMat = null;

    public DrawView(Context context)
    {
        super(context);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(6);
        linePaint.setColor(Color.argb(255, 3, 169, 244));

        areaPaint.setStyle(Paint.Style.FILL);
        areaPaint.setColor(Color.argb(50, 3, 169, 244));
    }

    public void setDrawPoint(List<Point[]> drawPoint, Mat currentMat)
    {
        this.drawPoint = null;
        this.drawPoint = drawPoint;

        if(this.currentMat != null)
        {
            this.currentMat.release();
        }

        this.currentMat = currentMat;
    }

    @Override
    protected void onDraw(Canvas canvas)
    {
        for(Point[] rect : drawPoint)
        {
            if(rect.length == 4)
            {
                float widthRatio = (float)this.getWidth() / (float)currentMat.size().width;
                float heightRatio = (float)this.getHeight() / (float)currentMat.size().height;

                Point[] points = new Point[rect.length];
                for(int i = 0; i < points.length; ++i)
                {
                    points[i] = new Point(rect[i].x * widthRatio, rect[i].y * heightRatio);
                }

                Path path  = new Path();
                path.moveTo((float)points[0].x, (float)points[0].y);
                path.lineTo((float)points[1].x, (float)points[1].y);
                path.lineTo((float)points[2].x, (float)points[2].y);
                path.lineTo((float)points[3].x, (float)points[3].y);
                path.lineTo((float)points[0].x, (float)points[0].y);

                canvas.drawPath(path, areaPaint);
                canvas.drawPath(path, linePaint);
            }
        }
    }
}

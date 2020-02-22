package com.example.Message;

import org.opencv.core.Mat;

public class ProcessMessage
{
    private String command;
    private Mat frame;

    public ProcessMessage(String command, Mat frame)
    {
        this.command = command;
        this.frame = frame;
    }

    public String getCommand()
    {
        return command;
    }

    public Mat getFrame()
    {
        return frame;
    }
}
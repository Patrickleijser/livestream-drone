package com.testdrone.patrickleijser.testdrone.controller;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.net.Uri;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.codeminders.ardrone.ARDrone;
import com.codeminders.ardrone.VideoDataDecoder;
import com.testdrone.patrickleijser.testdrone.BuildConfig;
import com.testdrone.patrickleijser.testdrone.R;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import javax.microedition.khronos.opengles.GL10;

public class ARDrone20MediaCodecDecoder extends VideoDataDecoder {

    private static final String TAG = "MEDIACODEC";
    private static final String MIME_TYPE = "video/avc";

    private PlayerThread mPlayer = null;
    private Surface mSurface;
    protected Handler mHandler = null;

    protected static ArrayList<Frame> mFrames;
    protected static int mFrameID;
    protected static boolean mIncompleteLastFrame;


    public ARDrone20MediaCodecDecoder(ARDrone drone, Surface surface) {
        super(drone);

        this.mSurface = surface;

        // Create decoder/player thread
        /*if(mPlayer == null) {
            mPlayer = new PlayerThread(mSurface);
            mPlayer.start();
        }*/
    }

    @Override
    public void run() {

		InputStream fin = getDataReader().getDataStream();
        byte[] data = new byte[8000]; // Max input array
        mFrameID = 0;
        mFrames = new ArrayList<Frame>();

        // Read data and parse into frames
        while(!Thread.interrupted()) {
            try {
                if ((fin.read(data)) != -1) {
                    getFramesFromData(data);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void finish() {
        // silence is golden
    }

    public static void getFramesFromData(byte[] data)
    {
        int dataLength = data.length;
        int frameLength = 0;
        mFrameID = 0;

        if(data.length <= 0) return;

        // each iteration in this loop indicates generation of a new frame
        for(int i = 0; ; )
        {
            if(i+3 >= dataLength) return;

            frameLength = data[i] << 24 |data[i+1] << 16 | data[i+2] << 8
                    | data[i+3];

            i += 4;

            if(frameLength > 0)
            {
                if(i+frameLength-1 >= dataLength) return;
                Frame frame = new Frame(mFrameID);
                frame.frameData = new byte[frameLength];
                System.arraycopy(data, i, frame.frameData, 0, frameLength);
                mFrames.add(frame);
                mFrameID++;
                i += frameLength;
            }

        }
    }

    private static class Frame
    {
        public int id;
        public byte[] frameData;

        public Frame(int id)
        {
            this.id = id;
        }
    }

    private class PlayerThread extends Thread
    {
        private MediaCodec mDecoder;
        private Surface mSurface;
        private MediaFormat mFormat;

        public PlayerThread(Surface surface)
        {
            this.mSurface = surface;
        }

        @Override
        public void run()
        {

            try {
                mDecoder = MediaCodec.createDecoderByType(MIME_TYPE);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage());
            }

            mFormat = MediaFormat.createVideoFormat(MIME_TYPE, 640, 368);

            mDecoder.configure(mFormat, mSurface, null, 0);

            if (mDecoder == null)
            {
                Log.e(TAG, "Can't find video info!");
                return;
            }

            mDecoder.start();

            while(!Thread.interrupted())
            {
                // Check if frames in queue
                if(mFrames.size() > 0)
                    break;

                byte[] frameData = new byte[mFrames.get(0).frameData.length];
                System.arraycopy(mFrames.get(0).frameData, 0, frameData, 0, mFrames.get(0).frameData.length);


                if(frameData.length == 1) // Just for the moment, to cope with the first pakets get lost because of missing ARP, see http://stackoverflow.com/questions/11812731/first-udp-message-to-a-specific-remote-ip-gets-lost
                    continue;

                int NAL_START = 1;
                //103, 104 -> SPS, PPS  | 101 -> Data
                int id = 0;
                int dataOffset = 0;

                //Later on this will be serversided, but for now...
                //Separate the SPSPPS from the Data
                for(int i = 0; i < frameData.length - 4; i++)
                {
                    id = frameData[i] << 24 |frameData[i+1] << 16 | frameData[i+2] << 8
                            | frameData[i+3];

                    if(id == NAL_START) {
                        if(frameData[i+4] == 101)
                        {
                            dataOffset = i;
                        }
                    }
                }


                byte[] SPSPPS = Arrays.copyOfRange(frameData, 0, dataOffset);
                byte[] data = Arrays.copyOfRange(frameData, dataOffset, frameData.length);

                if(SPSPPS.length != 0) {
                    int inIndex = mDecoder.dequeueInputBuffer(100000);

                    if(inIndex >= 0)
                    {
                        ByteBuffer input = mDecoder.getInputBuffer(inIndex);
                        input.clear();
                        input.put(SPSPPS);
                        mDecoder.queueInputBuffer(inIndex, 0, SPSPPS.length, 32, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                    }
                }

                int inIndex = mDecoder.dequeueInputBuffer(10000);
                if(inIndex >= 0)
                {
                    ByteBuffer inputBuffer = mDecoder.getInputBuffer(inIndex);
                    inputBuffer.clear();
                    //inputBuffer.put(data);
                    inputBuffer.put(data);
                    //codec.queueInputBuffer(inIndex, 0, data.length, 16, 0);
                    mDecoder.queueInputBuffer(inIndex, 0, data.length, 32, 0);
                }

                MediaCodec.BufferInfo buffInfo = new MediaCodec.BufferInfo();
                int outIndex = mDecoder.dequeueOutputBuffer(buffInfo, 10000);

                Log.d("MediaCodec", "OutIndex: " + outIndex);
                switch(outIndex)
                {
                    case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                        break;
                    case MediaCodec.INFO_TRY_AGAIN_LATER:
                        break;
                    case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                        break;
                    default:
                        ByteBuffer buffer = mDecoder.getOutputBuffer(outIndex);
                        mDecoder.releaseOutputBuffer(outIndex, true);
                }

            }

            mDecoder.stop();
            mDecoder.release();
        }
    }

}

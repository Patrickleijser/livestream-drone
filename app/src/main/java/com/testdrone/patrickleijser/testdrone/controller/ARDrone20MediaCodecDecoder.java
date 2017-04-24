package com.testdrone.patrickleijser.testdrone.controller;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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
import java.util.Arrays;
import java.util.logging.Logger;

import javax.microedition.khronos.opengles.GL10;

public class ARDrone20MediaCodecDecoder extends VideoDataDecoder {

    Logger  log = Logger.getLogger(this.getClass().getName());

    private Context context;
    private static final String TAG = "MEDIACODEC";
    private static final String mimeType = "video/avc";
    //private static final String SAMPLE = "android.resource://"+ BuildConfig.APPLICATION_ID+"/"+R.raw.test;
    private Surface surface;
    private MediaCodec codec;
    private MediaExtractor extractor;
    private MediaFormat format;

    public ARDrone20MediaCodecDecoder(ARDrone drone, Surface surface, Context c) {
        super(drone);

        this.context = c;
        this.surface = surface;
    }

    @Override
    public void run() {


		InputStream fin = getDataReader().getDataStream();
        long startMs = System.currentTimeMillis();

        //Configuring Media Decoder
        try {
            codec = MediaCodec.createDecoderByType("video/avc");
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", 720,1280);

        codec.configure(format, surface, null, 0);
        codec.start();


        while(!Thread.interrupted())
        {
            int frameSize = 0;
            byte[] frameData = new byte[1572864];
            try {
                fin.read(frameData);
            } catch (IOException e) {
                e.printStackTrace();
            }

            if(frameData.length == 1) // Just for the moment, to cope with the first pakets get lost because of missing ARP, see http://stackoverflow.com/questions/11812731/first-udp-message-to-a-specific-remote-ip-gets-lost
                continue;

            /*Edit: This part may be left out*/
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
                int inIndex = codec.dequeueInputBuffer(100000);

                if(inIndex >= 0)
                {
                    ByteBuffer input = codec.getInputBuffer(inIndex);
                    input.clear();
                    input.put(SPSPPS);
                    codec.queueInputBuffer(inIndex, 0, SPSPPS.length, 32, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                }
            }
            /*Edit end*/

            int inIndex = codec.dequeueInputBuffer(10000);
            if(inIndex >= 0)
            {
                ByteBuffer inputBuffer = codec.getInputBuffer(inIndex);
                inputBuffer.clear();
                //inputBuffer.put(data);
                inputBuffer.put(data);
                //codec.queueInputBuffer(inIndex, 0, data.length, 16, 0);
                codec.queueInputBuffer(inIndex, 0, data.length, 32, 0);
            }

            MediaCodec.BufferInfo buffInfo = new MediaCodec.BufferInfo();
            int outIndex = codec.dequeueOutputBuffer(buffInfo, 10000);

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
                    ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    codec.releaseOutputBuffer(outIndex, true);
            }


        }




    }

    @Override
    public void finish() {

        // silence is golden
    }



}

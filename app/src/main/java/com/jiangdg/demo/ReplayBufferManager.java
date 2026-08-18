package com.jiangdg.demo;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;

import com.jiangdg.ausbc.callback.IEncodeDataCallBack;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RAM replay buffer for the H.264 stream emitted by libausbc.
 *
 * The library exposes encoded H.264 packets, so storing the encoded access units
 * is substantially cheaper than keeping raw 720p frames in Java heap. The
 * exporter writes those access units directly to an MP4 container with
 * MediaMuxer; no second video encode is required.
 */
public final class ReplayBufferManager {
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final long MAX_RAM_BYTES = 256L * 1024L * 1024L;

    private static final class Packet {
        final IEncodeDataCallBack.DataType type;
        final byte[] data;
        final long timestampUs;

        Packet(IEncodeDataCallBack.DataType type, byte[] data, long timestampUs) {
            this.type = type;
            this.data = data;
            this.timestampUs = timestampUs;
        }
    }

    public interface ExportCallback {
        void onComplete(boolean success, String pathOrError);
    }

    private final Object lock = new Object();
    private final Deque<Packet> packets = new ArrayDeque<>();
    private final ExecutorService exportExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile int bufferSeconds = 30;
    private long bufferedBytes = 0;
    private byte[] latestSps;
    private byte[] latestPps;

    public void setBufferSeconds(int seconds) {
        if (seconds != 30 && seconds != 60 && seconds != 90 && seconds != 120) {
            return;
        }
        bufferSeconds = seconds;
        trimLocked(System.nanoTime() / 1000L);
    }

    public int getBufferSeconds() {
        return bufferSeconds;
    }

    public void addEncodedData(IEncodeDataCallBack.DataType type, ByteBuffer buffer,
                               int offset, int size, long timestampUs) {
        if (type == null || buffer == null || size <= 0 || offset < 0) {
            return;
        }
        ByteBuffer copyBuffer = buffer.duplicate();
        if (offset > copyBuffer.limit() || offset + size > copyBuffer.limit()) {
            return;
        }
        copyBuffer.position(offset);
        copyBuffer.limit(offset + size);
        byte[] data = new byte[size];
        copyBuffer.get(data);

        synchronized (lock) {
            if (type == IEncodeDataCallBack.DataType.H264_SPS) {
                updateSpsPps(data);
            }
            if (type == IEncodeDataCallBack.DataType.H264
                    || type == IEncodeDataCallBack.DataType.H264_KEY) {
                packets.addLast(new Packet(type, data, timestampUs));
                bufferedBytes += data.length;
                trimLocked(timestampUs);
            }
        }
    }

    public int getPacketCount() {
        synchronized (lock) {
            return packets.size();
        }
    }

    public void clear() {
        synchronized (lock) {
            packets.clear();
            bufferedBytes = 0;
            latestSps = null;
            latestPps = null;
        }
    }

    public void exportLast(int seconds, File outputFile, int width, int height,
                           int fps, ExportCallback callback) {
        final List<Packet> snapshot;
        final byte[] sps;
        final byte[] pps;
        synchronized (lock) {
            long newest = packets.isEmpty() ? 0 : packets.peekLast().timestampUs;
            long cutoff = newest - seconds * MICROS_PER_SECOND;
            snapshot = new ArrayList<>();
            for (Packet packet : packets) {
                if (packet.timestampUs >= cutoff) {
                    snapshot.add(packet);
                }
            }
            sps = latestSps == null ? null : latestSps.clone();
            pps = latestPps == null ? null : latestPps.clone();
        }

        exportExecutor.execute(() -> {
            String result;
            boolean success;
            try {
                if (snapshot.isEmpty()) {
                    throw new IOException("Replay buffer is empty");
                }
                success = writeMp4(snapshot, sps, pps, outputFile, width, height, fps);
                result = success ? outputFile.getAbsolutePath() : "MP4 export failed";
            } catch (Exception e) {
                success = false;
                result = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final boolean done = success;
            final String message = result;
            mainHandler.post(() -> callback.onComplete(done, message));
        });
    }

    private boolean writeMp4(List<Packet> snapshot, byte[] sps, byte[] pps,
                             File outputFile, int width, int height, int fps) throws IOException {
        int firstKey = -1;
        for (int i = 0; i < snapshot.size(); i++) {
            if (snapshot.get(i).type == IEncodeDataCallBack.DataType.H264_KEY) {
                firstKey = i;
                break;
            }
        }
        if (firstKey < 0) {
            throw new IOException("Waiting for an H.264 key frame");
        }
        if (sps == null || pps == null) {
            throw new IOException("Waiting for H.264 SPS/PPS");
        }

        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create output directory");
        }
        if (outputFile.exists() && !outputFile.delete()) {
            throw new IOException("Cannot replace output file");
        }

        MediaMuxer muxer = null;
        try {
            MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                    width, height);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, fps));
            videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            videoFormat.setByteBuffer("csd-1", ByteBuffer.wrap(pps));

            muxer = new MediaMuxer(outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(videoFormat);
            muxer.start();

            long baseTimestamp = snapshot.get(firstKey).timestampUs;
            long lastTimestamp = -1;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            for (int i = firstKey; i < snapshot.size(); i++) {
                Packet packet = snapshot.get(i);
                if (packet.type != IEncodeDataCallBack.DataType.H264
                        && packet.type != IEncodeDataCallBack.DataType.H264_KEY) {
                    continue;
                }
                byte[] sample = toAvccSample(packet.data);
                long pts = Math.max(0, packet.timestampUs - baseTimestamp);
                if (pts <= lastTimestamp) {
                    pts = lastTimestamp + 1;
                }
                lastTimestamp = pts;
                info.set(0, sample.length, pts, packet.type == IEncodeDataCallBack.DataType.H264_KEY
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(videoTrack, ByteBuffer.wrap(sample), info);
            }
            muxer.stop();
            return true;
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void trimLocked(long newestTimestampUs) {
        long cutoff = newestTimestampUs - bufferSeconds * MICROS_PER_SECOND;
        while (!packets.isEmpty()) {
            Packet first = packets.peekFirst();
            if (first.timestampUs >= cutoff && bufferedBytes <= MAX_RAM_BYTES) {
                break;
            }
            packets.removeFirst();
            bufferedBytes -= first.data.length;
        }
        while (!packets.isEmpty() && bufferedBytes > MAX_RAM_BYTES) {
            Packet first = packets.removeFirst();
            bufferedBytes -= first.data.length;
        }
    }

    private void updateSpsPps(byte[] data) {
        List<byte[]> nals = splitAnnexBNals(data);
        if (nals.size() >= 2) {
            latestSps = nals.get(0);
            latestPps = nals.get(1);
        } else if (nals.size() == 1) {
            int nalType = nals.get(0)[0] & 0x1F;
            if (nalType == 7) {
                latestSps = nals.get(0);
            } else if (nalType == 8) {
                latestPps = nals.get(0);
            }
        }
    }

    private static List<byte[]> splitAnnexBNals(byte[] data) {
        List<byte[]> result = new ArrayList<>();
        int start = findStartCode(data, 0);
        if (start < 0) {
            return result;
        }
        while (start >= 0) {
            int prefix = startCodeLength(data, start);
            int next = findStartCode(data, start + prefix);
            int end = next >= 0 ? next : data.length;
            if (end > start + prefix) {
                byte[] nal = new byte[end - start - prefix];
                System.arraycopy(data, start + prefix, nal, 0, nal.length);
                result.add(nal);
            }
            start = next;
        }
        return result;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i;
            }
            if (i + 4 <= data.length && data[i] == 0 && data[i + 1] == 0
                    && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] data, int index) {
        return index + 3 < data.length && data[index] == 0 && data[index + 1] == 0
                && data[index + 2] == 0 && data[index + 3] == 1 ? 4 : 3;
    }

    private static byte[] toAvccSample(byte[] data) throws IOException {
        List<byte[]> nals = splitAnnexBNals(data);
        if (nals.isEmpty()) {
            return data;
        }
        int total = 0;
        for (byte[] nal : nals) {
            total += 4 + nal.length;
        }
        byte[] output = new byte[total];
        int pos = 0;
        for (byte[] nal : nals) {
            int len = nal.length;
            output[pos++] = (byte) ((len >>> 24) & 0xFF);
            output[pos++] = (byte) ((len >>> 16) & 0xFF);
            output[pos++] = (byte) ((len >>> 8) & 0xFF);
            output[pos++] = (byte) (len & 0xFF);
            System.arraycopy(nal, 0, output, pos, nal.length);
            pos += nal.length;
        }
        return output;
    }

    public void release() {
        exportExecutor.shutdownNow();
        clear();
    }
}

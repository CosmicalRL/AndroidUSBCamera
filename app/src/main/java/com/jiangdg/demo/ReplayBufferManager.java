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

public final class ReplayBufferManager {
    private static final long US = 1_000_000L;
    private static final long MAX_RAM_BYTES = 256L * 1024L * 1024L;

    private static final class Packet {
        final IEncodeDataCallBack.DataType type;
        final byte[] data;
        final long pts;
        Packet(IEncodeDataCallBack.DataType type, byte[] data, long pts) {
            this.type = type;
            this.data = data;
            this.pts = pts;
        }
    }

    public interface ExportCallback { void onComplete(boolean success, String pathOrError); }

    private final Object lock = new Object();
    private final Deque<Packet> packets = new ArrayDeque<>();
    private final ExecutorService exporter = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int bufferSeconds = 30;
    private long ramBytes;
    private byte[] sps;
    private byte[] pps;

    public void setBufferSeconds(int seconds) {
        if (seconds != 30 && seconds != 60 && seconds != 90 && seconds != 120) return;
        synchronized (lock) {
            bufferSeconds = seconds;
            if (!packets.isEmpty()) trimLocked(packets.peekLast().pts);
        }
    }

    public int getBufferSeconds() { return bufferSeconds; }

    public void addEncodedData(IEncodeDataCallBack.DataType type, ByteBuffer buffer,
                               int offset, int size, long timestampUs) {
        if (type == null || buffer == null || offset < 0 || size <= 0 || offset + size > buffer.limit()) return;
        ByteBuffer copy = buffer.duplicate();
        copy.position(offset);
        copy.limit(offset + size);
        byte[] data = new byte[size];
        copy.get(data);

        synchronized (lock) {
            if (type == IEncodeDataCallBack.DataType.H264_SPS) updateSpsPps(data);
            if (type == IEncodeDataCallBack.DataType.H264 || type == IEncodeDataCallBack.DataType.H264_KEY) {
                packets.addLast(new Packet(type, data, timestampUs));
                ramBytes += data.length;
                trimLocked(timestampUs);
            }
        }
    }

    public int getPacketCount() {
        synchronized (lock) { return packets.size(); }
    }

    public void clear() {
        synchronized (lock) {
            packets.clear();
            ramBytes = 0;
            sps = null;
            pps = null;
        }
    }

    public void exportLast(int seconds, File output, int width, int height, int fps,
                           ExportCallback callback) {
        final List<Packet> snapshot = new ArrayList<>();
        final byte[] snapshotSps;
        final byte[] snapshotPps;
        synchronized (lock) {
            long newest = packets.isEmpty() ? 0 : packets.peekLast().pts;
            long cutoff = newest - Math.max(1, seconds) * US;
            for (Packet p : packets) if (p.pts >= cutoff) snapshot.add(p);
            snapshotSps = sps == null ? null : sps.clone();
            snapshotPps = pps == null ? null : pps.clone();
        }

        exporter.execute(() -> {
            boolean ok = false;
            String result;
            try {
                if (snapshot.isEmpty()) throw new IOException("Replay buffer is empty");
                writeMp4(snapshot, snapshotSps, snapshotPps, output, width, height, fps);
                ok = true;
                result = output.getAbsolutePath();
            } catch (Exception e) {
                result = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            boolean finalOk = ok;
            String finalResult = result;
            main.post(() -> callback.onComplete(finalOk, finalResult));
        });
    }

    private void writeMp4(List<Packet> list, byte[] spsData, byte[] ppsData,
                          File output, int width, int height, int fps) throws IOException {
        int firstKey = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).type == IEncodeDataCallBack.DataType.H264_KEY) { firstKey = i; break; }
        }
        if (firstKey < 0) throw new IOException("Waiting for H.264 key frame");
        if (spsData == null || ppsData == null) throw new IOException("Waiting for H.264 SPS/PPS");

        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create output directory");
        if (output.exists() && !output.delete()) throw new IOException("Cannot replace output file");

        MediaMuxer muxer = null;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, fps));
            format.setByteBuffer("csd-0", ByteBuffer.wrap(spsData));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsData));

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = muxer.addTrack(format);
            muxer.start();

            long base = list.get(firstKey).pts;
            long lastPts = -1;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int i = firstKey; i < list.size(); i++) {
                Packet p = list.get(i);
                if (p.type != IEncodeDataCallBack.DataType.H264 && p.type != IEncodeDataCallBack.DataType.H264_KEY) continue;
                byte[] sample = toAvcc(p.data);
                long pts = Math.max(0, p.pts - base);
                if (pts <= lastPts) pts = lastPts + 1;
                lastPts = pts;
                int flags = p.type == IEncodeDataCallBack.DataType.H264_KEY ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, sample.length, pts, flags);
                muxer.writeSampleData(track, ByteBuffer.wrap(sample), info);
            }
            muxer.stop();
        } finally {
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) { }
        }
    }

    private void trimLocked(long newestPts) {
        long cutoff = newestPts - bufferSeconds * US;
        while (!packets.isEmpty() && (packets.peekFirst().pts < cutoff || ramBytes > MAX_RAM_BYTES)) {
            ramBytes -= packets.removeFirst().data.length;
        }
    }

    private void updateSpsPps(byte[] data) {
        List<byte[]> nals = splitAnnexB(data);
        if (nals.size() >= 2) {
            sps = nals.get(0);
            pps = nals.get(1);
        } else if (nals.size() == 1) {
            int type = nals.get(0)[0] & 0x1F;
            if (type == 7) sps = nals.get(0);
            if (type == 8) pps = nals.get(0);
        }
    }

    private static List<byte[]> splitAnnexB(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        int start = findStart(data, 0);
        while (start >= 0) {
            int prefix = startLength(data, start);
            int next = findStart(data, start + prefix);
            int end = next >= 0 ? next : data.length;
            if (end > start + prefix) {
                byte[] nal = new byte[end - start - prefix];
                System.arraycopy(data, start + prefix, nal, 0, nal.length);
                out.add(nal);
            }
            start = next;
        }
        return out;
    }

    private static int findStart(byte[] d, int from) {
        for (int i = Math.max(0, from); i + 3 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) return i;
            if (i + 4 <= d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) return i;
        }
        return -1;
    }

    private static int startLength(byte[] d, int i) {
        return i + 3 < d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1 ? 4 : 3;
    }

    private static byte[] toAvcc(byte[] data) {
        List<byte[]> nals = splitAnnexB(data);
        if (nals.isEmpty()) return data;
        int total = 0;
        for (byte[] n : nals) total += 4 + n.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] n : nals) {
            int len = n.length;
            out[pos++] = (byte) (len >>> 24);
            out[pos++] = (byte) (len >>> 16);
            out[pos++] = (byte) (len >>> 8);
            out[pos++] = (byte) len;
            System.arraycopy(n, 0, out, pos, n.length);
            pos += n.length;
        }
        return out;
    }

    public void release() {
        exporter.shutdownNow();
        clear();
    }
}

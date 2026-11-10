package com.tideclock.messmachine.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Reads PCM or float WAV files to mono floats, writes 16-bit stereo WAV. */
public final class Wav {
    private Wav() {}

    public static Sample read(String key, InputStream in) throws IOException {
        byte[] b = readAll(in);
        if (b.length < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) throw new IOException("Not a WAV file");
        int p = 12, fmt = 1, ch = 1, rate = 44100, bits = 16, dataAt = -1, dataLen = 0;
        while (p + 8 <= b.length) {
            int len = le32(b, p + 4);
            if (tag(b, p, "fmt ")) {
                fmt = le16(b, p + 8); ch = le16(b, p + 10); rate = le32(b, p + 12); bits = le16(b, p + 22);
                if (fmt == 0xFFFE && len >= 26) fmt = le16(b, p + 32); // WAVE_FORMAT_EXTENSIBLE sub format
            } else if (tag(b, p, "data")) {
                dataAt = p + 8;
                dataLen = Math.min(len < 0 ? Integer.MAX_VALUE : len, b.length - dataAt);
                break;
            }
            p += 8 + len + (len & 1);
        }
        if (dataAt < 0) throw new IOException("WAV has no data");
        if (ch < 1) ch = 1;
        int bps = bits / 8, frames = dataLen / (bps * ch);
        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            float sum = 0;
            for (int c = 0; c < ch; c++) {
                int o = dataAt + (f * ch + c) * bps;
                float v;
                if (fmt == 3 && bits == 32) v = Float.intBitsToFloat(le32(b, o));
                else if (bits == 16) v = (short) le16(b, o) / 32768f;
                else if (bits == 24) v = (((b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | (b[o + 2] << 16))) / 8388608f;
                else if (bits == 32) v = le32(b, o) / 2147483648f;
                else if (bits == 8) v = ((b[o] & 0xff) - 128) / 128f;
                else throw new IOException("Unsupported WAV: " + bits + " bit");
                sum += v;
            }
            out[f] = sum / ch;
        }
        return new Sample(key, out, rate);
    }

    /** Interleaved stereo floats to a 16-bit WAV. */
    public static void writeStereo16(OutputStream os, float[] lr, int rate) throws IOException {
        int frames = lr.length / 2, dataLen = frames * 4;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE"); put(h, 12, "fmt ");
        le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 2); le32(h, 24, rate); le32(h, 28, rate * 4); le16(h, 32, 4); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, dataLen);
        os.write(h);
        byte[] buf = new byte[8192];
        int n = 0;
        for (float v : lr) {
            float c = Math.max(-1f, Math.min(1f, v));
            int s = Math.round(c * 32767f);
            buf[n++] = (byte) s;
            buf[n++] = (byte) (s >> 8);
            if (n == buf.length) { os.write(buf, 0, n); n = 0; }
        }
        os.write(buf, 0, n);
    }

    /** Mono floats to a 16-bit mono WAV. */
    public static void writeMono16(OutputStream os, float[] d, int rate) throws IOException {
        int dataLen = d.length * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, 36 + dataLen); put(h, 8, "WAVE"); put(h, 12, "fmt ");
        le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1); le32(h, 24, rate); le32(h, 28, rate * 2); le16(h, 32, 2); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, dataLen);
        os.write(h);
        byte[] buf = new byte[8192];
        int n = 0;
        for (float v : d) {
            int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
            buf[n++] = (byte) s;
            buf[n++] = (byte) (s >> 8);
            if (n == buf.length) { os.write(buf, 0, n); n = 0; }
        }
        os.write(buf, 0, n);
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        return o.toByteArray();
    }

    private static boolean tag(byte[] b, int p, String t) {
        for (int k = 0; k < 4; k++) if (b[p + k] != t.charAt(k)) return false;
        return true;
    }
    private static int le16(byte[] b, int p) { return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8); }
    private static int le32(byte[] b, int p) { return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8) | ((b[p + 2] & 0xff) << 16) | ((b[p + 3] & 0xff) << 24); }
    private static void put(byte[] b, int p, String t) { for (int k = 0; k < 4; k++) b[p + k] = (byte) t.charAt(k); }
    private static void le16(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); }
    private static void le32(byte[] b, int p, int v) { b[p] = (byte) v; b[p + 1] = (byte) (v >> 8); b[p + 2] = (byte) (v >> 16); b[p + 3] = (byte) (v >> 24); }
}

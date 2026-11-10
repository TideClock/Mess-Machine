package com.tideclock.messmachine.engine;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads FLAC files to mono floats (MM-8). The bundled sounds ship as FLAC: lossless, so every sample is the
 * same as the WAV it came from, at about half the size. Plain Java, so the engine test runs it too.
 * Follows the FLAC format: constant, verbatim, fixed and LPC subframes, Rice residuals, stereo decorrelation.
 */
public final class Flac {
    private Flac() {}

    public static Sample read(String key, InputStream in) throws IOException {
        return read(key, Wav.readAll(in));
    }

    public static Sample read(String key, byte[] b) throws IOException {
        if (b.length < 42 || b[0] != 'f' || b[1] != 'L' || b[2] != 'a' || b[3] != 'C') throw new IOException("Not a FLAC file");
        Bits r = new Bits(b, 4);
        int rate = 0, channels = 1, depth = 16;
        long total = 0;
        boolean last = false;
        while (!last) {
            last = r.u(1) == 1;
            int type = r.u(7), len = r.u(24);
            if (type == 0) {
                r.u(16); r.u(16); r.u(24); r.u(24);
                rate = r.u(20);
                channels = r.u(3) + 1;
                depth = r.u(5) + 1;
                total = ((long) r.u(4) << 32) | (r.u(32) & 0xFFFFFFFFL);
                r.skipBytes(16);
            } else r.skipBytes(len);
        }
        if (rate <= 0) throw new IOException("FLAC has no stream info");
        float[] out = new float[(int) Math.max(0, total)];
        int at = 0;
        double scale = 1.0 / (1L << (depth - 1)) / channels;
        long[][] ch = new long[channels][];
        while (r.bytesLeft() > 2 && (total == 0 || at < out.length)) {
            int n = frame(r, channels, depth, ch);
            if (n <= 0) break;
            if (total == 0 && at + n > out.length) out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, at + n));
            int m = Math.min(n, out.length - at);
            for (int k = 0; k < m; k++) {
                long sum = 0;
                for (int c = 0; c < channels; c++) sum += ch[c][k];
                out[at + k] = (float) (sum * scale);
            }
            at += m;
        }
        if (at < out.length) out = java.util.Arrays.copyOf(out, at);
        return new Sample(key, out, rate);
    }

    /** Decodes one frame into ch[c][0..n), returns n (the frame's block size). */
    private static int frame(Bits r, int channels, int depth, long[][] ch) throws IOException {
        r.align();
        if (r.u(14) != 0x3FFE) throw new IOException("FLAC frame sync lost");
        r.u(1); r.u(1);
        int bsCode = r.u(4), rateCode = r.u(4), assign = r.u(4), sizeCode = r.u(3);
        r.u(1);
        // the frame or sample number, coded like UTF-8
        int lead = r.u(8);
        int extra = 0;
        while (extra < 7 && ((lead << extra) & 0x80) != 0) extra++;
        for (int k = 1; k < extra; k++) r.u(8);
        int n;
        if (bsCode == 1) n = 192;
        else if (bsCode >= 2 && bsCode <= 5) n = 576 << (bsCode - 2);
        else if (bsCode == 6) n = r.u(8) + 1;
        else if (bsCode == 7) n = r.u(16) + 1;
        else if (bsCode >= 8) n = 256 << (bsCode - 8);
        else throw new IOException("FLAC reserved block size");
        if (rateCode == 12) r.u(8); else if (rateCode == 13 || rateCode == 14) r.u(16);
        int bits = sizeCode == 1 ? 8 : sizeCode == 2 ? 12 : sizeCode == 4 ? 16 : sizeCode == 5 ? 20 : sizeCode == 6 ? 24 : sizeCode == 7 ? 32 : depth;
        r.u(8); // header CRC-8
        int nch = assign < 8 ? assign + 1 : 2;
        if (nch != channels) throw new IOException("FLAC channel count changed");
        for (int c = 0; c < nch; c++) {
            if (ch[c] == null || ch[c].length < n) ch[c] = new long[Math.max(n, 4608)];
            // the side channel carries one extra bit
            int d = bits + ((assign == 8 && c == 1) || (assign == 9 && c == 0) || (assign == 10 && c == 1) ? 1 : 0);
            subframe(r, d, n, ch[c]);
        }
        if (assign == 8) for (int k = 0; k < n; k++) ch[1][k] = ch[0][k] - ch[1][k];
        else if (assign == 9) for (int k = 0; k < n; k++) ch[0][k] = ch[0][k] + ch[1][k];
        else if (assign == 10) for (int k = 0; k < n; k++) {
            long side = ch[1][k], mid = (ch[0][k] << 1) | (side & 1);
            ch[0][k] = (mid + side) >> 1;
            ch[1][k] = (mid - side) >> 1;
        }
        r.align();
        r.u(16); // frame CRC-16
        return n;
    }

    private static final int[][] FIXED = {{}, {1}, {2, -1}, {3, -3, 1}, {4, -6, 4, -1}};

    private static void subframe(Bits r, int depth, int n, long[] s) throws IOException {
        r.u(1);
        int type = r.u(6);
        int wasted = 0;
        if (r.u(1) == 1) { wasted = 1; while (r.u(1) == 0) wasted++; }
        depth -= wasted;
        if (type == 0) {
            long v = r.s(depth);
            for (int k = 0; k < n; k++) s[k] = v;
        } else if (type == 1) {
            for (int k = 0; k < n; k++) s[k] = r.s(depth);
        } else if (type >= 8 && type <= 12) {
            int order = type - 8;
            for (int k = 0; k < order; k++) s[k] = r.s(depth);
            residual(r, n, order, s);
            int[] c = FIXED[order];
            for (int k = order; k < n; k++) {
                long sum = 0;
                for (int j = 0; j < order; j++) sum += (long) c[j] * s[k - 1 - j];
                s[k] += sum;
            }
        } else if (type >= 32) {
            int order = (type & 31) + 1;
            for (int k = 0; k < order; k++) s[k] = r.s(depth);
            int precision = r.u(4) + 1;
            int shift = (int) r.s(5);
            long[] coef = new long[order];
            for (int j = 0; j < order; j++) coef[j] = r.s(precision);
            residual(r, n, order, s);
            for (int k = order; k < n; k++) {
                long sum = 0;
                for (int j = 0; j < order; j++) sum += coef[j] * s[k - 1 - j];
                s[k] += sum >> shift;
            }
        } else throw new IOException("FLAC reserved subframe type " + type);
        if (wasted > 0) for (int k = 0; k < n; k++) s[k] <<= wasted;
    }

    /** Rice-coded residuals land in s[order..n) and are added to the prediction afterwards. */
    private static void residual(Bits r, int n, int order, long[] s) throws IOException {
        int method = r.u(2);
        if (method > 1) throw new IOException("FLAC reserved residual coding");
        int pbits = method == 0 ? 4 : 5, escape = method == 0 ? 15 : 31;
        int porder = r.u(4), parts = 1 << porder;
        int k = order;
        for (int p = 0; p < parts; p++) {
            int count = (n >> porder) - (p == 0 ? order : 0);
            int param = r.u(pbits);
            if (param == escape) {
                int raw = r.u(5);
                for (int i = 0; i < count; i++) s[k++] = raw == 0 ? 0 : r.s(raw);
            } else {
                for (int i = 0; i < count; i++) {
                    long q = r.unary();
                    long v = (q << param) | (param == 0 ? 0 : r.u(param) & 0xFFFFFFFFL);
                    s[k++] = (v >>> 1) ^ -(v & 1);
                }
            }
        }
    }

    /** Big-endian bit reader over the whole file. */
    private static final class Bits {
        private final byte[] b;
        private long pos; // in bits

        Bits(byte[] b, int byteStart) { this.b = b; this.pos = byteStart * 8L; }

        int bytesLeft() { return (int) (b.length - (pos + 7) / 8); }

        void align() { pos = (pos + 7) & ~7L; }

        void skipBytes(int n) { align(); pos += n * 8L; }

        /** Unsigned, up to 32 bits (returned in an int; 32-bit values are read with & 0xFFFFFFFFL). */
        int u(int n) throws IOException {
            if (n == 0) return 0;
            if (pos + n > b.length * 8L) throw new IOException("FLAC ended early");
            long v = 0;
            for (int k = 0; k < n; ) {
                int byteAt = (int) (pos >>> 3), off = (int) (pos & 7);
                int take = Math.min(8 - off, n - k);
                int bitsHere = ((b[byteAt] & 0xFF) >>> (8 - off - take)) & ((1 << take) - 1);
                v = (v << take) | bitsHere;
                pos += take;
                k += take;
            }
            return (int) v;
        }

        /** Signed, two's complement, up to 32 bits. */
        long s(int n) throws IOException {
            if (n == 0) return 0;
            long v = u(n) & 0xFFFFFFFFL;
            return (v << (64 - n)) >> (64 - n);
        }

        /** Counts zero bits up to the next one bit. */
        long unary() throws IOException {
            long q = 0;
            while (true) {
                int byteAt = (int) (pos >>> 3);
                if (byteAt >= b.length) throw new IOException("FLAC ended early");
                int off = (int) (pos & 7);
                int rest = (b[byteAt] << off) & 0xFF;
                if (rest == 0) { q += 8 - off; pos += 8 - off; continue; }
                int z = Integer.numberOfLeadingZeros(rest) - 24;
                q += z;
                pos += z + 1;
                return q;
            }
        }
    }
}

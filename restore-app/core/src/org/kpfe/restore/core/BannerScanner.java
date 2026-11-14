package org.kpfe.restore.core;

import java.nio.charset.StandardCharsets;

/** Constant-memory exact stream matcher. Handles split and coalesced reads. */
public final class BannerScanner {
    private final byte[] pattern;
    private final int[] fallback;
    private int matched;
    private boolean found;

    public BannerScanner(String banner) {
        if (banner == null || banner.isEmpty()) throw new IllegalArgumentException("Missing banner");
        pattern = banner.getBytes(StandardCharsets.US_ASCII);
        if (pattern.length > 256) throw new IllegalArgumentException("Banner is too long");
        fallback = new int[pattern.length];
        for (int i = 1, j = 0; i < pattern.length; i++) {
            while (j > 0 && pattern[i] != pattern[j]) j = fallback[j - 1];
            if (pattern[i] == pattern[j]) j++;
            fallback[i] = j;
        }
    }

    public boolean feed(byte[] input, int offset, int length) {
        if (offset < 0 || length < 0 || offset > input.length - length)
            throw new IndexOutOfBoundsException();
        for (int i = offset; i < offset + length; i++) {
            while (matched > 0 && input[i] != pattern[matched]) matched = fallback[matched - 1];
            if (input[i] == pattern[matched]) matched++;
            if (matched == pattern.length) {
                found = true;
                matched = fallback[matched - 1];
            }
        }
        return found;
    }

    public boolean found() { return found; }
}

package com.mojie.proposal.model;

import java.util.*;

/** Stable, Unicode-safe edit blocks. Offsets use UTF-16, as browser text selections do. */
public final class ProposalDiff {
    private ProposalDiff() {}
    public record Item(int id, int start, int end, int lineNumber, String before, String after,
                       String contextBefore, String contextAfter) {}

    public static List<Item> between(String base, String proposed) {
        if (base.equals(proposed)) return List.of();
        List<String> a = lines(base), b = lines(proposed);
        int prefix = 0, suffix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
        int n = a.size() - prefix - suffix, m = b.size() - prefix - suffix;
        int offset = a.subList(0, prefix).stream().mapToInt(String::length).sum();
        List<Item> result = new ArrayList<>();
        // Very many short lines must not allocate an unbounded quadratic matrix.
        if ((long) (n + 1) * (m + 1) > 1_000_000) {
            add(result, base, offset, String.join("", a.subList(prefix, prefix + n)),
                    String.join("", b.subList(prefix, prefix + m)));
            return result;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--)
            lcs[i][j] = a.get(prefix + i).equals(b.get(prefix + j))
                    ? 1 + lcs[i + 1][j + 1] : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        int i = 0, j = 0, start = offset;
        StringBuilder old = new StringBuilder(), next = new StringBuilder();
        while (i < n || j < m) {
            if (i < n && j < m && a.get(prefix + i).equals(b.get(prefix + j))) {
                add(result, base, start, old.toString(), next.toString()); old.setLength(0); next.setLength(0);
                offset += a.get(prefix + i).length(); start = offset; i++; j++;
            } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                old.append(a.get(prefix + i)); offset += a.get(prefix + i++).length();
            } else next.append(b.get(prefix + j++));
        }
        add(result, base, start, old.toString(), next.toString());
        return List.copyOf(result);
    }
    private static List<String> lines(String text) {
        if (text.isEmpty()) return List.of();
        return Arrays.stream(text.split("(?<=\\n)")).toList();
    }
    private static void add(List<Item> result, String base, int start, String before, String after) {
        if (before.equals(after)) return;
        int p = 0, q = 0;
        while (p < before.length() && p < after.length() && before.codePointAt(p) == after.codePointAt(p))
            p += Character.charCount(before.codePointAt(p));
        while (q < before.length() - p && q < after.length() - p
                && before.codePointBefore(before.length() - q) == after.codePointBefore(after.length() - q))
            q += Character.charCount(before.codePointBefore(before.length() - q));
        start += p;
        int end = start + before.length() - p - q;
        int left = base.offsetByCodePoints(start, -Math.min(40, base.codePointCount(0, start)));
        int right = base.offsetByCodePoints(end, Math.min(40, base.codePointCount(end, base.length())));
        int line = 1;
        for (int k = 0; k < start; k++) if (base.charAt(k) == '\n') line++;
        result.add(new Item(result.size(), start, end, line, before.substring(p, before.length() - q),
                after.substring(p, after.length() - q), base.substring(left, start), base.substring(end, right)));
    }
}

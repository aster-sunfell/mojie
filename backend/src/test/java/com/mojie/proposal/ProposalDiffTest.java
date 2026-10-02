package com.mojie.proposal;
import com.mojie.proposal.model.ProposalDiff;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProposalDiffTest {
    @Test void separatesChangesAcrossUnchangedLinesAndTrimsUnchangedWords() {
        String base = "林舟游过河。\n\n他拿起红灯。\n";
        var items = ProposalDiff.between(base, "林舟走过桥。\n\n他拿起蓝灯。\n");
        assertEquals(2, items.size());
        assertEquals("游过河", items.getFirst().before());
        assertEquals("走过桥", items.getFirst().after());
        assertEquals(3, items.getLast().lineNumber());
        assertEquals("红", items.getLast().before());
        assertEquals("蓝", items.getLast().after());
    }
    @Test void handlesInsertionsDeletionsEmptyChaptersAndEmojiWithoutSplittingSurrogates() {
        for (String[] pair : List.of(new String[]{"", "新章😀\n"}, new String[]{"删除\n保留", "保留"},
                new String[]{"🦊走到桥边。", "🐱走到桥边。"}, new String[]{"一\n三", "一\n二\n三"},
                new String[]{"一\n二\n", "一\n"}, new String[]{"abc", ""})) verify(pair[0], pair[1]);
        var item = ProposalDiff.between("🦊走到桥边。", "🐱走到桥边。").getFirst();
        assertEquals("🦊", item.before()); assertEquals("🐱", item.after());
        assertTrue(ProposalDiff.between("一致", "一致").isEmpty());
    }
    @Test void editBlocksReconstructTheCandidateForRandomLinesAndRepeatedText() {
        Random random = new Random(731);
        String[] lines = {"林舟\n", "🦊\n", "\n", "一样\n", "桥\n"};
        for (int sample = 0; sample < 300; sample++) {
            StringBuilder a = new StringBuilder(), b = new StringBuilder();
            for (int i = random.nextInt(20); i > 0; i--) a.append(lines[random.nextInt(lines.length)]);
            for (int i = random.nextInt(20); i > 0; i--) b.append(lines[random.nextInt(lines.length)]);
            verify(a.toString(), b.toString());
        }
    }
    @Test void boundsMemoryForThousandsOfChangedShortLines() {
        verify("a\n".repeat(2500), "b\n".repeat(2500));
    }
    private static void verify(String base, String candidate) {
        var items = ProposalDiff.between(base, candidate);
        String result = base;
        for (int i = items.size() - 1; i >= 0; i--) {
            var item = items.get(i);
            assertEquals(item.before(), base.substring(item.start(), item.end()));
            result = result.substring(0, item.start()) + item.after() + result.substring(item.end());
        }
        assertEquals(candidate, result);
    }
}

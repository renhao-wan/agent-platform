package io.github.renhaowan.platform.knowledge.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 切块器（纯函数，见 docs/architecture/KNOWLEDGE-RAG.md §4）。
 * <p>规则：H2 为切分点；超长节再按 H3、段落、句子逐级降级；仍超长才硬切并保留重叠。
 * 表格整表尽量不拆（超长时按行切且重复表头），代码块永不拆开。
 * <p>为什么按标题切：作者写的标题就是语义边界，比任何算法猜的切点都准且零成本。
 */
public class MarkdownChunker {

    /**
     * 切块算法版本，参与 chunk_config 指纹。改切分/合并规则时必须加一——否则文档 hash 与
     * 三个参数都没变，旧索引会被继续沿用，新规则永远不生效。
     */
    public static final String VERSION = "2";

    private final int maxChars;
    private final int minChars;
    private final int overlapChars;

    public MarkdownChunker(int maxChars, int minChars, int overlapChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars 必须大于 0");
        }
        this.maxChars = maxChars;
        this.minChars = Math.max(0, minChars);
        this.overlapChars = Math.max(0, Math.min(overlapChars, maxChars / 2));
    }

    public List<KnowledgeChunk> chunk(String markdown) {
        String text = markdown == null ? "" : markdown.replace("\r\n", "\n");
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (Section section : splitSections(text)) {
            chunks.addAll(splitSection(section));
        }
        return mergeSmall(chunks);
    }

    /** 文档标题优先取 front-matter，其次取 H1。只做提取，不参与切分。 */
    public static String firstH1(String markdown) {
        boolean inFence = false;
        String text = markdown == null ? "" : markdown.replace("\r\n", "\n");
        for (String line : text.split("\n", -1)) {
            if (isFence(line)) {
                inFence = !inFence;
                continue;
            }
            if (!inFence && line.startsWith("# ")) {
                return line.substring(2).strip();
            }
        }
        return null;
    }

    private List<Section> splitSections(String markdown) {
        List<Section> sections = new ArrayList<>();
        String heading = "";
        StringBuilder body = new StringBuilder();
        boolean inFence = false;

        for (String line : markdown.split("\n", -1)) {
            if (isFence(line)) {
                inFence = !inFence;
                body.append(line).append('\n');
                continue;
            }
            if (!inFence && line.startsWith("## ")) {
                flushSection(sections, heading, body);
                heading = line.substring(3).strip();
                body = new StringBuilder();
                continue;
            }
            if (!inFence && line.startsWith("# ")) {
                // H1 是文档标题，不进块正文
                continue;
            }
            body.append(line).append('\n');
        }
        flushSection(sections, heading, body);
        return sections;
    }

    private List<KnowledgeChunk> splitSection(Section section) {
        String text = section.body().strip();
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.length() <= maxChars) {
            return List.of(new KnowledgeChunk(section.heading(), text));
        }

        List<Section> subs = splitBySubheadings(text);
        List<KnowledgeChunk> out = new ArrayList<>();
        if (subs.size() > 1) {
            for (Section sub : subs) {
                out.addAll(splitOversized(join(section.heading(), sub.heading()), sub.body().strip()));
            }
        } else {
            out.addAll(splitOversized(section.heading(), text));
        }
        return out;
    }

    private List<Section> splitBySubheadings(String text) {
        List<Section> sections = new ArrayList<>();
        String heading = "";
        StringBuilder body = new StringBuilder();
        boolean inFence = false;

        for (String line : text.split("\n", -1)) {
            if (isFence(line)) {
                inFence = !inFence;
                body.append(line).append('\n');
                continue;
            }
            if (!inFence && line.startsWith("### ")) {
                flushSection(sections, heading, body);
                heading = line.substring(4).strip();
                body = new StringBuilder();
                continue;
            }
            body.append(line).append('\n');
        }
        flushSection(sections, heading, body);
        return sections;
    }

    private List<KnowledgeChunk> splitOversized(String heading, String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> packed = packBlocks(splitParagraphs(text));
        List<KnowledgeChunk> out = new ArrayList<>(packed.size());
        for (String part : packed) {
            out.add(new KnowledgeChunk(heading, part));
        }
        return out;
    }

    /** 按空行切段落；代码块内的空行不算段落边界。表格没有空行，天然保持完整。 */
    private List<String> splitParagraphs(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inFence = false;

        for (String line : text.split("\n", -1)) {
            if (isFence(line)) {
                inFence = !inFence;
            }
            if (!inFence && line.isBlank()) {
                addBlock(blocks, current);
                continue;
            }
            current.append(line).append('\n');
        }
        addBlock(blocks, current);
        return blocks;
    }

    private List<String> packBlocks(List<String> blocks) {
        List<String> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String block : blocks) {
            if (block.length() > maxChars) {
                flushBlock(out, buffer);
                out.addAll(splitLongBlock(block));
                continue;
            }
            if (buffer.length() == 0) {
                buffer.append(block);
            } else if (buffer.length() + 2 + block.length() <= maxChars) {
                buffer.append("\n\n").append(block);
            } else {
                flushBlock(out, buffer);
                buffer.append(block);
            }
        }
        flushBlock(out, buffer);
        return out;
    }

    private List<String> splitLongBlock(String block) {
        String text = block.strip();
        if (text.startsWith("```") || text.startsWith("~~~")) {
            // 代码块宁可超过块上限，也不切开
            return List.of(text);
        }
        if (isTable(text)) {
            return splitTable(text);
        }

        List<String> sentences = splitSentences(text);
        if (sentences.size() > 1) {
            List<String> packed = new ArrayList<>();
            StringBuilder buffer = new StringBuilder();
            for (String sentence : sentences) {
                if (sentence.length() > maxChars) {
                    flushBlock(packed, buffer);
                    packed.addAll(hardSplit(sentence));
                    continue;
                }
                if (buffer.length() + sentence.length() <= maxChars) {
                    buffer.append(sentence);
                } else {
                    flushBlock(packed, buffer);
                    buffer.append(sentence);
                }
            }
            flushBlock(packed, buffer);
            if (packed.size() > 1) {
                return packed;
            }
        }
        return hardSplit(text);
    }

    private List<String> splitTable(String table) {
        String[] lines = table.split("\n");
        if (lines.length <= 2) {
            return List.of(table);
        }
        String header = lines[0] + "\n" + lines[1];
        List<String> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder(header);
        for (int i = 2; i < lines.length; i++) {
            String row = lines[i];
            if (buffer.length() + 1 + row.length() > maxChars && buffer.length() > header.length()) {
                out.add(buffer.toString());
                buffer = new StringBuilder(header);
            }
            buffer.append('\n').append(row);
        }
        out.add(buffer.toString());
        return out;
    }

    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("(?<=[。！？!?；;])")) {
            String sentence = raw.strip();
            if (!sentence.isEmpty()) {
                out.add(sentence);
            }
        }
        return out;
    }

    private List<String> hardSplit(String text) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        int step = Math.max(1, maxChars - overlapChars);
        List<String> out = new ArrayList<>();
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + maxChars);
            out.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
        }
        return out;
    }

    /** 过小块的唯一处理：并入相邻块。独立成块的"本制度自某日起施行"没有检索价值。 */
    private List<KnowledgeChunk> mergeSmall(List<KnowledgeChunk> chunks) {
        List<KnowledgeChunk> out = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            if (!out.isEmpty()) {
                KnowledgeChunk previous = out.get(out.size() - 1);
                if (previous.text().length() < minChars) {
                    out.set(out.size() - 1, new KnowledgeChunk(
                            mergeSection(previous.section(), chunk.section()),
                            previous.text() + "\n\n" + chunk.text()));
                    continue;
                }
            }
            out.add(chunk);
        }
        if (out.size() > 1 && out.get(out.size() - 1).text().length() < minChars) {
            KnowledgeChunk last = out.remove(out.size() - 1);
            KnowledgeChunk previous = out.remove(out.size() - 1);
            out.add(new KnowledgeChunk(previous.section(), previous.text() + "\n\n" + last.text()));
        }
        return out;
    }

    /**
     * H1 与首个 H2 之间的导语通常过短，会先与首个 H2 合并。此时必须采用后者的小节名，
     * 否则"住宿标准"这类标题被吞掉：前缀丢失关键词（降低召回），来源标注也退化成文档标题。
     */
    private static String mergeSection(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        return first;
    }

    private static void flushSection(List<Section> sections, String heading, StringBuilder body) {
        String text = body.toString().strip();
        if (!text.isEmpty()) {
            sections.add(new Section(heading, text));
        }
    }

    private static void addBlock(List<String> blocks, StringBuilder current) {
        String block = current.toString().strip();
        if (!block.isEmpty()) {
            blocks.add(block);
        }
        current.setLength(0);
    }

    private static void flushBlock(List<String> out, StringBuilder buffer) {
        String block = buffer.toString().strip();
        if (!block.isEmpty()) {
            out.add(block);
        }
        buffer.setLength(0);
    }

    private static boolean isFence(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("```") || trimmed.startsWith("~~~");
    }

    private static boolean isTable(String text) {
        String[] lines = text.split("\n");
        if (lines.length < 2) {
            return false;
        }
        for (String line : lines) {
            if (!line.isBlank() && !line.strip().startsWith("|")) {
                return false;
            }
        }
        return true;
    }

    private static String join(String heading, String sub) {
        if (sub.isEmpty()) {
            return heading;
        }
        return heading.isEmpty() ? sub : heading + " > " + sub;
    }

    private record Section(String heading, String body) {
    }
}

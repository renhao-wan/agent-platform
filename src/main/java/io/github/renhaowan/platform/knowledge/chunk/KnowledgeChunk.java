package io.github.renhaowan.platform.knowledge.chunk;

/**
 * 一个索引块：{@code section} 是「H2 > H3」路径，{@code text} 是正文。
 * <p>向量化与返回都用 {@link #prefixed(String)} 的结果——切片脱离原文后，
 * "超过上述标准"这类指代会失效，前缀是让块自解释的最低成本手段。
 */
public record KnowledgeChunk(String section, String text) {

    public String prefixed(String docTitle) {
        String title = docTitle == null ? "" : docTitle.strip();
        String heading = section == null ? "" : section.strip();
        String prefix = title.isEmpty() ? heading : heading.isEmpty() ? title : title + " > " + heading;
        return prefix.isEmpty() ? text : prefix + "\n" + text;
    }
}

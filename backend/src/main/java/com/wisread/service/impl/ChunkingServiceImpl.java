package com.wisread.service.impl;

import com.wisread.service.ChunkingService;
import com.wisread.service.TokenCounter;

import com.wisread.model.PageText;
import com.wisread.model.TextChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分块服务的实现。
 * 实现要点：以“句子”为最小切分单元（按中英文标点断句），贪心地把句子累加进当前块，
 * 块大小控制在 800~1200 token；当达到上限且与下一句会超限、且已满足最小块时，
 * 切出新块并对新块保留上一块尾部 150 token 的重叠（overlap），以保留跨块上下文，
 * 降低句子被硬切断导致语义丢失、检索召回下降的风险。当前实现为单页内分块。
 */
@Service
public class ChunkingServiceImpl implements ChunkingService {

    // 单块 token 下限：低于此值不急于切分，尽量凑满一个语义完整的块
    private static final int MIN_CHUNK_TOKENS = 800;
    // 单块 token 上限：超过即考虑切分，避免超出 Embedding 模型上下文并稀释检索精度
    private static final int MAX_CHUNK_TOKENS = 1200;
    // 相邻块尾部重叠的 token 数：把上一块末尾内容带入新块，保留跨块上下文
    private static final int OVERLAP_TOKENS = 150;

    private final TokenCounter tokenCounter;

    public ChunkingServiceImpl(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    /**
     * 将一页文本切分为多个 800~1200 token 的块，并保留 150 token 尾部重叠。
     * 为何重叠：句子边界无法保证落在块的边界，重叠可让被切断处的上下文在新块开头重现，
     * 避免关键信息因跨块割裂而检索不到；也能提升向量对片段语义的表征质量。
     */
    public List<TextChunk> splitPage(PageText page, int nextChunkIndex) {
        // 归一化：压缩空白、去首尾空格，避免空行/全角空格干扰切分
        String text = normalize(page.text());
        if (text.isBlank()) {
            return List.of();
        }

        // 按中英文句末标点切句，句子是拼接块的基本单元，保证不切断单句
        List<String> sentences = splitSentences(text);
        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int chunkIndex = nextChunkIndex;

        for (String sentence : sentences) {
            // 仅当：当前块已有内容、加入本句会超上限、且当前块已达最小块时，才切分
            // 三重条件保证块既不过碎（>=MIN）也不过肥（<=MAX）
            if (current.length() > 0
                    && tokenCounter.count(current.toString()) + tokenCounter.count(sentence) > MAX_CHUNK_TOKENS
                    && tokenCounter.count(current.toString()) >= MIN_CHUNK_TOKENS) {
                chunks.add(new TextChunk(current.toString().trim(), page.page(), page.page(), tokenCounter.count(current.toString())));
                // 切分后，把上一块末尾 OVERLAP_TOKENS 个 token 作为新块的起始，保留上下文连续性
                current = new StringBuilder(takeTail(current.toString(), OVERLAP_TOKENS));
                chunkIndex++;
            }
            appendWithSpace(current, sentence);
        }

        // 收尾：循环结束后剩余内容作为一个块（可能不满 MAX，但已是句边界自然结束）
        if (!current.isEmpty()) {
            chunks.add(new TextChunk(current.toString().trim(), page.page(), page.page(), tokenCounter.count(current.toString())));
        }

        // 保持原顺序拷贝（此处为兼容原实现的直接返回等价写法）
        List<TextChunk> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            result.add(chunks.get(i));
        }
        return result;
    }

    // 文本预处理：null 转空串，连续空白合并为单空格，去掉首尾空白
    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    // 按句末标点（中文 。！？ 与英文 .!?）断句，零宽断言保证标点保留在句尾
    private List<String> splitSentences(String text) {
        String[] parts = text.split("(?<=[。！？.!?])");
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                sentences.add(part.trim());
            }
        }
        return sentences;
    }

    // 从文本尾部取约 tokens 个 token 作为重叠内容（按空格分词后从后往前累加）
    private String takeTail(String text, int tokens) {
        String[] words = text.split(" ");
        int total = 0;
        StringBuilder tail = new StringBuilder();
        for (int i = words.length - 1; i >= 0; i--) {
            int next = total + tokenCounter.count(words[i]);
            // 一旦再加一个词就超限且已有内容，则停止（保证至少带一点重叠）
            if (next > tokens && tail.length() > 0) {
                break;
            }
            total = next;
            tail.insert(0, words[i] + " ");
        }
        return tail.toString().trim();
    }

    // 拼接句子时用一个空格分隔，避免相邻句子粘连
    private void appendWithSpace(StringBuilder builder, String value) {
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value);
    }
}

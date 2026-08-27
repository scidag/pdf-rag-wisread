package com.wisread.service.impl;

import com.wisread.service.CitationParsingService;

import com.wisread.dto.SourceResponse;
import com.wisread.model.ChunkSearchResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用解析与校验服务的实现。
 * 实现要点：用正则从答案中提取所有 [数字] 引用标记，去重并按“编号 i 对应第 i 个检索片段”
 * （1-based）的规则映射回真实 chunk；只输出那些编号确实落在检索结果范围内的来源，
 * 从而过滤掉模型可能编造的无效编号。来源内容截断为摘要长度以便前端展示。
 */
@Service
public class CitationParsingServiceImpl implements CitationParsingService {

    // 匹配形如 [1]、[23] 的引用标记，捕获组为其中的数字编号
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");
    // 引用来源内容摘要的最大长度，超出部分以省略号截断
    private static final int SNIPPET_LENGTH = 120;

    /**
     * 解析答案中的引用编号并校验其是否落在检索到的 chunks 内。
     * 为什么用 LinkedHashSet：既去重又保留答案中引用出现的顺序，使展示列表更贴近原文顺序。
     * 为什么按 i+1 映射：检索结果列表的下标 i 在生成时即对应模型应使用的编号 i+1，
     * 编号不在集合内（大于列表长度或答案未提及）的片段一律不展示，杜绝虚构引用。
     */
    public List<SourceResponse> parseAndValidate(String answer, List<ChunkSearchResult> retrievedChunks) {
        Set<Integer> markers = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(answer);
        while (matcher.find()) {
            markers.add(Integer.valueOf(matcher.group(1)));
        }

        List<SourceResponse> sources = new ArrayList<>();
        for (int i = 0; i < retrievedChunks.size(); i++) {
            int marker = i + 1;
            // 只有当答案确实引用了这个编号，才把对应真实片段作为来源输出
            if (markers.contains(marker)) {
                ChunkSearchResult chunk = retrievedChunks.get(i);
                sources.add(new SourceResponse(
                        marker,
                        chunk.chunkId(),
                        chunk.documentId(),
                        chunk.filename(),
                        chunk.pageStart(),
                        chunk.pageEnd(),
                        // 内容截断为摘要，避免把整段 chunk 塞进引用面板
                        truncate(chunk.content())
                ));
            }
        }
        return sources;
    }

    // 将过长的片段内容截断为摘要，末尾补省略号
    private String truncate(String content) {
        if (content.length() <= SNIPPET_LENGTH) {
            return content;
        }
        return content.substring(0, SNIPPET_LENGTH) + "...";
    }
}

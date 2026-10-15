package com.wisread.service.impl;

import com.wisread.dto.KnowledgeRetrievalTestRequest;
import com.wisread.dto.KnowledgeRetrievalTestResponse;
import com.wisread.dto.SourceResponse;
import com.wisread.entity.KnowledgeBase;
import com.wisread.exception.ApiException;
import com.wisread.model.ChunkSearchResult;
import com.wisread.model.KnowledgeChunkSearchResult;
import com.wisread.service.CitationParsingService;
import com.wisread.service.EmbeddingService;
import com.wisread.service.KnowledgeBaseService;
import com.wisread.service.KnowledgeRetrievalTestService;
import com.wisread.service.KnowledgeVectorIndexingService;
import com.wisread.service.QueryRewriteService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库检索测试服务实现（KnowledgeRetrievalTestServiceImpl）。
 *
 * <p>做什么：按"改写 → 召回 → 重排 → 生成答案"编排一次 RAG 全链路演练：
 * <ol>
 *   <li>归属校验：仅知识库所有者可测（findOwnedKnowledgeBase，越权 403）；</li>
 *   <li>改写：复用现有 QueryRewriteService（无历史时其内部直接透传原问题，标注 passthrough）；</li>
 *   <li>召回：按库配置 topK 向量检索 + 相似度阈值过滤 + rerankK 截断，
 *       口径统一走 {@link KnowledgeRetrievalPipeline}，与 Chat 绑库问答严格一致；</li>
 *   <li>重排：当前为占位实现（不接重排模型，仅距离排序 + 截断），响应中明确标注；</li>
 *   <li>答案：复用与问答链路同一 ChatModel 生成带编号引用的答案，
 *       引用经 CitationParsingService 校验；命中为空/全部低于阈值时拒答，不调用答案生成。</li>
 * </ol>
 * 为什么：让用户在不发起真实会话的情况下，预览某问题在当前检索配置下的召回与回答效果。
 */
@Service
public class KnowledgeRetrievalTestServiceImpl implements KnowledgeRetrievalTestService {

    // 拒答文案（命中为空/全部超阈值时返回，不调用答案生成）
    private static final String REJECTION_ANSWER = "未在知识库中找到与问题相关的内容";
    // rerank 占位说明：开启时
    private static final String RERANK_PLACEHOLDER_NOTE = "当前为距离排序占位，未接重排模型";
    // rerank 占位说明：未开启时
    private static final String RERANK_DISABLED_NOTE = "未启用重排，按向量距离升序返回";
    // topK 回退默认值，与 ChatServiceImpl 的 wisread.retrieval.top-k 默认值保持一致
    private static final int FALLBACK_TOP_K = 10;
    // 召回内容摘要的最大长度，超出以省略号截断
    private static final int SNIPPET_LENGTH = 120;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeVectorIndexingService knowledgeVectorIndexingService;
    private final EmbeddingService embeddingService;
    private final QueryRewriteService queryRewriteService;
    private final CitationParsingService citationParsingService;
    private final ChatModel chatModel;

    public KnowledgeRetrievalTestServiceImpl(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeVectorIndexingService knowledgeVectorIndexingService,
            EmbeddingService embeddingService,
            QueryRewriteService queryRewriteService,
            CitationParsingService citationParsingService,
            ChatModel chatModel
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeVectorIndexingService = knowledgeVectorIndexingService;
        this.embeddingService = embeddingService;
        this.queryRewriteService = queryRewriteService;
        this.citationParsingService = citationParsingService;
        this.chatModel = chatModel;
    }

    /**
     * 执行一次检索测试，分阶段记录结果与耗时。
     */
    @Override
    public KnowledgeRetrievalTestResponse test(
            Long userId,
            Long knowledgeBaseId,
            KnowledgeRetrievalTestRequest request
    ) {
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "问题不能为空");
        }
        // 归属校验先行：越权/不存在直接 403，不做任何检索与生成
        KnowledgeBase knowledgeBase = knowledgeBaseService.findOwnedKnowledgeBase(userId, knowledgeBaseId);

        // 改写阶段：复用现有改写服务（无历史时其内部透传原问题）
        long rewriteStart = System.nanoTime();
        String rewrittenQuestion = queryRewriteService.rewrite(question, List.of(), userId);
        boolean rewritten = !rewrittenQuestion.equals(question);
        long rewriteMs = elapsedMs(rewriteStart);

        // 召回阶段：query embedding + 向量检索 + 阈值过滤 + rerankK 截断（与 Chat 绑库口径一致）
        long recallStart = System.nanoTime();
        int topK = KnowledgeRetrievalPipeline.resolveTopK(knowledgeBase, FALLBACK_TOP_K);
        float[] queryEmbedding = embeddingService.embed(List.of(rewrittenQuestion), userId).get(0);
        List<KnowledgeChunkSearchResult> hits = knowledgeVectorIndexingService.searchKnowledgeChunks(
                userId, knowledgeBaseId, queryEmbedding, topK);
        List<ChunkSearchResult> chunks = KnowledgeRetrievalPipeline.filterAndTruncate(hits, knowledgeBase);
        long recallMs = elapsedMs(recallStart);

        // 重排阶段：占位实现，不重排，仅确认截断结果（保证口径与 Chat 一致）
        long rerankStart = System.nanoTime();
        boolean rerankEnabled = Boolean.TRUE.equals(knowledgeBase.getRerankEnabled());
        long rerankMs = elapsedMs(rerankStart);

        KnowledgeRetrievalTestResponse.UsedConfig usedConfig = new KnowledgeRetrievalTestResponse.UsedConfig(
                topK,
                knowledgeBase.getSimilarityThreshold(),
                knowledgeBase.getRerankEnabled(),
                knowledgeBase.getRerankK()
        );
        KnowledgeRetrievalTestResponse.RewriteInfo rewriteInfo = new KnowledgeRetrievalTestResponse.RewriteInfo(
                question,
                rewrittenQuestion,
                rewritten,
                rewritten ? "llm" : "passthrough"
        );
        KnowledgeRetrievalTestResponse.RerankInfo rerankInfo = new KnowledgeRetrievalTestResponse.RerankInfo(
                knowledgeBase.getRerankEnabled(),
                "placeholder",
                rerankEnabled ? RERANK_PLACEHOLDER_NOTE : RERANK_DISABLED_NOTE
        );

        // 拒答：命中为空或全部低于阈值，不调用答案生成
        if (chunks.isEmpty()) {
            return new KnowledgeRetrievalTestResponse(
                    rewriteInfo,
                    List.of(),
                    rerankInfo,
                    REJECTION_ANSWER,
                    true,
                    List.of(),
                    usedConfig,
                    new KnowledgeRetrievalTestResponse.Timings(rewriteMs, recallMs, rerankMs, 0L)
            );
        }

        // 答案阶段：复用问答链路同一 ChatModel 生成带编号引用的答案
        long answerStart = System.nanoTime();
        String answer = generateAnswer(rewrittenQuestion, chunks);
        List<SourceResponse> citations = citationParsingService.parseAndValidate(answer, chunks);
        long answerMs = elapsedMs(answerStart);

        return new KnowledgeRetrievalTestResponse(
                rewriteInfo,
                toRecallItems(chunks),
                rerankInfo,
                answer,
                false,
                citations,
                usedConfig,
                new KnowledgeRetrievalTestResponse.Timings(rewriteMs, recallMs, rerankMs, answerMs)
        );
    }

    /**
     * 生成带编号引用的答案：系统提示要求模型仅依据文档作答并使用 [1]..[n] 编号，
     * 与 Chat 链路 buildPrompt 的核心约定一致（无多轮历史与长期记忆）。
     */
    private String generateAnswer(String question, List<ChunkSearchResult> chunks) {
        StringBuilder system = new StringBuilder(
                "你只能根据提供的文档内容回答。\n"
                        + "如果文档没有相关信息，回答“文档中没有找到相关信息”。\n"
                        + "引用必须使用系统提供的编号 [1]..[" + chunks.size() + "]。\n"
                        + "禁止自行生成不存在的编号。\n"
        );
        for (int i = 0; i < chunks.size(); i++) {
            ChunkSearchResult chunk = chunks.get(i);
            system.append("\n[")
                    .append(i + 1)
                    .append("] 《")
                    .append(chunk.filename())
                    .append("》 ")
                    .append(locationText(chunk))
                    .append("\n")
                    .append(chunk.content())
                    .append("\n");
        }
        List<Message> messages = List.of(
                new SystemMessage(system.toString()),
                new UserMessage(question)
        );
        var response = chatModel.call(new Prompt(messages));
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 将截断后的命中列表转换为召回明细（含相似度与内容摘要）。
     */
    private List<KnowledgeRetrievalTestResponse.RecallItem> toRecallItems(List<ChunkSearchResult> chunks) {
        List<KnowledgeRetrievalTestResponse.RecallItem> items = new ArrayList<>(chunks.size());
        for (ChunkSearchResult chunk : chunks) {
            items.add(new KnowledgeRetrievalTestResponse.RecallItem(
                    chunk.chunkId(),
                    chunk.documentId(),
                    chunk.filename(),
                    chunk.pageStart(),
                    chunk.pageEnd(),
                    chunk.locationLabel(),
                    chunk.distance(),
                    1 - chunk.distance(),
                    truncate(chunk.content())
            ));
        }
        return items;
    }

    /**
     * 按定位单位拼出位置文案，与 Chat 链路口径一致。
     */
    private String locationText(ChunkSearchResult chunk) {
        String label = chunk.locationLabel() == null ? "页" : chunk.locationLabel();
        int start = chunk.pageStart();
        int end = chunk.pageEnd();
        String range = start == end ? String.valueOf(start) : start + "-" + end;
        if (label.startsWith("工作表")) {
            return label + " 第 " + range + " 行";
        }
        return "第 " + range + " " + label;
    }

    private String truncate(String content) {
        if (content.length() <= SNIPPET_LENGTH) {
            return content;
        }
        return content.substring(0, SNIPPET_LENGTH) + "...";
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

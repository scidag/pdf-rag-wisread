package com.wisread.service.impl;

import com.wisread.model.RetryRewriteResult;
import com.wisread.service.QueryRewriteService;
import com.wisread.service.TokenCounter;
import com.wisread.service.UsageLogService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 问题改写服务实现（QueryRewriteServiceImpl）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>无历史则直接返回原问题——首轮对话无需改写，也省一次 LLM 调用。</li>
 *   <li>有历史时，把历史对话（role: content）拼接后连同当前问题发给 LLM，
 *       要求只输出改写后的独立问题，并做 token 用量记录。</li>
 *   <li>若 LLM 返回为空，则回退使用原问题，保证检索流程不中断。</li>
 * </ul>
 */
@Service
public class QueryRewriteServiceImpl implements QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteServiceImpl.class);

    private final ChatModel chatModel;
    private final TokenCounter tokenCounter;
    private final UsageLogService usageLogService;
    private final String chatModelName;
    @org.springframework.beans.factory.annotation.Value("${wisread.rewrite.skip-without-pronoun:true}")
    private boolean skipWithoutPronoun;

    public QueryRewriteServiceImpl(
            ChatModel chatModel,
            TokenCounter tokenCounter,
            UsageLogService usageLogService,
            @Value("${spring.ai.dashscope.chat.options.model:qwen3.7-plus}") String chatModelName
    ) {
        this.chatModel = chatModel;
        this.tokenCounter = tokenCounter;
        this.usageLogService = usageLogService;
        this.chatModelName = chatModelName;
    }

    /**
     * 改写用户问题。
     *
     * <p>做什么：无历史直接返回原问题；有历史则调 LLM 将其改写为独立问题。
     * 为什么：仅多轮对话才需要结合上下文补全指代，首轮改写无收益且浪费调用。
     */
    public String rewrite(String question, List<com.wisread.entity.Message> history, Long userId) {
        // 无历史则无需改写，原样返回，避免无意义 LLM 调用
        if (history.isEmpty()) {
            return question;
        }
        // 无指代且不是过短问题时跳过改写，减少无效 LLM 调用
        if (skipWithoutPronoun && !containsPronoun(question)) {
            return question;
        }
        // 将历史消息拼成 "role: content" 文本，作为上下文
        String historyText = history.stream()
                .map(message -> message.getRole() + ": " + message.getContent())
                .collect(Collectors.joining("\n"));
        SystemMessage system = new SystemMessage(
                "根据历史对话，把用户当前问题改写为独立完整的问题，只输出改写后的问题。"
        );
        // 组装输入：历史 + 当前问题
        String inputText = "历史对话：\n" + historyText + "\n\n当前问题：" + question;
        UserMessage user = new UserMessage(inputText);
        // 调用大模型生成改写结果
        String rewritten = chatModel.call(new Prompt(List.of(system, user)))
                .getResult()
                .getOutput()
                .getText();
        // 记录本次改写消耗的 token（输入 + 输出）
        usageLogService.log(userId, chatModelName, tokenCounter.count(inputText), tokenCounter.count(rewritten));
        // LLM 返回为空时回退到原问题，保证检索流程不中断
        return rewritten == null || rewritten.isBlank() ? question : rewritten.trim();
    }

    private boolean containsPronoun(String question) {
        return question.matches(".*(它|他|她|这个|那个|这些|那些|上述|上面|以上).*");
    }

    /**
     * 补检索改写（Agentic RAG 阶段 1，spec 4.2.5）。
     *
     * <p>做什么：对首轮改写后的独立问题换一个检索角度再生成一个检索表达。
     * 为什么：首轮命中不足时，换检索角度可能召回新候选；结果用状态机表达，
     * 失败/无效绝不回退原问题重查（相同查询只是重复付费）。
     */
    @Override
    public RetryRewriteResult rewriteForRetry(String question, Long userId) {
        SystemMessage system = new SystemMessage(
                "针对下面的问题换一个检索角度生成检索词。"
                        + "必须保留原问题的关键实体、时间范围、版本号与限定条件，"
                        + "不得改变原问题的语义目标，只换检索角度。"
                        + "只输出一个检索表达，不回答问题。"
        );
        UserMessage user = new UserMessage(question);
        String output;
        try {
            output = chatModel.call(new Prompt(List.of(system, user)))
                    .getResult()
                    .getOutput()
                    .getText();
        } catch (Exception e) {
            // LLM 调用未完成：不计量，直接结束补检（PASSTHROUGH）
            log.error("补检改写 LLM 调用失败 userId={}", userId, e);
            return RetryRewriteResult.of(RetryRewriteResult.Type.PASSTHROUGH);
        }
        // 调用已完成即计量（含结果判 INVALID 的场景），与 rewrite() 同一模式
        usageLogService.log(userId, chatModelName, tokenCounter.count(question), tokenCounter.count(output));
        String trimmed = output == null ? null : output.trim();
        if (trimmed == null || trimmed.isEmpty() || trimmed.equals(question)) {
            // 无效输出：调用已计量，但不再执行无意义的相同检索
            log.info("补检改写结果无效，结束补检 userId={} type=INVALID", userId);
            return RetryRewriteResult.of(RetryRewriteResult.Type.INVALID);
        }
        if (log.isDebugEnabled()) {
            log.debug("补检改写完成 userId={} before={} after={}", userId, question, trimmed);
        }
        return RetryRewriteResult.rewritten(trimmed);
    }
}

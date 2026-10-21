package com.wisread.service;

import com.wisread.model.RetryRewriteResult;
import com.wisread.service.impl.QueryRewriteServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 补检改写 rewriteForRetry 单测（spec 4.2.5 结果状态机）。
 * REWRITTEN 允许继续补检；PASSTHROUGH（LLM 失败）与 INVALID（空白/与输入相同）都直接结束补检；
 * 计量边界：调用完成即计量（含 INVALID），异常未完成不计量。
 */
@ExtendWith(MockitoExtension.class)
class QueryRewriteForRetryTest {

    private static final String QUESTION = "这个制度什么时候生效";

    @Mock
    private ChatModel chatModel;

    @Mock
    private TokenCounter tokenCounter;

    @Mock
    private UsageLogService usageLogService;

    private QueryRewriteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new QueryRewriteServiceImpl(chatModel, tokenCounter, usageLogService, "qwen-test");
    }

    private void mockChatOutput(String output) {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
    }

    @Test
    void validRewriteReturnsRewritten() {
        mockChatOutput("该制度的生效日期规定");

        RetryRewriteResult result = service.rewriteForRetry(QUESTION, 1L);

        assertThat(result.type()).isEqualTo(RetryRewriteResult.Type.REWRITTEN);
        assertThat(result.text()).isEqualTo("该制度的生效日期规定");
        verify(usageLogService, times(1)).log(eq(1L), eq("qwen-test"), anyInt(), anyInt());
    }

    @Test
    void blankOutputReturnsInvalid() {
        mockChatOutput("   ");

        RetryRewriteResult result = service.rewriteForRetry(QUESTION, 1L);

        assertThat(result.type()).isEqualTo(RetryRewriteResult.Type.INVALID);
        // 调用已完成（含判无效的场景）也计量
        verify(usageLogService, times(1)).log(eq(1L), eq("qwen-test"), anyInt(), anyInt());
    }

    @Test
    void sameAsInputReturnsInvalid() {
        mockChatOutput(QUESTION);

        RetryRewriteResult result = service.rewriteForRetry(QUESTION, 1L);

        assertThat(result.type()).isEqualTo(RetryRewriteResult.Type.INVALID);
        verify(usageLogService, times(1)).log(eq(1L), eq("qwen-test"), anyInt(), anyInt());
    }

    @Test
    void llmExceptionReturnsPassthrough() {
        doThrow(new RuntimeException("LLM 不可用")).when(chatModel).call(any(Prompt.class));

        RetryRewriteResult result = service.rewriteForRetry(QUESTION, 1L);

        assertThat(result.type()).isEqualTo(RetryRewriteResult.Type.PASSTHROUGH);
        // 调用未完成不计量
        verifyNoInteractions(usageLogService);
    }

    @Test
    void promptContainsConstraintCopy() {
        mockChatOutput("该制度的生效日期规定");

        service.rewriteForRetry(QUESTION, 1L);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        List<Message> instructions = promptCaptor.getValue().getInstructions();
        SystemMessage systemMessage = instructions.stream()
                .filter(message -> message instanceof SystemMessage)
                .map(message -> (SystemMessage) message)
                .findFirst()
                .orElseThrow();
        // 提示词约束：保留关键实体、只输出一个检索表达（spec 4.2.5）
        assertThat(systemMessage.getText()).contains("关键实体");
        assertThat(systemMessage.getText()).contains("只输出");
    }
}

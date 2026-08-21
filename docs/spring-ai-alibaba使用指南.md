# 智阅项目 Spring AI Alibaba 使用指南

> 面向“只了解一点 Spring AI Alibaba”的开发者。本文以当前代码实现为准，先讲它是什么，再讲本项目现在怎么用，最后给最小示例和常见坑。

## 1. Spring AI Alibaba 是什么

### 1.1 Spring AI 提供抽象层

Spring AI 是 Spring 生态的 AI 应用抽象层，类似“LLM 界的 JDBC”。业务代码不直接拼 HTTP 调各家模型，而是面向统一接口写：

- `ChatModel`：对话生成，支持同步 `call(Prompt)` 和流式 `stream(Prompt)`
- `EmbeddingModel`：文本向量化，`embed(List<String>)` 返回 `List<float[]>`
- `Prompt` / `Message`：封装模型输入，常见实现是 `SystemMessage`、`UserMessage`、`AssistantMessage`
- `ChatClient`：比 `ChatModel` 更高级的链式 API，适合简单问答

底层是通义千问、OpenAI、DeepSeek 还是本地模型，对业务代码基本透明。

### 1.2 Spring AI Alibaba 是阿里基于 Spring AI 的实现

Spring AI Alibaba 是阿里开源项目，基于 Spring AI，把通义系列模型、阿里云百炼 DashScope 等能力接入 Java 应用。除了基础模型适配，还提供 Agent、Graph 工作流、记忆、工具调用、MCP、Nacos 集成等能力。

官方推荐依赖是 `spring-ai-alibaba-starter-dashscope`，配置前缀是 `spring.ai.dashscope.*`。

### 1.3 本项目现在用的是哪种接入方式

需要注意：本项目的 `README.md` 和架构文档写的是“Spring AI Alibaba”，但当前实际依赖是 Spring AI 官方的 **OpenAI 兼容 starter**：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

配置里把 `base-url` 指向 DashScope 的 OpenAI 兼容地址 `.../compatible-mode`，所以调用的是 Qwen 模型，但代码层用的仍是 `ChatModel` / `EmbeddingModel` 这两个标准接口。

简单理解：**本项目没有引入 Alibaba 专属依赖，只用了 Spring AI 通用接口 + DashScope 兼容端点**。好处是当前代码已经足够支撑 RAG 问答；以后需要 Agent、Graph 等 Alibaba 专属能力时，再换官方 starter 即可。

## 2. 当前依赖和配置

### 2.1 Maven 依赖（`backend/pom.xml`）

```xml
<properties>
    <spring-ai.version>1.1.0</spring-ai.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
</dependencies>
```

Spring AI 1.x 的构件在 Spring Milestones 仓库，所以 `pom.xml` 里保留了这个仓库配置：

```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <url>https://repo.spring.io/milestone</url>
    </repository>
</repositories>
```

### 2.2 应用配置（`backend/src/main/resources/application.yml`）

当前配置：

```yaml
spring:
  ai:
    model:
      chat: openai
      embedding: openai
    openai:
      api-key: ${DASHSCOPE_API_KEY}
      base-url: ${DASHSCOPE_BASE_URL:https://.../compatible-mode}
      chat:
        options:
          model: qwen3.7-plus
          temperature: 0.7
      embedding:
        options:
          model: qwen3.7-text-embedding
```

各配置含义：

| 配置 | 作用 |
|---|---|
| `spring.ai.model.chat` | 告诉 Spring AI 当前用哪个模型供应商，这里是 `openai` 兼容模式 |
| `spring.ai.openai.api-key` | DashScope / 百炼的 API Key |
| `spring.ai.openai.base-url` | DashScope OpenAI 兼容端点，必须以 `/compatible-mode` 结尾 |
| `spring.ai.openai.chat.options.model` | 对话模型名 |
| `spring.ai.openai.embedding.options.model` | Embedding 模型名 |

项目里还有两个开关控制本地 Mock：

```yaml
wisread:
  embedding:
    mock-enabled: false
  chat:
    mock-enabled: false
```

把 `mock-enabled` 设为 `true` 时，`LocalChatModel` 和 `LocalEmbeddingModel` 会通过 `@Primary` 覆盖真实模型 bean，适合没有 API Key 时跑通流程。

## 3. 本项目怎么用 Spring AI

### 3.1 ChatModel：多轮问答 + SSE 流式输出

`ChatModel` 注入位置：

- `ChatService`：主问答
- `QueryRewriteService`：多轮对话时改写用户问题

以 `ChatService.buildPrompt` 为例，它把检索到的文档片段放进 `SystemMessage`，把历史对话和当前问题放进消息列表：

```java
List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
messages.add(new SystemMessage(systemPrompt));   // 文档片段 + 回答约束
messages.add(new UserMessage(historyUserText));  // 历史问题
messages.add(new AssistantMessage(historyAnswerText)); // 历史回答
messages.add(new UserMessage(question));         // 当前问题

Prompt prompt = new Prompt(messages);
```

流式生成时调用 `chatModel.stream(prompt)`，每次拿到一个 token 就通过 SSE 发给前端：

```java
chatModel.stream(prompt).subscribe(
    response -> {
        String token = response.getResult().getOutput().getText();
        // 发送 SSE delta
    },
    error -> emitter.completeWithError(error),
    () -> emitter.complete()
);
```

同步调用（`QueryRewriteService`）更简单：

```java
String rewritten = chatModel.call(prompt)
        .getResult()
        .getOutput()
        .getText();
```

### 3.2 EmbeddingModel：文档切块后向量化

`EmbeddingService` 是对 `EmbeddingModel` 的薄封装：

```java
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public List<float[]> embed(List<String> texts) {
        return embeddingModel.embed(texts);
    }
}
```

调用链：

1. `DocumentProcessingService` 用 PDFBox 解析 PDF，再按页切块；
2. 把每个 `TextChunk` 的文本交给 `EmbeddingService.embed(...)`；
3. `VectorIndexingService` 把向量写成 PostgreSQL `vector` 类型，存到 `document_chunks.embedding`；
4. 问答时 `ChatService` 对查询做同样向量化，再用 pgvector 的 `<=>` 算子做相似度检索。

### 3.3 Mock 模式：没有 API Key 也能跑

本地 Mock 类：

- `LocalChatModel`：实现 `ChatModel`，从系统提示里取第一段文档原文拼答案，输出 `[1]` 引用；
- `LocalEmbeddingModel`：实现 `EmbeddingModel`，用 SHA-256 生成 1024 维确定性向量。

两者都满足：

```java
@Component
@Primary
@ConditionalOnProperty(
        name = "wisread.chat.mock-enabled",
        havingValue = "true",
        matchIfMissing = true
)
```

所以只要配置开关为 `true`，Spring 会自动注入 Mock bean；关闭后就注入 DashScope 对应的真实 bean。业务代码不需要感知切换。

## 4. 最小可运行示例

如果以后要加一个简单 AI 接口，推荐用 `ChatClient`：

```java
@RestController
public class AiDemoController {

    private final ChatClient chatClient;

    public AiDemoController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/ai/demo")
    public String ask(@RequestParam String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}
```

需要流式输出时用 `ChatModel` 更直接，因为可以逐个 token 控制 SSE 事件，就像 `ChatService` 做的那样。

## 5. 以后切换官方 Spring AI Alibaba starter（可选）

如果要用 Agent、Graph 工作流、官方 DashScope 适配等能力，可以在 `pom.xml` 替换依赖：

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
    <version>1.1.2.1</version>
</dependency>
```

配置改成：

```yaml
spring:
  ai:
    model:
      chat: dashscope
      embedding: dashscope
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen-plus
          temperature: 0.7
      embedding:
        options:
          model: text-embedding-v3
```

因为两者都实现 Spring AI 标准接口，业务代码里的 `ChatModel`、`EmbeddingModel` 注入点基本不用改。版本号要和项目 Spring Boot / Spring AI 版本匹配，建议使用 `spring-ai-alibaba-bom` 做版本管理，并以官方文档为准。

## 6. 常见坑

- **不要提交真实 API Key**：`application.yml` 里的 key 应全部来自环境变量；不要把真实 Key 写进仓库。
- **base-url 必须以 `/compatible-mode` 结尾**：这是 DashScope 提供 OpenAI 兼容协议的关键路径。
- **换 Embedding 模型前先确认向量维度**：`document_chunks.embedding` 固定为 `VECTOR(1024)`，换模型后维度不一致会入库失败，还需要重建历史索引。
- **改模型名时同步改 `embedding_model_version`**：当前它直接取配置里的 embedding 模型名，用于识别新旧索引是否一致。
- **Mock 开关是独立配置**：`wisread.chat.mock-enabled` 和 `wisread.embedding.mock-enabled` 分别控制，本地跑通流程时可以都开，接真实模型时都关。

## 7. 官方资料

- Spring AI Alibaba 概览：<https://java2ai.com/en/docs/overview/>
- 组件列表与使用指南：<https://java2ai.com/docs/1.0.0.2/tutorials/starters-and-quick-guide/>
- DashScope Chat Model 配置：<http://java2ai.com/integration/chatmodels/dashScope/>

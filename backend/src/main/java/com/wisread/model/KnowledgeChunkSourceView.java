package com.wisread.model;

/**
 * 知识库切片引用视图（内存数据模型，不持久化）。
 * 按 chunkId 批量查询知识库切片的展示信息（不含 embedding 向量），
 * 供会话历史消息回看时解析绑库问答的引用来源使用。
 */
public record KnowledgeChunkSourceView(
        Long chunkId, // 切片ID（knowledge_chunks.id）
        Long documentId, // 所属知识库文档ID（knowledge_documents.id）
        String filename, // 所属文档的文件名
        String content, // 切片文本内容
        int pageStart, // 起始定位序号（含）
        int pageEnd, // 结束定位序号（含）
        String locationLabel // 定位单位标签，例如“页”“段落”“行”“工作表 Sheet1”
) {
}

package entity;

import java.io.Serializable;

/**
 * 校园知识库切片分块实体类
 */
public class KnowledgeChunk implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long chunkId;
    private Long docId;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    private String embedding;
    private String createdAt;

    public KnowledgeChunk() {}

    public KnowledgeChunk(Long docId, Integer chunkIndex, String content, Integer tokenCount) {
        this.docId = docId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.tokenCount = tokenCount;
    }

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "KnowledgeChunk{" +
                "chunkId=" + chunkId +
                ", docId=" + docId +
                ", chunkIndex=" + chunkIndex +
                ", tokenCount=" + tokenCount +
                '}';
    }
}

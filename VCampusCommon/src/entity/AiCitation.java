package entity;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * AI 知识库引用来源实体类
 */
public class AiCitation implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long citationId;
    private Long messageId;
    private Long chunkId;
    private String docTitle;
    private BigDecimal similarityScore;
    private String excerpt;
    private String createdAt;

    public AiCitation() {}

    public AiCitation(String docTitle, BigDecimal similarityScore, String excerpt) {
        this.docTitle = docTitle;
        this.similarityScore = similarityScore;
        this.excerpt = excerpt;
    }

    public Long getCitationId() {
        return citationId;
    }

    public void setCitationId(Long citationId) {
        this.citationId = citationId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public String getDocTitle() {
        return docTitle;
    }

    public void setDocTitle(String docTitle) {
        this.docTitle = docTitle;
    }

    public BigDecimal getSimilarityScore() {
        return similarityScore;
    }

    public void setSimilarityScore(BigDecimal similarityScore) {
        this.similarityScore = similarityScore;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public void setExcerpt(String excerpt) {
        this.excerpt = excerpt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "AiCitation{" +
                "citationId=" + citationId +
                ", docTitle='" + docTitle + '\'' +
                ", similarityScore=" + similarityScore +
                '}';
    }
}

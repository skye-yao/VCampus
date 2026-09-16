package entity;

import java.io.Serializable;

/** 商品评价：一名用户对一件商品最多一条，且必须购买过该商品。 */
public class ProductReview implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long reviewId;
    private Long productId;
    private String userId;
    private Integer rating;
    private String content;
    private String createdAt;
    private String displayName;

    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    /** 展示用昵称：管理员看完整账号，作者看“我”，其他人看脱敏昵称。 */
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}

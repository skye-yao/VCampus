package entity;

import java.io.Serializable;

public class Book implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final java.util.List<String> CATEGORIES = java.util.List.of(
            "计算机", "文学", "艺术", "历史", "经济管理", "哲学", "自然科学", "其他");

    private int id;
    private int titleId;
    private int totalCopies = 1;
    private int availableCopies;
    private java.util.List<Integer> copyIds = new java.util.ArrayList<>();
    public int getTitleId() { return titleId == 0 ? id : titleId; }
    public void setTitleId(int value) { titleId = value; }
    public int getTotalCopies() { return totalCopies; }
    public void setTotalCopies(int value) { totalCopies = value; }
    public int getAvailableCopies() { return availableCopies; }
    public void setAvailableCopies(int value) { availableCopies = value; }
    public java.util.List<Integer> getCopyIds() { return copyIds; }
    public void setCopyIds(java.util.List<Integer> value) { copyIds = value; }
    private String isbn;
    private String name;
    private String author;
    private String publisher;
    private String category = "其他";

    public String getCategory() {
        return category == null || category.isBlank() ? "其他" : category;
    }

    public void setCategory(String category) {
        this.category = category == null || category.isBlank() ? "其他" : category.trim();
    }
    private java.math.BigDecimal price;
    public java.math.BigDecimal getPrice() { return price; }
    public void setPrice(java.math.BigDecimal price) { this.price = price; }

    /**
     * 图书状态：
     * 0 - 可借
     * 1 - 已借
     * 2 - 预约
     * 3 - 遗失
     */
    private int status;

    public Book() {
    }

    public Book(int id, String isbn, String name, String author,
                String publisher, int status) {
        this.id = id;
        this.isbn = isbn;
        this.name = name;
        this.author = author;
        this.publisher = publisher;
        this.status = status;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getIsbn() {
        return isbn;
    }

    public void setIsbn(String isbn) {
        this.isbn = isbn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getPublisher() {
        return publisher;
    }

    public void setPublisher(String publisher) {
        this.publisher = publisher;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "Book{" +
                "id=" + id +
                ", isbn='" + isbn + '\'' +
                ", name='" + name + '\'' +
                ", author='" + author + '\'' +
                ", publisher='" + publisher + '\'' +
                ", category='" + getCategory() + '\'' +
                ", status=" + status +
                '}';
    }
}

package entity;

import java.io.Serializable;

public class Book implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String isbn;
    private String name;
    private String author;
    private String publisher;

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
                ", status=" + status +
                '}';
    }
}
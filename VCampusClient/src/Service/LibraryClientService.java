package Service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import entity.Book;
import entity.BookReview;
import entity.BorrowRecord;
import entity.FineRecord;
import entity.Reservation;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 图书馆客户端业务门面，统一维护 action、参数和响应数据转换。 */
public class LibraryClientService {

    private static final LibraryClientService INSTANCE = new LibraryClientService();

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class,
                    (JsonSerializer<LocalDateTime>) (value, type, context) -> context.serialize(value.toString()))
            .registerTypeAdapter(LocalDateTime.class,
                    (JsonDeserializer<LocalDateTime>) (json, type, context) -> LocalDateTime.parse(json.getAsString()))
            .create();

    private LibraryClientService() {}

    public static LibraryClientService getInstance() { return INSTANCE; }
    public CompletableFuture<List<java.util.Map<String,String>>> getAdminRecords(String kind) {
        return simpleRequest("getadminrecords", "kind", kind).thenApply(r ->
                list(r, "records", new TypeToken<List<java.util.Map<String,String>>>() {}.getType()));
    }

    public CompletableFuture<List<vo.LostBookNotice>> getPublicLossNotices() {
        return send(request("getpubliclossnotices"))
                .thenApply(r -> list(r, "notices", new TypeToken<List<vo.LostBookNotice>>() {}.getType()));
    }

    public CompletableFuture<List<Book>> searchBooks(String keyword) {
        Message request = request("searchbook");
        request.putData("keyword", keyword == null ? "" : keyword.trim());
        return send(request).thenApply(r -> list(r, "books", new TypeToken<List<Book>>() {}.getType()));
    }

    public CompletableFuture<Book> getBookDetail(int bookId) {
        return simpleRequest("getbookdetail", "bookId", bookId)
                .thenApply(r -> object(r, "book", Book.class));
    }

    public CompletableFuture<Void> reserveBook(int bookId) { return bookAction("reservebook", bookId); }
    public CompletableFuture<Void> cancelReservation(int reservationId) {
        return simpleRequest("cancelreservation", "reservationId", reservationId).thenApply(r -> null);
    }

    public CompletableFuture<List<BorrowRecord>> getBorrowHistory() {
        return send(request("getborrowhistory"))
                .thenApply(r -> list(r, "borrowHistory", new TypeToken<List<BorrowRecord>>() {}.getType()));
    }

    public CompletableFuture<List<BorrowRecord>> getCurrentBorrow() {
        return send(request("getcurrentborrow"))
                .thenApply(r -> list(r, "currentBorrow", new TypeToken<List<BorrowRecord>>() {}.getType()));
    }

    public CompletableFuture<List<Reservation>> getReservations() {
        return send(request("getreservations"))
                .thenApply(r -> list(r, "reservations", new TypeToken<List<Reservation>>() {}.getType()));
    }

    public CompletableFuture<List<BookReview>> getBookReviews(int bookId) {
        return simpleRequest("getbookreviews", "bookId", bookId)
                .thenApply(r -> list(r, "reviews", new TypeToken<List<BookReview>>() {}.getType()));
    }

    public CompletableFuture<Void> addBookReview(int bookId, String content) {
        Message request = request("addbookreview");
        request.putData("bookId", bookId);
        request.putData("content", content == null ? "" : content.trim());
        return send(request).thenApply(r -> null);
    }

    public CompletableFuture<Void> deleteBookReview(int reviewId) {
        return simpleRequest("deletebookreview", "reviewId", reviewId).thenApply(r -> null);
    }

    public CompletableFuture<Void> reportLoss(int bookId) { return bookAction("reportloss", bookId); }
    public CompletableFuture<Void> cancelLoss(int bookId) { return bookAction("cancelloss", bookId); }

    public CompletableFuture<List<FineRecord>> getFineRecords() {
        return send(request("getfinerecords"))
                .thenApply(r -> list(r, "fineRecords", new TypeToken<List<FineRecord>>() {}.getType()));
    }

    public CompletableFuture<Void> payFine(int fineId) {
        return simpleRequest("payfine", "fineId", fineId).thenApply(r -> null);
    }

    public CompletableFuture<Void> addBook(Book book) {
        return bookPayloadAction("addbook", book);
    }

    public CompletableFuture<Void> updateBook(Book book) {
        return bookPayloadAction("updatebook", book);
    }

    public CompletableFuture<Void> removeBook(int bookId) { return bookAction("removebook", bookId); }

    public CompletableFuture<Integer> getBookStatus(int bookId) {
        return simpleRequest("getbookstatus", "bookId", bookId).thenApply(r -> {
            Object value = r.getData("status");
            if (value instanceof Number) return ((Number) value).intValue();
            return Integer.parseInt(String.valueOf(value));
        });
    }

    private CompletableFuture<Void> bookAction(String action, int bookId) {
        return simpleRequest(action, "bookId", bookId).thenApply(r -> null);
    }

    private CompletableFuture<Void> bookPayloadAction(String action, Book book) {
        Message request = request(action);
        request.putData("book", book);
        return send(request).thenApply(r -> null);
    }

    private CompletableFuture<Message> simpleRequest(String action, String key, Object value) {
        Message request = request(action);
        request.putData(key, value);
        return send(request);
    }

    private Message request(String action) {
        return new Message(MessageType.REQUEST, "library", action);
    }

    private CompletableFuture<Message> send(Message request) {
        return SocketClient.getInstance().sendAsync(request).thenApply(response -> {
            if (response == null) throw new LibraryClientException("服务端未返回响应");
            if (response.getCode() != MessageCode.SUCCESS) {
                throw new LibraryClientException(response.getMessage() == null ? "图书馆操作失败" : response.getMessage());
            }
            return response;
        });
    }

    private <T> T object(Message response, String key, Class<T> clazz) {
        Object value = response.getData(key);
        return value == null ? null : gson.fromJson(gson.toJsonTree(value), clazz);
    }

    private <T> List<T> list(Message response, String key, Type type) {
        Object value = response.getData(key);
        if (value == null) return Collections.emptyList();
        List<T> result = gson.fromJson(gson.toJsonTree(value), type);
        return result == null ? Collections.emptyList() : result;
    }

    public static class LibraryClientException extends RuntimeException {
        public LibraryClientException(String message) { super(message); }
    }
}

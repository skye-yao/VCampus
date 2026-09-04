package handler;

import entity.Book;
import entity.BookReview;
import entity.BorrowRecord;
import entity.FineRecord;
import entity.Reservation;
import enums.Role;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.LibraryServerService;
import session.SessionManager;
import session.UserSession;

import java.util.List;

/**
 * 图书馆模块请求处理器
 *
 * <p>负责图书查询、预约、借阅记录、书评、挂失、罚款以及管理员图书管理等业务请求。</p>
 */
public class LibraryHandler {

    private final LibraryServerService libraryService = new LibraryServerService();

    /**
     * 图书馆模块统一请求入口
     *
     * @param request 客户端请求消息
     * @return 服务端响应消息
     */
    public Message handle(Message request) {

        String action = request.getAction();

        Message response = new Message(
                MessageType.RESPONSE,
                "library",
                action
        );

        response.setUID(request.getUID());

        // 1. 检查 action
        if (action == null || action.isBlank()) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("Action 不能为空");
            return response;
        }

        // 2. 检查登录状态
        String token = request.getToken();

        UserSession session = SessionManager.getInstance().getSession(token);

        if (session == null) {
            response.setCode(MessageCode.UNAUTHORIZED);
            response.setMessage("登录会话已失效，请重新登录");
            return response;
        }

        // 当前登录用户信息统一从服务端 Session 获取
        String userId = session.getUsername();
        String role = session.getRole();

        try {

            switch (action.toLowerCase()) {
                case "getadminrecords":
                    if (!isAdmin(role)) return forbidden(response);
                    String kind = request.getData("kind");
                    response.putData("records", new dao.LibraryAdminDAO().findRecords(kind));
                    response.setCode(MessageCode.SUCCESS);
                    return response;
                case "cancelreservation":
                    Integer reservationId = getIntegerData(request, "reservationId");
                    if (reservationId == null || reservationId <= 0) {
                        response.setCode(MessageCode.BAD_REQUEST);
                        response.setMessage("预约编号无效");
                    } else if (libraryService.cancelReservation(userId, reservationId)) {
                        response.setCode(MessageCode.SUCCESS);
                        response.setMessage("预约已取消");
                    } else {
                        response.setCode(MessageCode.CONFLICT);
                        response.setMessage("只能取消本人仍在预约中的记录，请刷新后重试");
                    }
                    return response;
                case "getpubliclossnotices":
                    response.putData("notices", libraryService.getPublicLossNotices());
                    response.setCode(MessageCode.SUCCESS);
                    return response;

                // =========================
                // 图书查询
                // =========================
                case "searchbook":
                    return handleSearchBook(request, response);

                case "getbookdetail":
                    return handleGetBookDetail(request, response);

                // =========================
                // 图书预约 / 借阅信息
                // =========================
                case "reservebook":
                    return handleReserveBook(request, response, userId);

                case "getborrowhistory":
                    return handleGetBorrowHistory(response, userId);

                case "getcurrentborrow":
                    return handleGetCurrentBorrow(response, userId);

                case "getreservations":
                    return handleGetReservations(response, userId);

                // =========================
                // 图书评价
                // =========================
                case "getbookreviews":
                    return handleGetBookReviews(request, response);

                case "addbookreview":
                    return handleAddBookReview(request, response, userId);

                case "deletebookreview":
                    return handleDeleteBookReview(request, response, userId, isAdmin(role));

                // =========================
                // 图书挂失
                // =========================
                case "reportloss":
                    return handleReportLoss(request, response, userId);

                case "cancelloss":
                    return handleCancelLoss(request, response, userId);

                // =========================
                // 罚款
                // =========================
                case "getfinerecords":
                    return handleGetFineRecords(response, userId);

                case "payfine":
                    return handlePayFine(request, response, userId);

                // =========================
                // 管理员图书管理
                // =========================
                case "addbook":
                    return handleAddBook(request, response, role);

                case "updatebook":
                    return handleUpdateBook(request, response, role);

                case "removebook":
                    return handleRemoveBook(request, response, role);

                case "getbookstatus":
                    return handleGetBookStatus(request, response, role);

                default:
                    response.setCode(MessageCode.BAD_REQUEST);
                    response.setMessage("不支持的图书馆操作: " + action);
                    return response;
            }

        } catch (Exception e) {

            response.setCode(MessageCode.ERROR);
            response.setMessage("图书馆模块处理异常: " + e.getMessage());

            return response;
        }
    }

    // =========================================================
    // 一、图书查询
    // =========================================================

    /**
     * 图书搜索
     * 支持书名、作者、ISBN关键字搜索
     */
    private Message handleSearchBook(
            Message request,
            Message response
    ) throws Exception {

        String keyword = request.getData("keyword");

        List<Book> books = libraryService.searchBook(keyword);

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("查询成功");
        response.putData("books", books);

        return response;
    }

    /**
     * 查询图书详情
     */
    private Message handleGetBookDetail(
            Message request,
            Message response
    ) throws Exception {

        Integer bookId = getIntegerData(request, "bookId");

        if (bookId == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少或非法的图书编号");
            return response;
        }

        Book book = libraryService.getBookDetail(bookId);

        if (book == null) {
            response.setCode(MessageCode.NOT_FOUND);
            response.setMessage("图书不存在");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("查询成功");
        response.putData("book", book);

        return response;
    }

    // =========================================================
    // 二、图书预约 / 借阅信息
    // =========================================================

    /**
     * 预约图书
     */
    private Message handleReserveBook(
            Message request,
            Message response,
            String userId
    ) throws Exception {

        Integer bookId = getIntegerData(request, "bookId");

        if (bookId == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少或非法的图书编号");
            return response;
        }

        boolean success = libraryService.reserveBook(userId, bookId);

        if (!success) {
            response.setCode(MessageCode.CONFLICT);
            response.setMessage("预约失败：该书已借出、已预约或已挂失，请刷新列表查看最新状态");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("预约成功");

        return response;
    }

    /**
     * 查询借阅历史
     */
    private Message handleGetBorrowHistory(
            Message response,
            String userId
    ) throws Exception {

        List<BorrowRecord> records =
                libraryService.getBorrowHistory(userId);

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("查询成功");
        response.putData("borrowHistory", records);

        return response;
    }

    /**
     * 查询当前借阅
     */
    private Message handleGetCurrentBorrow(
            Message response,
            String userId
    ) throws Exception {

        List<BorrowRecord> records =
                libraryService.getCurrentBorrow(userId);

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("查询成功");
        response.putData("currentBorrow", records);

        return response;
    }

    /**
     * 查询当前用户预约记录
     */
    private Message handleGetReservations(
            Message response,
            String userId
    ) throws Exception {

        List<Reservation> reservations =
                libraryService.getReservations(userId);

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("查询成功");
        response.putData("reservations", reservations);

        return response;
    }

    // =========================================================
    // 三、图书评价
    // =========================================================

    /**
     * 查询某本图书的评价
     */
    private Message handleGetBookReviews(
            Message request,
            Message response
    ) throws Exception {

        Integer bookId = getIntegerData(request, "bookId");

        if (bookId == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少或非法的图书编号");
            return response;
        }

        List<BookReview> reviews =
                libraryService.getBookReviews(bookId);

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("查询成功");
        response.putData("reviews", reviews);

        return response;
    }

    /**
     * 添加图书评价
     */
    private Message handleAddBookReview(
            Message request,
            Message response,
            String userId
    ) throws Exception {

        Integer bookId = getIntegerData(request, "bookId");
        String content = request.getData("content");

        if (bookId == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少或非法的图书编号");
            return response;
        }

        if (content == null || content.isBlank()) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("评价内容不能为空");
            return response;
        }

        boolean success = libraryService.addBookReview(
                userId,
                bookId,
                content
        );

        if (!success) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("评价发表失败");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("评价发表成功");

        return response;
    }

    /**
     * 删除自己的评价
     */
    private Message handleDeleteBookReview(
            Message request,
            Message response,
            String userId,
            boolean admin
    ) throws Exception {

        Integer reviewId = getIntegerData(request, "reviewId");

        if (reviewId == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少或非法的评价编号");
            return response;
        }

        boolean success = libraryService.deleteBookReview(
                userId,
                reviewId,
                admin
        );

        if (!success) {
            response.setCode(MessageCode.FORBIDDEN);
            response.setMessage("评价不存在或无权删除");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("评价删除成功");

        return response;
    }

    // =========================================================
    // 四、图书挂失
    // =========================================================

    /**
     * 图书挂失
     */
    private Message handleReportLoss(
            Message request,
            Message response,
            String userId
    ) throws Exception {

        Integer bookId = getIntegerData(request, "bookId");

        if (bookId == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少或非法的图书编号");
            return response;
        }

        boolean success = libraryService.reportLoss(
                userId,
                bookId
        );

        if (!success) {
            response.setCode(MessageCode.CONFLICT);
            response.setMessage("挂失失败，请确认该图书属于当前借阅且未被挂失");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("图书挂失成功");

        return response;
    }

    /**
     * 解除挂失
     */
    private Message handleCancelLoss(
            Message request,
            Message response,
            String userId
    ) throws Exception {

        Integer bookId = getIntegerData(request, "bookId");

        if (bookId == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少或非法的图书编号");
            return response;
        }

        boolean success = libraryService.cancelLoss(
                userId,
                bookId
        );

        if (!success) {
            response.setCode(MessageCode.CONFLICT);
            response.setMessage("解除挂失失败");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("已解除挂失");

        return response;
    }

    // =========================================================
    // 五、罚款
    // =========================================================

    /**
     * 查询罚款记录
     */
    private Message handleGetFineRecords(
            Message response,
            String userId
    ) throws Exception {

        List<FineRecord> records =
                libraryService.getFineRecords(userId);

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("查询成功");
        response.putData("fineRecords", records);

        return response;
    }

    /**
     * 缴纳罚款
     */
    private Message handlePayFine(
            Message request,
            Message response,
            String userId
    ) throws Exception {

        Integer fineId = getIntegerData(request, "fineId");

        if (fineId == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少或非法的罚款记录编号");
            return response;
        }

        boolean success = libraryService.payFine(
                userId,
                fineId
        );

        if (!success) {
            response.setCode(MessageCode.CONFLICT);
            response.setMessage("缴费失败，罚款不存在、已缴费或不属于当前用户");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("缴费成功");

        return response;
    }

    // =========================================================
    // 六、管理员图书管理
    // =========================================================

    /**
     * 图书上架
     */
    private Message handleAddBook(
            Message request,
            Message response,
            String role
    ) throws Exception {

        if (!isAdmin(role)) {
            return forbidden(response);
        }

        Book book = readBook(request);

        if (book == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少图书信息");
            return response;
        }

        boolean success = libraryService.addBook(book);

        if (!success) {
            response.setCode(MessageCode.CONFLICT);
            response.setMessage("图书上架失败，可能存在相同ISBN或数据不完整");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("图书上架成功");

        return response;
    }

    /**
     * 修改图书信息
     */
    private Message handleUpdateBook(
            Message request,
            Message response,
            String role
    ) throws Exception {

        if (!isAdmin(role)) {
            return forbidden(response);
        }

        Book book = readBook(request);

        if (book == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少图书信息");
            return response;
        }

        boolean success = libraryService.updateBook(book);

        if (!success) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("图书信息修改失败");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("图书信息修改成功");

        return response;
    }

    /**
     * 图书下架
     */
    private Message handleRemoveBook(
            Message request,
            Message response,
            String role
    ) throws Exception {

        if (!isAdmin(role)) {
            return forbidden(response);
        }

        Integer bookId = getIntegerData(request, "bookId");

        if (bookId == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少或非法的图书编号");
            return response;
        }

        boolean success = libraryService.removeBook(bookId);

        if (!success) {
            response.setCode(MessageCode.CONFLICT);
            response.setMessage("图书下架失败，图书不存在或仍存在关联记录");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("图书下架成功");

        return response;
    }

    /**
     * 查询图书状态
     */
    private Message handleGetBookStatus(
            Message request,
            Message response,
            String role
    ) throws Exception {

        if (!isAdmin(role)) {
            return forbidden(response);
        }

        Integer bookId = getIntegerData(request, "bookId");

        if (bookId == null) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("缺少或非法的图书编号");
            return response;
        }

        Integer status = libraryService.getBookStatus(bookId);

        if (status == null) {
            response.setCode(MessageCode.NOT_FOUND);
            response.setMessage("图书不存在");
            return response;
        }

        response.setCode(MessageCode.SUCCESS);
        response.setMessage("查询成功");
        response.putData("status", status);

        return response;
    }

    // =========================================================
    // 七、工具方法
    // =========================================================

    /**
     * 判断用户是否为管理员。
     *
     * 当前 UserSession 中保存的是 Role 的中文 description，
     * 即“管理员”“教师”“学生”。
     */
    private Book readBook(Message request) {
        Object value = request.getData("book");
        if (value == null) return null;
        com.google.gson.Gson gson = util.JsonUtil.createGson();
        return gson.fromJson(gson.toJsonTree(value), Book.class);
    }

    private boolean isAdmin(String role) {
        return Role.ADMIN.getDescription().equals(role);
    }

    /**
     * 构造无权限响应
     */
    private Message forbidden(Message response) {

        response.setCode(MessageCode.FORBIDDEN);
        response.setMessage("当前用户无权限执行该操作");

        return response;
    }

    /**
     * 从 Message.data 中安全获取 Integer。
     *
     * Gson 将 Map<String, Object> 中的 JSON 数字反序列化时，
     * 可能得到 Double 等 Number 类型，因此不能直接强转 Integer。
     */
    private Integer getIntegerData(
            Message request,
            String key
    ) {

        if (request.getData() == null) {
            return null;
        }

        Object value = request.getData().get(key);

        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

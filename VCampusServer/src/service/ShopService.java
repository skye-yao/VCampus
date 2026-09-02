package service;

import dao.CartItemDAO;
import dao.OrderItemDAO;
import dao.ProductDAO;
import dao.ShopOrderDAO;
import dao.ShopRefundDAO;
import entity.CartItem;
import entity.OrderItem;
import entity.Product;
import entity.ShopOrder;
import entity.ShopRefund;
import enums.OrderStatus;
import enums.ProductStatus;
import enums.RefundStatus;
import exception.BusinessException;
import exception.DatabaseException;
import util.DBUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 商店模块业务服务。 */
public class ShopService {
    private final ProductDAO productDAO = new ProductDAO();
    private final CartItemDAO cartItemDAO = new CartItemDAO();
    private final ShopOrderDAO shopOrderDAO = new ShopOrderDAO();
    private final OrderItemDAO orderItemDAO = new OrderItemDAO();
    private final ShopRefundDAO shopRefundDAO = new ShopRefundDAO();
    private final IBankPaymentService bankPaymentService;

    public ShopService() {
        this(null);
    }

    public ShopService(IBankPaymentService bankPaymentService) {
        this.bankPaymentService = bankPaymentService;
    }

    public List<Product> listProducts(String keyword, String category, boolean admin) {
        try {
            return productDAO.findAll(keyword, category, admin);
        } catch (SQLException e) {
            throw new DatabaseException("查询商品失败", e);
        }
    }

    public Product getProduct(long productId) {
        try {
            Product product = productDAO.findById(productId);
            if (product == null) throw new BusinessException("商品不存在");
            return product;
        } catch (SQLException e) {
            throw new DatabaseException("查询商品详情失败", e);
        }
    }

    public List<CartItem> listCart(String userId) {
        try {
            return cartItemDAO.findByUserId(userId);
        } catch (SQLException e) {
            throw new DatabaseException("查询购物车失败", e);
        }
    }

    public void addCartItem(String userId, long productId, int quantity) {
        requirePositive(quantity, "购买数量");
        try {
            Product product = productDAO.findById(productId);
            if (product == null) throw new BusinessException("商品不存在");
            if (product.getStatus() != ProductStatus.ON_SALE) throw new BusinessException("商品已下架");
            if (product.getStock() < quantity) throw new BusinessException("商品库存不足");
            cartItemDAO.addOrIncrease(userId, productId, quantity);
        } catch (SQLException e) {
            throw new DatabaseException("加入购物车失败", e);
        }
    }

    public void updateCartItem(String userId, long cartItemId, int quantity) {
        requirePositive(quantity, "购买数量");
        try {
            if (!cartItemDAO.updateQuantity(userId, cartItemId, quantity)) {
                throw new BusinessException("购物车记录不存在");
            }
        } catch (SQLException e) {
            throw new DatabaseException("修改购物车失败", e);
        }
    }

    public void removeCartItem(String userId, long cartItemId) {
        try {
            if (!cartItemDAO.remove(userId, cartItemId)) {
                throw new BusinessException("购物车记录不存在");
            }
        } catch (SQLException e) {
            throw new DatabaseException("删除购物车商品失败", e);
        }
    }

    /** 当前基础版将购物车全部商品创建为一张订单。 */
    public ShopOrder createOrder(String userId) {
        Connection conn = null;
        try {
            conn = DBUtil.getConnection();
            conn.setAutoCommit(false);

            List<CartItem> cartItems = cartItemDAO.findByUserId(conn, userId, true);
            if (cartItems.isEmpty()) throw new BusinessException("购物车为空，无法创建订单");

            BigDecimal total = BigDecimal.ZERO;
            for (CartItem cartItem : cartItems) {
                if (cartItem.getProductStatus() != ProductStatus.ON_SALE) {
                    throw new BusinessException("商品已下架：" + cartItem.getProductName());
                }
                if (cartItem.getQuantity() > cartItem.getAvailableStock()) {
                    throw new BusinessException("商品库存不足：" + cartItem.getProductName());
                }
                if (!productDAO.decreaseStock(conn, cartItem.getProductId(), cartItem.getQuantity())) {
                    throw new BusinessException("商品库存发生变化，请刷新后重试：" + cartItem.getProductName());
                }
                total = total.add(cartItem.getSubtotal());
            }

            ShopOrder order = new ShopOrder();
            order.setOrderNo(newBusinessNo("SO"));
            order.setUserId(userId);
            order.setTotalAmount(total);
            order.setStatus(OrderStatus.WAIT_PAY);
            order.setExpiresAt(LocalDateTime.now().plusMinutes(30).withNano(0).toString().replace('T', ' '));
            long orderId = shopOrderDAO.insert(conn, order);
            order.setOrderId(orderId);

            for (CartItem cartItem : cartItems) {
                OrderItem item = new OrderItem();
                item.setOrderId(orderId);
                item.setProductId(cartItem.getProductId());
                item.setProductNameSnapshot(cartItem.getProductName());
                item.setUnitPrice(cartItem.getUnitPrice());
                item.setQuantity(cartItem.getQuantity());
                item.setSubtotal(cartItem.getSubtotal());
                orderItemDAO.insert(conn, item);
            }

            cartItemDAO.clear(conn, userId);
            conn.commit();
            return order;
        } catch (BusinessException e) {
            rollback(conn);
            throw e;
        } catch (SQLException e) {
            rollback(conn);
            throw new DatabaseException("创建订单失败", e);
        } finally {
            resetAndClose(conn);
        }
    }

    public List<ShopOrder> listOrders(String userId, boolean admin) {
        try {
            return shopOrderDAO.findByUserId(userId, admin);
        } catch (SQLException e) {
            throw new DatabaseException("查询订单列表失败", e);
        }
    }

    public Map<String, Object> getOrderDetail(String userId, long orderId, boolean admin) {
        try {
            ShopOrder order = shopOrderDAO.findById(orderId);
            checkOrderOwner(order, userId, admin);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("order", order);
            detail.put("items", orderItemDAO.findByOrderId(orderId));
            return detail;
        } catch (SQLException e) {
            throw new DatabaseException("查询订单详情失败", e);
        }
    }

    public void cancelOrder(String userId, long orderId) {
        Connection conn = null;
        try {
            conn = DBUtil.getConnection();
            conn.setAutoCommit(false);
            ShopOrder order = shopOrderDAO.findById(conn, orderId, true);
            checkOrderOwner(order, userId, false);
            if (order.getStatus() != OrderStatus.WAIT_PAY) {
                throw new BusinessException("只有待支付订单可以取消");
            }
            if (!shopOrderDAO.changeStatus(conn, orderId, OrderStatus.WAIT_PAY, OrderStatus.CANCELLED)) {
                throw new BusinessException("订单状态已变化，请刷新后重试");
            }
            for (OrderItem item : orderItemDAO.findByOrderId(conn, orderId)) {
                productDAO.increaseStock(conn, item.getProductId(), item.getQuantity());
            }
            conn.commit();
        } catch (BusinessException e) {
            rollback(conn);
            throw e;
        } catch (SQLException e) {
            rollback(conn);
            throw new DatabaseException("取消订单失败", e);
        } finally {
            resetAndClose(conn);
        }
    }

    public ShopOrder payOrder(String userId, long orderId, String paymentPassword, String requestId) {
        if (paymentPassword == null || paymentPassword.isBlank()) throw new BusinessException("请输入支付密码");
        if (requestId == null || requestId.isBlank()) throw new BusinessException("支付请求编号不能为空");
        if (bankPaymentService == null) throw new BusinessException("校园银行模块尚未启用，暂时无法支付");

        Connection conn = null;
        try {
            conn = DBUtil.getConnection();
            conn.setAutoCommit(false);
            ShopOrder order = shopOrderDAO.findById(conn, orderId, true);
            checkOrderOwner(order, userId, false);
            if (order.getStatus() != OrderStatus.WAIT_PAY) throw new BusinessException("订单不是待支付状态");
            if (LocalDateTime.parse(order.getExpiresAt().replace(' ', 'T')).isBefore(LocalDateTime.now())) {
                throw new BusinessException("订单已超过支付截止时间，请先取消订单");
            }

            BigDecimal actualAmount = orderItemDAO.findByOrderId(conn, orderId).stream()
                    .map(OrderItem::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (actualAmount.compareTo(order.getTotalAmount()) != 0) {
                throw new BusinessException("订单金额校验失败");
            }
            String transactionNo = bankPaymentService.payShopOrder(
                    conn, userId, orderId, actualAmount, paymentPassword, requestId);
            if (!shopOrderDAO.markPaid(conn, orderId, transactionNo)) {
                throw new BusinessException("订单状态已变化，支付结果未写入");
            }
            conn.commit();
            order.setStatus(OrderStatus.PAID);
            order.setPaymentTransactionNo(transactionNo);
            return order;
        } catch (BusinessException e) {
            rollback(conn);
            throw e;
        } catch (SQLException e) {
            rollback(conn);
            throw new DatabaseException("支付订单失败", e);
        } finally {
            resetAndClose(conn);
        }
    }

    public long applyRefund(String userId, long orderId, String reason) {
        if (reason == null || reason.trim().isEmpty()) throw new BusinessException("请填写退款原因");
        try {
            ShopOrder order = shopOrderDAO.findById(orderId);
            checkOrderOwner(order, userId, false);
            if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PROCESSING
                    && order.getStatus() != OrderStatus.COMPLETED) {
                throw new BusinessException("当前订单状态不能申请退款");
            }
            ShopRefund refund = new ShopRefund();
            refund.setRefundNo(newBusinessNo("RF"));
            refund.setOrderId(orderId);
            refund.setUserId(userId);
            refund.setRefundAmount(order.getTotalAmount());
            refund.setReason(reason.trim());
            refund.setStatus(RefundStatus.APPLIED);
            refund.setOriginalTransactionNo(order.getPaymentTransactionNo());
            return shopRefundDAO.insert(refund);
        } catch (SQLException e) {
            if ("23000".equals(e.getSQLState())) throw new BusinessException("该订单已经提交过退款申请");
            throw new DatabaseException("提交退款申请失败", e);
        }
    }

    public long createProduct(Product product, boolean admin) {
        requireAdmin(admin);
        validateProduct(product, false);
        try {
            product.setStatus(product.getStatus() == null ? ProductStatus.ON_SALE : product.getStatus());
            return productDAO.insert(product);
        } catch (SQLException e) {
            throw new DatabaseException("新增商品失败", e);
        }
    }

    public void updateProduct(Product product, boolean admin) {
        requireAdmin(admin);
        validateProduct(product, true);
        try {
            if (!productDAO.update(product)) throw new BusinessException("商品已被其他管理员修改，请刷新后重试");
        } catch (SQLException e) {
            throw new DatabaseException("修改商品失败", e);
        }
    }

    public void changeProductStatus(long productId, ProductStatus status, boolean admin) {
        requireAdmin(admin);
        if (status == null) throw new BusinessException("商品状态不正确");
        try {
            if (!productDAO.changeStatus(productId, status)) throw new BusinessException("商品不存在");
        } catch (SQLException e) {
            throw new DatabaseException("修改商品状态失败", e);
        }
    }

    public void updateProductStock(long productId, int stock, boolean admin) {
        requireAdmin(admin);
        if (stock < 0) throw new BusinessException("库存不能小于0");
        try {
            if (!productDAO.updateStock(productId, stock)) throw new BusinessException("商品不存在");
        } catch (SQLException e) {
            throw new DatabaseException("调整库存失败", e);
        }
    }

    private void validateProduct(Product product, boolean requireId) {
        if (product == null) throw new BusinessException("商品信息不能为空");
        if (requireId && product.getProductId() == null) throw new BusinessException("商品编号不能为空");
        if (product.getProductName() == null || product.getProductName().trim().isEmpty()) {
            throw new BusinessException("商品名称不能为空");
        }
        if (product.getCategory() == null || product.getCategory().trim().isEmpty()) {
            throw new BusinessException("商品分类不能为空");
        }
        if (product.getPrice() == null || product.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("商品价格必须大于0");
        }
        if (product.getStock() == null || product.getStock() < 0) throw new BusinessException("库存不能小于0");
        if (requireId && product.getVersion() == null) throw new BusinessException("商品版本不能为空");
    }

    private void checkOrderOwner(ShopOrder order, String userId, boolean admin) {
        if (order == null) throw new BusinessException("订单不存在");
        if (!admin && !userId.equals(order.getUserId())) throw new BusinessException("无权操作该订单");
    }

    private void requireAdmin(boolean admin) {
        if (!admin) throw new BusinessException("仅管理员可以执行此操作");
    }

    private void requirePositive(int value, String name) {
        if (value <= 0) throw new BusinessException(name + "必须大于0");
    }

    private String newBusinessNo(String prefix) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return prefix + time + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private void rollback(Connection conn) {
        if (conn != null) try { conn.rollback(); } catch (SQLException ignored) { }
    }

    private void resetAndClose(Connection conn) {
        if (conn != null) {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
            DBUtil.close(conn, null, null);
        }
    }
}

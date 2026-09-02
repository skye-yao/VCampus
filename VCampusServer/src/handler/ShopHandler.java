package handler;

import entity.Product;
import enums.ProductStatus;
import exception.BusinessException;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.ShopService;
import session.SessionManager;
import session.UserSession;

import java.math.BigDecimal;

/** 商店模块请求处理器。 */
public class ShopHandler {
    private final ShopService shopService = new ShopService();

    public Message handle(Message request) {
        Message response = new Message(MessageType.RESPONSE, "shop", request.getAction());
        response.setUID(request.getUID());

        UserSession session = SessionManager.getInstance().getSession(request.getToken());
        if (session == null) {
            response.setCode(MessageCode.UNAUTHORIZED);
            response.setMessage("登录会话已失效，请重新登录");
            return response;
        }
        if (request.getAction() == null || request.getAction().isBlank()) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("Action不能为空");
            return response;
        }

        String userId = session.getUsername();
        boolean admin = "管理员".equals(session.getRole());
        try {
            switch (request.getAction().toUpperCase()) {
                case "SHOP_PRODUCT_LIST" -> response.putData("products", shopService.listProducts(
                        string(request, "keyword"), string(request, "category"), admin));
                case "SHOP_PRODUCT_DETAIL" -> response.putData("product",
                        shopService.getProduct(number(request, "productId")));
                case "SHOP_CART_LIST" -> response.putData("cartItems", shopService.listCart(userId));
                case "SHOP_CART_ADD" -> shopService.addCartItem(userId,
                        number(request, "productId"), integer(request, "quantity"));
                case "SHOP_CART_UPDATE" -> shopService.updateCartItem(userId,
                        number(request, "cartItemId"), integer(request, "quantity"));
                case "SHOP_CART_REMOVE" -> shopService.removeCartItem(userId,
                        number(request, "cartItemId"));
                case "SHOP_ORDER_CREATE" -> response.putData("order", shopService.createOrder(userId));
                case "SHOP_ORDER_LIST" -> response.putData("orders", shopService.listOrders(userId, admin));
                case "SHOP_ORDER_DETAIL" -> response.setData(shopService.getOrderDetail(
                        userId, number(request, "orderId"), admin));
                case "SHOP_ORDER_CANCEL" -> shopService.cancelOrder(userId, number(request, "orderId"));
                case "SHOP_ORDER_PAY" -> response.putData("order", shopService.payOrder(
                        userId, number(request, "orderId"), string(request, "paymentPassword"),
                        string(request, "requestId")));
                case "SHOP_REFUND_APPLY" -> response.putData("refundId", shopService.applyRefund(
                        userId, number(request, "orderId"), string(request, "reason")));
                case "SHOP_PRODUCT_CREATE" -> response.putData("productId",
                        shopService.createProduct(product(request), admin));
                case "SHOP_PRODUCT_UPDATE" -> shopService.updateProduct(product(request), admin);
                case "SHOP_PRODUCT_STATUS_CHANGE" -> shopService.changeProductStatus(
                        number(request, "productId"), ProductStatus.fromCode(string(request, "status")), admin);
                case "SHOP_PRODUCT_STOCK_UPDATE" -> shopService.updateProductStock(
                        number(request, "productId"), integer(request, "stock"), admin);
                default -> throw new BusinessException("暂不支持的商店操作：" + request.getAction());
            }
            response.setCode(MessageCode.SUCCESS);
            response.setMessage("操作成功");
        } catch (BusinessException e) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage(e.getMessage());
        } catch (DatabaseException e) {
            response.setCode(MessageCode.ERROR);
            response.setMessage("服务端数据库异常：" + e.getMessage());
        } catch (Exception e) {
            response.setCode(MessageCode.ERROR);
            response.setMessage("服务端内部错误：" + e.getMessage());
        }
        return response;
    }

    private Product product(Message request) {
        Product product = new Product();
        product.setProductId(optionalNumber(request, "productId"));
        product.setProductName(string(request, "productName"));
        product.setDescription(string(request, "description"));
        product.setCategory(string(request, "category"));
        String price = string(request, "price");
        if (price != null && !price.isBlank()) product.setPrice(new BigDecimal(price));
        product.setStock(optionalInteger(request, "stock"));
        String status = string(request, "status");
        product.setStatus(status == null ? ProductStatus.ON_SALE : ProductStatus.fromCode(status));
        product.setVersion(optionalInteger(request, "version"));
        return product;
    }

    private String string(Message request, String key) {
        Object value = request.getData(key);
        return value == null ? null : String.valueOf(value);
    }

    private long number(Message request, String key) {
        Long value = optionalNumber(request, key);
        if (value == null) throw new BusinessException(key + "不能为空");
        return value;
    }

    private Long optionalNumber(Message request, String key) {
        Object value = request.getData(key);
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private int integer(Message request, String key) {
        Integer value = optionalInteger(request, key);
        if (value == null) throw new BusinessException(key + "不能为空");
        return value;
    }

    private Integer optionalInteger(Message request, String key) {
        Object value = request.getData(key);
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(String.valueOf(value));
    }
}

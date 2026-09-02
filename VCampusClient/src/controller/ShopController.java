package controller;

import app.ClientMain;
import com.google.gson.Gson;
import entity.CartItem;
import entity.OrderItem;
import entity.Product;
import entity.ShopOrder;
import enums.OrderStatus;
import enums.ProductStatus;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.ClientSession;
import util.AlertUtil;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** 商店JavaFX控制器。 */
public class ShopController {
    @FXML private TabPane shopTabs;
    @FXML private Tab adminTab;

    @FXML private TextField keywordField;
    @FXML private ComboBox<String> categoryCombo;
    @FXML private Spinner<Integer> productQuantitySpinner;
    @FXML private TableView<Product> productTable;
    @FXML private TableColumn<Product, Long> productIdColumn;
    @FXML private TableColumn<Product, String> productNameColumn;
    @FXML private TableColumn<Product, String> productCategoryColumn;
    @FXML private TableColumn<Product, BigDecimal> productPriceColumn;
    @FXML private TableColumn<Product, Integer> productStockColumn;
    @FXML private TableColumn<Product, ProductStatus> productStatusColumn;

    @FXML private TableView<CartItem> cartTable;
    @FXML private TableColumn<CartItem, String> cartNameColumn;
    @FXML private TableColumn<CartItem, BigDecimal> cartPriceColumn;
    @FXML private TableColumn<CartItem, Integer> cartQuantityColumn;
    @FXML private TableColumn<CartItem, BigDecimal> cartSubtotalColumn;
    @FXML private Spinner<Integer> cartQuantitySpinner;
    @FXML private Label cartTotalLabel;

    @FXML private TableView<ShopOrder> orderTable;
    @FXML private TableColumn<ShopOrder, String> orderNoColumn;
    @FXML private TableColumn<ShopOrder, BigDecimal> orderAmountColumn;
    @FXML private TableColumn<ShopOrder, OrderStatus> orderStatusColumn;
    @FXML private TableColumn<ShopOrder, String> orderCreatedColumn;

    @FXML private TableView<Product> adminProductTable;
    @FXML private TableColumn<Product, Long> adminIdColumn;
    @FXML private TableColumn<Product, String> adminNameColumn;
    @FXML private TableColumn<Product, String> adminCategoryColumn;
    @FXML private TableColumn<Product, BigDecimal> adminPriceColumn;
    @FXML private TableColumn<Product, Integer> adminStockColumn;
    @FXML private TableColumn<Product, ProductStatus> adminStatusColumn;
    @FXML private TextField adminNameField;
    @FXML private TextField adminCategoryField;
    @FXML private TextField adminPriceField;
    @FXML private TextField adminStockField;
    @FXML private TextArea adminDescriptionArea;

    private final Gson gson = new Gson();

    @FXML
    public void initialize() {
        configureTables();
        categoryCombo.setItems(FXCollections.observableArrayList(
                "全部分类", "文具", "教材资料", "校园纪念品", "生活用品"));
        categoryCombo.getSelectionModel().selectFirst();
        productQuantitySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 99, 1));
        cartQuantitySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 99, 1));

        boolean admin = isAdmin();
        if (!admin) shopTabs.getTabs().remove(adminTab);
        adminProductTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> fillAdminForm(selected));
        cartTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            if (selected != null && selected.getQuantity() != null) {
                cartQuantitySpinner.getValueFactory().setValue(selected.getQuantity());
            }
        });
        refreshProducts();
        refreshCart();
        refreshOrders();
    }

    private void configureTables() {
        productIdColumn.setCellValueFactory(new PropertyValueFactory<>("productId"));
        productNameColumn.setCellValueFactory(new PropertyValueFactory<>("productName"));
        productCategoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));
        productPriceColumn.setCellValueFactory(new PropertyValueFactory<>("price"));
        productStockColumn.setCellValueFactory(new PropertyValueFactory<>("stock"));
        productStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        productStatusColumn.setCellFactory(column -> statusCell());

        cartNameColumn.setCellValueFactory(new PropertyValueFactory<>("productName"));
        cartPriceColumn.setCellValueFactory(new PropertyValueFactory<>("unitPrice"));
        cartQuantityColumn.setCellValueFactory(new PropertyValueFactory<>("quantity"));
        cartSubtotalColumn.setCellValueFactory(new PropertyValueFactory<>("subtotal"));

        orderNoColumn.setCellValueFactory(new PropertyValueFactory<>("orderNo"));
        orderAmountColumn.setCellValueFactory(new PropertyValueFactory<>("totalAmount"));
        orderStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        orderStatusColumn.setCellFactory(column -> orderStatusCell());
        orderCreatedColumn.setCellValueFactory(new PropertyValueFactory<>("createdAt"));

        adminIdColumn.setCellValueFactory(new PropertyValueFactory<>("productId"));
        adminNameColumn.setCellValueFactory(new PropertyValueFactory<>("productName"));
        adminCategoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));
        adminPriceColumn.setCellValueFactory(new PropertyValueFactory<>("price"));
        adminStockColumn.setCellValueFactory(new PropertyValueFactory<>("stock"));
        adminStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        adminStatusColumn.setCellFactory(column -> statusCell());
    }

    private TableCell<Product, ProductStatus> statusCell() {
        return new TableCell<>() {
            @Override protected void updateItem(ProductStatus status, boolean empty) {
                super.updateItem(status, empty);
                setText(empty || status == null ? null : status.getDescription());
            }
        };
    }

    private TableCell<ShopOrder, OrderStatus> orderStatusCell() {
        return new TableCell<>() {
            @Override protected void updateItem(OrderStatus status, boolean empty) {
                super.updateItem(status, empty);
                setText(empty || status == null ? null : status.getDescription());
            }
        };
    }

    @FXML private void handleBack() { ClientMain.switchScene("/resources/fxml/MainView.fxml"); }

    @FXML private void handleSearch() { refreshProducts(); }

    @FXML
    private void handleProductDetail() {
        Product product = productTable.getSelectionModel().getSelectedItem();
        if (product == null) {
            AlertUtil.showWarning("商品详情", "请先选择一个商品");
            return;
        }
        String text = "商品：" + product.getProductName() + "\n分类：" + product.getCategory()
                + "\n价格：¥" + product.getPrice() + "\n库存：" + product.getStock()
                + "\n\n" + (product.getDescription() == null ? "" : product.getDescription());
        AlertUtil.showInfo("商品详情", text);
    }

    @FXML
    private void handleAddCart() {
        Product product = productTable.getSelectionModel().getSelectedItem();
        if (product == null) {
            AlertUtil.showWarning("加入购物车", "请先选择一个商品");
            return;
        }
        Message request = request(MessageType.SHOP_CART_ADD);
        request.putData("productId", product.getProductId());
        request.putData("quantity", productQuantitySpinner.getValue());
        send(request, response -> {
            AlertUtil.showInfo("购物车", "商品已加入购物车");
            refreshCart();
        });
    }

    @FXML
    private void handleUpdateCart() {
        CartItem item = cartTable.getSelectionModel().getSelectedItem();
        if (item == null) {
            AlertUtil.showWarning("购物车", "请先选择一条购物车记录");
            return;
        }
        Message request = request(MessageType.SHOP_CART_UPDATE);
        request.putData("cartItemId", item.getCartItemId());
        request.putData("quantity", cartQuantitySpinner.getValue());
        send(request, response -> refreshCart());
    }

    @FXML
    private void handleRemoveCart() {
        CartItem item = cartTable.getSelectionModel().getSelectedItem();
        if (item == null) {
            AlertUtil.showWarning("购物车", "请先选择一条购物车记录");
            return;
        }
        Message request = request(MessageType.SHOP_CART_REMOVE);
        request.putData("cartItemId", item.getCartItemId());
        send(request, response -> refreshCart());
    }

    @FXML
    private void handleCreateOrder() {
        if (cartTable.getItems().isEmpty()) {
            AlertUtil.showWarning("创建订单", "购物车为空");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION, "确认使用购物车中的全部商品创建订单？",
                ButtonType.OK, ButtonType.CANCEL);
        confirmation.setHeaderText("创建订单");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        send(request(MessageType.SHOP_ORDER_CREATE), response -> {
            AlertUtil.showInfo("创建订单", "订单创建成功，请在30分钟内支付");
            refreshProducts();
            refreshCart();
            refreshOrders();
            shopTabs.getSelectionModel().select(2);
        });
    }

    @FXML
    private void handleOrderDetail() {
        ShopOrder order = selectedOrder();
        if (order == null) return;
        Message request = request(MessageType.SHOP_ORDER_DETAIL);
        request.putData("orderId", order.getOrderId());
        send(request, response -> {
            OrderItem[] items = gson.fromJson(gson.toJson(response.getData("items")), OrderItem[].class);
            StringBuilder text = new StringBuilder("订单号：").append(order.getOrderNo())
                    .append("\n状态：").append(order.getStatus().getDescription())
                    .append("\n总额：¥").append(order.getTotalAmount()).append("\n\n商品明细：\n");
            for (OrderItem item : items) {
                text.append(item.getProductNameSnapshot()).append(" × ").append(item.getQuantity())
                        .append("    ¥").append(item.getSubtotal()).append('\n');
            }
            AlertUtil.showInfo("订单详情", text.toString());
        });
    }

    @FXML
    private void handleCancelOrder() {
        ShopOrder order = selectedOrder();
        if (order == null) return;
        Message request = request(MessageType.SHOP_ORDER_CANCEL);
        request.putData("orderId", order.getOrderId());
        send(request, response -> {
            AlertUtil.showInfo("取消订单", "订单已取消，库存已经返还");
            refreshProducts();
            refreshOrders();
        });
    }

    @FXML
    private void handlePayOrder() {
        ShopOrder order = selectedOrder();
        if (order == null) return;
        Optional<String> password = showPasswordDialog(order);
        if (password.isEmpty()) return;
        Message request = request(MessageType.SHOP_ORDER_PAY);
        request.putData("orderId", order.getOrderId());
        request.putData("paymentPassword", password.get());
        request.putData("requestId", UUID.randomUUID().toString());
        send(request, response -> {
            AlertUtil.showInfo("订单支付", "支付成功");
            refreshOrders();
        });
    }

    @FXML
    private void handleApplyRefund() {
        ShopOrder order = selectedOrder();
        if (order == null) return;
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("整单退款");
        dialog.setHeaderText("订单：" + order.getOrderNo());
        dialog.setContentText("退款原因：");
        Optional<String> reason = dialog.showAndWait();
        if (reason.isEmpty()) return;
        Message request = request(MessageType.SHOP_REFUND_APPLY);
        request.putData("orderId", order.getOrderId());
        request.putData("reason", reason.get());
        send(request, response -> AlertUtil.showInfo("退款申请", "退款申请已提交，等待管理员审核"));
    }

    @FXML private void handleAdminNew() { adminProductTable.getSelectionModel().clearSelection(); fillAdminForm(null); }

    @FXML
    private void handleAdminCreate() {
        Product input = readAdminForm(false);
        if (input == null) return;
        Message request = productRequest(MessageType.SHOP_PRODUCT_CREATE, input);
        send(request, response -> {
            AlertUtil.showInfo("商品管理", "商品新增成功");
            fillAdminForm(null);
            refreshProducts();
        });
    }

    @FXML
    private void handleAdminUpdate() {
        Product selected = adminProductTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            AlertUtil.showWarning("商品管理", "请先选择要修改的商品");
            return;
        }
        Product input = readAdminForm(true);
        if (input == null) return;
        input.setProductId(selected.getProductId());
        input.setVersion(selected.getVersion());
        Message request = productRequest(MessageType.SHOP_PRODUCT_UPDATE, input);
        send(request, response -> {
            AlertUtil.showInfo("商品管理", "商品信息已更新");
            refreshProducts();
        });
    }

    @FXML
    private void handleAdminToggleStatus() {
        Product selected = adminProductTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            AlertUtil.showWarning("商品管理", "请先选择商品");
            return;
        }
        ProductStatus target = selected.getStatus() == ProductStatus.ON_SALE
                ? ProductStatus.OFF_SALE : ProductStatus.ON_SALE;
        Message request = request(MessageType.SHOP_PRODUCT_STATUS_CHANGE);
        request.putData("productId", selected.getProductId());
        request.putData("status", target.getCode());
        send(request, response -> refreshProducts());
    }

    @FXML
    private void handleAdminStock() {
        Product selected = adminProductTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            AlertUtil.showWarning("库存管理", "请先选择商品");
            return;
        }
        try {
            int stock = Integer.parseInt(adminStockField.getText().trim());
            Message request = request(MessageType.SHOP_PRODUCT_STOCK_UPDATE);
            request.putData("productId", selected.getProductId());
            request.putData("stock", stock);
            send(request, response -> refreshProducts());
        } catch (NumberFormatException e) {
            AlertUtil.showWarning("库存管理", "库存必须是整数");
        }
    }

    private void refreshProducts() {
        Message request = request(MessageType.SHOP_PRODUCT_LIST);
        request.putData("keyword", keywordField == null ? "" : keywordField.getText());
        String category = categoryCombo == null ? "" : categoryCombo.getValue();
        request.putData("category", "全部分类".equals(category) ? "" : category);
        send(request, response -> {
            Product[] products = gson.fromJson(gson.toJson(response.getData("products")), Product[].class);
            productTable.setItems(FXCollections.observableArrayList(products));
            adminProductTable.setItems(FXCollections.observableArrayList(products));
        });
    }

    private void refreshCart() {
        send(request(MessageType.SHOP_CART_LIST), response -> {
            CartItem[] items = gson.fromJson(gson.toJson(response.getData("cartItems")), CartItem[].class);
            cartTable.setItems(FXCollections.observableArrayList(items));
            BigDecimal total = Arrays.stream(items).map(CartItem::getSubtotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            cartTotalLabel.setText("合计：¥" + total);
        });
    }

    private void refreshOrders() {
        send(request(MessageType.SHOP_ORDER_LIST), response -> {
            ShopOrder[] orders = gson.fromJson(gson.toJson(response.getData("orders")), ShopOrder[].class);
            orderTable.setItems(FXCollections.observableArrayList(orders));
        });
    }

    private Message request(MessageType action) {
        return new Message(MessageType.REQUEST, "shop", action.name());
    }

    private Message productRequest(MessageType action, Product product) {
        Message request = request(action);
        request.putData("productId", product.getProductId());
        request.putData("productName", product.getProductName());
        request.putData("description", product.getDescription());
        request.putData("category", product.getCategory());
        request.putData("price", product.getPrice() == null ? null : product.getPrice().toPlainString());
        request.putData("stock", product.getStock());
        request.putData("status", product.getStatus() == null ? ProductStatus.ON_SALE.getCode() : product.getStatus().getCode());
        request.putData("version", product.getVersion());
        return request;
    }

    private void send(Message request, Consumer<Message> onSuccess) {
        SocketClient.getInstance().sendAsync(request).thenAccept(response -> Platform.runLater(() -> {
            if (response.getCode() == MessageCode.SUCCESS) onSuccess.accept(response);
            else AlertUtil.showError("商店操作失败", response.getMessage());
        })).exceptionally(ex -> {
            Platform.runLater(() -> AlertUtil.showError("网络异常", "商店请求失败：" + ex.getMessage()));
            return null;
        });
    }

    private ShopOrder selectedOrder() {
        ShopOrder order = orderTable.getSelectionModel().getSelectedItem();
        if (order == null) AlertUtil.showWarning("订单", "请先选择一张订单");
        return order;
    }

    private Optional<String> showPasswordDialog(ShopOrder order) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("校园银行支付");
        dialog.setHeaderText("订单金额：¥" + order.getTotalAmount());
        ButtonType payButton = new ButtonType("确认支付", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(payButton, ButtonType.CANCEL);
        PasswordField password = new PasswordField();
        password.setPromptText("请输入6位支付密码");
        GridPane pane = new GridPane();
        pane.setHgap(10);
        pane.setVgap(10);
        pane.add(new Label("支付密码："), 0, 0);
        pane.add(password, 1, 0);
        dialog.getDialogPane().setContent(pane);
        dialog.setResultConverter(button -> button == payButton ? password.getText() : null);
        Optional<String> result = dialog.showAndWait();
        password.clear();
        return result;
    }

    private Product readAdminForm(boolean update) {
        try {
            Product product = new Product();
            product.setProductName(adminNameField.getText().trim());
            product.setCategory(adminCategoryField.getText().trim());
            product.setDescription(adminDescriptionArea.getText().trim());
            product.setPrice(new BigDecimal(adminPriceField.getText().trim()));
            product.setStock(Integer.parseInt(adminStockField.getText().trim()));
            product.setStatus(ProductStatus.ON_SALE);
            return product;
        } catch (Exception e) {
            AlertUtil.showWarning("商品信息", "请完整填写商品名称、分类、价格和整数库存");
            return null;
        }
    }

    private void fillAdminForm(Product product) {
        adminNameField.setText(product == null ? "" : product.getProductName());
        adminCategoryField.setText(product == null ? "" : product.getCategory());
        adminPriceField.setText(product == null || product.getPrice() == null ? "" : product.getPrice().toPlainString());
        adminStockField.setText(product == null || product.getStock() == null ? "" : String.valueOf(product.getStock()));
        adminDescriptionArea.setText(product == null ? "" : product.getDescription());
    }

    private boolean isAdmin() {
        return "管理员".equals(ClientSession.getInstance().getRole());
    }
}

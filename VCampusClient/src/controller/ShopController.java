package controller;

import app.ClientMain;
import com.google.gson.Gson;
import entity.CartItem;
import entity.OrderItem;
import entity.Product;
import entity.ShopOrder;
import entity.ShopRefund;
import enums.OrderStatus;
import enums.ProductStatus;
import enums.RefundStatus;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
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
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/** 商店JavaFX控制器。 */
public class ShopController {
    @FXML private TabPane shopTabs;
    @FXML private Tab adminTab;
    @FXML private Tab cartTab;
    @FXML private Tab orderTab;
    @FXML private Label shopSubtitleLabel;

    @FXML private TextField keywordField;
    @FXML private ComboBox<String> categoryCombo;
    @FXML private Spinner<Integer> productQuantitySpinner;
    @FXML private Label productQuantityLabel;
    @FXML private Button addCartButton;
    @FXML private TableView<Product> productTable;
    @FXML private TableColumn<Product, Long> productIdColumn;
    @FXML private TableColumn<Product, String> productNameColumn;
    @FXML private TableColumn<Product, String> productCategoryColumn;
    @FXML private TableColumn<Product, BigDecimal> productPriceColumn;
    @FXML private TableColumn<Product, Integer> productStockColumn;
    @FXML private TableColumn<Product, ProductStatus> productStatusColumn;

    @FXML private TableView<CartItem> cartTable;
    @FXML private TableColumn<CartItem, CartItem> cartSelectColumn;
    @FXML private TableColumn<CartItem, String> cartNameColumn;
    @FXML private TableColumn<CartItem, BigDecimal> cartPriceColumn;
    @FXML private TableColumn<CartItem, Integer> cartQuantityColumn;
    @FXML private TableColumn<CartItem, BigDecimal> cartSubtotalColumn;
    @FXML private Spinner<Integer> cartQuantitySpinner;
    @FXML private Label cartTotalLabel;
    @FXML private CheckBox selectAllCartCheckBox;

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
    @FXML private TableColumn<Product, ProductStatus> adminStatusColumn;
    @FXML private TextField adminNameField;
    @FXML private ComboBox<String> adminCategoryField;
    @FXML private TextField adminPriceField;
    @FXML private TextArea adminDescriptionArea;
    @FXML private TableView<Product> inventoryProductTable;
    @FXML private TableColumn<Product, Long> inventoryIdColumn;
    @FXML private TableColumn<Product, String> inventoryNameColumn;
    @FXML private TableColumn<Product, String> inventoryCategoryColumn;
    @FXML private TableColumn<Product, Integer> inventoryStockColumn;
    @FXML private TableColumn<Product, ProductStatus> inventoryStatusColumn;
    @FXML private TextField inventoryStockField;
    @FXML private TableView<ShopRefund> adminRefundTable;
    @FXML private TableColumn<ShopRefund, String> adminRefundNoColumn;
    @FXML private TableColumn<ShopRefund, Long> adminRefundOrderColumn;
    @FXML private TableColumn<ShopRefund, String> adminRefundUserColumn;
    @FXML private TableColumn<ShopRefund, BigDecimal> adminRefundAmountColumn;
    @FXML private TableColumn<ShopRefund, RefundStatus> adminRefundStatusColumn;
    @FXML private TableView<ShopOrder> salesOrderTable;
    @FXML private TableColumn<ShopOrder, String> salesOrderNoColumn;
    @FXML private TableColumn<ShopOrder, String> salesUserColumn;
    @FXML private TableColumn<ShopOrder, BigDecimal> salesAmountColumn;
    @FXML private TableColumn<ShopOrder, OrderStatus> salesStatusColumn;
    @FXML private TableColumn<ShopOrder, String> salesTimeColumn;
    @FXML private Label salesTotalOrdersLabel;
    @FXML private Label salesPaidOrdersLabel;
    @FXML private Label salesAmountLabel;
    @FXML private Label salesRefundedLabel;

    private final Gson gson = new Gson();
    private final Set<Long> selectedCartItemIds = new LinkedHashSet<>();

    @FXML
    public void initialize() {
        configureTables();
        categoryCombo.setItems(FXCollections.observableArrayList(
                "全部分类", "文具", "教材资料", "校园纪念品", "生活用品"));
        categoryCombo.getSelectionModel().selectFirst();
        adminCategoryField.setItems(FXCollections.observableArrayList(
                "文具", "教材资料", "校园纪念品", "生活用品"));
        productQuantitySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 99, 1));
        cartQuantitySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 99, 1));

        boolean admin = isAdmin();
        if (admin) {
            shopTabs.getTabs().removeAll(cartTab, orderTab);
            shopSubtitleLabel.setText("商品维护 · 库存管理 · 经营记录");
            productQuantityLabel.setVisible(false); productQuantityLabel.setManaged(false);
            productQuantitySpinner.setVisible(false); productQuantitySpinner.setManaged(false);
            addCartButton.setVisible(false); addCartButton.setManaged(false);
        } else {
            shopTabs.getTabs().remove(adminTab);
        }
        adminProductTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> fillAdminForm(selected));
        inventoryProductTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> inventoryStockField.setText(
                        selected == null || selected.getStock() == null ? "" : String.valueOf(selected.getStock())));
        cartTable.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selected) -> {
            if (selected != null && selected.getQuantity() != null) {
                cartQuantitySpinner.getValueFactory().setValue(selected.getQuantity());
            }
        });
        refreshProducts();
        if (admin) refreshAdminDashboard();
        else { refreshCart(); refreshOrders(); }
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
        cartSelectColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue()));
        cartSelectColumn.setCellFactory(column -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            {
                checkBox.setOnAction(event -> {
                    CartItem row = getItem();
                    if (row == null || row.getCartItemId() == null) return;
                    if (checkBox.isSelected()) selectedCartItemIds.add(row.getCartItemId());
                    else selectedCartItemIds.remove(row.getCartItemId());
                    updateSelectedCartSummary();
                });
            }
            @Override protected void updateItem(CartItem row, boolean empty) {
                super.updateItem(row, empty);
                if (empty || row == null) { setGraphic(null); return; }
                checkBox.setSelected(selectedCartItemIds.contains(row.getCartItemId()));
                setGraphic(checkBox); setContentDisplay(ContentDisplay.GRAPHIC_ONLY); setAlignment(javafx.geometry.Pos.CENTER);
            }
        });

        orderNoColumn.setCellValueFactory(new PropertyValueFactory<>("orderNo"));
        orderAmountColumn.setCellValueFactory(new PropertyValueFactory<>("totalAmount"));
        orderStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        orderStatusColumn.setCellFactory(column -> orderStatusCell());
        orderCreatedColumn.setCellValueFactory(new PropertyValueFactory<>("createdAt"));

        adminIdColumn.setCellValueFactory(new PropertyValueFactory<>("productId"));
        adminNameColumn.setCellValueFactory(new PropertyValueFactory<>("productName"));
        adminCategoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));
        adminPriceColumn.setCellValueFactory(new PropertyValueFactory<>("price"));
        adminStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        adminStatusColumn.setCellFactory(column -> statusCell());

        inventoryIdColumn.setCellValueFactory(new PropertyValueFactory<>("productId"));
        inventoryNameColumn.setCellValueFactory(new PropertyValueFactory<>("productName"));
        inventoryCategoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));
        inventoryStockColumn.setCellValueFactory(new PropertyValueFactory<>("stock"));
        inventoryStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        inventoryStatusColumn.setCellFactory(column -> statusCell());

        adminRefundNoColumn.setCellValueFactory(new PropertyValueFactory<>("refundNo"));
        adminRefundOrderColumn.setCellValueFactory(new PropertyValueFactory<>("orderId"));
        adminRefundUserColumn.setCellValueFactory(new PropertyValueFactory<>("userId"));
        adminRefundAmountColumn.setCellValueFactory(new PropertyValueFactory<>("refundAmount"));
        adminRefundStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        adminRefundStatusColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(RefundStatus status, boolean empty) {
                super.updateItem(status, empty);
                setText(empty || status == null ? null : status.getDescription());
            }
        });
        salesOrderNoColumn.setCellValueFactory(new PropertyValueFactory<>("orderNo"));
        salesUserColumn.setCellValueFactory(new PropertyValueFactory<>("userId"));
        salesAmountColumn.setCellValueFactory(new PropertyValueFactory<>("totalAmount"));
        salesStatusColumn.setCellValueFactory(new PropertyValueFactory<>("status"));
        salesStatusColumn.setCellFactory(column -> orderStatusCell());
        salesTimeColumn.setCellValueFactory(new PropertyValueFactory<>("createdAt"));
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
        showProductDetail(product);
    }

    private void showProductDetail(Product product) {
        String text = "商品：" + product.getProductName() + "\n分类：" + product.getCategory()
                + "\n价格：¥" + product.getPrice() + "\n库存：" + product.getStock()
                + "\n\n" + (product.getDescription() == null ? "" : product.getDescription());
        AlertUtil.showInfo("商品详情", text);
    }

    @FXML
    private void handleCartProductDetail() {
        CartItem item = cartTable.getSelectionModel().getSelectedItem();
        if (item == null) { AlertUtil.showWarning("商品详情", "请先选择一条购物车记录"); return; }
        Message request = request(MessageType.SHOP_PRODUCT_DETAIL);
        request.putData("productId", item.getProductId());
        send(request, response -> {
            Product product = gson.fromJson(gson.toJson((Object) response.getData("product")), Product.class);
            showProductDetail(product);
        });
    }

    @FXML
    private void handleSelectAllCart() {
        selectedCartItemIds.clear();
        if (selectAllCartCheckBox.isSelected()) {
            for (CartItem item : cartTable.getItems()) selectedCartItemIds.add(item.getCartItemId());
        }
        cartTable.refresh(); updateSelectedCartSummary();
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
        send(request, response -> { selectedCartItemIds.remove(item.getCartItemId()); refreshCart(); });
    }

    @FXML
    private void handleCreateOrder() {
        if (cartTable.getItems().isEmpty()) {
            AlertUtil.showWarning("创建订单", "购物车为空");
            return;
        }
        if (selectedCartItemIds.isEmpty()) {
            AlertUtil.showWarning("创建订单", "请至少勾选一件购物车商品");
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "确认使用已勾选的 " + selectedCartItemIds.size() + " 件商品创建订单？",
                ButtonType.OK, ButtonType.CANCEL);
        confirmation.setHeaderText("创建订单");
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        Message request = request(MessageType.SHOP_ORDER_CREATE);
        request.putData("cartItemIds", new ArrayList<>(selectedCartItemIds));
        send(request, response -> {
            AlertUtil.showInfo("创建订单", "订单创建成功，请在30分钟内支付");
            selectedCartItemIds.clear();
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
        showOrderDetail(order);
    }

    private void showOrderDetail(ShopOrder order) {
        Message request = request(MessageType.SHOP_ORDER_DETAIL);
        request.putData("orderId", order.getOrderId());
        send(request, response -> {
            OrderItem[] items = gson.fromJson(gson.toJson((Object) response.getData("items")), OrderItem[].class);
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
        send(request, response -> {
            AlertUtil.showInfo("退款申请", "退款申请已提交，订单已进入退款中");
            refreshOrders();
        });
    }

    @FXML
    private void handleOpenCreateProduct() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("新增商品");
        dialog.setHeaderText("填写新商品信息（新增后默认上架）");
        ButtonType createButton = new ButtonType("确认新增", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButton, ButtonType.CANCEL);
        java.net.URL stylesheet = getClass().getResource("/resources/css/style.css");
        if (stylesheet != null) dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());

        TextField nameField = new TextField();
        nameField.setPromptText("商品名称不能与现有商品重复");
        ComboBox<String> categoryField = new ComboBox<>(FXCollections.observableArrayList(
                "文具", "教材资料", "校园纪念品", "生活用品"));
        categoryField.setPromptText("请选择商品分类");
        categoryField.setMaxWidth(Double.MAX_VALUE);
        TextField priceField = new TextField();
        priceField.setPromptText("例如：15.50");
        TextField stockField = new TextField();
        stockField.setPromptText("请输入非负整数");
        TextArea descriptionArea = new TextArea();
        descriptionArea.setPromptText("请输入商品说明");
        descriptionArea.setPrefRowCount(4);
        descriptionArea.setWrapText(true);
        nameField.getStyleClass().add("form-control");
        categoryField.getStyleClass().add("form-control");
        priceField.getStyleClass().add("form-control");
        stockField.getStyleClass().add("form-control");

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.setPadding(new Insets(8));
        javafx.scene.layout.ColumnConstraints labelColumn = new javafx.scene.layout.ColumnConstraints();
        labelColumn.setMinWidth(88);
        labelColumn.setPrefWidth(88);
        javafx.scene.layout.ColumnConstraints valueColumn = new javafx.scene.layout.ColumnConstraints();
        valueColumn.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        valueColumn.setFillWidth(true);
        form.getColumnConstraints().addAll(labelColumn, valueColumn);
        form.addRow(0, new Label("商品名称"), nameField);
        form.addRow(1, new Label("商品分类"), categoryField);
        form.addRow(2, new Label("商品价格"), priceField);
        form.addRow(3, new Label("初始库存"), stockField);
        form.addRow(4, new Label("商品说明"), descriptionArea);
        GridPane.setHgrow(nameField, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(categoryField, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(priceField, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(stockField, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(descriptionArea, javafx.scene.layout.Priority.ALWAYS);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setMinWidth(580);
        dialog.getDialogPane().setPrefWidth(620);

        Product[] validatedProduct = new Product[1];
        dialog.getDialogPane().lookupButton(createButton).addEventFilter(
                javafx.event.ActionEvent.ACTION, event -> {
                    validatedProduct[0] = readCreateProductForm(
                            nameField, categoryField, priceField, stockField, descriptionArea);
                    if (validatedProduct[0] == null) event.consume();
                });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != createButton) return;

        Product input = validatedProduct[0];
        Message request = productRequest(MessageType.SHOP_PRODUCT_CREATE, input);
        send(request, response -> {
            AlertUtil.showInfo("商品管理", "商品新增成功，已默认上架");
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
        Product input = readAdminForm();
        if (input == null) return;
        input.setProductId(selected.getProductId());
        input.setVersion(selected.getVersion());
        // 基本信息修改不改变库存；携带当前值仅用于服务端完整性校验。
        input.setStock(selected.getStock());
        input.setStatus(selected.getStatus());
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
        request.putData("version", selected.getVersion());
        send(request, response -> refreshProducts());
    }

    @FXML
    private void handleAdminStock() {
        Product selected = inventoryProductTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            AlertUtil.showWarning("库存管理", "请先选择商品");
            return;
        }
        try {
            int stock = Integer.parseInt(inventoryStockField.getText().trim());
            if (stock < 0) throw new NumberFormatException("negative stock");
            Message request = request(MessageType.SHOP_PRODUCT_STOCK_UPDATE);
            request.putData("productId", selected.getProductId());
            request.putData("stock", stock);
            request.putData("version", selected.getVersion());
            send(request, response -> {
                AlertUtil.showInfo("库存管理", "库存调整成功");
                refreshProducts();
            });
        } catch (NumberFormatException e) {
            AlertUtil.showWarning("库存管理", "库存必须是非负整数");
        }
    }

    private void refreshProducts() {
        Message request = request(MessageType.SHOP_PRODUCT_LIST);
        request.putData("keyword", keywordField == null ? "" : keywordField.getText());
        String category = categoryCombo == null ? "" : categoryCombo.getValue();
        request.putData("category", "全部分类".equals(category) ? "" : category);
        send(request, response -> {
            Product[] products = gson.fromJson(gson.toJson((Object) response.getData("products")), Product[].class);
            productTable.setItems(FXCollections.observableArrayList(products));
            adminProductTable.setItems(FXCollections.observableArrayList(products));
            inventoryProductTable.setItems(FXCollections.observableArrayList(products));
        });
    }

    private void refreshCart() {
        send(request(MessageType.SHOP_CART_LIST), response -> {
            CartItem[] items = gson.fromJson(gson.toJson((Object) response.getData("cartItems")), CartItem[].class);
            cartTable.setItems(FXCollections.observableArrayList(items));
            Set<Long> currentIds = new java.util.HashSet<>();
            for (CartItem item : items) currentIds.add(item.getCartItemId());
            selectedCartItemIds.retainAll(currentIds);
            updateSelectedCartSummary();
        });
    }

    private void updateSelectedCartSummary() {
        BigDecimal total = cartTable.getItems().stream()
                .filter(item -> selectedCartItemIds.contains(item.getCartItemId()))
                .map(CartItem::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        cartTotalLabel.setText("已选 " + selectedCartItemIds.size() + " 项，合计：¥" + total);
        boolean allSelected = !cartTable.getItems().isEmpty()
                && selectedCartItemIds.size() == cartTable.getItems().size();
        selectAllCartCheckBox.setSelected(allSelected);
    }

    private void refreshOrders() {
        send(request(MessageType.SHOP_ORDER_LIST), response -> {
            ShopOrder[] orders = gson.fromJson(gson.toJson((Object) response.getData("orders")), ShopOrder[].class);
            orderTable.setItems(FXCollections.observableArrayList(orders));
        });
    }

    @FXML
    private void handleSalesOrderDetail() {
        ShopOrder order = salesOrderTable.getSelectionModel().getSelectedItem();
        if (order == null) { AlertUtil.showWarning("销售记录", "请先选择一条订单"); return; }
        showOrderDetail(order);
    }

    @FXML private void handleApproveRefund() { reviewRefund(true); }
    @FXML private void handleRejectRefund() { reviewRefund(false); }

    private void reviewRefund(boolean approved) {
        ShopRefund refund = adminRefundTable.getSelectionModel().getSelectedItem();
        if (refund == null) { AlertUtil.showWarning("退款审核", "请先选择退款申请"); return; }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("退款审核"); dialog.setHeaderText((approved ? "同意退款" : "拒绝退款") + "：" + refund.getRefundNo());
        dialog.setContentText("审核意见："); Optional<String> comment = dialog.showAndWait();
        if (comment.isEmpty()) return;
        Message request = request(MessageType.SHOP_REFUND_REVIEW);
        request.putData("refundId", refund.getRefundId()); request.putData("approved", approved);
        request.putData("comment", comment.get()); request.putData("requestId", UUID.randomUUID().toString());
        send(request, response -> {
            AlertUtil.showInfo("退款审核", approved ? "退款已原路退回校园银行账户" : "退款申请已拒绝");
            refreshAdminDashboard(); refreshProducts();
        });
    }

    @FXML private void handleRefreshAdminDashboard() { refreshAdminDashboard(); }

    private void refreshAdminDashboard() {
        send(request(MessageType.SHOP_SALES_SUMMARY), response -> {
            ShopOrder[] orders = gson.fromJson(gson.toJson((Object) response.getData("orders")), ShopOrder[].class);
            ShopRefund[] refunds = gson.fromJson(gson.toJson((Object) response.getData("refunds")), ShopRefund[].class);
            salesOrderTable.setItems(FXCollections.observableArrayList(orders));
            adminRefundTable.setItems(FXCollections.observableArrayList(refunds));
            Object summaryObject = response.getData("summary");
            @SuppressWarnings("unchecked") java.util.Map<String,Object> summary =
                    gson.fromJson(gson.toJson((Object) summaryObject), java.util.Map.class);
            salesTotalOrdersLabel.setText("全部订单：" + whole(summary.get("totalOrders")));
            salesPaidOrdersLabel.setText("有效销售：" + whole(summary.get("paidOrders")));
            salesAmountLabel.setText("销售金额：¥" + money(summary.get("salesAmount")));
            salesRefundedLabel.setText("已退款：" + whole(summary.get("refundedOrders")));
        });
    }

    private String whole(Object value) {
        if (value instanceof Number number) return String.valueOf(number.longValue());
        return String.valueOf(value);
    }

    private String money(Object value) {
        try { return new BigDecimal(String.valueOf(value)).setScale(2).toPlainString(); }
        catch (Exception e) { return "0.00"; }
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

    private Product readAdminForm() {
        try {
            Product product = new Product();
            product.setProductName(adminNameField.getText().trim());
            if (adminCategoryField.getValue() == null) throw new IllegalArgumentException("未选择分类");
            product.setCategory(adminCategoryField.getValue());
            product.setDescription(adminDescriptionArea.getText().trim());
            product.setPrice(new BigDecimal(adminPriceField.getText().trim()));
            return product;
        } catch (Exception e) {
            AlertUtil.showWarning("商品信息", "请完整填写商品名称、分类和正确的商品价格");
            return null;
        }
    }

    private Product readCreateProductForm(TextField nameField, ComboBox<String> categoryField,
                                          TextField priceField, TextField stockField,
                                          TextArea descriptionArea) {
        try {
            String name = nameField.getText().trim();
            if (name.isEmpty() || categoryField.getValue() == null) {
                throw new IllegalArgumentException("missing field");
            }
            BigDecimal price = new BigDecimal(priceField.getText().trim());
            int stock = Integer.parseInt(stockField.getText().trim());
            if (price.compareTo(BigDecimal.ZERO) <= 0 || stock < 0) {
                throw new IllegalArgumentException("invalid number");
            }
            Product product = new Product();
            product.setProductName(name);
            product.setCategory(categoryField.getValue());
            product.setPrice(price);
            product.setStock(stock);
            product.setDescription(descriptionArea.getText().trim());
            product.setStatus(ProductStatus.ON_SALE);
            return product;
        } catch (Exception e) {
            AlertUtil.showWarning("新增商品", "请完整填写信息；价格必须大于0，初始库存必须是非负整数");
            return null;
        }
    }

    private void fillAdminForm(Product product) {
        adminNameField.setText(product == null ? "" : product.getProductName());
        if (product == null) adminCategoryField.getSelectionModel().clearSelection();
        else adminCategoryField.setValue(product.getCategory());
        adminPriceField.setText(product == null || product.getPrice() == null ? "" : product.getPrice().toPlainString());
        adminDescriptionArea.setText(product == null ? "" : product.getDescription());
    }

    private boolean isAdmin() {
        return "管理员".equals(ClientSession.getInstance().getRole());
    }
}

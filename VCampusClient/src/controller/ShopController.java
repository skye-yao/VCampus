package controller;

import app.ClientMain;
import com.google.gson.Gson;
import entity.CartItem;
import entity.OrderItem;
import entity.Product;
import entity.ShopOrder;
import entity.ShopRefund;
import entity.ShopOperationLog;
import enums.OrderStatus;
import enums.ProductStatus;
import enums.RefundStatus;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.ClientSession;
import util.AlertUtil;
import util.ShopImageClientCodec;
import util.TableResize;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
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

    @FXML private ToggleButton productViewToggle;
    @FXML private ScrollPane productGalleryScroll;
    @FXML private TilePane productGallery;

    @FXML private TableView<CartItem> cartTable;
    @FXML private TableColumn<CartItem, CartItem> cartSelectColumn;
    @FXML private TableColumn<CartItem, String> cartNameColumn;
    @FXML private TableColumn<CartItem, BigDecimal> cartPriceColumn;
    @FXML private TableColumn<CartItem, Integer> cartQuantityColumn;
    @FXML private TableColumn<CartItem, BigDecimal> cartSubtotalColumn;
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
    @FXML private ImageView adminProductImageView;
    @FXML private Label adminImagePlaceholderLabel;
    @FXML private Button replaceProductImageButton;
    @FXML private Button createProductButton;
    @FXML private Label shopUploadStatusLabel;
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
    @FXML private TableView<ShopOperationLog> operationLogTable;
    @FXML private Tab operationLogTab;
    @FXML private TableColumn<ShopOperationLog, String> logOperatorColumn;
    @FXML private TableColumn<ShopOperationLog, String> logActionColumn;
    @FXML private TableColumn<ShopOperationLog, String> logTargetColumn;
    @FXML private TableColumn<ShopOperationLog, Long> logTargetIdColumn;
    @FXML private TableColumn<ShopOperationLog, String> logReasonColumn;
    @FXML private TableColumn<ShopOperationLog, String> logTimeColumn;

    private final Gson gson = new Gson();
    private final Set<Long> selectedCartItemIds = new LinkedHashSet<>();
    private long adminPreviewGeneration;
    private boolean imageUploadInProgress;
    /** “重置”会同时改动关键字和分类，期间抑制分类监听的重复查询。 */
    private boolean suppressProductFilterRefresh;
    /** 商品中心缩略图缓存：商品编号 -> 已解码的小图，避免每次切换视图都重新请求。 */
    private final Map<Long, Image> productThumbnails = new HashMap<>();
    /** 已确认没有图片的商品，用于显示“暂无图片”占位。 */
    private final Set<Long> productThumbnailsMissing = new LinkedHashSet<>();
    /** 当前查询结果，列表视图与缩略图视图共用同一批商品。 */
    private List<Product> currentProducts = List.of();
    private final Map<Long, ImageView> galleryImageViews = new HashMap<>();
    private final Map<Long, Label> galleryPlaceholders = new LinkedHashMap<>();
    /** 单次缩略图请求的商品数量，避免商品很多时超过服务端的单次处理上限。 */
    private static final int THUMBNAIL_BATCH_SIZE = 60;

    @FXML
    public void initialize() {
        configureTables();
        categoryCombo.setItems(FXCollections.observableArrayList(
                "全部分类", "文具", "教材资料", "校园纪念品", "生活用品"));
        categoryCombo.getSelectionModel().selectFirst();
        // 分类是“选中即筛选”的控件：切换分类立即刷新，不必再点查询。
        categoryCombo.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> {
                    if (selected == null || suppressProductFilterRefresh) return;
                    refreshProducts();
                });
        adminCategoryField.setItems(FXCollections.observableArrayList(
                "文具", "教材资料", "校园纪念品", "生活用品"));
        productQuantitySpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 99, 1));

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
        replaceProductImageButton.setDisable(true);
        adminProductTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> {
                    fillAdminForm(selected);
                    loadAdminProductPreview(selected);
                });
        inventoryProductTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, selected) -> inventoryStockField.setText(
                        selected == null || selected.getStock() == null ? "" : String.valueOf(selected.getStock())));
        applyProductView();
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
        cartQuantityColumn.setCellFactory(column -> cartQuantityCell());
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
        logOperatorColumn.setCellValueFactory(new PropertyValueFactory<>("operatorId"));
        logActionColumn.setCellValueFactory(new PropertyValueFactory<>("action"));
        logActionColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String action, boolean empty) {
                super.updateItem(action, empty);
                setText(empty || action == null ? null : switch (action) {
                    case "PRODUCT_CREATE" -> "新增商品";
                    case "PRODUCT_UPDATE" -> "修改商品";
                    case "PRODUCT_STATUS_CHANGE" -> "上架/下架";
                    case "PRODUCT_STOCK_UPDATE" -> "调整库存";
                    case "PRODUCT_IMAGE_UPDATE" -> "更换商品图片";
                    case "REFUND_APPROVE" -> "同意退款";
                    case "REFUND_REJECT" -> "拒绝退款";
                    default -> action;
                });
            }
        });
        logTargetColumn.setCellValueFactory(new PropertyValueFactory<>("targetType"));
        logTargetColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String target, boolean empty) {
                super.updateItem(target, empty);
                setText(empty || target == null ? null : switch (target) {
                    case "PRODUCT" -> "商品";
                    case "REFUND" -> "退款单";
                    default -> target;
                });
            }
        });
        logTargetIdColumn.setCellValueFactory(new PropertyValueFactory<>("targetId"));
        logReasonColumn.setCellValueFactory(new PropertyValueFactory<>("reason"));
        logTimeColumn.setCellValueFactory(new PropertyValueFactory<>("createdAt"));
        // 所有表格的列宽随窗口大小自适应。
        TableResize.fillWidth(productTable, cartTable, orderTable, adminProductTable,
                inventoryProductTable, adminRefundTable, salesOrderTable, operationLogTable);
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

    /** 重置商品中心的筛选条件：清空关键字并回到“全部分类”。 */
    @FXML
    private void handleResetProductFilters() {
        suppressProductFilterRefresh = true;
        try {
            keywordField.clear();
            categoryCombo.getSelectionModel().selectFirst();
        } finally {
            suppressProductFilterRefresh = false;
        }
        refreshProducts();
    }

    @FXML
    private void handleProductDetail() {
        Product product = productTable.getSelectionModel().getSelectedItem();
        if (product == null) {
            AlertUtil.showWarning("商品详情", "请先选择一个商品");
            return;
        }
        requestProductDetail(product.getProductId(), this::showProductDetail);
    }

    private void requestProductDetail(long productId, BiConsumer<Product, String> onLoaded) {
        Message request = request(MessageType.SHOP_PRODUCT_DETAIL);
        request.putData("productId", productId);
        send(request, response -> {
            Object rawProduct = response.getData("product");
            if (rawProduct == null) {
                AlertUtil.showError("商品详情", "服务端没有返回商品信息");
                return;
            }
            Product product = gson.fromJson(gson.toJson(rawProduct), Product.class);
            Object rawImage = response.getData("imageBase64");
            onLoaded.accept(product, rawImage instanceof String value ? value : null);
        });
    }

    private Image decodeProductImage(String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) return null;
        try {
            Image image = new Image(new ByteArrayInputStream(Base64.getDecoder().decode(imageBase64)));
            return image.isError() ? null : image;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void showProductDetail(Product product, String imageBase64) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("商品详情");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        java.net.URL stylesheet = getClass().getResource("/resources/css/style.css");
        if (stylesheet != null) dialog.getDialogPane().getStylesheets().add(stylesheet.toExternalForm());

        StackPane picture = new StackPane();
        picture.getStyleClass().add("shop-image-frame");
        picture.setMinSize(280, 250);
        Label placeholder = new Label(imageBase64 == null ? "暂无图片" : "图片加载中…");
        placeholder.getStyleClass().add("shop-image-placeholder");
        ImageView imageView = new ImageView();
        imageView.setFitWidth(260);
        imageView.setFitHeight(230);
        imageView.setPreserveRatio(true);
        picture.getChildren().addAll(placeholder, imageView);
        if (imageBase64 != null) CompletableFuture.supplyAsync(() -> decodeProductImage(imageBase64))
                .thenAccept(image -> Platform.runLater(() -> {
                    imageView.setImage(image);
                    placeholder.setText("暂无图片");
                    placeholder.setVisible(image == null);
                }));

        Label name = new Label(product.getProductName());
        name.getStyleClass().add("shop-detail-name");
        name.setWrapText(true);
        Label price = new Label("¥ " + product.getPrice());
        price.getStyleClass().add("shop-detail-price");
        Label description = new Label(product.getDescription() == null ? "" : product.getDescription());
        description.setWrapText(true);
        VBox information = new VBox(13, name, new Label("分类：" + product.getCategory()), price,
                new Label("库存：" + product.getStock()), description);
        information.setPrefWidth(320);
        HBox content = new HBox(22, picture, information);
        content.setPadding(new Insets(18));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(700);
        dialog.showAndWait();
    }

    @FXML
    private void handleCartProductDetail() {
        CartItem item = cartTable.getSelectionModel().getSelectedItem();
        if (item == null) { AlertUtil.showWarning("商品详情", "请先选择一条购物车记录"); return; }
        requestProductDetail(item.getProductId(), this::showProductDetail);
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

    /** 数量列的行内“−/+”控件：直接改数量，不再需要底部的输入框和按钮。 */
    private TableCell<CartItem, Integer> cartQuantityCell() {
        return new TableCell<>() {
            private final Button minus = new Button("−");
            private final Button plus = new Button("+");
            private final Label value = new Label();
            private final HBox box = new HBox(6, minus, value, plus);

            {
                box.setAlignment(Pos.CENTER_LEFT);
                value.setAlignment(Pos.CENTER);
                value.setMinWidth(28);
                minus.getStyleClass().add("cart-step-button");
                plus.getStyleClass().add("cart-step-button");
                value.getStyleClass().add("cart-step-value");
                minus.setOnAction(event -> changeCartQuantity(rowItem(), -1));
                plus.setOnAction(event -> changeCartQuantity(rowItem(), 1));
            }

            private CartItem rowItem() {
                return getTableRow() == null ? null : getTableRow().getItem();
            }

            @Override
            protected void updateItem(Integer quantity, boolean empty) {
                super.updateItem(quantity, empty);
                CartItem row = rowItem();
                if (empty || quantity == null || row == null) {
                    setGraphic(null);
                    return;
                }
                value.setText(String.valueOf(quantity));
                boolean onSale = row.getProductStatus() == null
                        || row.getProductStatus() == ProductStatus.ON_SALE;
                Integer stock = row.getAvailableStock();
                minus.setDisable(!onSale || quantity <= 1);
                plus.setDisable(!onSale || (stock != null && quantity >= stock));
                setGraphic(box);
            }
        };
    }

    /**
     * 行内修改购物车数量。
     *
     * <p>先在本地更新数量与小计，界面立即响应；再把新数量发给服务端，
     * 无论成功还是失败都重新拉取一次购物车，以服务端数据为准。
     */
    private void changeCartQuantity(CartItem item, int delta) {
        if (item == null || item.getCartItemId() == null) return;
        if (item.getProductStatus() != null && item.getProductStatus() != ProductStatus.ON_SALE) {
            AlertUtil.showWarning("购物车", "商品已下架，无法修改数量");
            return;
        }
        int current = item.getQuantity() == null ? 1 : item.getQuantity();
        int target = current + delta;
        if (target < 1) return;
        if (item.getAvailableStock() != null && target > item.getAvailableStock()) {
            AlertUtil.showWarning("购物车", "库存不足，最多可购买 " + item.getAvailableStock() + " 件");
            return;
        }
        item.setQuantity(target);
        if (item.getUnitPrice() != null) {
            item.setSubtotal(item.getUnitPrice().multiply(BigDecimal.valueOf(target)));
        }
        cartTable.refresh();
        updateSelectedCartSummary();

        Message request = request(MessageType.SHOP_CART_UPDATE);
        request.putData("cartItemId", item.getCartItemId());
        request.putData("quantity", target);
        send(request, response -> refreshCart(), this::refreshCart);
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
        File[] selectedImage = new File[1];
        ImageView imagePreview = new ImageView();
        imagePreview.setFitWidth(175);
        imagePreview.setFitHeight(120);
        imagePreview.setPreserveRatio(true);
        Label imageFileLabel = new Label("未选择图片，可稍后在商品维护中上传");
        imageFileLabel.getStyleClass().add("hint-text");
        Button chooseImageButton = new Button("选择 JPG/PNG 图片");
        chooseImageButton.getStyleClass().add("btn-secondary");
        chooseImageButton.setOnAction(event -> {
            File file = chooseProductImage(dialog.getDialogPane().getScene().getWindow());
            if (file == null) return;
            selectedImage[0] = file;
            imageFileLabel.setText(file.getName());
            imagePreview.setImage(new Image(file.toURI().toString(), true));
        });
        VBox imageBox = new VBox(8, chooseImageButton, imageFileLabel, imagePreview);
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
        form.addRow(4, new Label("商品图片"), imageBox);
        form.addRow(5, new Label("商品说明"), descriptionArea);
        GridPane.setHgrow(nameField, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(categoryField, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(priceField, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(stockField, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(descriptionArea, javafx.scene.layout.Priority.ALWAYS);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setMinWidth(620);
        dialog.getDialogPane().setPrefWidth(680);

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
        Runnable afterCreate = () -> {
            AlertUtil.showInfo("商品管理", "商品新增成功，已默认上架");
            refreshProducts();
            refreshOperationLogs();
        };
        if (selectedImage[0] == null) send(request, response -> afterCreate.run());
        else sendImageRequest(request, selectedImage[0], afterCreate, () -> { });
    }

    private File chooseProductImage(javafx.stage.Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择商品图片（最大 1 MiB）");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "JPG 或 PNG 图片", "*.jpg", "*.jpeg", "*.png"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) return null;
        try {
            if (Files.size(file.toPath()) > 1024 * 1024) {
                AlertUtil.showWarning("商品图片", "图片不能超过 1 MiB，请选择较小的图片");
                return null;
            }
        } catch (IOException e) {
            AlertUtil.showError("商品图片", "无法读取图片文件：" + e.getMessage());
            return null;
        }
        return file;
    }

    private void sendImageRequest(Message request, File file, Runnable onSuccess, Runnable onFailure) {
        if (imageUploadInProgress) return;
        imageUploadInProgress = true;
        createProductButton.setDisable(true);
        replaceProductImageButton.setDisable(true);
        shopUploadStatusLabel.setText("正在上传图片，请稍候…");
        java.util.concurrent.atomic.AtomicBoolean requestStarted = new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture.supplyAsync(() -> {
            try {
                return ShopImageClientCodec.encode(file.toPath());
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }).thenCompose(imageBase64 -> {
            request.putData("imageBase64", imageBase64);
            requestStarted.set(true);
            return SocketClient.getInstance().sendAsync(request);
        }).whenComplete((response, error) -> Platform.runLater(() -> {
            imageUploadInProgress = false;
            createProductButton.setDisable(false);
            replaceProductImageButton.setDisable(adminProductTable.getSelectionModel().getSelectedItem() == null);
            shopUploadStatusLabel.setText("");
            if (error != null) {
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (requestStarted.get()) {
                    AlertUtil.showWarning("商品图片", "上传结果尚未确认。请先刷新商品列表和操作日志，确认是否已经保存，再决定是否重试。\n原因：" + cause.getMessage());
                    refreshProducts();
                    refreshOperationLogs();
                } else {
                    AlertUtil.showError("商品图片", "读取图片失败：" + cause.getMessage());
                }
                onFailure.run();
            } else if (response.getCode() != MessageCode.SUCCESS) {
                AlertUtil.showError("商品图片", response.getMessage());
                onFailure.run();
            } else {
                onSuccess.run();
            }
        }));
    }

    @FXML
    private void handleReplaceProductImage() {
        Product selected = adminProductTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            AlertUtil.showWarning("商品图片", "请先选择要更换图片的商品");
            return;
        }
        File file = chooseProductImage(shopTabs.getScene().getWindow());
        if (file == null) return;
        Image localPreview = new Image(file.toURI().toString(), true);
        adminProductImageView.setImage(localPreview);
        adminImagePlaceholderLabel.setVisible(false);
        Message request = request(MessageType.SHOP_PRODUCT_IMAGE_SET);
        request.putData("productId", selected.getProductId());
        request.putData("version", selected.getVersion());
        sendImageRequest(request, file, () -> {
            AlertUtil.showInfo("商品图片", "商品图片已更新");
            refreshProducts();
            refreshOperationLogs();
        }, () -> loadAdminProductPreview(selected));
    }

    private void loadAdminProductPreview(Product selected) {
        long generation = ++adminPreviewGeneration;
        adminProductImageView.setImage(null);
        adminImagePlaceholderLabel.setVisible(true);
        adminImagePlaceholderLabel.setText(selected == null ? "选择商品后预览图片" : "图片加载中…");
        replaceProductImageButton.setDisable(selected == null || imageUploadInProgress);
        if (selected == null) return;
        long selectedId = selected.getProductId();
        requestProductDetail(selectedId, (product, imageBase64) -> {
            Product current = adminProductTable.getSelectionModel().getSelectedItem();
            if (generation != adminPreviewGeneration || current == null
                    || !Long.valueOf(selectedId).equals(current.getProductId())) return;
            CompletableFuture.supplyAsync(() -> decodeProductImage(imageBase64))
                    .thenAccept(image -> Platform.runLater(() -> {
                        Product stillSelected = adminProductTable.getSelectionModel().getSelectedItem();
                        if (generation != adminPreviewGeneration || stillSelected == null
                                || !Long.valueOf(selectedId).equals(stillSelected.getProductId())) return;
                        adminProductImageView.setImage(image);
                        adminImagePlaceholderLabel.setText("暂无图片");
                        adminImagePlaceholderLabel.setVisible(image == null);
                    }));
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
            refreshOperationLogs();
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
        send(request, response -> {
            refreshProducts();
            refreshOperationLogs();
        });
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
                refreshOperationLogs();
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
            Product previous = adminProductTable.getSelectionModel().getSelectedItem();
            Long selectedId = previous == null ? null : previous.getProductId();
            Product[] products = gson.fromJson(gson.toJson((Object) response.getData("products")), Product[].class);
            productTable.setItems(FXCollections.observableArrayList(products));
            adminProductTable.setItems(FXCollections.observableArrayList(products));
            inventoryProductTable.setItems(FXCollections.observableArrayList(products));
            // 商品数据可能已变化（例如管理员换了图），缩略图缓存随之失效并重绘。
            currentProducts = List.of(products);
            productThumbnails.clear();
            productThumbnailsMissing.clear();
            if (isGalleryVisible()) renderProductGallery();
            if (selectedId != null) {
                for (Product product : products) {
                    if (selectedId.equals(product.getProductId())) {
                        adminProductTable.getSelectionModel().select(product);
                        break;
                    }
                }
            }
        });
    }

    /** 商品中心当前的展示方式：true 表示缩略图，false 表示列表。 */
    private boolean isGalleryVisible() {
        return productViewToggle != null && productViewToggle.isSelected();
    }

    /** 在列表和缩略图两种视图之间切换。 */
    @FXML
    private void handleToggleProductView() {
        applyProductView();
        if (isGalleryVisible()) renderProductGallery();
    }

    /** 两个视图共用同一批商品与同一份选中状态，只切换显示区域。 */
    private void applyProductView() {
        boolean gallery = isGalleryVisible();
        productViewToggle.setText(gallery ? "列表" : "缩略图");
        productTable.setVisible(!gallery);
        productTable.setManaged(!gallery);
        productGalleryScroll.setVisible(gallery);
        productGalleryScroll.setManaged(gallery);
    }

    /** 按当前商品列表重建缩略图卡片，只为尚未缓存的商品请求缩略图。 */
    private void renderProductGallery() {
        productGallery.getChildren().clear();
        galleryImageViews.clear();
        galleryPlaceholders.clear();
        if (currentProducts.isEmpty()) {
            Label empty = new Label("没有符合条件的商品");
            empty.getStyleClass().add("shop-gallery-empty");
            productGallery.getChildren().add(empty);
            return;
        }
        List<Long> pending = new ArrayList<>();
        for (Product product : currentProducts) {
            Long id = product.getProductId();
            if (id != null && !productThumbnails.containsKey(id) && !productThumbnailsMissing.contains(id)) {
                pending.add(id);
            }
            productGallery.getChildren().add(createGalleryCard(product));
        }
        applyThumbnailsToGallery();
        if (!pending.isEmpty()) requestProductThumbnails(pending);
    }

    private VBox createGalleryCard(Product product) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(156);
        imageView.setFitHeight(116);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        Long productId = product.getProductId();
        Label placeholder = new Label(placeholderTextFor(productId));
        placeholder.getStyleClass().add("shop-gallery-placeholder");

        StackPane frame = new StackPane(placeholder, imageView);
        frame.getStyleClass().add("shop-gallery-frame");
        frame.setPrefSize(170, 128);
        frame.setMinSize(170, 128);

        Label name = new Label(product.getProductName());
        name.getStyleClass().add("shop-gallery-name");
        name.setWrapText(true);
        name.setMaxWidth(170);
        name.setMinHeight(34);

        Label price = new Label("¥ " + product.getPrice());
        price.getStyleClass().add("shop-gallery-price");

        Label meta = new Label("库存 " + (product.getStock() == null ? "-" : product.getStock())
                + " · " + (product.getStatus() == null ? "-" : product.getStatus().getDescription()));
        meta.getStyleClass().add("shop-gallery-meta");

        VBox card = new VBox(6, frame, name, price, meta);
        card.getStyleClass().add("shop-gallery-card");
        card.setPrefWidth(188);
        card.setOnMouseClicked(event -> {
            selectProductFromGallery(product, card);
            if (event.getClickCount() == 2 && productId != null) {
                requestProductDetail(productId, this::showProductDetail);
            }
        });

        if (productId != null) {
            galleryImageViews.put(productId, imageView);
            galleryPlaceholders.put(productId, placeholder);
        }
        return card;
    }

    private String placeholderTextFor(Long productId) {
        if (productId == null) return "暂无图片";
        if (productThumbnails.containsKey(productId)) return "";
        return productThumbnailsMissing.contains(productId) ? "暂无图片" : "图片加载中…";
    }

    /** 点卡片即选中该商品，工具条上的“商品详情”“加入购物车”依然可用。 */
    private void selectProductFromGallery(Product product, VBox card) {
        productTable.getSelectionModel().select(product);
        for (Node node : productGallery.getChildren()) {
            node.getStyleClass().remove("shop-gallery-card-selected");
        }
        if (!card.getStyleClass().contains("shop-gallery-card-selected")) {
            card.getStyleClass().add("shop-gallery-card-selected");
        }
    }

    /** 缩略图分批请求，服务端只返回确实有图片的项。 */
    private void requestProductThumbnails(List<Long> productIds) {
        for (int start = 0; start < productIds.size(); start += THUMBNAIL_BATCH_SIZE) {
            int end = Math.min(start + THUMBNAIL_BATCH_SIZE, productIds.size());
            requestProductThumbnailBatch(new ArrayList<>(productIds.subList(start, end)));
        }
    }

    /** 请求一批缩略图；本批没有返回的商品即视为没有图片。 */
    private void requestProductThumbnailBatch(List<Long> batch) {
        Message request = request(MessageType.SHOP_PRODUCT_THUMBNAILS);
        request.putData("productIds", batch);
        send(request, response -> {
            Map<String, String> encoded = new LinkedHashMap<>();
            if (response.getData("thumbnails") instanceof Map<?, ?> raw) {
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (entry.getValue() instanceof String value && !value.isBlank()) {
                        encoded.put(String.valueOf(entry.getKey()), value);
                    }
                }
            }
            CompletableFuture.supplyAsync(() -> {
                Map<Long, Image> decoded = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : encoded.entrySet()) {
                    try {
                        Image image = decodeProductImage(entry.getValue());
                        if (image != null) decoded.put(Long.parseLong(entry.getKey()), image);
                    } catch (NumberFormatException ignored) {
                        // 键不是商品编号时跳过。
                    }
                }
                return decoded;
            }).thenAccept(decoded -> Platform.runLater(() -> {
                productThumbnails.putAll(decoded);
                for (Long id : batch) {
                    if (!productThumbnails.containsKey(id)) productThumbnailsMissing.add(id);
                }
                applyThumbnailsToGallery();
            }));
        });
    }

    /** 把已解码的缩略图填到对应卡片上，取不到图的显示占位文字。 */
    private void applyThumbnailsToGallery() {
        for (Map.Entry<Long, ImageView> entry : galleryImageViews.entrySet()) {
            Image image = productThumbnails.get(entry.getKey());
            Label placeholder = galleryPlaceholders.get(entry.getKey());
            if (image != null) {
                entry.getValue().setImage(image);
                if (placeholder != null) placeholder.setVisible(false);
            } else if (placeholder != null) {
                placeholder.setText(placeholderTextFor(entry.getKey()));
                placeholder.setVisible(true);
            }
        }
    }

    private void refreshCart() {
        send(request(MessageType.SHOP_CART_LIST), response -> {
            CartItem[] items = gson.fromJson(gson.toJson((Object) response.getData("cartItems")), CartItem[].class);
            CartItem previous = cartTable.getSelectionModel().getSelectedItem();
            Long selectedId = previous == null ? null : previous.getCartItemId();
            cartTable.setItems(FXCollections.observableArrayList(items));
            // 刷新会替换整个列表，这里按编号恢复原来的选中行，
            // “删除”“商品详情”仍然作用于用户刚才选的那一行。
            if (selectedId != null) {
                for (CartItem item : items) {
                    if (selectedId.equals(item.getCartItemId())) {
                        cartTable.getSelectionModel().select(item);
                        break;
                    }
                }
            }
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
        if (refund.getStatus() != RefundStatus.APPLIED) {
            AlertUtil.showWarning("退款审核", "该退款申请已处理，不能重复审核");
            return;
        }
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

    @FXML private void handleRefreshOperationLogs() { refreshOperationLogs(); }

    @FXML private void handleOperationLogTabChanged() {
        if (operationLogTab != null && operationLogTab.isSelected()) refreshOperationLogs();
    }

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
        refreshOperationLogs();
    }

    private void refreshOperationLogs() {
        if (!isAdmin() || operationLogTable == null) return;
        Message request = request(MessageType.SHOP_OPERATION_LOG_QUERY);
        request.putData("limit", 200);
        send(request, response -> {
            ShopOperationLog[] logs = gson.fromJson(
                    gson.toJson((Object) response.getData("logs")), ShopOperationLog[].class);
            operationLogTable.setItems(FXCollections.observableArrayList(logs));
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
        send(request, onSuccess, () -> { });
    }

    /** 带失败回调的发送：失败时除提示外，还能把界面上的临时改动回滚掉。 */
    private void send(Message request, Consumer<Message> onSuccess, Runnable onFailure) {
        SocketClient.getInstance().sendAsync(request).thenAccept(response -> Platform.runLater(() -> {
            if (response.getCode() == MessageCode.SUCCESS) onSuccess.accept(response);
            else {
                AlertUtil.showError("商店操作失败", response.getMessage());
                onFailure.run();
            }
        })).exceptionally(ex -> {
            Platform.runLater(() -> {
                AlertUtil.showError("网络异常", "商店请求失败：" + ex.getMessage());
                onFailure.run();
            });
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

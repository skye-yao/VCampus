# Shop Product Images Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admin-uploaded product pictures appear in the product detail dialog on every client connected to the shared server and survive changing the server computer when it reconnects to the same MySQL database.

**Architecture:** Store picture bytes in a separate MySQL table keyed by product ID. Keep pictures out of list responses; encode them only in detail responses and admin write requests over the existing JSON-lines Socket protocol. Validate on the server, write picture changes and audit logs in one transaction, and use the existing product version for concurrent replacements.

**Tech Stack:** Java 25, JavaFX, Gson, JDBC, MySQL 8/InnoDB, PowerShell/IntelliJ module build.

**Spec:** `docs/superpowers/specs/2026-09-14-shop-product-images-design.md`

## Global Constraints

- All clients connect to one active server; the server connects to the same shared MySQL database after host changes.
- Accept JPEG/PNG only; raw image size at most 1 MiB. No file paths supplied by clients are trusted or stored.
- Existing product, cart, order, refund and bank behavior remains unchanged. Old products without pictures show a placeholder.
- Never put image bytes in `SHOP_PRODUCT_LIST`. Do not commit `db.properties` or database credentials.
- Preserve existing data in `virtual_campus`; schema changes must be additive and repeatable.

---

## File map

- `VCampusServer/src/resources/init.sql`: repeatable `tbl_product_image` creation, including foreign key.
- `VCampusServer/src/dao/ProductImageDAO.java`: binary image persistence in caller-owned transactions.
- `VCampusServer/src/service/ProductImageCodec.java`: Base64, size, magic-byte and decoder validation.
- `VCampusServer/src/service/ShopService.java`: admin create/replace transaction and detail retrieval.
- `VCampusServer/src/dao/ProductDAO.java`: version-checked image replacement gate.
- `VCampusServer/src/handler/ShopHandler.java`, `VCampusCommon/src/protocol/MessageType.java`: wire actions/data and authorization.
- `VCampusClient/src/controller/ShopController.java`, `VCampusClient/src/resources/fxml/ShopView.fxml`, `VCampusClient/src/resources/css/style.css`: choose/preview/upload, admin image maintenance, shared product/cart detail UI.
- `VCampusServer/tests/service/ProductImageCodecTest.java`, `VCampusServer/tests/dao/ProductImageDAOIntegrationTest.java`: focused executable tests.

### Task 1: Image validation and persistent schema

**Files:**
- Create: `VCampusServer/src/service/ProductImageCodec.java`
- Create: `VCampusServer/tests/service/ProductImageCodecTest.java`
- Modify: `VCampusServer/src/resources/init.sql` after `tbl_product`

**Interfaces:**
- Produces: `ProductImageCodec.decode(String)` returning `ProductImageCodec.ValidatedImage(String mimeType, byte[] bytes)`; throws `BusinessException` for invalid input.
- Produces: `tbl_product_image(product_id BIGINT PRIMARY KEY, mime_type VARCHAR(20), image_data MEDIUMBLOB, updated_at TIMESTAMP)` with FK to `tbl_product`.

- [ ] **Step 1: Write the failing codec test.** Generate a 2×2 PNG with `ImageIO.write`; use a standalone `main` and explicit `AssertionError` so no JUnit dependency is added. Cover corrupt Base64, non-image bytes and a 1 MiB+1-byte payload as rejected inputs.

```java
BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
ByteArrayOutputStream png = new ByteArrayOutputStream();
ImageIO.write(source, "png", png);
var decoded = ProductImageCodec.decode(Base64.getEncoder().encodeToString(png.toByteArray()));
if (!"image/png".equals(decoded.mimeType())) throw new AssertionError("PNG MIME");
expectBusinessException(() -> ProductImageCodec.decode("%%%"));
expectBusinessException(() -> ProductImageCodec.decode(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
expectBusinessException(() -> ProductImageCodec.decode(Base64.getEncoder().encodeToString(new byte[1024 * 1024 + 1])));
```
- [ ] **Step 2: Compile and run the test; verify it fails because `ProductImageCodec` does not exist.** Run `javac -cp out/production/VCampusCommon -d build-check/product-images/test-classes VCampusServer/src/service/ProductImageCodec.java VCampusServer/tests/service/ProductImageCodecTest.java` after creating the output directory; before implementation expect source-file/class-not-found failure.
- [ ] **Step 3: Implement the codec.** Reject Base64 text longer than `((1024*1024+2)/3)*4+4` before decode, then reject decoded bytes above `1024*1024`. Require a valid signature and decoded image with dimensions from 1×1 through 4096×4096; return a defensive copy.

```java
public final class ProductImageCodec {
    public record ValidatedImage(String mimeType, byte[] bytes) { }
    public static ValidatedImage decode(String base64) {
        if (base64 == null || base64.isBlank() || base64.length() > ((1024 * 1024 + 2) / 3) * 4 + 4)
            throw new BusinessException("图片为空或超过1 MiB");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(base64); }
        catch (IllegalArgumentException e) { throw new BusinessException("图片编码不正确"); }
        if (bytes.length > 1024 * 1024) throw new BusinessException("图片超过1 MiB");
        String mime = isPng(bytes) ? "image/png" : isJpeg(bytes) ? "image/jpeg" : null;
        if (mime == null) throw new BusinessException("只支持PNG或JPEG图片");
        BufferedImage image;
        try { image = ImageIO.read(new ByteArrayInputStream(bytes)); }
        catch (IOException e) { throw new BusinessException("图片内容不正确"); }
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                || image.getWidth() > 4096 || image.getHeight() > 4096)
            throw new BusinessException("图片内容或尺寸不正确");
        return new ValidatedImage(mime, bytes.clone());
    }
    private static boolean isPng(byte[] b) {
        return b.length >= 8 && (b[0] & 255) == 137 && b[1] == 80 && b[2] == 78
                && b[3] == 71 && b[4] == 13 && b[5] == 10 && b[6] == 26 && b[7] == 10;
    }
    private static boolean isJpeg(byte[] b) {
        return b.length >= 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216
                && (b[2] & 255) == 255;
    }
}
```
- [ ] **Step 4: Add additive DDL and run the codec test.** No SQL `UPDATE` or `DROP`; quote the entire Java classpath argument in PowerShell.

```sql
CREATE TABLE IF NOT EXISTS tbl_product_image (
    product_id BIGINT NOT NULL PRIMARY KEY,
    mime_type VARCHAR(20) NOT NULL,
    image_data MEDIUMBLOB NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id)
        REFERENCES tbl_product(product_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```
- [ ] **Step 5: Commit only Task 1 files.**

### Task 2: DAO, detail and administrator write operations

**Files:**
- Create: `VCampusServer/src/dao/ProductImageDAO.java`
- Create: `VCampusServer/tests/dao/ProductImageDAOIntegrationTest.java`
- Modify: `VCampusServer/src/dao/ProductDAO.java`
- Modify: `VCampusServer/src/service/ShopService.java`
- Modify: `VCampusServer/src/handler/ShopHandler.java`
- Modify: `VCampusCommon/src/protocol/MessageType.java`

**Interfaces:**
- Consumes: `ProductImageCodec.decode(String)` and `tbl_product_image` from Task 1.
- Produces: `ProductImageDAO.upsert(Connection,long,String,byte[])` and `findByProductId(Connection,long)` returning a record with MIME and bytes.
- Produces: `ShopService.getProductDetail(long) -> Map<String,Object>` with keys `product`, `imageBase64`, `imageMimeType`; `createProduct(String,Product,String,boolean)`; `replaceProductImage(String,long,int,String,boolean)`.
- Wire: `SHOP_PRODUCT_CREATE` may include `imageBase64`; new `SHOP_PRODUCT_IMAGE_SET` requires `productId`, `version`, `imageBase64`; `SHOP_PRODUCT_DETAIL` returns existing `product` plus optional image fields.

- [ ] **Step 1: Write the failing DAO integration test.** In one JDBC transaction, insert a temporary product with a unique name through `ProductDAO.insert(conn, product)`, call `upsert` with a small PNG byte array, assert `findByProductId` returns identical bytes/MIME, replace with JPEG bytes, assert replacement, and `rollback` in `finally`. Skip with a clear message only if the configured test database is unavailable; do not alter permanent products.
- [ ] **Step 2: Run the test and verify missing DAO/schema is reported.** Once Task 1 DDL has been applied to the test database, compile/run `dao.ProductImageDAOIntegrationTest`; before DAO implementation expect class-not-found/compile failure.
- [ ] **Step 3: Implement DAO and service transactions.** DAO uses prepared statements and caller-provided `Connection`, never opens its own connection. Create validates optional image before insert, then inserts product, optional image and audit row before commit. Replacement validates first, locks/checks product, bumps version, upserts image, writes `PRODUCT_IMAGE_UPDATE` audit row and commits; any failure rolls back. Detail Base64-encodes only the requested picture.

```java
// ProductDAO: version gate for concurrent image replacement.
String sql = "UPDATE tbl_product SET version=version+1 WHERE product_id=? AND version=?";
// ProductImageDAO: binary bytes, not a file path.
String upsert = "INSERT INTO tbl_product_image(product_id,mime_type,image_data) VALUES(?,?,?) "
        + "ON DUPLICATE KEY UPDATE mime_type=VALUES(mime_type),image_data=VALUES(image_data)";
// ShopService.replaceProductImage, inside one caller-owned transaction:
var image = ProductImageCodec.decode(imageBase64);
if (!productDAO.bumpVersionForImage(conn, productId, expectedVersion))
    throw new BusinessException("商品已被其他管理员修改，请刷新后重试");
productImageDAO.upsert(conn, productId, image.mimeType(), image.bytes());
operationLogDAO.insert(conn, operatorId, "PRODUCT_IMAGE_UPDATE", "PRODUCT", productId,
        null, "image=" + image.mimeType(), "更换商品图片");
conn.commit();
```
- [ ] **Step 4: Wire the handler and enum.** Keep the existing session-based admin check; reject non-admin `SHOP_PRODUCT_IMAGE_SET`. Missing `imageBase64` means no image only for create, not replace. Do not put raw bytes or server paths in `Product` or list output.

```java
// MessageType adds SHOP_PRODUCT_IMAGE_SET.
case "SHOP_PRODUCT_DETAIL" -> response.setData(shopService.getProductDetail(number(request, "productId")));
case "SHOP_PRODUCT_CREATE" -> response.setData(shopService.createProduct(
        userId, product(request), string(request, "imageBase64"), admin));
case "SHOP_PRODUCT_IMAGE_SET" -> shopService.replaceProductImage(
        userId, number(request, "productId"), integer(request, "version"),
        string(request, "imageBase64"), admin);
```
- [ ] **Step 5: Run DAO test and compile Common/Server modules in IntelliJ.** Verify existing shop requests still compile and the DAO test passes against a local test schema. Commit only Task 2 files.

### Task 3: JavaFX upload and detail experience

**Files:**
- Modify: `VCampusClient/src/controller/ShopController.java`
- Modify: `VCampusClient/src/resources/fxml/ShopView.fxml`
- Modify: `VCampusClient/src/resources/css/style.css`

**Interfaces:**
- Consumes: Task 2 wire fields/actions.
- Produces: create-dialog optional image selection; admin selected-product preview/replacement; one detail dialog path for product list and cart.

- [ ] **Step 1: Make a manual regression checklist before editing.** Existing no-image product detail, cart detail, create-without-picture and admin basic-info update must still work; record these four cases in the Task 3 commit message or test notes.
- [ ] **Step 2: Add selection and upload handling.** Preview uses a local URI, but upload sends Base64 bytes only. Read and encode off the FX thread; disable submit while running and re-enable on completion/error. Optional create image goes in `SHOP_PRODUCT_CREATE`; replacement uses `SHOP_PRODUCT_IMAGE_SET` with selected product ID/version.

```java
FileChooser chooser = new FileChooser();
chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("图片", "*.jpg", "*.jpeg", "*.png"));
File chosen = chooser.showOpenDialog(shopTabs.getScene().getWindow());
if (chosen != null) preview.setImage(new Image(chosen.toURI().toString(), true));
CompletableFuture<String> encoded = CompletableFuture.supplyAsync(() -> {
    try {
        if (Files.size(chosen.toPath()) > 1024 * 1024) throw new IllegalArgumentException("图片超过1 MiB");
        return Base64.getEncoder().encodeToString(Files.readAllBytes(chosen.toPath()));
    } catch (IOException e) { throw new CompletionException(e); }
});
```
- [ ] **Step 3: Unify detail requests.** Both handlers call one method using `SHOP_PRODUCT_DETAIL`; parse `product`, `imageBase64`, `imageMimeType`; display an `ImageView` and styled text, with “暂无图片” when absent or undecodable.

```java
private void requestProductDetail(long productId) {
    Message message = request(MessageType.SHOP_PRODUCT_DETAIL);
    message.putData("productId", productId);
    send(message, response -> {
        Product product = gson.fromJson(gson.toJson(response.getData("product")), Product.class);
        Object encoded = response.getData("imageBase64");
        showProductDetail(product, encoded instanceof String value ? value : null);
    });
}
```
- [ ] **Step 4: Add admin preview and CSS.** On admin product selection, fetch detail for preview; show “暂无图片” until response; add “更换图片” near basic information and reuse existing green/white CSS. After success refresh product/version, preview and audit log.

```xml
<ImageView fx:id="adminProductImageView" fitHeight="160" fitWidth="200" preserveRatio="true"/>
<Button text="更换图片" onAction="#handleReplaceProductImage" styleClass="btn-secondary"/>
```
- [ ] **Step 5: Compile the Client module in IntelliJ and perform the four checklist regressions.** Also test a valid PNG, replacement with JPEG, corrupt local file and oversized file. Commit only Task 3 files.

### Task 4: End-to-end migration and deployment check

**Files:**
- Modify: `docs/superpowers/specs/2026-09-14-shop-product-images-design.md` only if observed behavior differs from the approved design.
- Create: `docs/shop-product-images-deployment.md` with the final operator instructions.

**Interfaces:**
- Consumes: Tasks 1–3 complete.
- Produces: reproducible migration, test results and server-host-change instructions.

- [ ] **Step 1: Apply the additive `CREATE TABLE IF NOT EXISTS tbl_product_image` statement to an existing test database.** Run it twice and verify the second run succeeds, existing `tbl_product` rows/orders remain unchanged, and `SHOW CREATE TABLE tbl_product_image` reports the FK.
- [ ] **Step 2: Test two-client visibility.** Administrator on client A uploads/replaces a picture; client B opens product detail without restarting and sees the new picture. An ordinary user attempting the wire action receives a rejected response.
- [ ] **Step 3: Test host migration against the same database.** Start a second server process on a different host or with an equivalent isolated server configuration pointing to the same MySQL (stop the first server before reusing port 8888); verify the image is still present. If a second physical host is unavailable, record this as not verified rather than claiming it passed.
- [ ] **Step 4: Document deployment truthfully.** `db.properties` on the active server must point to the shared database address; clients connect to the server address. `localhost` in the template is local-only. Git pull alone does not copy uploaded images; a MySQL backup includes them. Never print or commit passwords.
- [ ] **Step 5: Run final clean Common/Server/Client builds and relevant tests, inspect `git diff --check` and `git status`, then commit only deployment documentation.** Report exact passing checks and any physical-host test that could not be run.

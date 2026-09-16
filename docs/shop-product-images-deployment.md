# 商店商品图片：部署与验收

商品图片保存在 MySQL 的 `tbl_product_image`，客户端只连接校园服务端。商品列表不传图片；打开商品详情时，服务端从数据库按商品编号读取图片（详情走的是原图，没有缩略图缓存，因此上传的图越大打开越慢）。旧商品没有图片也可正常显示，详情显示“暂无图片”。商品中心支持在列表和缩略图两种视图之间切换：列表视图沿用原来的表格，缩略图视图由服务端按需把原图缩放成小图后返回，仍然不会把原图二进制塞进商品列表接口。

## 已有数据库升级

在实际供服务端使用的 `virtual_campus` 数据库中，使用 MySQL Workbench 执行下面这一段即可。不要为了加图片而重跑整份 `init.sql`，以免触发其中其他模块的数据更新。

```sql
USE `virtual_campus`;
CREATE TABLE IF NOT EXISTS `tbl_product_image` (
    `product_id` BIGINT NOT NULL COMMENT '商品编号',
    `mime_type` VARCHAR(20) NOT NULL COMMENT 'image/png 或 image/jpeg',
    `image_data` MEDIUMBLOB NOT NULL COMMENT '商品图片二进制内容',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`product_id`),
    CONSTRAINT `fk_product_image_product` FOREIGN KEY (`product_id`)
        REFERENCES `tbl_product` (`product_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商店商品图片表';
```

这段语句可重复执行，不删除已有商品、订单或图片。新建数据库时，项目 `VCampusServer/src/resources/init.sql` 已包含同一张表。服务端启动本身不会自动执行 `init.sql`。

## 服务端和客户端

1. 先更新并启动新版服务端，再启动新版客户端；旧服务端未停止时，8888 端口可能已被占用。
2. 服务端的 `VCampusServer/src/resources/db.properties` 中，`db.url` 要指向那一台**真正共用的 MySQL**。当前文件若写 `localhost`，就只会连接当前服务端电脑上的 MySQL；换服务端电脑时请改为数据库所在电脑的地址。不要把数据库密码提交到 Git。
3. 客户端连接正在运行的服务端地址，不直接连接 MySQL。
4. 管理员进入“校园商店 → 商店后台 → 商品维护”：新增商品时可选 JPG/PNG；对已有商品选中后可预览并更换图片。原图最大 1 MiB，尺寸不超过 4096×4096。
5. 师生在商品中心或购物车点击“商品详情”即可查看图片。更换图片后，其他客户端**重新打开详情**就会取到新图；已经打开的对话框不会自动热更新。
6. 商品中心的“缩略图”按钮可在表格和缩略图墙之间切换，再点一次回到列表。缩略图按原图更新时间在服务端缓存，换图后会自动失效；点缩略图卡片即选中该商品，双击可直接打开详情。
7. 缩略图读取分两步：先只查“有没有图、原图什么时候更新”这类小字段，命中缓存就直接返回，不再读原图二进制、也不重新解码；只有没命中的商品才去读图并缩放。服务端启动时还会在后台把已有图片的缩略图先算一遍，所以第一位打开商品中心的用户不必等现场生成，控制台会打印一行“商品缩略图缓存预热完成：N 张，用时 M ms”（预热失败只影响速度，不影响功能）。

## 换服务端电脑

只要新服务端连接的是**同一个 MySQL 数据库**，旧图片仍在；不需要复制 `product-images` 文件夹。如果连数据库也换电脑，应导出并恢复整个数据库（包括 `tbl_product_image`）。仅从 Git 拉取代码、另建空数据库，不会带来运行时上传的图片。

## 安全与边界

客户端的文件名、扩展名和 MIME 声明不作为可信依据；服务端重新验证图片内容、格式和大小。上传与图片替换仅管理员可操作；替换使用商品版本号防止两个管理员互相覆盖，数据库写入与操作日志在同一事务。普通商品列表没有图片二进制，避免一次拉取所有图片；缩略图接口只接受商品编号列表，返回的是服务端生成的 JPEG 小图，单次请求最多 120 个商品。

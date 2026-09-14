# 商店商品图片：部署与验收

商品图片保存在 MySQL 的 `tbl_product_image`，客户端只连接校园服务端。商品列表不传图片；打开商品详情时，服务端从数据库按商品编号读取图片。旧商品没有图片也可正常显示，详情显示“暂无图片”。

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

## 换服务端电脑

只要新服务端连接的是**同一个 MySQL 数据库**，旧图片仍在；不需要复制 `product-images` 文件夹。如果连数据库也换电脑，应导出并恢复整个数据库（包括 `tbl_product_image`）。仅从 Git 拉取代码、另建空数据库，不会带来运行时上传的图片。

## 安全与边界

客户端的文件名、扩展名和 MIME 声明不作为可信依据；服务端重新验证图片内容、格式和大小。上传与图片替换仅管理员可操作；替换使用商品版本号防止两个管理员互相覆盖，数据库写入与操作日志在同一事务。普通商品列表没有图片二进制，避免一次拉取所有图片。

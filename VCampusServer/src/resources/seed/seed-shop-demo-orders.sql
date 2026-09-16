-- 商店演示订单与对应的银行流水（可选执行；可重复执行）
-- 前提：已执行 ../init.sql（商品与演示账号就位）。商品图片见 seed-product-images.sql。
-- 作用：补两笔“已支付”的演示订单及其扣款/入账流水，并同步账户余额与商品库存，让销售记录和银行流水都有内容可看。
-- 执行前请先选库：USE `virtual_campus`;

SET NAMES utf8mb4;

-- ========== 1. 订单头（按订单号判重，不重复插入） ==========
INSERT INTO `tbl_shop_order` (`order_no`,`user_id`,`total_amount`,`status`,`expires_at`,`paid_at`)
SELECT 'SEED-SO-1','213242789',57.50,'PAID', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `tbl_shop_order` WHERE `order_no`='SEED-SO-1');

INSERT INTO `tbl_shop_order` (`order_no`,`user_id`,`total_amount`,`status`,`expires_at`,`paid_at`)
SELECT 'SEED-SO-2','213242790',36.00,'PAID', NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `tbl_shop_order` WHERE `order_no`='SEED-SO-2');

-- ========== 2. 订单明细（按商品名取当前名称与单价做快照） ==========
INSERT INTO `tbl_order_item` (`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT o.`order_id`, p.`product_id`, p.`product_name`, p.`price`, 1, p.`price`
FROM `tbl_shop_order` o JOIN `tbl_product` p ON p.`product_name`='全脂纯牛奶（250ml×12盒）'
WHERE o.`order_no`='SEED-SO-1'
  AND NOT EXISTS (SELECT 1 FROM `tbl_order_item` i WHERE i.`order_id`=o.`order_id`);

INSERT INTO `tbl_order_item` (`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT o.`order_id`, p.`product_id`, p.`product_name`, p.`price`, 1, p.`price`
FROM `tbl_shop_order` o JOIN `tbl_product` p ON p.`product_name`='原味黄油饼干（200g袋装）'
WHERE o.`order_no`='SEED-SO-1'
  AND EXISTS (SELECT 1 FROM `tbl_order_item` i WHERE i.`order_id`=o.`order_id`)
  AND NOT EXISTS (SELECT 1 FROM `tbl_order_item` i JOIN `tbl_product` p2 ON p2.`product_id`=i.`product_id`
                  WHERE i.`order_id`=o.`order_id` AND p2.`product_name`='原味黄油饼干（200g袋装）');

INSERT INTO `tbl_order_item` (`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT o.`order_id`, p.`product_id`, p.`product_name`, p.`price`, 2, p.`price`*2
FROM `tbl_shop_order` o JOIN `tbl_product` p ON p.`product_name`='可乐汽水（330ml×6罐）'
WHERE o.`order_no`='SEED-SO-2'
  AND NOT EXISTS (SELECT 1 FROM `tbl_order_item` i WHERE i.`order_id`=o.`order_id`);

-- ========== 3. 银行流水（买家支出 + 财务入账），只在流水不存在时执行一次 ==========
SET @seed_pay_new = (SELECT COUNT(*) FROM `tbl_bank_transaction` WHERE `request_id` LIKE 'SEED-OUT-%') = 0;

INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,`balance_after`,`related_order_id`,`request_id`,`remark`)
SELECT CONCAT('SEEDTXOUT', o.`order_id`), b.`account_id`, 'admin', 'SHOP_PAYMENT',
       -o.`total_amount`, b.`balance` - o.`total_amount`, o.`order_id`,
       CONCAT('SEED-OUT-', o.`order_id`), CONCAT('演示订单支付：', o.`order_no`)
FROM `tbl_shop_order` o JOIN `tbl_bank_account` b ON b.`user_id`=o.`user_id`
WHERE o.`order_no` IN ('SEED-SO-1','SEED-SO-2') AND @seed_pay_new;

INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,`balance_after`,`related_order_id`,`request_id`,`remark`)
SELECT CONCAT('SEEDTXIN', o.`order_id`), b.`account_id`, o.`user_id`, 'SHOP_INCOME',
       o.`total_amount`,
       b.`balance` + (SELECT IFNULL(SUM(o2.`total_amount`),0) FROM `tbl_shop_order` o2
                      WHERE o2.`order_no` IN ('SEED-SO-1','SEED-SO-2') AND o2.`order_no` <= o.`order_no`),
       o.`order_id`, CONCAT('SEED-IN-', o.`order_id`), CONCAT('演示商店收入：', o.`order_no`)
FROM `tbl_shop_order` o JOIN `tbl_bank_account` b ON b.`user_id`='admin'
WHERE o.`order_no` IN ('SEED-SO-1','SEED-SO-2') AND @seed_pay_new;

-- ========== 4. 同步账户余额与商品库存 ==========
UPDATE `tbl_bank_account` b
JOIN `tbl_shop_order` o ON o.`user_id`=b.`user_id`
SET b.`balance` = b.`balance` - o.`total_amount`
WHERE o.`order_no` IN ('SEED-SO-1','SEED-SO-2') AND @seed_pay_new;

UPDATE `tbl_bank_account` b
SET b.`balance` = b.`balance` + (SELECT IFNULL(SUM(o.`total_amount`),0) FROM `tbl_shop_order` o
                                 WHERE o.`order_no` IN ('SEED-SO-1','SEED-SO-2'))
WHERE b.`user_id`='admin' AND @seed_pay_new;

UPDATE `tbl_product` p
JOIN `tbl_order_item` i ON i.`product_id`=p.`product_id`
JOIN `tbl_shop_order` o ON o.`order_id`=i.`order_id`
SET p.`stock` = GREATEST(p.`stock` - i.`quantity`, 0)
WHERE o.`order_no` IN ('SEED-SO-1','SEED-SO-2') AND @seed_pay_new;

-- ========== 5. 回填订单的支付流水号 ==========
UPDATE `tbl_shop_order` o
JOIN `tbl_bank_transaction` t ON t.`request_id`=CONCAT('SEED-OUT-', o.`order_id`)
SET o.`payment_transaction_no` = t.`transaction_no`
WHERE o.`order_no` IN ('SEED-SO-1','SEED-SO-2') AND o.`payment_transaction_no` IS NULL;

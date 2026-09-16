-- 先执行 init.sql 和 migrations/v301_chat.sql。两个账号来自 init.sql。
-- 示例：张三向李雨桐申请好友；登录李雨桐后手工同意，即可测试聊天。
-- 不预先绕过好友同意，也不重置已处理的申请。重复导入不会新增重复关系。
USE virtual_campus;
INSERT IGNORE INTO tbl_chat_friend(user_low,user_high,requester,status)
SELECT '213242789','213242790','213242789','PENDING'
WHERE EXISTS(SELECT 1 FROM tbl_user WHERE UID='213242789' AND status='ACTIVE')
  AND EXISTS(SELECT 1 FROM tbl_user WHERE UID='213242790' AND status='ACTIVE');

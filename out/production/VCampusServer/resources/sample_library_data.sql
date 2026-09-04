-- 仅供全新、空白图书馆数据库使用；重复执行会拒绝，避免重复记录与自增号消耗。
DELIMITER $$
DROP PROCEDURE IF EXISTS seed_library_once$$
CREATE PROCEDURE seed_library_once()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  START TRANSACTION;
  IF EXISTS(SELECT 1 FROM tblBook) OR EXISTS(SELECT 1 FROM tblBorrowRecord)
     OR EXISTS(SELECT 1 FROM tblReservation) OR EXISTS(SELECT 1 FROM tblBookReview)
     OR EXISTS(SELECT 1 FROM tblLossRecord) OR EXISTS(SELECT 1 FROM tblFineRecord) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Library is not empty; sample import refused';
  END IF;
-- ============================================================
-- 插入测试数据
-- ============================================================

-- 插入测试图书
INSERT INTO `tblBook` (`id`, `isbn`, `name`, `author`, `publisher`, `status`) VALUES
(1, '978-7-302-12345-6', '数据库系统概论', '王珊', '清华大学出版社', 0),
(2, '978-7-111-67890-1', '深入理解计算机系统', 'Randal E.Bryant', '机械工业出版社', 1),
(3, '978-7-121-34567-8', '算法导论', 'Thomas H.Cormen', '电子工业出版社', 2),
(4, '978-7-302-98765-4', '软件工程', 'Roger S.Pressman', '清华大学出版社', 0),
(5, '978-7-111-54321-0', '计算机网络：自顶向下方法', 'James F.Kurose', '机械工业出版社', 3),
(6, '978-7-302-11111-1', '操作系统概念', 'Abraham Silberschatz', '清华大学出版社', 0),
(7, '978-7-121-22222-2', 'Python编程从入门到实践', 'Eric Matthes', '电子工业出版社', 1),
(8, '978-7-111-33333-3', '数据结构与算法分析', 'Mark Allen Weiss', '机械工业出版社', 0)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- 插入测试借阅记录 (关联已有的 tbl_user 中的用户)
INSERT INTO `tblBorrowRecord` (`userid`, `bookid`, `borrowTime`, `returnTime`, `dueTime`, `status`) VALUES
('213242789', 1, '2026-08-01 10:00:00', NULL, '2026-08-31 23:59:59', 0),
('213242789', 2, '2026-07-15 09:30:00', '2026-08-10 16:20:00', '2026-08-14 23:59:59', 1),
('teacher01', 3, '2026-08-20 14:00:00', NULL, '2026-09-19 23:59:59', 0),
('admin', 4, '2026-07-01 08:00:00', '2026-07-20 17:00:00', '2026-07-20 23:59:59', 2),
('213242789', 5, '2026-08-25 11:00:00', NULL, '2026-09-24 23:59:59', 0),
('teacher01', 6, '2026-08-10 13:30:00', '2026-08-28 10:00:00', '2026-09-09 23:59:59', 1),
('admin', 7, '2026-08-15 09:00:00', NULL, '2026-09-14 23:59:59', 0)
ON DUPLICATE KEY UPDATE `borrowTime`=VALUES(`borrowTime`);

-- 插入测试预约记录
INSERT INTO `tblReservation` (`userid`, `bookid`, `reserveTime`, `status`) VALUES
('213242789', 3, '2026-08-28 10:30:00', 0),
('teacher01', 1, '2026-08-29 15:00:00', 1),
('admin', 5, '2026-08-27 09:00:00', 2),
('213242789', 4, '2026-08-30 16:30:00', 0),
('teacher01', 2, '2026-08-26 11:20:00', 0),
('admin', 6, '2026-08-25 14:00:00', 0)
ON DUPLICATE KEY UPDATE `reserveTime`=VALUES(`reserveTime`);

-- 插入测试书评
INSERT INTO `tblBookReview` (`userid`, `bookid`, `content`, `createTime`) VALUES
('213242789', 1, '这本书讲得非常清晰，尤其是关系数据库理论部分，深入浅出，非常适合初学者。', '2026-08-15 20:30:00'),
('teacher01', 2, 'CSAPP是计算机专业的必读经典，本书从底层到上层讲解得淋漓尽致。', '2026-08-20 19:00:00'),
('admin', 3, '算法导论内容全面但偏理论，配合习题练习效果更好。', '2026-08-10 14:20:00'),
('213242789', 4, '软件工程的经典教材，对开发流程和项目管理有很好的指导意义。', '2026-08-22 10:15:00'),
('teacher01', 5, '自顶向下方法非常符合教学规律，从应用层开始理解网络更容易上手。', '2026-08-18 16:40:00'),
('admin', 6, '操作系统概念的经典之作，进程管理和内存管理讲得非常透彻。', '2026-08-12 09:00:00')
ON DUPLICATE KEY UPDATE `content`=VALUES(`content`);

-- 插入测试挂失记录
INSERT INTO `tblLossRecord` (`userid`, `bookid`, `lossTime`, `status`) VALUES
('213242789', 5, '2026-08-20 08:30:00', 0),
('teacher01', 7, '2026-08-25 16:00:00', 1),
('admin', 2, '2026-08-18 10:00:00', 0),
('213242789', 3, '2026-08-28 09:20:00', 0)
ON DUPLICATE KEY UPDATE `lossTime`=VALUES(`lossTime`);

-- 插入测试罚款记录
INSERT INTO `tblFineRecord` (`userid`, `amount`, `reason`, `status`) VALUES
('213242789', 15.50, '图书《数据库系统概论》逾期归还，逾期5天', 0),
('teacher01', 30.00, '图书《操作系统概念》遗失，赔偿罚金', 1),
('admin', 10.00, '图书《算法导论》逾期归还，逾期3天', 0),
('213242789', 20.00, '图书《计算机网络》逾期归还，逾期7天', 1),
('admin', 5.00, '图书《深入理解计算机系统》轻微损坏', 0)
ON DUPLICATE KEY UPDATE `reason`=VALUES(`reason`);
  UPDATE tblBook b SET status=CASE
    WHEN EXISTS(SELECT 1 FROM tblLossRecord l WHERE l.bookid=b.id AND l.status=0) THEN 3
    WHEN EXISTS(SELECT 1 FROM tblBorrowRecord r WHERE r.bookid=b.id AND r.status IN(0,2) AND r.returnTime IS NULL) THEN 1
    WHEN EXISTS(SELECT 1 FROM tblReservation r WHERE r.bookid=b.id AND r.status=0) THEN 2
    ELSE 0 END;
  COMMIT;
END$$
CALL seed_library_once()$$
DROP PROCEDURE seed_library_once$$
DELIMITER ;

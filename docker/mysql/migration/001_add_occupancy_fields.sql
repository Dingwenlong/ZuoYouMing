-- ============================================
-- 数据库迁移脚本：占座检测优化
-- 版本：1.0
-- 日期：2026-03-07
-- 基线脚本：docker/mysql/init/init.sql
-- 说明：为 sys_reservation 表添加占座检测相关字段
-- 特点：幂等性设计，可重复执行
-- ============================================

-- 创建迁移记录表（用于跟踪已执行的迁移）
CREATE TABLE IF NOT EXISTS `sys_migration` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `version` VARCHAR(50) NOT NULL UNIQUE,
    `description` VARCHAR(255),
    `executed_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `success` TINYINT(1) DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据库迁移记录表';

-- 检查是否已执行此迁移
SET @migration_version = '001_add_occupancy_fields';
SET @executed = (SELECT COUNT(*) FROM `sys_migration` WHERE `version` = @migration_version);

-- 如果未执行，则执行迁移
-- 注意：MySQL 不支持 IF NOT EXISTS 用于 ADD COLUMN，需要使用存储过程或忽略错误

-- 方案：使用忽略错误的方式添加字段
-- 如果字段已存在，会报错但脚本继续执行

-- 添加 last_present_time 字段
SET @sql1 = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
     WHERE TABLE_SCHEMA = DATABASE() 
     AND TABLE_NAME = 'sys_reservation' 
     AND COLUMN_NAME = 'last_present_time') = 0,
    'ALTER TABLE sys_reservation ADD COLUMN last_present_time DATETIME DEFAULT NULL COMMENT ''最后在场时间（用于占座检测）''',
    'SELECT ''Column last_present_time already exists'' AS message'
);
PREPARE stmt1 FROM @sql1;
EXECUTE stmt1;
DEALLOCATE PREPARE stmt1;

-- 添加 total_away_minutes 字段
SET @sql2 = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
     WHERE TABLE_SCHEMA = DATABASE() 
     AND TABLE_NAME = 'sys_reservation' 
     AND COLUMN_NAME = 'total_away_minutes') = 0,
    'ALTER TABLE sys_reservation ADD COLUMN total_away_minutes INT DEFAULT 0 COMMENT ''累计离开时长(分钟)''',
    'SELECT ''Column total_away_minutes already exists'' AS message'
);
PREPARE stmt2 FROM @sql2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- 添加 occupancy_alert_sent 字段
SET @sql3 = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS 
     WHERE TABLE_SCHEMA = DATABASE() 
     AND TABLE_NAME = 'sys_reservation' 
     AND COLUMN_NAME = 'occupancy_alert_sent') = 0,
    'ALTER TABLE sys_reservation ADD COLUMN occupancy_alert_sent TINYINT(1) DEFAULT 0 COMMENT ''是否已发送占座预警 0:否 1:是''',
    'SELECT ''Column occupancy_alert_sent already exists'' AS message'
);
PREPARE stmt3 FROM @sql3;
EXECUTE stmt3;
DEALLOCATE PREPARE stmt3;

-- 记录迁移执行
INSERT IGNORE INTO `sys_migration` (`version`, `description`) 
VALUES (@migration_version, '添加占座检测相关字段：last_present_time, total_away_minutes, occupancy_alert_sent');

-- 验证字段是否添加成功
SELECT 
    COLUMN_NAME AS '字段名',
    DATA_TYPE AS '数据类型',
    IS_NULLABLE AS '允许空值',
    COLUMN_DEFAULT AS '默认值',
    COLUMN_COMMENT AS '注释'
FROM 
    INFORMATION_SCHEMA.COLUMNS 
WHERE 
    TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sys_reservation'
    AND COLUMN_NAME IN ('last_present_time', 'total_away_minutes', 'occupancy_alert_sent')
ORDER BY 
    COLUMN_NAME;

-- 输出迁移结果
SELECT CONCAT('迁移 ', @migration_version, ' 执行完成') AS '状态';

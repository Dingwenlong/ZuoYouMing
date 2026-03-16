# 占座逻辑链简化计划

## 当前问题分析

### 1. 逻辑链过于复杂
当前占座检测涉及多个组件、多种状态判断，流程繁琐：

```
ReservationJob.checkOccupancy() (每5分钟)
    ↓
OccupancyMonitorService.performOccupancyCheck()
    ↓
查询 checked_in/away 状态的预约
    ↓
遍历每个预约 checkSingleReservation()
    ↓
查询/创建 SeatOccupancy 记录
    ↓
计算离开时长
    ↓
判断 warning/occupied 状态
    ↓
发送预警或执行违规处理
```

### 2. 数据冗余
- 需要维护独立的 `sys_seat_occupancy` 表
- 需要同步 `lastDetectedTime` 和 `updateTime`
- 需要管理 `totalAwayMinutes` 和 `occupancyStatus`

### 3. 调用链路混乱
占座检测的触发点分散：
- 定时任务（每5分钟）
- 用户签到时创建记录
- 用户暂离返回时更新记录
- 闭馆时批量处理

## 简化方案

### 核心思想
**移除独立的占座检测表，直接在预约记录中跟踪离开状态**

### 具体改动

#### 1. 预约表新增字段
```sql
ALTER TABLE sys_reservation ADD COLUMN last_present_time DATETIME COMMENT '最后在场时间';
ALTER TABLE sys_reservation ADD COLUMN total_away_minutes INT DEFAULT 0 COMMENT '累计离开时长(分钟)';
ALTER TABLE sys_reservation ADD COLUMN occupancy_alert_sent TINYINT DEFAULT 0 COMMENT '是否已发送占座预警';
```

#### 2. 简化检测逻辑
将原来的多表查询简化为单表查询：

```java
// 原逻辑：查询占座检测表，计算离开时长
SeatOccupancy record = occupancyMapper.selectByReservationId(reservationId);
long awayMillis = now.getTime() - record.getLastDetectedTime().getTime();

// 新逻辑：直接使用预约表的 last_present_time
long awayMillis = now.getTime() - reservation.getLastPresentTime().getTime();
```

#### 3. 统一更新入口
所有"用户在场"的确认都通过统一的 `updatePresence()` 方法：

```java
public void updatePresence(Long reservationId) {
    Reservation res = getById(reservationId);
    Date now = new Date();
    
    // 累加离开时长
    if (res.getLastPresentTime() != null) {
        long awayMillis = now.getTime() - res.getLastPresentTime().getTime();
        int awayMinutes = (int) (awayMillis / (1000 * 60));
        if (awayMinutes > 0) {
            res.setTotalAwayMinutes(res.getTotalAwayMinutes() + awayMinutes);
        }
    }
    
    res.setLastPresentTime(now);
    res.setOccupancyAlertSent(0); // 重置预警标记
    updateById(res);
}
```

#### 4. 定时任务简化
```java
@Scheduled(cron = "0 */5 * * * ?")
public void checkOccupancy() {
    Date now = new Date();
    int threshold = configService.getIntValue("occupancy_threshold", 60);
    int warningTime = configService.getIntValue("occupancy_warning_time", 45);
    
    // 直接查询预约表
    List<Reservation> list = reservationService.list(
        new LambdaQueryWrapper<Reservation>()
            .in(Reservation::getStatus, "checked_in", "away")
    );
    
    for (Reservation res : list) {
        long awayMinutes = (now.getTime() - res.getLastPresentTime().getTime()) / (1000 * 60);
        
        if (awayMinutes >= threshold) {
            handleOccupancyViolation(res);
        } else if (awayMinutes >= warningTime && res.getOccupancyAlertSent() == 0) {
            sendOccupancyWarning(res, awayMinutes);
            res.setOccupancyAlertSent(1);
            reservationService.updateById(res);
        }
    }
}
```

### 5. 删除冗余组件
- 删除 `SeatOccupancy` 实体类
- 删除 `SeatOccupancyMapper` 接口
- 删除 `OccupancyMonitorService` 中的部分方法
- 可选：删除 `sys_seat_occupancy` 表（或保留历史数据）

## 实施步骤

### Phase 1: 数据库迁移
1. 在 `sys_reservation` 表添加新字段
2. 将现有 `sys_seat_occupancy` 数据迁移到 `sys_reservation`
3. 验证数据一致性

### Phase 2: 代码改造
1. 修改 `Reservation` 实体类，添加新字段
2. 在 `ReservationService` 中添加 `updatePresence()` 方法
3. 修改签到、暂离返回等调用点，使用新方法
4. 简化 `OccupancyMonitorService`，移除冗余逻辑

### Phase 3: 清理
1. 删除 `SeatOccupancy` 实体和 Mapper
2. 删除 `sys_seat_occupancy` 表（确认无误后）
3. 更新相关文档

## 收益

1. **减少数据表**：从2张表减少到1张表
2. **简化查询**：无需JOIN操作，单表查询即可
3. **减少代码量**：预计减少200-300行代码
4. **降低维护成本**：逻辑集中，易于理解和维护
5. **提高性能**：减少数据库查询次数

## 风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| 数据迁移不完整 | 中 | 迁移前备份，迁移后验证数据一致性 |
| 并发更新冲突 | 低 | 使用数据库乐观锁或分布式锁 |
| 功能回退 | 低 | 保留原表一段时间，确认无误后再删除 |

## 工作量估算

- 数据库迁移：2小时
- 代码改造：4小时
- 测试验证：3小时
- **总计：约1个工作日**

package com.library.seat.modules.reservation.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.library.seat.common.Result;
import com.library.seat.modules.reservation.entity.Appeal;
import com.library.seat.modules.reservation.entity.Reservation;
import com.library.seat.modules.reservation.mapper.AppealMapper;
import com.library.seat.modules.reservation.mapper.ReservationMapper;
import com.library.seat.modules.seat.entity.Seat;
import com.library.seat.modules.seat.service.SeatService;
import com.library.seat.modules.sys.entity.SysUser;
import com.library.seat.modules.sys.service.UserDetailsServiceImpl;

@Service
public class ReservationService extends ServiceImpl<ReservationMapper, Reservation> {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    @Autowired
    private SeatService seatService;

    @Autowired
    private AppealMapper appealMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private StatsService statsService;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private com.library.seat.modules.sys.service.ISysNotificationService notificationService;

    @Autowired
    private com.library.seat.modules.sys.service.SysLogService sysLogService;

    @Autowired
    private com.library.seat.modules.sys.service.ISysConfigService configService;

    @Autowired
    private com.library.seat.modules.occupancy.service.OccupancyMonitorService occupancyMonitorService;

    public com.library.seat.modules.sys.service.ISysNotificationService getNotificationService() {
        return notificationService;
    }

    public void broadcastReservationUpdate(Long userId, String event, String reason) {
        broadcastReservationUpdate(userId, event, reason, true);
    }

    public void broadcastReservationUpdate(Long userId, String event, String reason, boolean includeStats) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", event);
        payload.put("reason", reason);
        payload.put("timestamp", new Date());

        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/reservation_update",
                payload
        );
        
        // 同时广播公共状态更新（用于消息广场实时刷新座位信息）
        broadcastUserSeatStatus(userId);
        
        // 广播统计信息更新（用于首页实时刷新数据）
        if (includeStats) {
            statsService.broadcastStats();
        }
    }

    public void broadcastUserSeatStatus(Long userId) {
        SysUser user = userDetailsService.getById(userId);
        if (user == null) return;

        // 获取当前活跃预约
        Reservation activeRes = this.getOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .in(Reservation::getStatus, "reserved", "checked_in", "away")
                .last("LIMIT 1"));

        String seatNo = null;
        if (activeRes != null) {
            Seat seat = seatService.getById(activeRes.getSeatId());
            if (seat != null) {
                seatNo = seat.getSeatNo();
            }
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("username", user.getUsername());
        payload.put("seatNo", seatNo);
        payload.put("event", "user_seat_change");
        messagingTemplate.convertAndSend("/topic/user_seat_status", payload);
    }

    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> reserve(Reservation reservation) {
        String lockKey = "lock:seat:" + reservation.getSeatId();
        // 简单分布式锁 (SetNX) - 延长锁时间到30秒，防止批量预约时锁过期
        Boolean lock = redisTemplate.opsForValue().setIfAbsent(lockKey, String.valueOf(reservation.getUserId()), 30, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(lock)) {
            return Result.error("该座位正在被抢占，请稍后重试");
        }

        try {
            // 0. 检查用户信用分
            int minScore = configService.getIntValue("min_credit_score", 60);
            SysUser user = userDetailsService.getById(reservation.getUserId());
            if (user != null && user.getCreditScore() < minScore) {
                return Result.error("您的信用分低于 " + minScore + " 分，暂时无法预约。请通过申诉或联系管理员处理。");
            }

            // 1. 检查座位是否存在
            Seat seat = seatService.getById(reservation.getSeatId());
            if (seat == null || "maintenance".equals(seat.getStatus())) {
                return Result.error("座位不存在或正在维护中");
            }

            // 获取需要预约的时段列表
            List<String> slots = reservation.getSlots();
            if (slots == null || slots.isEmpty()) {
                if (reservation.getSlot() != null) {
                    slots = java.util.Collections.singletonList(reservation.getSlot());
                } else {
                    return Result.error("请选择预约时段");
                }
            }

            // 2. 预检查：验证所有时段是否都可用（避免部分预约成功）
            for (String slot : slots) {
                // 检查座位在该时段是否已被占用
                Long seatOccupied = this.baseMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                        .eq(Reservation::getSeatId, reservation.getSeatId())
                        .eq(Reservation::getSlot, slot)
                        .in(Reservation::getStatus, "reserved", "checked_in", "away"));
                if (seatOccupied > 0) {
                    return Result.error("座位在时段 " + slot + " 已被预约");
                }

                // 检查用户在该时段是否已有预约
                Long userReserved = this.baseMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                        .eq(Reservation::getUserId, reservation.getUserId())
                        .eq(Reservation::getSlot, slot)
                        .in(Reservation::getStatus, "reserved", "checked_in", "away"));
                if (userReserved > 0) {
                    return Result.error("您在时段 " + slot + " 已有预约，请勿重复预约");
                }
            }

            // 3. 创建预约 - 使用批量插入确保原子性
            Date now = new Date();
            List<Reservation> createdReservations = new java.util.ArrayList<>();
            
            // 先构建所有预约对象
            for (String slot : slots) {
                Reservation res = new Reservation();
                org.springframework.beans.BeanUtils.copyProperties(reservation, res);
                res.setSlot(slot);
                res.setStatus("reserved");
                if (res.getType() == null) {
                    res.setType("appointment");
                }
                
                // 计算该时段的起止时间
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTime(now);
                int year = cal.get(java.util.Calendar.YEAR);
                int month = cal.get(java.util.Calendar.MONTH);
                int day = cal.get(java.util.Calendar.DAY_OF_MONTH);
                
                java.util.Calendar startCal = java.util.Calendar.getInstance();
                startCal.set(year, month, day, 0, 0, 0);
                java.util.Calendar endCal = java.util.Calendar.getInstance();
                endCal.set(year, month, day, 0, 0, 0);
                
                switch (slot) {
                    case "morning":
                        startCal.set(java.util.Calendar.HOUR_OF_DAY, 8);
                        endCal.set(java.util.Calendar.HOUR_OF_DAY, 12);
                        break;
                    case "afternoon":
                        startCal.set(java.util.Calendar.HOUR_OF_DAY, 13);
                        endCal.set(java.util.Calendar.HOUR_OF_DAY, 17);
                        break;
                    case "evening":
                        startCal.set(java.util.Calendar.HOUR_OF_DAY, 18);
                        endCal.set(java.util.Calendar.HOUR_OF_DAY, 22);
                        break;
                }
                
                res.setStartTime(startCal.getTime());
                res.setEndTime(endCal.getTime());
                
                // 设置签到截止时间: 
                // 1. 标准截止时间: start_time + checkin_after_window
                // 2. 宽限截止时间: 如果当前预约时已过初始时段，确保有足够的签到时间 (late_reservation_grace_period)
                int checkInAfter = configService.getIntValue("checkin_after_window", 15);
                int gracePeriod = configService.getIntValue("late_reservation_grace_period", 5);
                
                long standardDeadline = res.getStartTime().getTime() + (long) checkInAfter * 60 * 1000;
                long graceDeadline = now.getTime() + (long) gracePeriod * 60 * 1000;
                
                res.setDeadline(new Date(Math.max(standardDeadline, graceDeadline)));
                res.setCreateTime(now);
                createdReservations.add(res);
            }
            
            // 批量保存所有预约 - 使用saveBatch确保原子性
            this.saveBatch(createdReservations);

            // 4. 更新座位实时状态 (如果当前有时段被占用，则设为 occupied)
            updateSeatRealtimeStatus(reservation.getSeatId());

            // 5. 广播更新
            broadcastReservationUpdate(reservation.getUserId(), "reservation_success", "预约成功");
            statsService.broadcastStats();

            // 6. 发送通知
            notificationService.send(reservation.getUserId(), "预约成功", "您已成功预约座位 " + seat.getSeatNo() + " (" + String.join(",", slots) + ")，请在规定时间内签到。", "success");

            // 7. 记录日志
            sysLogService.log(user.getUsername(), "批量预约座位", "座位号: " + seat.getSeatNo() + ", 时段: " + String.join(",", slots));

            // 返回第一个时段的信息用于前端倒计时
            Reservation first = createdReservations.get(0);
            Map<String, Object> result = new HashMap<>();
            result.put("id", first.getId());
            result.put("startTime", first.getStartTime());
            result.put("deadline", first.getDeadline());

            return Result.success(result);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private void updateSeatRealtimeStatus(Long seatId) {
        String currentSlot = getCurrentSlot();
        if (currentSlot == null) {
            seatService.updateStatus(seatId, "available");
            return;
        }

        Long count = this.baseMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSeatId, seatId)
                .eq(Reservation::getSlot, currentSlot)
                .in(Reservation::getStatus, "reserved", "checked_in", "away"));
        
        String newStatus = count > 0 ? "occupied" : "available";
        seatService.updateStatus(seatId, newStatus);
        seatService.broadcastSeatUpdate(seatId, newStatus);
    }

    private String getCurrentSlot() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (hour >= 8 && hour < 12) return "morning";
        if (hour >= 13 && hour < 17) return "afternoon";
        if (hour >= 18 && hour < 22) return "evening";
        return null;
    }

    private void autoCheckInNextSlots(Reservation current) {
        String nextSlot = getNextSlot(current.getSlot());
        if (nextSlot == null) return;

        Reservation nextRes = this.getOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, current.getUserId())
                .eq(Reservation::getSeatId, current.getSeatId())
                .eq(Reservation::getSlot, nextSlot)
                .eq(Reservation::getStatus, "reserved"));

        if (nextRes != null) {
            nextRes.setStatus("checked_in");
            nextRes.setDeadline(null);
            this.updateById(nextRes);
            // 递归处理下一个
            autoCheckInNextSlots(nextRes);
        }
    }

    private String getNextSlot(String slot) {
        if ("morning".equals(slot)) return "afternoon";
        if ("afternoon".equals(slot)) return "evening";
        return null;
    }

    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> checkIn(Long id, Long userId, Map<String, Object> params) {
        Reservation reservation = this.getById(id);

        if (reservation == null || !reservation.getUserId().equals(userId)) {
            return Result.error("预约记录不存在");
        }
        
        if (!"reserved".equals(reservation.getStatus()) && !"away".equals(reservation.getStatus())) {
            return Result.error("当前状态不可签到");
        }
        
        long nowTime = System.currentTimeMillis();
        long startTime = reservation.getStartTime().getTime();
        int beforeWindow = configService.getIntValue("checkin_before_window", 15);
        int afterWindow = configService.getIntValue("checkin_after_window", 15);

        // 1. 针对预约签到 (reserved -> checked_in)
        if ("reserved".equals(reservation.getStatus())) {
            if (nowTime < startTime - (long) beforeWindow * 60 * 1000) {
                return Result.error("尚未到达签到时间，请在起始时间前" + beforeWindow + "分钟内签到");
            }
            if (reservation.getDeadline() != null && nowTime > reservation.getDeadline().getTime()) {
                return Result.error("已超过签到截止时间");
            }
        }
        
        // 2. 针对暂离返回 (away -> checked_in)
        if ("away".equals(reservation.getStatus())) {
            if (reservation.getDeadline() != null && nowTime > reservation.getDeadline().getTime()) {
                 return Result.error("已超过暂离返回截止时间");
            }
        }

        // 校验位置和扫码 (必须同时满足)
        if (params != null) {
            boolean hasLocation = params.containsKey("lat") && params.containsKey("lng");
            boolean hasQrCode = params.containsKey("qrCode");

            if (!hasLocation || !hasQrCode) {
                return Result.error("签到失败：必须同时提供位置信息和座位二维码");
            }

            // 1. 定位校验
            double userLat = Double.parseDouble(params.get("lat").toString());
            double userLng = Double.parseDouble(params.get("lng").toString());
            double libLat = configService.getDoubleValue("library_latitude", 0);
            double libLng = configService.getDoubleValue("library_longitude", 0);
            
            if (libLat != 0 && libLng != 0) {
                double distance = calculateDistance(userLat, userLng, libLat, libLng);
                if (distance > 200) {
                    return Result.error(String.format("您距离图书馆太远 (%.1f米)，请到馆后再扫码签到", distance));
                }
            }

            // 2. 扫码校验
            String qrCode = params.get("qrCode").toString();
            Seat seat = seatService.getById(reservation.getSeatId());
            if (seat != null) {
                String expected = "Area:" + seat.getArea() + ",SeatNo:" + seat.getSeatNo();
                if (!expected.equals(qrCode)) {
                    return Result.error("二维码信息不匹配，请扫描正确的座位二维码");
                }
            }
        } else {
            return Result.error("请开启定位并扫码进行签到");
        }

        // 保存原始状态用于判断签到类型
        String originalStatus = reservation.getStatus();
        
        // 更新预约状态
        reservation.setStatus("checked_in");
        reservation.setDeadline(null); // 清除截止时间
        this.updateById(reservation);

        // 更新在场时间（用于占座检测）
        // 区分首次签到和暂离返回
        if ("away".equals(originalStatus)) {
            this.confirmPresence(reservation.getId(), com.library.seat.modules.reservation.enums.PresenceType.TEMP_RETURN);
        } else {
            this.confirmPresence(reservation.getId(), com.library.seat.modules.reservation.enums.PresenceType.CHECK_IN);
        }

        // 处理连续时段自动签到
        autoCheckInNextSlots(reservation);
        
        seatService.broadcastSeatUpdate(reservation.getSeatId(), "occupied");

        // 发送通知
        notificationService.send(userId, "签到成功", "您已成功签到，祝您学习愉快！", "success");

        // 记录日志
        SysUser user = userDetailsService.getById(userId);
        Seat seat = seatService.getById(reservation.getSeatId());
        if (user != null && seat != null) {
            sysLogService.log(user.getUsername(), "座位签到", "座位号: " + seat.getSeatNo());
        }

        return Result.success(true);
    }

    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6371000; // meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }
    
    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> leave(Long id, Long userId) {
        Reservation reservation = this.getById(id);

        if (reservation == null || !reservation.getUserId().equals(userId)) {
            return Result.error("预约记录不存在");
        }
        
        if (!"checked_in".equals(reservation.getStatus())) {
            return Result.error("当前状态不可暂离");
        }

        // 更新预约状态 (暂离)
        reservation.setStatus("away");
        // Update updateTime to track when leave started
        reservation.setUpdateTime(new Date());
        // 设置暂离返回截止时间: 当前时间 + 配置分钟数
        int violationTime = configService.getIntValue("violation_time", 30);
        reservation.setDeadline(new Date(System.currentTimeMillis() + (long) violationTime * 60 * 1000));
        this.updateById(reservation);

        // 暂离时不更新占座检测记录的lastDetectedTime，让占座检测能够计算离开时长
        // 用户暂离期间也会被占座检测监控

        return Result.success(true);
    }

    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> release(Long id, Long userId) {
        Reservation reservation = this.getById(id);

        if (reservation == null || !reservation.getUserId().equals(userId)) {
            return Result.error("预约记录不存在");
        }
        
        // 允许 release 的状态: reserved, checked_in, away
        if ("completed".equals(reservation.getStatus()) || "cancelled".equals(reservation.getStatus()) || "violation".equals(reservation.getStatus())) {
            return Result.error("当前状态无需释放");
        }

        String originalStatus = reservation.getStatus();
        long nowTime = System.currentTimeMillis();
        long startTime = reservation.getStartTime().getTime();
        long endTime = reservation.getEndTime().getTime();
        int bufferTime = configService.getIntValue("release_buffer_time", 15);
        int minUsageTime = configService.getIntValue("min_usage_time", 30); // 最少使用时长（分钟）

        // 检查退座规则1：未签到用户在起始时间 bufferTime 分钟内退座视为违约
        if ("reserved".equals(originalStatus)) {
            if (nowTime > startTime - (long) bufferTime * 60 * 1000) {
                // 视为违约
                reservation.setStatus("violation");
                reservation.setEndTime(new Date());
                this.updateById(reservation);

                // 释放座位
                Seat seat = seatService.getById(reservation.getSeatId());
                if (seat != null) {
                    seat.setStatus("available");
                    seatService.updateById(seat);
                    seatService.broadcastSeatUpdate(seat.getId(), "available");
                }

                // 扣除信用分 (-10)
                userDetailsService.deductCreditScore(userId, 10);

                // 发送通知
                notificationService.send(userId, "退座违约扣分", "由于您在预约起始时间" + bufferTime + "分钟内取消，已被视为违约并扣除信用分。", "error");
                
                return Result.success(true);
            }
        }

        // 检查退座规则2：已签到用户使用时长不足视为违约
        if ("checked_in".equals(originalStatus) || "away".equals(originalStatus)) {
            // 计算实际使用时长（从签到时间到退座时间）
            long actualUsageTime = (nowTime - startTime) / (1000 * 60); // 转换为分钟
            
            if (actualUsageTime < minUsageTime) {
                // 视为违约 - 恶意占座（签到后立即退座）
                reservation.setStatus("violation");
                reservation.setEndTime(new Date());
                this.updateById(reservation);

                // 释放座位
                Seat seat = seatService.getById(reservation.getSeatId());
                if (seat != null) {
                    seat.setStatus("available");
                    seatService.updateById(seat);
                    seatService.broadcastSeatUpdate(seat.getId(), "available");
                }

                // 扣除信用分 (-10)
                userDetailsService.deductCreditScore(userId, 10);

                // 发送通知
                notificationService.send(userId, "退座违约扣分", "由于您使用座位时长不足" + minUsageTime + "分钟，已被视为恶意占座并扣除信用分。", "error");
                
                // 记录日志
                SysUser user = userDetailsService.getById(userId);
                if (user != null && seat != null) {
                    sysLogService.log(user.getUsername(), "恶意占座违约", "座位号: " + seat.getSeatNo() + ", 使用时长: " + actualUsageTime + "分钟");
                }
                
                return Result.success(true);
            }
        }

        // 更新预约状态
        reservation.setStatus("completed");
        reservation.setEndTime(new Date()); // Actual end time
        this.updateById(reservation);

        // 释放座位
        Seat seat = seatService.getById(reservation.getSeatId());
        if (seat != null) {
            seat.setStatus("available");
            seatService.updateById(seat);
            seatService.broadcastSeatUpdate(seat.getId(), "available");
        }

        // 履约奖励: +2 信用分 (仅限已签到并正常结束的情况)
        if ("checked_in".equals(originalStatus) || "away".equals(originalStatus)) {
            userDetailsService.addCreditScore(userId, 2);
        }

        // 发送通知
        String notifyTitle = "reserved".equals(originalStatus) ? "取消预约成功" : "释放座位成功";
        String notifyMsg = "reserved".equals(originalStatus) ? "您的预约已取消，座位已释放。" : "您已成功结束座位使用，欢迎下次光临。";
        notificationService.send(userId, notifyTitle, notifyMsg, "info");

        // 记录日志
        SysUser user = userDetailsService.getById(userId);
        if (user != null && seat != null) {
            String op = "取消预约";
            if ("checked_in".equals(originalStatus) || "away".equals(originalStatus)) {
                op = "释放座位";
            }
            sysLogService.log(user.getUsername(), op, "座位号: " + seat.getSeatNo());
        }

        return Result.success(true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void terminateActiveReservationBySeat(Long seatId, String reason) {
        terminateActiveReservationBySeat(seatId, reason, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void terminateActiveReservationBySeat(Long seatId, String reason, boolean includeStats) {
        // 查找该座位下的活跃预约 (reserved, checked_in, away)
        Reservation reservation = this.getOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getSeatId, seatId)
                .in(Reservation::getStatus, "reserved", "checked_in", "away")
                .last("LIMIT 1"));

        if (reservation != null) {
            reservation.setStatus("completed");
            reservation.setEndTime(new Date());
            this.updateById(reservation);
            
            // 通知学生预约已结束
            broadcastReservationUpdate(reservation.getUserId(), "reservation_ended", reason, includeStats);

            // 发送系统通知
            notificationService.send(reservation.getUserId(), "预约被取消", "您预约的座位已被管理员取消，原因: " + reason, "warning");
        }
    }

    public Result<List<Reservation>> getMyHistory(Long userId) {
        List<Reservation> list = this.list(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .orderByDesc(Reservation::getCreateTime));
        
        // 补全座位号
        for (Reservation res : list) {
            Seat seat = seatService.getById(res.getSeatId());
            if (seat != null) res.setSeatNo(seat.getSeatNo());
        }
        
        return Result.success(list);
    }

    public Result<Reservation> getActiveReservation(Long userId) {
        Reservation res = this.getOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .in(Reservation::getStatus, "reserved", "checked_in", "away")
                .orderByAsc(Reservation::getStartTime)
                .last("LIMIT 1"));
        
        if (res != null) {
            Seat seat = seatService.getById(res.getSeatId());
            if (seat != null) res.setSeatNo(seat.getSeatNo());
        }
        
        return Result.success(res);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> appeal(Appeal appeal, Long userId) {
        // 检查是否已存在申诉
        Appeal existingAppeal = appealMapper.selectByReservationId(appeal.getReservationId());
        if (existingAppeal != null) {
            return Result.error("该预约已有申诉记录，请勿重复提交");
        }

        // 获取预约信息
        Reservation reservation = this.getById(appeal.getReservationId());
        if (reservation == null) {
            return Result.error("预约记录不存在");
        }

        // 验证预约是否属于当前用户
        if (!reservation.getUserId().equals(userId)) {
            return Result.error("无权申诉此预约");
        }

        // 只有违规状态的预约可以申诉
        if (!"violation".equals(reservation.getStatus())) {
            return Result.error("只有违规的预约可以申诉");
        }

        appeal.setUserId(userId);
        appeal.setCreateTime(new Date());
        appeal.setStatus("pending");
        appeal.setCreditReturned(0);
        appeal.setCreditAmount(0);
        appealMapper.insert(appeal);

        // 发送通知给管理员
        notificationService.send(userId, "申诉已提交", "您的违规申诉已提交，请等待管理员审核。", "info");

        // 记录日志
        SysUser user = userDetailsService.getById(userId);
        if (user != null) {
            sysLogService.log(user.getUsername(), "提交申诉", "预约ID: " + appeal.getReservationId() + ", 申诉类型: " + appeal.getAppealType());
        }

        return Result.success(true);
    }

    /**
     * 获取用户的申诉列表
     */
    public Result<List<Appeal>> getMyAppeals(Long userId) {
        List<Appeal> appeals = appealMapper.selectByUserId(userId);
        return Result.success(appeals);
    }

    /**
     * 获取所有申诉列表（管理员使用）
     */
    public Result<List<Map<String, Object>>> getAllAppeals() {
        List<Appeal> appeals = appealMapper.selectAllWithOrder();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Appeal appeal : appeals) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", appeal.getId());
            item.put("reservationId", appeal.getReservationId());
            item.put("userId", appeal.getUserId());
            item.put("appealType", appeal.getAppealType());
            item.put("reason", appeal.getReason());
            item.put("images", appeal.getImages());
            item.put("status", appeal.getStatus());
            item.put("reply", appeal.getReply());
            item.put("creditReturned", appeal.getCreditReturned());
            item.put("creditAmount", appeal.getCreditAmount());
            item.put("createTime", appeal.getCreateTime());
            item.put("updateTime", appeal.getUpdateTime());

            // 补充用户信息
            SysUser user = userDetailsService.getById(appeal.getUserId());
            if (user != null) {
                item.put("username", user.getUsername());
                item.put("realName", user.getRealName());
            }

            // 补充预约信息
            Reservation reservation = this.getById(appeal.getReservationId());
            if (reservation != null) {
                item.put("reservationStatus", reservation.getStatus());
                Seat seat = seatService.getById(reservation.getSeatId());
                if (seat != null) {
                    item.put("seatNo", seat.getSeatNo());
                }
            }

            result.add(item);
        }

        return Result.success(result);
    }

    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> forceRelease(Long id) {
        Reservation reservation = this.getById(id);
        if (reservation == null) {
            return Result.error("Reservation not found");
        }

        // Mark as completed (or maybe admin_release? spec says just release)
        // If it was already completed/violation, maybe do nothing?
        // Let's assume we force it to completed status and free seat.

        reservation.setStatus("completed");
        reservation.setEndTime(new Date());
        this.updateById(reservation);

        // Free seat
        Seat seat = seatService.getById(reservation.getSeatId());
        if (seat != null) {
            seat.setStatus("available");
            seatService.updateById(seat);
            seatService.broadcastSeatUpdate(seat.getId(), "available");
        }

        // 通知学生预约已结束 (解决前端卡顿)
        broadcastReservationUpdate(reservation.getUserId(), "reservation_ended", "admin_force_release");

        // 发送系统通知
        notificationService.send(reservation.getUserId(), "预约被取消", "您预约的座位已被管理员取消。", "warning");

        // 记录日志
        String adminUsername = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
        sysLogService.log(adminUsername, "强制释放座位", "座位号: " + seat.getSeatNo() + ", 原用户: " + reservation.getUserId());

        return Result.success(true);
    }

    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> reviewAppeal(Long id, String status, String reply) {
        Appeal appeal = appealMapper.selectById(id);
        if (appeal == null) {
            return Result.error("申诉记录不存在");
        }

        // 检查是否已审核过
        if (!"pending".equals(appeal.getStatus())) {
            return Result.error("该申诉已处理，请勿重复审核");
        }

        appeal.setStatus(status);
        appeal.setReply(reply);
        appeal.setUpdateTime(new Date());

        // 如果审核通过，返还信用分
        if ("approved".equals(status)) {
            Reservation reservation = this.getById(appeal.getReservationId());
            if (reservation != null && "violation".equals(reservation.getStatus())) {
                // 根据违规类型确定返还分数
                int creditToReturn = determineCreditReturnAmount(reservation);

                // 返还信用分
                userDetailsService.addCreditScore(appeal.getUserId(), creditToReturn);

                // 更新申诉记录
                appeal.setCreditReturned(1);
                appeal.setCreditAmount(creditToReturn);

                // 将预约状态改为 completed（撤销违规标记）
                reservation.setStatus("completed");
                this.updateById(reservation);

                // 发送通知
                notificationService.send(appeal.getUserId(), "申诉已通过", 
                        String.format("您的违规申诉已审核通过，返还%d信用分。", creditToReturn), "success");

                // 记录日志
                String adminUsername = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
                SysUser user = userDetailsService.getById(appeal.getUserId());
                sysLogService.log(adminUsername, "申诉审核通过", 
                        String.format("用户: %s, 预约ID: %d, 返还信用分: %d, 申诉类型: %s", 
                                user != null ? user.getUsername() : "未知", 
                                appeal.getReservationId(), 
                                creditToReturn,
                                appeal.getAppealType()));
            }
        } else {
            // 驳回申诉
            notificationService.send(appeal.getUserId(), "申诉被驳回", 
                    "您的违规申诉未通过审核，原因：" + reply, "error");

            // 记录日志
            String adminUsername = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
            SysUser user = userDetailsService.getById(appeal.getUserId());
            sysLogService.log(adminUsername, "申诉审核驳回", 
                    String.format("用户: %s, 预约ID: %d, 原因: %s", 
                            user != null ? user.getUsername() : "未知", 
                            appeal.getReservationId(), 
                            reply));
        }

        appealMapper.updateById(appeal);
        return Result.success(true);
    }

    /**
     * 根据预约信息确定应返还的信用分数
     */
    private int determineCreditReturnAmount(Reservation reservation) {
        // 默认返还10分
        int baseAmount = 10;

        // 如果是占座违规，返还15分
        // 通过检查是否有占座检测记录来判断
        // 这里简化处理，根据预约状态判断
        // 实际上可以通过 sys_seat_occupancy 表查询

        // 获取配置中的扣分标准
        int occupancyDeduct = configService.getIntValue("occupancy_credit_deduct", 15);
        int violationDeduct = 10; // 默认违约扣分

        // 如果返还分数应该等于当时扣除的分数
        // 这里简化处理，返回默认值
        return baseAmount;
    }

    /**
     * 统一在场确认入口（优化版）
     * 所有用户在场行为都调用此方法
     * 
     * @param reservationId 预约ID
     * @param type 在场确认类型
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmPresence(Long reservationId, com.library.seat.modules.reservation.enums.PresenceType type) {
        Reservation res = this.getById(reservationId);
        if (res == null) return;

        Date now = new Date();

        // 累加本次离开时长到总离开时长（用于统计用户行为）
        if (res.getLastPresentTime() != null) {
            long awayMillis = now.getTime() - res.getLastPresentTime().getTime();
            int awayMinutes = (int) (awayMillis / (1000 * 60));
            if (awayMinutes > 0) {
                Integer totalAway = res.getTotalAwayMinutes();
                if (totalAway == null) totalAway = 0;
                res.setTotalAwayMinutes(totalAway + awayMinutes);
            }
        }

        // 更新在场时间
        res.setLastPresentTime(now);
        
        // 重置预警状态（用户已确认在场，清除之前的预警标记）
        res.setOccupancyAlertSent(0);

        this.updateById(res);

        // 记录在场事件日志
        log.info("Presence confirmed: reservationId={}, type={}, userId={}", 
                reservationId, type, res.getUserId());
    }

    /**
     * 更新用户在场时间（兼容旧方法）
     * 在用户签到、暂离返回、扫码等操作时调用
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePresence(Long reservationId) {
        confirmPresence(reservationId, com.library.seat.modules.reservation.enums.PresenceType.CHECK_IN);
    }
}

package com.library.seat.modules.occupancy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.library.seat.modules.occupancy.entity.SeatOccupancy;
import com.library.seat.modules.occupancy.mapper.SeatOccupancyMapper;
import com.library.seat.modules.reservation.entity.Reservation;
import com.library.seat.modules.reservation.service.ReservationService;
import com.library.seat.modules.seat.entity.Seat;
import com.library.seat.modules.seat.service.SeatService;
import com.library.seat.modules.sys.entity.SysUser;
import com.library.seat.modules.sys.service.ISysConfigService;
import com.library.seat.modules.sys.service.ISysNotificationService;
import com.library.seat.modules.sys.service.SysLogService;
import com.library.seat.modules.sys.service.UserDetailsServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 占座检测服务（简化版）
 * 使用 sys_reservation 表的 last_present_time 字段替代独立的占座检测表
 */
@Service
public class OccupancyMonitorService extends ServiceImpl<SeatOccupancyMapper, SeatOccupancy> {

    private static final Logger log = LoggerFactory.getLogger(OccupancyMonitorService.class);

    @Autowired
    @org.springframework.context.annotation.Lazy
    private ReservationService reservationService;

    @Autowired
    private SeatService seatService;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private ISysNotificationService notificationService;

    @Autowired
    private SysLogService sysLogService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 执行占座检测（优化版 - 滑动窗口计算）
     * 直接使用 sys_reservation 表的 last_present_time 字段
     * 使用滑动窗口计算当前离开时长，而非累加
     */
    @Transactional(rollbackFor = Exception.class)
    public void performOccupancyCheck() {
        log.info("Starting occupancy check...");

        int occupancyThreshold = configService.getIntValue("occupancy_threshold", 60);
        int warningTime = configService.getIntValue("occupancy_warning_time", 45);
        Date now = new Date();

        // 获取所有 checked_in 和 away 状态的预约
        List<Reservation> activeReservations = reservationService.list(
                new LambdaQueryWrapper<Reservation>()
                        .in(Reservation::getStatus, "checked_in", "away")
                        .isNotNull(Reservation::getLastPresentTime)
        );

        for (Reservation reservation : activeReservations) {
            try {
                checkSingleReservation(reservation, now, occupancyThreshold, warningTime);
            } catch (Exception e) {
                log.error("Error checking reservation {}: {}", reservation.getId(), e.getMessage());
            }
        }

        log.info("Occupancy check completed. Checked {} reservations.", activeReservations.size());
    }

    /**
     * 检查单个预约的占座状态（优化版 - 滑动窗口）
     */
    private void checkSingleReservation(Reservation reservation, Date now,
                                       int occupancyThreshold, int warningTime) {
        // 滑动窗口计算：当前时间 - 最后在场时间
        long awayMillis = now.getTime() - reservation.getLastPresentTime().getTime();
        int awayMinutes = (int) (awayMillis / (1000 * 60));

        Integer alertSent = reservation.getOccupancyAlertSent();
        if (alertSent == null) alertSent = 0;

        if (awayMinutes >= occupancyThreshold) {
            // 占座判定 - 执行自动签退
            handleOccupancyViolation(reservation, awayMinutes);
        } else if (awayMinutes >= warningTime && alertSent == 0) {
            // 发送预警
            handleOccupancyWarning(reservation, awayMinutes, occupancyThreshold);
            reservation.setOccupancyAlertSent(1);
            reservationService.updateById(reservation);
        }
    }

    /**
     * 处理占座预警
     */
    private void handleOccupancyWarning(Reservation reservation, int awayMinutes, int threshold) {
        Long userId = reservation.getUserId();
        Seat seat = seatService.getById(reservation.getSeatId());
        String seatNo = seat != null ? seat.getSeatNo() : "未知座位";

        // 发送WebSocket预警
        Map<String, Object> warningMsg = new HashMap<>();
        warningMsg.put("type", "occupancy_warning");
        warningMsg.put("reservationId", reservation.getId());
        warningMsg.put("seatNo", seatNo);
        warningMsg.put("awayMinutes", awayMinutes);
        warningMsg.put("threshold", threshold);
        warningMsg.put("message", String.format("您已离开座位%d分钟，超过%d分钟将被视为占座并自动签退，请尽快返回！",
                awayMinutes, threshold));

        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/alerts",
                warningMsg
        );

        // 发送系统通知
        notificationService.send(userId, "占座预警",
                String.format("您预约的座位%s已离开%d分钟，请尽快返回，否则将被自动签退并扣分。",
                        seatNo, awayMinutes), "warning");

        log.info("Occupancy warning sent to user {} for reservation {}", userId, reservation.getId());
    }

    /**
     * 处理占座违规 - 自动签退
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleOccupancyViolation(Reservation reservation, int awayMinutes) {
        Long userId = reservation.getUserId();
        Long seatId = reservation.getSeatId();
        Seat seat = seatService.getById(seatId);
        String seatNo = seat != null ? seat.getSeatNo() : "未知座位";

        // 1. 更新预约状态为 violation
        reservation.setStatus("violation");
        reservation.setEndTime(new Date());
        reservationService.updateById(reservation);

        // 2. 释放座位
        if (seat != null) {
            seat.setStatus("available");
            seatService.updateById(seat);
            seatService.broadcastSeatUpdate(seatId, "available");
        }

        // 3. 扣除信用分
        int creditDeduct = configService.getIntValue("occupancy_credit_deduct", 15);
        userDetailsService.deductCreditScore(userId, creditDeduct);

        // 4. 发送WebSocket通知
        Map<String, Object> checkoutMsg = new HashMap<>();
        checkoutMsg.put("type", "auto_checkout");
        checkoutMsg.put("reservationId", reservation.getId());
        checkoutMsg.put("seatNo", seatNo);
        checkoutMsg.put("reason", "occupancy");
        checkoutMsg.put("creditDeducted", creditDeduct);

        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/alerts",
                checkoutMsg
        );

        // 5. 广播预约更新
        reservationService.broadcastReservationUpdate(userId, "reservation_ended", "occupancy_violation");

        // 6. 发送系统通知
        notificationService.send(userId, "占座违规自动签退",
                String.format("由于您长时间离开座位%s（超过占座阈值），系统已自动签退并扣除%d信用分。",
                        seatNo, creditDeduct), "error");

        // 7. 记录日志
        SysUser user = userDetailsService.getById(userId);
        if (user != null) {
            sysLogService.log("system", "占座违规自动签退",
                    String.format("用户: %s, 座位: %s, 离开时长: %d分钟, 扣分: %d",
                            user.getUsername(), seatNo, awayMinutes, creditDeduct));
        }

        log.info("Auto checkout executed for reservation {} due to occupancy violation", reservation.getId());
    }

    /**
     * 获取实时监控数据（简化版）
     * 直接从 sys_reservation 表查询
     */
    public List<Map<String, Object>> getMonitoringData() {
        List<Reservation> reservations = reservationService.list(
                new LambdaQueryWrapper<Reservation>()
                        .in(Reservation::getStatus, "checked_in", "away")
        );

        List<Map<String, Object>> result = new ArrayList<>();
        Date now = new Date();

        for (Reservation reservation : reservations) {
            Map<String, Object> item = new HashMap<>();
            item.put("reservationId", reservation.getId());
            item.put("userId", reservation.getUserId());
            item.put("seatId", reservation.getSeatId());
            item.put("checkInTime", reservation.getLastPresentTime());
            item.put("totalAwayMinutes", reservation.getTotalAwayMinutes());

            // 计算当前离开时长
            if (reservation.getLastPresentTime() != null) {
                long awayMillis = now.getTime() - reservation.getLastPresentTime().getTime();
                item.put("currentAwayMinutes", (int) (awayMillis / (1000 * 60)));
            } else {
                item.put("currentAwayMinutes", 0);
            }

            // 补充用户信息
            SysUser user = userDetailsService.getById(reservation.getUserId());
            if (user != null) {
                item.put("username", user.getUsername());
                item.put("realName", user.getRealName());
            }

            // 补充座位信息
            Seat seat = seatService.getById(reservation.getSeatId());
            if (seat != null) {
                item.put("seatNo", seat.getSeatNo());
                item.put("area", seat.getArea());
            }

            result.add(item);
        }

        return result;
    }

    /**
     * 手动触发签退（管理员使用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void manualCheckout(Long reservationId, String reason) {
        Reservation reservation = reservationService.getById(reservationId);
        if (reservation == null || !("checked_in".equals(reservation.getStatus()) || "away".equals(reservation.getStatus()))) {
            throw new RuntimeException("预约记录不存在或状态不正确");
        }

        // 更新预约状态
        reservation.setStatus("completed");
        reservation.setEndTime(new Date());
        reservationService.updateById(reservation);

        // 释放座位
        Seat seat = seatService.getById(reservation.getSeatId());
        if (seat != null) {
            seat.setStatus("available");
            seatService.updateById(seat);
            seatService.broadcastSeatUpdate(seat.getId(), "available");
        }

        // 发送通知
        reservationService.broadcastReservationUpdate(reservation.getUserId(), "reservation_ended", "manual_checkout");
        notificationService.send(reservation.getUserId(), "座位已释放",
                "您的座位已被管理员释放，原因：" + reason, "warning");

        log.info("Manual checkout executed for reservation {} by admin, reason: {}", reservationId, reason);
    }

    /**
     * 闭馆自动签退
     */
    @Transactional(rollbackFor = Exception.class)
    public void autoCheckoutAtClosing() {
        log.info("Executing auto checkout at closing time...");

        // 获取所有 checked_in 和 away 状态的预约
        List<Reservation> activeReservations = reservationService.list(
                new LambdaQueryWrapper<Reservation>()
                        .in(Reservation::getStatus, "checked_in", "away")
        );

        for (Reservation reservation : activeReservations) {
            try {
                // 更新预约状态为 completed
                reservation.setStatus("completed");
                reservation.setEndTime(new Date());
                reservationService.updateById(reservation);

                // 释放座位
                Seat seat = seatService.getById(reservation.getSeatId());
                if (seat != null) {
                    seat.setStatus("available");
                    seatService.updateById(seat);
                    seatService.broadcastSeatUpdate(seat.getId(), "available");
                }

                // 发送通知
                reservationService.broadcastReservationUpdate(reservation.getUserId(), "reservation_ended", "closing_time");
                notificationService.send(reservation.getUserId(), "闭馆自动签退",
                        "图书馆即将闭馆，您的座位已自动释放，欢迎下次光临！", "info");

                log.info("Auto checkout at closing for reservation {}", reservation.getId());
            } catch (Exception e) {
                log.error("Error processing closing checkout for reservation {}: {}",
                        reservation.getId(), e.getMessage());
            }
        }

        log.info("Auto checkout at closing completed. Processed {} reservations.", activeReservations.size());
    }

    /**
     * 发送闭馆提醒
     */
    public void sendClosingReminder() {
        log.info("Sending closing reminder...");

        List<Reservation> activeReservations = reservationService.list(
                new LambdaQueryWrapper<Reservation>()
                        .in(Reservation::getStatus, "checked_in", "away")
        );

        for (Reservation reservation : activeReservations) {
            notificationService.send(reservation.getUserId(), "闭馆提醒",
                    "图书馆将在30分钟后闭馆，请合理安排学习时间，及时带走个人物品。", "warning");
        }

        log.info("Closing reminder sent to {} users.", activeReservations.size());
    }
}

package com.library.seat.modules.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.library.seat.modules.occupancy.service.OccupancyMonitorService;
import com.library.seat.modules.reservation.entity.Reservation;
import com.library.seat.modules.reservation.service.ReservationService;
import com.library.seat.modules.seat.entity.Seat;
import com.library.seat.modules.seat.service.SeatService;
import com.library.seat.modules.sys.service.UserDetailsServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReservationJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationJob.class);

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private SeatService seatService;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private com.library.seat.modules.sys.service.SysLogService sysLogService;

    @Autowired
    private OccupancyMonitorService occupancyMonitorService;

    private void sendUserAlert(Long userId, String message) {
        // Send to specific user: /user/{userId}/queue/alerts
        // Note: Client needs to subscribe to /user/queue/alerts
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/alerts",
                message
        );
    }

    /**
     * 每分钟检查违约记录及临近到期提醒
     */
    @Scheduled(cron = "0 * * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void checkViolation() {
        log.info("Executing Violation Check Job...");

        Date now = new Date();
        Date soon = new Date(System.currentTimeMillis() + 5 * 60 * 1000); // 5 mins later

        // 1. Check 15-min no-show (reserved -> violation)
        List<Reservation> noShows = reservationService.list(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, "reserved")
                .lt(Reservation::getDeadline, now));

        for (Reservation res : noShows) {
            handleViolation(res, "No-show violation");
        }

        // 2. Check 30-min away (away -> violation)
        List<Reservation> timeOuts = reservationService.list(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, "away")
                .lt(Reservation::getDeadline, now));

        for (Reservation res : timeOuts) {
            handleViolation(res, "Away timeout violation");
        }

        // 3. Proactive Reminders (5 mins before deadline)
        List<Reservation> soonToExpire = reservationService.list(new LambdaQueryWrapper<Reservation>()
                .in(Reservation::getStatus, "reserved", "away")
                .gt(Reservation::getDeadline, now)
                .lt(Reservation::getDeadline, soon));

        for (Reservation res : soonToExpire) {
            String msg = res.getStatus().equals("reserved") ?
                "您的预约即将在5分钟内过期，请尽快签到！" :
                "您的暂离时间即将在5分钟内过期，请尽快返回签到！";
            sendUserAlert(res.getUserId(), msg);
        }
    }

    private void handleViolation(Reservation res, String reason) {
        log.info("Violation found: id={}, reason={}", res.getId(), reason);

        // Mark as violation
        res.setStatus("violation");
        reservationService.updateById(res);

        // Free seat
        Seat seat = seatService.getById(res.getSeatId());
        if (seat != null) {
            seat.setStatus("available");
            seatService.updateById(seat);
            seatService.broadcastSeatUpdate(seat.getId(), "available");
        }

        // Deduct credit score (-10)
        userDetailsService.deductCreditScore(res.getUserId(), 10);

        // Notify user via alert
        sendUserAlert(res.getUserId(), "您的预约因违规已取消: " + reason);

        // 发送系统通知
        reservationService.getNotificationService().send(res.getUserId(), "违规取消通知", "您预约的座位因超时未签到或未返回已被自动释放，信用分已扣除。", "error");

        // 通知前端清理状态 (解决卡顿)
        reservationService.broadcastReservationUpdate(res.getUserId(), "reservation_ended", "violation");

        // 记录日志
        com.library.seat.modules.sys.entity.SysUser user = userDetailsService.getById(res.getUserId());
        if (user != null && seat != null) {
            sysLogService.log("system", "预约违规", "用户: " + user.getUsername() + ", 座位: " + seat.getSeatNo() + ", 原因: " + reason);
        }
    }

    /**
     * 每分钟检查已完成的预约 (endTime < now)
     */
    @Scheduled(cron = "0 * * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void checkExpiration() {
        log.info("Executing Expiration Check Job...");

        Date now = new Date();

        // Find checked_in reservations that have ended
        List<Reservation> expired = reservationService.list(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, "checked_in")
                .lt(Reservation::getEndTime, now));

        for (Reservation res : expired) {
            log.info("Expiration found: reservationId={}", res.getId());

            // Mark completed
            res.setStatus("completed");
            reservationService.updateById(res);

            // Free seat
            Seat seat = seatService.getById(res.getSeatId());
            if (seat != null) {
                seat.setStatus("available");
                seatService.updateById(seat);
                seatService.broadcastSeatUpdate(seat.getId(), "available");
            }

            // 通知前端清理状态
            reservationService.broadcastReservationUpdate(res.getUserId(), "reservation_ended", "expired");
        }
    }

    /**
     * 每5分钟执行占座检测
     */
    @Scheduled(cron = "0 */5 * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void checkOccupancy() {
        log.info("Executing Occupancy Check Job...");
        occupancyMonitorService.performOccupancyCheck();
    }

    /**
     * 每日21:30发送闭馆提醒
     */
    @Scheduled(cron = "0 30 21 * * ?")
    public void sendClosingReminder() {
        log.info("Executing Closing Reminder Job...");
        occupancyMonitorService.sendClosingReminder();
    }

    /**
     * 每日22:00执行闭馆自动签退
     */
    @Scheduled(cron = "0 0 22 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void autoCheckoutAtClosing() {
        log.info("Executing Auto Checkout at Closing Job...");
        occupancyMonitorService.autoCheckoutAtClosing();
    }
}

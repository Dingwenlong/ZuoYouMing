package com.library.seat.modules.reservation.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.seat.common.Result;
import com.library.seat.modules.reservation.entity.Appeal;
import com.library.seat.modules.reservation.entity.Reservation;
import com.library.seat.modules.reservation.service.ReservationService;
import com.library.seat.modules.sys.entity.SysUser;
import com.library.seat.modules.sys.service.UserDetailsServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

@Tag(name = "预约业务模块", description = "预约、签到、暂离、退座、申诉")
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private static final Logger log = LoggerFactory.getLogger(ReservationController.class);

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            log.warn("Current user is not authenticated");
            return null;
        }
        String username = auth.getName();
        SysUser user = userDetailsService.lambdaQuery().eq(SysUser::getUsername, username).one();
        return user != null ? user.getId() : null;
    }

    @Operation(summary = "创建预约")
    @PostMapping
    public Result<Map<String, Object>> reserve(@RequestBody Reservation reservation) {
        log.info("Received reservation request: {}", reservation);
        try {
            Long userId = getCurrentUserId();
            if (userId == null) {
                return Result.error("用户未登录或Token失效");
            }
            reservation.setUserId(userId);
            return (Result<Map<String, Object>>) (Result<?>) reservationService.reserve(reservation);
        } catch (Exception e) {
            log.error("Failed to create reservation", e);
            throw e; // Let global exception handler process it, or return Result.error
        }
    }

    @Operation(summary = "签到")
    @PostMapping("/{id}/check-in")
    public Result<Boolean> checkIn(@PathVariable Long id, @RequestBody(required = false) java.util.Map<String, Object> params) {
        return reservationService.checkIn(id, getCurrentUserId(), params);
    }

    @Operation(summary = "暂离")
    @PostMapping("/{id}/leave")
    public Result<Boolean> leave(@PathVariable Long id) {
        return reservationService.leave(id, getCurrentUserId());
    }

    @Operation(summary = "主动退座")
    @PostMapping("/{id}/release")
    public Result<Boolean> release(@PathVariable Long id) {
        return reservationService.release(id, getCurrentUserId());
    }

    @Operation(summary = "获取当前用户的历史记录")
    @GetMapping("/my-history")
    public Result<List<Reservation>> getMyHistory() {
        return reservationService.getMyHistory(getCurrentUserId());
    }

    @Operation(summary = "获取当前用户的活跃预约")
    @GetMapping("/active")
    public Result<Reservation> getActive() {
        return reservationService.getActiveReservation(getCurrentUserId());
    }

    @Operation(summary = "提交违规申诉")
    @PostMapping("/{id}/appeal")
    public Result<Boolean> appeal(@PathVariable Long id, @RequestBody Appeal appeal) {
        appeal.setReservationId(id);
        return reservationService.appeal(appeal, getCurrentUserId());
    }

    @Operation(summary = "获取我的申诉列表")
    @GetMapping("/my-appeals")
    public Result<List<Appeal>> getMyAppeals() {
        return reservationService.getMyAppeals(getCurrentUserId());
    }

    @Operation(summary = "获取所有申诉列表（管理员）")
    @GetMapping("/appeals")
    public Result<List<Map<String, Object>>> getAllAppeals() {
        return reservationService.getAllAppeals();
    }

    @Operation(summary = "管理员强制释放座位")
    @PostMapping("/{id}/force-release")
    public Result<Boolean> forceRelease(@PathVariable Long id) {
        // 检查当前用户是否为管理员
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error("用户未登录或Token失效");
        }
        
        SysUser user = userDetailsService.getById(userId);
        if (user == null || (!"admin".equals(user.getRole()) && !"librarian".equals(user.getRole()))) {
            return Result.error("无权操作，仅管理员或图书馆员可强制释放座位");
        }
        
        return reservationService.forceRelease(id);
    }

    @Operation(summary = "管理员处理申诉")
    @PostMapping("/appeals/{id}/review")
    public Result<Boolean> reviewAppeal(
            @PathVariable Long id,
            @RequestBody Map<String, String> params) {
        // 检查当前用户是否为管理员
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error("用户未登录或Token失效");
        }
        
        SysUser user = userDetailsService.getById(userId);
        if (user == null || (!"admin".equals(user.getRole()) && !"librarian".equals(user.getRole()))) {
            return Result.error("无权操作，仅管理员或图书馆员可处理申诉");
        }
        
        // params: status (approved/rejected), reply
        String status = params.get("status");
        String reply = params.get("reply");
        return reservationService.reviewAppeal(id, status, reply);
    }
}

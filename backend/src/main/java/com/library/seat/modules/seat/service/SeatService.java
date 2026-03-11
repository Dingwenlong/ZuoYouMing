package com.library.seat.modules.seat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.library.seat.common.Result;
import com.library.seat.modules.seat.entity.Seat;
import com.library.seat.modules.seat.mapper.SeatMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class SeatService extends ServiceImpl<SeatMapper, Seat> {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.library.seat.modules.reservation.service.ReservationService reservationService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private com.library.seat.modules.reservation.service.StatsService statsService;

    public void broadcastSeatUpdate(Long seatId, String status) {
        Map<String, Object> message = new HashMap<>();
        message.put("id", seatId); // Change to 'id' to match frontend handleSeatUpdate
        message.put("status", status);
        message.put("event", "seat_update");
        messagingTemplate.convertAndSend("/topic/seats", message);
    }

    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> addSeat(Seat seat) {
        // Unique check
        long count = this.count(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getSeatNo, seat.getSeatNo())
                .eq(Seat::getDeleted, 0));
        
        if (count > 0) {
            return Result.error("座位号已存在");
        }
        
        seat.setCreateTime(new Date());
        seat.setDeleted(0);
        if (seat.getStatus() == null) {
            seat.setStatus("available");
        }
        
        return Result.success(this.save(seat));
    }

    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> updateSeat(Seat seat) {
        Seat oldSeat = this.getById(seat.getId());
        if (oldSeat == null) {
            return Result.error("座位不存在");
        }
        
        // Unique check if seatNo changed
        if (seat.getSeatNo() != null && !seat.getSeatNo().equals(oldSeat.getSeatNo())) {
            long count = this.count(new LambdaQueryWrapper<Seat>()
                    .eq(Seat::getSeatNo, seat.getSeatNo())
                    .eq(Seat::getDeleted, 0));
            if (count > 0) {
                return Result.error("座位号已存在");
            }
        }
        
        seat.setUpdateTime(new Date());
        return Result.success(this.updateById(seat));
    }

    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> deleteSeat(Long id) {
        Seat seat = this.getById(id);
        if (seat == null) {
            return Result.error("座位不存在");
        }
        
        if (!"available".equals(seat.getStatus()) && !"maintenance".equals(seat.getStatus())) {
            return Result.error("座位正在使用中，无法删除");
        }
        
        seat.setDeleted(1);
        seat.setUpdateTime(new Date());
        return Result.success(this.updateById(seat));
    }
    
    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> batchDelete(List<Long> ids) {
        for (Long id : ids) {
            Result<Boolean> res = deleteSeat(id);
            if (!res.isSuccess()) {
                throw new RuntimeException("删除失败: ID=" + id + ", " + res.getMsg());
            }
        }
        return Result.success(true);
    }
    
    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> batchImport(List<Seat> seats) {
        for (Seat seat : seats) {
            Result<Boolean> res = addSeat(seat);
            if (!res.isSuccess()) {
                 // Or skip? For now, fail fast
                 throw new RuntimeException("导入失败: " + seat.getSeatNo() + ", " + res.getMsg());
            }
        }
        return Result.success(true);
    }

    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> deleteAll() {
        // 1. 先清理所有活跃预约
        List<Seat> activeSeats = this.list(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getDeleted, 0)
                .eq(Seat::getStatus, "occupied"));
        
        for (Seat seat : activeSeats) {
            reservationService.terminateActiveReservationBySeat(seat.getId(), "admin_clear_all_seats", false);
        }

        // 2. 逻辑删除所有座位
        boolean success = this.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Seat>()
                .set(Seat::getDeleted, 1)
                .set(Seat::getUpdateTime, new Date())
                .eq(Seat::getDeleted, 0));
        
        // 3. 广播统计信息更新 (只广播一次)
        statsService.broadcastStats();
        
        return Result.success(success);
    }

    public List<Seat> listWithSlotStatus(LambdaQueryWrapper<Seat> queryWrapper) {
        List<Seat> seats = this.list(queryWrapper);
        if (seats.isEmpty()) return seats;

        List<Long> seatIds = seats.stream()
                .map(Seat::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        java.time.LocalDate today = java.time.LocalDate.now();
        java.util.Date startOfDay = java.util.Date.from(today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        java.util.Date endOfDay = java.util.Date.from(today.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());

        List<com.library.seat.modules.reservation.entity.Reservation> reservations = reservationService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.library.seat.modules.reservation.entity.Reservation>()
                .in(com.library.seat.modules.reservation.entity.Reservation::getSeatId, seatIds)
                .in(com.library.seat.modules.reservation.entity.Reservation::getStatus, "reserved", "checked_in", "away")
                .ge(com.library.seat.modules.reservation.entity.Reservation::getStartTime, startOfDay)
                .lt(com.library.seat.modules.reservation.entity.Reservation::getStartTime, endOfDay)
                .eq(com.library.seat.modules.reservation.entity.Reservation::getDeleted, 0));

        // 建立 座位ID -> 时段 -> 状态 的映射
        Map<Long, Map<String, String>> seatSlotMap = new HashMap<>();
        for (com.library.seat.modules.reservation.entity.Reservation res : reservations) {
            seatSlotMap.computeIfAbsent(res.getSeatId(), k -> new HashMap<>())
                    .put(res.getSlot(), res.getStatus());
        }

        for (Seat seat : seats) {
            Map<String, String> slotStatuses = new HashMap<>();
            Map<String, String> reservedSlots = seatSlotMap.getOrDefault(seat.getId(), new HashMap<>());
            
            slotStatuses.put("morning", reservedSlots.getOrDefault("morning", "available"));
            slotStatuses.put("afternoon", reservedSlots.getOrDefault("afternoon", "available"));
            slotStatuses.put("evening", reservedSlots.getOrDefault("evening", "available"));
            
            // 如果座位本身在维护，则所有时段都是 maintenance
            if ("maintenance".equals(seat.getStatus())) {
                slotStatuses.put("morning", "maintenance");
                slotStatuses.put("afternoon", "maintenance");
                slotStatuses.put("evening", "maintenance");
            }
            
            seat.setSlotStatuses(slotStatuses);
        }
        return seats;
    }

    public void updateStatus(Long seatId, String status) {
        this.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Seat>()
                .set(Seat::getStatus, status)
                .eq(Seat::getId, seatId));
    }
}

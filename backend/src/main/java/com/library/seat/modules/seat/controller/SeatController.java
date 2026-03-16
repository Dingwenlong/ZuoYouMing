package com.library.seat.modules.seat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.library.seat.common.Result;
import com.library.seat.modules.reservation.service.ReservationService;
import com.library.seat.modules.seat.entity.Seat;
import com.library.seat.modules.seat.service.SeatService;
import com.library.seat.modules.sys.service.SysLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "座位管理", description = "座位信息管理接口")
@RestController
@RequestMapping("/api/v1/seats")
public class SeatController {

    @Autowired
    private SeatService seatService;
    
    @Autowired
    private ReservationService reservationService;
    
    @Autowired
    private SysLogService sysLogService;

    private String getCurrentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @Operation(summary = "获取所有座位列表", description = "支持区域筛选")
    @GetMapping
    public Result<List<Seat>> list(@Parameter(description = "区域 (A区/B区...)", required = false) @RequestParam(required = false) String area) {
        return Result.success(seatService.listWithSlotStatus(new LambdaQueryWrapper<Seat>()
                .eq(area != null, Seat::getArea, area)
                .eq(Seat::getDeleted, 0)));
    }

    @Operation(summary = "获取座位地图数据", description = "包含坐标信息的座位列表")
    @GetMapping("/map")
    public Result<List<Seat>> getMap() {
        return Result.success(seatService.listWithSlotStatus(new LambdaQueryWrapper<Seat>()
                .eq(Seat::getDeleted, 0)));
    }

    @Operation(summary = "新增座位")
    @PostMapping
    @PreAuthorize("hasAnyAuthority('admin', 'librarian')")
    public Result<Boolean> add(@RequestBody Seat seat) {
        Result<Boolean> res = seatService.addSeat(seat);
        if (res.isSuccess()) {
            sysLogService.log(getCurrentUsername(), "新增座位", "座位号: " + seat.getSeatNo());
        }
        return res;
    }

    @Operation(summary = "修改座位信息")
    @PutMapping
    @PreAuthorize("hasAnyAuthority('admin', 'librarian')")
    public Result<Boolean> update(@RequestBody Seat seat) {
        Seat oldSeat = seatService.getById(seat.getId());
        Result<Boolean> res = seatService.updateSeat(seat);
        if (res.isSuccess()) {
            String detail = String.format("座位ID: %d", seat.getId());
            if (oldSeat != null && !oldSeat.getStatus().equals(seat.getStatus())) {
                detail = String.format("座位号: %s, 状态变更: %s -> %s", 
                        oldSeat.getSeatNo(), oldSeat.getStatus(), seat.getStatus());
            }
            sysLogService.log(getCurrentUsername(), "修改座位", detail);
            
            // 如果座位从 occupied 变为其他状态，清理预约
            if (oldSeat != null && seat.getStatus() != null && !"occupied".equals(seat.getStatus()) && !seat.getStatus().equals(oldSeat.getStatus())) {
                reservationService.terminateActiveReservationBySeat(seat.getId(), "admin_force_release");
            }
            
            // 广播通知
            seatService.broadcastSeatUpdate(seat.getId(), seat.getStatus());
        }
        return res;
    }

    @Operation(summary = "删除座位")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('admin', 'librarian')")
    public Result<Boolean> delete(@PathVariable Long id) {
        Result<Boolean> res = seatService.deleteSeat(id);
        if (res.isSuccess()) {
            sysLogService.log(getCurrentUsername(), "删除座位", "座位ID: " + id);
            
            // 删除时也清理预约
            reservationService.terminateActiveReservationBySeat(id, "admin_delete_seat");
            
            // 广播通知 (状态设为 null 或其他标识已删除)
            seatService.broadcastSeatUpdate(id, "deleted");
        }
        return res;
    }

    @Operation(summary = "批量删除座位")
    @PostMapping("/batch/delete")
    @PreAuthorize("hasAnyAuthority('admin', 'librarian')")
    public Result<Boolean> batchDelete(@RequestBody List<Long> ids) {
        Result<Boolean> res = seatService.batchDelete(ids);
        if (res.isSuccess()) {
            sysLogService.log(getCurrentUsername(), "批量删除座位", "ID列表: " + ids.toString());
        }
        return res;
    }

    @Operation(summary = "批量导入座位")
    @PostMapping("/batch/import")
    @PreAuthorize("hasAnyAuthority('admin', 'librarian')")
    public Result<Boolean> batchImport(@RequestBody List<Seat> seats) {
        Result<Boolean> res = seatService.batchImport(seats);
        if (res.isSuccess()) {
            sysLogService.log(getCurrentUsername(), "批量导入座位", "数量: " + seats.size());
        }
        return res;
    }

    @Operation(summary = "清空所有座位")
    @DeleteMapping("/all")
    @PreAuthorize("hasAuthority('admin')")
    public Result<Boolean> deleteAll() {
        Result<Boolean> res = seatService.deleteAll();
        if (res.isSuccess()) {
            sysLogService.log(getCurrentUsername(), "清空所有座位", "操作执行成功");
        }
        return res;
    }

    @Operation(summary = "管理员修改座位状态", description = "强制释放/设为故障")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('admin', 'librarian')")
    public Result<Boolean> updateStatus(
            @PathVariable Long id, 
            @Parameter(description = "状态: available, occupied, maintenance") @RequestBody Map<String, String> params) {
        
        String status = params.get("status");
        if (status == null) {
            return Result.error("Status is required");
        }
        
        Seat seat = seatService.getById(id);
        if (seat == null) {
            return Result.error("Seat not found");
        }
        
        String oldStatus = seat.getStatus();
        seat.setStatus(status);
        boolean success = seatService.updateById(seat);
        
        if (success) {
             sysLogService.log(getCurrentUsername(), "修改座位状态", 
                     String.format("ID: %d, %s -> %s", id, oldStatus, status));
             
             // 强制释放或设为故障时，结束该座位的所有活跃预约。
             if (!"occupied".equals(status)) {
                 String reason = "maintenance".equals(status) ? "admin_mark_maintenance" : "admin_force_release";
                 reservationService.terminateActiveReservationBySeat(id, reason);
             }
             
             seatService.broadcastSeatUpdate(id, status);
        }
        
        return Result.success(success);
    }
}

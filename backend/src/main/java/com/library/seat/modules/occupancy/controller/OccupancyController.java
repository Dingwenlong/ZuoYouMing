package com.library.seat.modules.occupancy.controller;

import com.library.seat.common.Result;
import com.library.seat.modules.occupancy.service.OccupancyMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "占座监控模块", description = "占座检测、自动签退、监控数据")
@RestController
@RequestMapping("/api/v1/occupancy")
public class OccupancyController {

    @Autowired
    private OccupancyMonitorService occupancyMonitorService;

    @Operation(summary = "获取实时监控数据")
    @GetMapping("/monitoring")
    public Result<List<Map<String, Object>>> getMonitoringData() {
        return Result.success(occupancyMonitorService.getMonitoringData());
    }

    @Operation(summary = "手动触发签退")
    @PostMapping("/{reservationId}/checkout")
    public Result<Boolean> manualCheckout(
            @PathVariable Long reservationId,
            @RequestBody Map<String, String> params) {
        String reason = params.getOrDefault("reason", "管理员手动释放");
        occupancyMonitorService.manualCheckout(reservationId, reason);
        return Result.success(true);
    }

    @Operation(summary = "立即执行占座检测（调试用）")
    @PostMapping("/check-now")
    public Result<Boolean> performCheckNow() {
        occupancyMonitorService.performOccupancyCheck();
        return Result.success(true);
    }
}

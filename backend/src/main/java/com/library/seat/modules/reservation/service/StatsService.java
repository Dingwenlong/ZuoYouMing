package com.library.seat.modules.reservation.service;

import com.library.seat.modules.reservation.entity.Reservation;
import com.library.seat.modules.reservation.service.ReservationService;
import com.library.seat.modules.seat.entity.Seat;
import com.library.seat.modules.seat.service.SeatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StatsService {

    @Autowired
    private com.library.seat.modules.seat.service.SeatService seatService;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private ReservationService reservationService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    public void broadcastStats() {
        messagingTemplate.convertAndSend("/topic/stats", getDashboardStats());
    }

    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // 1. 座位状态统计
        List<com.library.seat.modules.seat.entity.Seat> allSeats = seatService.list();
        long total = allSeats.size();
        long available = allSeats.stream().filter(s -> "available".equals(s.getStatus())).count();
        long occupied = allSeats.stream().filter(s -> "occupied".equals(s.getStatus())).count();
        long maintenance = allSeats.stream().filter(s -> "maintenance".equals(s.getStatus())).count();
        
        stats.put("totalSeats", total);
        stats.put("available", available);
        stats.put("occupied", occupied);
        stats.put("maintenance", maintenance);
        
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate rangeStart = today.minusDays(6);
        java.util.Date startTime = java.util.Date.from(rangeStart.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        java.util.Date endTime = java.util.Date.from(today.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());

        List<Reservation> recentReservations = reservationService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Reservation>()
                        .select(Reservation::getUserId, Reservation::getCreateTime)
                        .ge(Reservation::getCreateTime, startTime)
                        .lt(Reservation::getCreateTime, endTime)
                        .eq(Reservation::getDeleted, 0));

        Map<java.time.LocalDate, Set<Long>> distinctUsersByDay = new HashMap<>();
        for (Reservation reservation : recentReservations) {
            if (reservation.getUserId() == null || reservation.getCreateTime() == null) {
                continue;
            }
            java.time.LocalDate day = reservation.getCreateTime()
                    .toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();
            distinctUsersByDay.computeIfAbsent(day, key -> new HashSet<>()).add(reservation.getUserId());
        }

        stats.put("todayReservations", distinctUsersByDay.getOrDefault(today, Collections.emptySet()).size());

        List<String> dates = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            java.time.LocalDate d = today.minusDays(i);
            dates.add(d.toString());
            counts.add((long) distinctUsersByDay.getOrDefault(d, Collections.emptySet()).size());
        }
        
        Map<String, Object> trend = new HashMap<>();
        trend.put("dates", dates);
        trend.put("counts", counts);
        stats.put("trend", trend);

        return stats;
    }

    public Map<String, Object> getHeatmapDataWrapper(String dateStr, boolean simulate) {
        // 1. 获取所有实际存在的区域
        List<Seat> allSeats = seatService.list(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Seat>()
                .eq(Seat::getDeleted, 0));
        List<String> areas = allSeats.stream()
                .map(Seat::getArea)
                .filter(a -> a != null && !a.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        
        if (areas.isEmpty()) {
            areas = Arrays.asList("A区", "B区", "C区", "D区", "E区");
        }

        List<Object[]> data = getHeatmapData(dateStr, simulate);
        
        Map<String, Object> result = new HashMap<>();
        result.put("data", data);
        result.put("areas", areas);
        return result;
    }

    public List<Object[]> getHeatmapData(String dateStr, boolean simulate) {
        // 1. 获取所有实际存在的区域
        List<Seat> allSeats = seatService.list(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Seat>()
                .eq(Seat::getDeleted, 0));
        List<String> areas = allSeats.stream()
                .map(Seat::getArea)
                .filter(a -> a != null && !a.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        
        // 如果没有数据，使用默认区域
        if (areas.isEmpty()) {
            areas = Arrays.asList("A区", "B区", "C区", "D区", "E区");
        }

        int[] hourThresholds = {8, 10, 12, 14, 16, 18, 20, 22};
        List<Object[]> heatmap = new ArrayList<>();
        
        java.time.LocalDate targetDate;
        if (dateStr == null || dateStr.isEmpty()) {
            targetDate = java.time.LocalDate.now();
        } else {
            try {
                targetDate = java.time.LocalDate.parse(dateStr);
            } catch (Exception e) {
                targetDate = java.time.LocalDate.now();
            }
        }

        // 2. 统计选定日期的预约数据
        java.time.LocalDateTime startOfDay = targetDate.atStartOfDay();
        java.time.LocalDateTime endOfDay = targetDate.plusDays(1).atStartOfDay();
        
        List<com.library.seat.modules.reservation.entity.Reservation> reservations = reservationService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.library.seat.modules.reservation.entity.Reservation>()
                .ge(com.library.seat.modules.reservation.entity.Reservation::getStartTime, java.util.Date.from(startOfDay.atZone(java.time.ZoneId.systemDefault()).toInstant()))
                .lt(com.library.seat.modules.reservation.entity.Reservation::getStartTime, java.util.Date.from(endOfDay.atZone(java.time.ZoneId.systemDefault()).toInstant()))
                .eq(com.library.seat.modules.reservation.entity.Reservation::getDeleted, 0));
                
        // 获取座位区域映射
        Map<Long, String> seatAreaMap = allSeats.stream()
                .filter(s -> s.getArea() != null)
                .collect(Collectors.toMap(Seat::getId, Seat::getArea, (a, b) -> a));

        // 按区域统计座位总数
        Map<String, Long> areaTotalSeats = allSeats.stream()
                .filter(s -> s.getArea() != null)
                .collect(Collectors.groupingBy(Seat::getArea, Collectors.counting()));

        Random random = new Random();

        for (int j = 0; j < areas.size(); j++) {
            String area = areas.get(j);
            long totalSeatsInArea = areaTotalSeats.getOrDefault(area, 1L);
            
            for (int i = 0; i < hourThresholds.length; i++) {
                final int h = hourThresholds[i];
                final String currentArea = area;
                
                // 3. 计算该时段内的占用情况 (优化逻辑：检查时间重叠)
                long count = reservations.stream()
                        .filter(r -> {
                            String rArea = seatAreaMap.get(r.getSeatId());
                            if (rArea == null || !rArea.equals(currentArea)) return false;
                            
                            // 预约时间段
                            long rStart = r.getStartTime().getTime();
                            long rEnd = r.getEndTime() != null ? r.getEndTime().getTime() : System.currentTimeMillis();
                            
                            // 插槽时间段 (2小时)
                            java.util.Calendar slotStart = java.util.Calendar.getInstance();
                            slotStart.setTime(java.util.Date.from(startOfDay.atZone(java.time.ZoneId.systemDefault()).toInstant()));
                            slotStart.set(java.util.Calendar.HOUR_OF_DAY, h);
                            
                            java.util.Calendar slotEnd = (java.util.Calendar) slotStart.clone();
                            slotEnd.add(java.util.Calendar.HOUR, 2);
                            
                            // 判断是否有重叠
                            return rStart < slotEnd.getTimeInMillis() && rEnd > slotStart.getTimeInMillis();
                        })
                        .count();
                
                // 计算强度：该时段的预约量占区域总座位的比例
                int intensity = (int) Math.min(100, ((double) count / totalSeatsInArea) * 100); 
                
                if (simulate) {
                    // 如果是空数据，给一点随机基础值增强视觉效果
                    if (intensity < 5) {
                        intensity = 5 + random.nextInt(10);
                    }
                    // 高峰模拟
                    if ((h >= 14 && h <= 16) || (h >= 18 && h <= 20)) {
                        intensity += random.nextInt(15);
                    }
                }
                
                heatmap.add(new Object[]{i, j, Math.min(100, intensity)});
            }
        }
        
        return heatmap;
    }

    public Map<String, Object> getTrendData() {
        // Mock trend data (last 7 days)
        Map<String, Object> result = new HashMap<>();
        List<String> dates = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        
        for (int i = 6; i >= 0; i--) {
            dates.add("Day " + i);
            values.add((int)(Math.random() * 50));
        }
        
        result.put("dates", dates);
        result.put("values", values);
        return result;
    }

    public Map<String, Object> getCongestionData(String dateStr, boolean simulate) {
        List<Seat> allSeats = seatService.list(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Seat>()
                .eq(Seat::getDeleted, 0));
        
        // 按区域分组
        Map<String, List<Seat>> seatsByArea = allSeats.stream()
                .filter(s -> s.getArea() != null && !s.getArea().isEmpty())
                .collect(Collectors.groupingBy(Seat::getArea));
        
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> sortedAreas = seatsByArea.keySet().stream().sorted().collect(Collectors.toList());

        java.time.LocalDate targetDate;
        if (dateStr == null || dateStr.isEmpty()) {
            targetDate = java.time.LocalDate.now();
        } else {
            try {
                targetDate = java.time.LocalDate.parse(dateStr);
            } catch (Exception e) {
                targetDate = java.time.LocalDate.now();
            }
        }

        // 如果是今天，显示实时拥堵度
        if (targetDate.equals(java.time.LocalDate.now())) {
            for (String area : sortedAreas) {
                List<Seat> seats = seatsByArea.get(area);
                long total = seats.size();
                long occupied = seats.stream()
                        .filter(s -> "occupied".equals(s.getStatus()))
                        .count();
                
                double occupancyRate = total > 0 ? (double) occupied / total * 100 : 0;
                result.put(area, Math.round(occupancyRate * 10) / 10.0);
            }
        } else {
            // 如果是历史日期，基于该天的热力图数据计算平均拥堵度
            List<Object[]> heatmap = getHeatmapData(dateStr, simulate);
            // heatmap data format: [hourIndex, areaIndex, intensity]
            
            for (int j = 0; j < sortedAreas.size(); j++) {
                String area = sortedAreas.get(j);
                final int areaIdx = j;
                
                double avgIntensity = heatmap.stream()
                        .filter(h -> (int)h[1] == areaIdx)
                        .mapToDouble(h -> ((Number)h[2]).doubleValue())
                        .average()
                        .orElse(0.0);
                
                result.put(area, Math.round(avgIntensity * 10) / 10.0);
            }
        }
        
        return result;
    }

    public Map<String, Object> getViolationTrend() {
        Map<String, Object> result = new HashMap<>();
        List<String> dates = new ArrayList<>();
        List<Long> violationCounts = new ArrayList<>();
        
        java.time.LocalDate today = java.time.LocalDate.now();
        
        for (int i = 6; i >= 0; i--) {
            java.time.LocalDate d = today.minusDays(i);
            dates.add(d.toString());
            
            java.time.LocalDateTime s = d.atStartOfDay();
            java.time.LocalDateTime e = d.plusDays(1).atStartOfDay();
            
            // 统计状态为 violation 的预约记录
            long count = reservationService.count(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.library.seat.modules.reservation.entity.Reservation>()
                    .eq(com.library.seat.modules.reservation.entity.Reservation::getStatus, "violation")
                    .ge(com.library.seat.modules.reservation.entity.Reservation::getUpdateTime, s)
                    .lt(com.library.seat.modules.reservation.entity.Reservation::getUpdateTime, e));
            violationCounts.add(count);
        }
        
        result.put("dates", dates);
        result.put("counts", violationCounts);
        return result;
    }
}

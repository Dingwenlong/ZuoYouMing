package com.library.seat.modules.occupancy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.seat.modules.occupancy.entity.SeatOccupancy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 占座检测记录 Mapper（已弃用）
 * 占座检测逻辑已迁移到 sys_reservation 表
 * 保留此类以兼容历史数据，新代码不再使用
 */
@Mapper
@Deprecated
public interface SeatOccupancyMapper extends BaseMapper<SeatOccupancy> {
    // 所有方法已迁移到 ReservationService
    // 保留此类以兼容历史数据查询
}

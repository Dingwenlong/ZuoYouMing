package com.library.seat.modules.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.library.seat.modules.reservation.entity.Appeal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AppealMapper extends BaseMapper<Appeal> {

    @Select("SELECT * FROM sys_appeal WHERE user_id = #{userId} ORDER BY create_time DESC")
    List<Appeal> selectByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM sys_appeal WHERE reservation_id = #{reservationId} LIMIT 1")
    Appeal selectByReservationId(@Param("reservationId") Long reservationId);

    @Select("SELECT * FROM sys_appeal ORDER BY create_time DESC")
    List<Appeal> selectAllWithOrder();
}

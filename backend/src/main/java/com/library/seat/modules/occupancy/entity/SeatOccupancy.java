package com.library.seat.modules.occupancy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.util.Date;

/**
 * 占座检测记录实体（已弃用）
 * 占座检测逻辑已迁移到 sys_reservation 表
 * 保留此类以兼容历史数据，新代码不再使用
 */
@TableName("sys_seat_occupancy")
@Schema(description = "占座检测记录（已弃用，使用 sys_reservation 表替代）")
@Deprecated
public class SeatOccupancy implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "关联预约ID")
    private Long reservationId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "座位ID")
    private Long seatId;

    @Schema(description = "签到时间")
    private Date checkInTime;

    @Schema(description = "最后检测到的时间")
    private Date lastDetectedTime;

    @Schema(description = "累计离开时长(分钟)")
    private Integer totalAwayMinutes;

    @Schema(description = "状态: normal(正常), warning(预警), occupied(占座)")
    private String occupancyStatus;

    @Schema(description = "预警次数")
    private Integer warningCount;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新时间")
    private Date updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public void setReservationId(Long reservationId) {
        this.reservationId = reservationId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSeatId() {
        return seatId;
    }

    public void setSeatId(Long seatId) {
        this.seatId = seatId;
    }

    public Date getCheckInTime() {
        return checkInTime;
    }

    public void setCheckInTime(Date checkInTime) {
        this.checkInTime = checkInTime;
    }

    public Date getLastDetectedTime() {
        return lastDetectedTime;
    }

    public void setLastDetectedTime(Date lastDetectedTime) {
        this.lastDetectedTime = lastDetectedTime;
    }

    public Integer getTotalAwayMinutes() {
        return totalAwayMinutes;
    }

    public void setTotalAwayMinutes(Integer totalAwayMinutes) {
        this.totalAwayMinutes = totalAwayMinutes;
    }

    public String getOccupancyStatus() {
        return occupancyStatus;
    }

    public void setOccupancyStatus(String occupancyStatus) {
        this.occupancyStatus = occupancyStatus;
    }

    public Integer getWarningCount() {
        return warningCount;
    }

    public void setWarningCount(Integer warningCount) {
        this.warningCount = warningCount;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }
}

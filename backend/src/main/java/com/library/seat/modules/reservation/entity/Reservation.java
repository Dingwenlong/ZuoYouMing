package com.library.seat.modules.reservation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.Date;
import java.util.List;

@TableName("sys_reservation")
@Schema(description = "预约信息")
public class Reservation implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "预约ID")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "座位ID")
    private Long seatId;

    @TableField(exist = false)
    @Schema(description = "座位号")
    private String seatNo;

    @Schema(description = "开始时间")
    private Date startTime;

    @Schema(description = "结束时间")
    private Date endTime;

    @Schema(description = "截止时间 (签到/暂离)")
    private Date deadline;

    @Schema(description = "预约类型: appointment (预约), immediate (扫码入座)", example = "appointment")
    private String type;

    @Schema(description = "时段: morning(08:00-12:00), afternoon(13:00-17:00), evening(18:00-22:00)", example = "morning")
    private String slot;

    @TableField(exist = false)
    @Schema(description = "批量预约时段")
    private List<String> slots;

    @Schema(description = "状态: reserved, checked_in, completed, cancelled, violation", example = "reserved")
    private String status;

    @Schema(description = "最后在场时间（用于占座检测）")
    private Date lastPresentTime;

    @Schema(description = "累计离开时长(分钟)")
    private Integer totalAwayMinutes;

    @Schema(description = "是否已发送占座预警 0:否 1:是")
    private Integer occupancyAlertSent;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新时间")
    private Date updateTime;

    @Schema(description = "是否删除 0:否 1:是")
    private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getSeatNo() {
        return seatNo;
    }

    public void setSeatNo(String seatNo) {
        this.seatNo = seatNo;
    }

    public Date getStartTime() {
        return startTime;
    }

    public void setStartTime(Date startTime) {
        this.startTime = startTime;
    }

    public Date getEndTime() {
        return endTime;
    }

    public void setEndTime(Date endTime) {
        this.endTime = endTime;
    }

    public Date getDeadline() {
        return deadline;
    }

    public void setDeadline(Date deadline) {
        this.deadline = deadline;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSlot() {
        return slot;
    }

    public void setSlot(String slot) {
        this.slot = slot;
    }

    public List<String> getSlots() {
        return slots;
    }

    public void setSlots(List<String> slots) {
        this.slots = slots;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public Date getLastPresentTime() {
        return lastPresentTime;
    }

    public void setLastPresentTime(Date lastPresentTime) {
        this.lastPresentTime = lastPresentTime;
    }

    public Integer getTotalAwayMinutes() {
        return totalAwayMinutes;
    }

    public void setTotalAwayMinutes(Integer totalAwayMinutes) {
        this.totalAwayMinutes = totalAwayMinutes;
    }

    public Integer getOccupancyAlertSent() {
        return occupancyAlertSent;
    }

    public void setOccupancyAlertSent(Integer occupancyAlertSent) {
        this.occupancyAlertSent = occupancyAlertSent;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}

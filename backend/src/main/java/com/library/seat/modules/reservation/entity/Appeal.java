package com.library.seat.modules.reservation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.Date;

@TableName("sys_appeal")
@Schema(description = "违规申诉")
public class Appeal implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "关联预约记录ID")
    private Long reservationId;

    @Schema(description = "申诉用户ID")
    private Long userId;

    @Schema(description = "申诉类型: PHONE_DEAD, QR_CODE_DAMAGED, GPS_ERROR, SYSTEM_ERROR, OTHER")
    private String appealType;

    @Schema(description = "申诉理由")
    private String reason;

    @Schema(description = "图片凭证")
    private String images;

    @Schema(description = "状态: pending, approved, rejected")
    private String status;

    @Schema(description = "管理员回复")
    private String reply;

    @Schema(description = "是否已返还信用分 0:否 1:是")
    private Integer creditReturned;

    @Schema(description = "返还信用分数量")
    private Integer creditAmount;

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

    public String getAppealType() {
        return appealType;
    }

    public void setAppealType(String appealType) {
        this.appealType = appealType;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public Integer getCreditReturned() {
        return creditReturned;
    }

    public void setCreditReturned(Integer creditReturned) {
        this.creditReturned = creditReturned;
    }

    public Integer getCreditAmount() {
        return creditAmount;
    }

    public void setCreditAmount(Integer creditAmount) {
        this.creditAmount = creditAmount;
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

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}

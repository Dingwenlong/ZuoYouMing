package com.library.seat.modules.reservation.enums;

/**
 * 在场确认类型枚举
 * 用于标识用户确认在场的方式
 */
public enum PresenceType {
    CHECK_IN("签到", "用户正常签到"),
    QR_SCAN("扫码", "用户扫描座位二维码"),
    TEMP_RETURN("暂离返回", "用户暂离后返回签到"),
    HEARTBEAT("心跳", "系统心跳检测（预留）");

    private final String name;
    private final String description;

    PresenceType(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }
}

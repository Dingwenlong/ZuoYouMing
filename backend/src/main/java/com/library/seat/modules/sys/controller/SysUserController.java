package com.library.seat.modules.sys.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.seat.common.Result;
import com.library.seat.modules.sys.entity.SysUser;
import com.library.seat.modules.sys.service.UserDetailsServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "用户管理", description = "系统用户管理接口")
@RestController
@RequestMapping("/api/v1/users")
public class SysUserController {

    @Autowired
    private UserDetailsServiceImpl userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.library.seat.modules.sys.service.ISysNotificationService notificationService;

    @Operation(summary = "获取用户列表")
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('admin')")
    public Result<Page<SysUser>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String realName) {
        Page<SysUser> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        
        // 如果两个参数相同（来自消息广场的模糊搜索），使用 OR 逻辑
        if (StringUtils.hasText(username) && username.equals(realName)) {
            queryWrapper.and(q -> q.like(SysUser::getUsername, username)
                    .or()
                    .like(SysUser::getRealName, realName));
        } else {
            queryWrapper.like(StringUtils.hasText(username), SysUser::getUsername, username)
                        .like(StringUtils.hasText(realName), SysUser::getRealName, realName);
        }
        
        queryWrapper.orderByDesc(SysUser::getCreateTime);
        return Result.success(userService.page(pageObj, queryWrapper));
    }

    @Operation(summary = "新增用户")
    @PostMapping
    @PreAuthorize("hasAuthority('admin')")
    public Result<Boolean> add(@RequestBody SysUser user) {
        if (StringUtils.hasText(user.getPassword())) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        }
        return Result.success(userService.save(user));
    }

    @Operation(summary = "修改用户")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('admin')")
    public Result<Boolean> update(@PathVariable Long id, @RequestBody SysUser user) {
        user.setId(id);
        if (StringUtils.hasText(user.getPassword())) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(null); // Ensure empty password doesn't overwrite existing one
        }
        boolean success = userService.updateById(user);
        if (success) {
            notificationService.send(id, "个人信息修改通知", "管理员已修改您的个人信息，请检查是否正确。", "info");
        }
        return Result.success(success);
    }

    @Operation(summary = "修改用户状态")
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('admin')")
    public Result<Boolean> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> params) {
        String status = params.get("status");
        if (status == null) {
            return Result.error("Status is required");
        }

        SysUser user = userService.getById(id);
        if (user == null) {
            return Result.error("User not found");
        }

        user.setStatus(status);
        return Result.success(userService.updateById(user));
    }
}

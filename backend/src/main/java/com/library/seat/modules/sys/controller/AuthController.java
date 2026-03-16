package com.library.seat.modules.sys.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.library.seat.common.Result;
import com.library.seat.common.utils.JwtUtils;
import com.library.seat.modules.sys.entity.SysUser;
import com.library.seat.modules.sys.service.UserDetailsServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Tag(name = "认证管理", description = "用户登录与注册相关接口")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserDetailsServiceImpl userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${wechat.mp.app-id:}")
    private String wechatAppId;

    @Value("${wechat.mp.app-secret:}")
    private String wechatAppSecret;

    private final RestTemplate restTemplate = new RestTemplate();

    @Operation(summary = "用户登录", description = "使用账号密码登录并获取 JWT Token")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getUsername(), loginRequest.getPassword())
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String token = jwtUtils.generateToken(userDetails);

        SysUser sysUser = userDetailsService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, loginRequest.getUsername()));

        // Update last login time
        sysUser.setLastLoginTime(new java.util.Date());
        userDetailsService.updateById(sysUser);

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userInfo", sysUser);

        return Result.success(data);
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<String> logout() {
        // JWT is stateless, client just discards token.
        // Can implement blacklist if needed.
        return Result.success("Logged out successfully");
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/info")
    public Result<SysUser> info() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        SysUser sysUser = userDetailsService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        return Result.success(sysUser);
    }

    @Operation(summary = "微信小程序登录")
    @PostMapping("/wechat-login")
    public Result<Map<String, Object>> wechatLogin(@RequestBody WechatLoginRequest request) {
        // Mock implementation for now
        // 1. Verify code with WeChat API -> get openid
        // 2. Find user by openid (need to add openid to SysUser or separate table)
        // 3. If not found, auto-register or return error
        // 4. Generate JWT

        // Mocking a successful login for 'student'
        // In real impl, use request.getCode()

        String mockUsername = "student";
        UserDetails userDetails = userDetailsService.loadUserByUsername(mockUsername);
        String token = jwtUtils.generateToken(userDetails);

        SysUser sysUser = userDetailsService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, mockUsername));

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userInfo", sysUser);

        return Result.success(data);
    }

    @Operation(summary = "微信公众号登录")
    @PostMapping("/wechat-mp-login")
    public Result<Map<String, Object>> wechatMpLogin(@RequestBody WechatLoginRequest request) {
        if (request.getCode() == null || request.getCode().isEmpty()) {
            return Result.error("Code cannot be empty");
        }

        // 1. Get Access Token & OpenID
        String tokenUrl = String.format(
                "https://api.weixin.qq.com/sns/oauth2/access_token?appid=%s&secret=%s&code=%s&grant_type=authorization_code",
                wechatAppId, wechatAppSecret, request.getCode()
        );

        String tokenResponse = restTemplate.getForObject(tokenUrl, String.class);
        JSONObject tokenJson = JSONObject.parseObject(tokenResponse);

        if (tokenJson.containsKey("errcode")) {
            return Result.error("WeChat Auth Failed: " + tokenJson.getString("errmsg"));
        }

        String openid = tokenJson.getString("openid");
        String accessToken = tokenJson.getString("access_token");

        // 2. Find User by OpenID
        SysUser sysUser = userDetailsService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getOpenId, openid));

        if (sysUser == null) {
            // 3. Auto Register
            // Get User Info (Optional, but good for real name/avatar)
            String userInfoUrl = String.format(
                    "https://api.weixin.qq.com/sns/userinfo?access_token=%s&openid=%s&lang=zh_CN",
                    accessToken, openid
            );
            String userInfoResponse = restTemplate.getForObject(userInfoUrl, String.class);
            JSONObject userInfoJson = JSONObject.parseObject(userInfoResponse);

            sysUser = new SysUser();
            sysUser.setOpenId(openid);
            sysUser.setUsername("wx_" + UUID.randomUUID().toString().substring(0, 8)); // Random username
            sysUser.setPassword(passwordEncoder.encode("123456")); // Default password
            sysUser.setRole("student");
            sysUser.setStatus("active");

            if (!userInfoJson.containsKey("errcode")) {
                sysUser.setRealName(userInfoJson.getString("nickname"));
                sysUser.setAvatar(userInfoJson.getString("headimgurl"));
            } else {
                sysUser.setRealName("WeChat User");
            }

            userDetailsService.save(sysUser);
        }

        // 4. Generate Token
        UserDetails userDetails = userDetailsService.loadUserByUsername(sysUser.getUsername());
        String token = jwtUtils.generateToken(userDetails);

        // Update login time
        sysUser.setLastLoginTime(new java.util.Date());
        userDetailsService.updateById(sysUser);

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userInfo", sysUser);

        return Result.success(data);
    }

    @Operation(summary = "用户注册", description = "新用户注册并自动登录")
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        // 1. Validate params
        if (request.getUsername() == null || request.getUsername().isEmpty()) {
            return Result.error(400, "用户名不能为空");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            return Result.error(400, "密码长度不能少于6位");
        }
        if (request.getPhone() == null || request.getPhone().isEmpty()) {
            return Result.error(400, "手机号不能为空");
        }

        // 2. Check if user exists
        long count = userDetailsService.count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername()));
        if (count > 0) {
            return Result.error(400, "用户名已存在，请直接登录");
        }

        // 3. Check if phone exists (Uniqueness validation)
        long phoneCount = userDetailsService.count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, request.getPhone()));
        if (phoneCount > 0) {
            return Result.error(400, "该手机号已被注册");
        }

        // 4. Create user
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setPhone(request.getPhone());
        user.setRole("student");
        user.setStatus("active");
        user.setCreditScore(100);
        user.setCreateTime(new java.util.Date());
        user.setDeleted(0);
        // Default avatar
        user.setAvatar("https://api.dicebear.com/7.x/avataaars/svg?seed=" + request.getUsername());

        userDetailsService.save(user);

        // 5. Auto login (Generate Token)
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtUtils.generateToken(userDetails);

        // Update last login
        user.setLastLoginTime(new java.util.Date());
        userDetailsService.updateById(user);

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userInfo", user);

        return Result.success(data);
    }

    @Operation(summary = "完善个人信息", description = "更新当前登录用户的真实姓名、手机号和学号/工号")
    @PutMapping("/profile")
    public Result<Boolean> updateProfile(@RequestBody SysUser user) {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        SysUser currentUser = userDetailsService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));

        if (currentUser == null) {
            return Result.error("用户不存在");
        }

        // 仅允许更新特定字段
        if (user.getRealName() != null) {
            currentUser.setRealName(user.getRealName());
        }
        if (user.getPhone() != null) {
            currentUser.setPhone(user.getPhone());
        }
        // 更新学号/工号，需校验唯一性
        if (user.getStudentId() != null && !user.getStudentId().isEmpty()) {
            // 检查学号是否已被其他用户使用
            SysUser existingUser = userDetailsService.getOne(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getStudentId, user.getStudentId())
                    .ne(SysUser::getId, currentUser.getId()));
            if (existingUser != null) {
                return Result.error(400, "该学号/工号已被使用");
            }
            currentUser.setStudentId(user.getStudentId());
        }
        currentUser.setUpdateTime(new java.util.Date());

        return Result.success(userDetailsService.updateById(currentUser));
    }

    @Schema(description = "登录请求参数")
    public static class LoginRequest {
        @Schema(description = "账号", example = "admin", required = true)
        private String username;

        @Schema(description = "密码", example = "123456", required = true)
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    @Schema(description = "注册请求参数")
    public static class RegisterRequest {
        @Schema(description = "账号", example = "2021001", required = true)
        private String username;

        @Schema(description = "密码", example = "password123", required = true)
        private String password;

        @Schema(description = "手机号", example = "13800138000", required = true)
        private String phone;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }
    }

    public static class WechatLoginRequest {
        private String code;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }
    }
}

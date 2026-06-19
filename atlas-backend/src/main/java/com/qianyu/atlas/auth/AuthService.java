package com.qianyu.atlas.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qianyu.atlas.auth.AuthDtos.AuthResponse;
import com.qianyu.atlas.auth.AuthDtos.LoginRequest;
import com.qianyu.atlas.auth.AuthDtos.RegisterRequest;
import com.qianyu.atlas.common.BizException;
import com.qianyu.atlas.security.JwtService;
import com.qianyu.atlas.user.User;
import com.qianyu.atlas.user.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        boolean usernameExists = userMapper.exists(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.username()));
        if (usernameExists) {
            throw new BizException("用户名已存在");
        }

        if (StringUtils.hasText(request.email())) {
            boolean emailExists = userMapper.exists(new LambdaQueryWrapper<User>()
                    .eq(User::getEmail, request.email()));
            if (emailExists) {
                throw new BizException("邮箱已存在");
            }
        }

        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userMapper.insert(user);

        String token = jwtService.issue(user.getId(), user.getUsername());
        return new AuthResponse(user.getId(), user.getUsername(), token);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.account())
                .or(wrapper -> wrapper.eq(User::getEmail, request.account()))
                .last("limit 1"));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException("账号或密码错误");
        }
        String token = jwtService.issue(user.getId(), user.getUsername());
        return new AuthResponse(user.getId(), user.getUsername(), token);
    }
}
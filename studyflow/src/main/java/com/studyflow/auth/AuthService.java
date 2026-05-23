package com.studyflow.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.common.BusinessException;
import com.studyflow.user.UserEntity;
import com.studyflow.user.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public UserProfileResponse register(AuthRegisterRequest request) {
        boolean exists = userMapper.exists(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, request.username()));
        if (exists) {
            throw new BusinessException("用户名已存在");
        }

        UserEntity user = new UserEntity();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setRole("USER");
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        return new UserProfileResponse(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    public LoginResponse login(AuthLoginRequest request) {
        UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, request.username()));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException("用户名或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException("账号状态异常");
        }
        StpUtil.login(user.getId());
        return new LoginResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                StpUtil.getTokenName(),
                StpUtil.getTokenValue()
        );
    }

    public UserProfileResponse currentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return new UserProfileResponse(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    public void logout() {
        StpUtil.logout();
    }
}

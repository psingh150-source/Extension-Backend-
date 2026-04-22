package com.newtab.dashboard.service;

import com.newtab.dashboard.dto.response.UserResponse;
import com.newtab.dashboard.entity.User;
import com.newtab.dashboard.exception.UnauthorizedException;
import com.newtab.dashboard.repository.UserRepository;
import com.newtab.dashboard.util.SecurityUtils;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getCurrentUserEntity() {
        UUID userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new UnauthorizedException("User not authenticated");
        }
        return userRepository.findById(userId)
            .orElseThrow(() -> new UnauthorizedException("User not found"));
    }

    public UserResponse getCurrentUser() {
        User user = getCurrentUserEntity();
        return UserResponse.builder()
            .id(user.getId())
            .name(user.getName())
            .email(user.getEmail())
            .role(user.getRole())
            .pictureUrl(user.getPictureUrl())
            .build();
    }
}
